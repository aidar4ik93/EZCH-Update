// EVA on-device brain: a small chat model (GGUF) run by llama.cpp inside the ":llm" process.
// One model, one context, one generation at a time. The fixed system prompt stays in the KV cache:
// each turn only the new part of the conversation is decoded (prefix reuse), which is what makes
// a 1–1.5B model usable on a TV CPU.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include "llama.h"

#define TAG "EvaLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;   // tokens currently in the KV cache (sequence 0)
    std::mutex lock;
    std::atomic<bool> stop{false};
    int threads = 4;
};

std::once_flag backend_once;

// Length of the longest prefix of `s` made of complete UTF-8 characters.
size_t complete_utf8(const std::string &s) {
    size_t i = s.size();
    // walk back over at most 3 continuation bytes to find the start of the last character
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {
            size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        --i; ++back;
    }
    return s.size();
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const jsize len = env->GetStringLength(s);
    std::u16string u16(len, u'\0');
    env->GetStringRegion(s, 0, len, reinterpret_cast<jchar *>(&u16[0]));
    std::string out;   // UTF-16 → UTF-8 (standard, not JNI "modified" UTF-8)
    for (size_t i = 0; i < u16.size(); ++i) {
        uint32_t cp = u16[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < u16.size()) { cp = 0x10000 + ((cp - 0xD800) << 10) + (u16[i + 1] - 0xDC00); ++i; }
        if (cp < 0x80) out += (char) cp;
        else if (cp < 0x800) { out += (char) (0xC0 | (cp >> 6)); out += (char) (0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += (char) (0xE0 | (cp >> 12)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F)); }
        else { out += (char) (0xF0 | (cp >> 18)); out += (char) (0x80 | ((cp >> 12) & 0x3F)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F)); }
    }
    return out;
}

bool emit(JNIEnv *env, jobject sink, jmethodID onBytes, const std::string &bytes) {
    jbyteArray arr = env->NewByteArray((jsize) bytes.size());
    env->SetByteArrayRegion(arr, 0, (jsize) bytes.size(), reinterpret_cast<const jbyte *>(bytes.data()));
    jboolean go = env->CallBooleanMethod(sink, onBytes, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return go == JNI_TRUE;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_homeezch_llm_EvaLlmNative_load(JNIEnv *env, jclass, jstring jpath, jint nCtx, jint nThreads) {
    std::call_once(backend_once, [] {
        llama_log_set([](ggml_log_level level, const char *text, void *) {
            if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
        }, nullptr);
        llama_backend_init();
    });
    const std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;   // pages come from the file: the system can drop them under memory pressure
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("cannot load %s", path.c_str()); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::max(512, (int) nCtx);
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = std::max(1, (int) nThreads);
    cp.n_threads_batch = std::max(1, (int) nThreads);
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOGE("cannot create context"); return 0; }
    auto *e = new Engine();
    e->model = model; e->ctx = ctx; e->vocab = llama_model_get_vocab(model); e->threads = cp.n_threads;
    LOGI("loaded %s ctx=%d threads=%d", path.c_str(), (int) cp.n_ctx, cp.n_threads);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_homeezch_llm_EvaLlmNative_free(JNIEnv *, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    e->stop = true;
    std::lock_guard<std::mutex> g(e->lock);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_homeezch_llm_EvaLlmNative_stop(JNIEnv *, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e) e->stop = true;
}

/**
 * roles/contents: the whole conversation (system first), formatted with the model's own chat template.
 * Streams UTF-8 bytes to sink.onBytes(byte[]): Boolean (false = stop). Returns number of generated tokens, or < 0 on error.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_homeezch_llm_EvaLlmNative_generate(JNIEnv *env, jclass, jlong handle, jobjectArray jroles, jobjectArray jcontents,
                                                    jint maxTokens, jfloat temperature, jobject sink) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return -1;
    std::lock_guard<std::mutex> g(e->lock);
    e->stop = false;
    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onBytes = env->GetMethodID(sinkClass, "onBytes", "([B)Z");
    if (!onBytes) return -2;

    // 1. Conversation → prompt text with the model's chat template.
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; ++i) {
        roles[i] = jstr(env, (jstring) env->GetObjectArrayElement(jroles, i));
        contents[i] = jstr(env, (jstring) env->GetObjectArrayElement(jcontents, i));
    }
    for (jsize i = 0; i < n; ++i) msgs[i] = {roles[i].c_str(), contents[i].c_str()};
    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    std::vector<char> buf(8192);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (len > (int) buf.size()) { buf.resize(len); len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size()); }
    if (len < 0) return -3;
    const std::string prompt(buf.data(), len);

    // 2. Tokenize the whole prompt (special tokens of the template are parsed).
    int count = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), count, true, true) < 0) return -4;
    const int nCtx = (int) llama_n_ctx(e->ctx);
    if ((int) tokens.size() + maxTokens + 8 > nCtx) return -5;   // caller trims the history

    // 3. Reuse the KV cache for the common prefix (system prompt + earlier turns).
    size_t common = 0;
    while (common < e->cached.size() && common < tokens.size() && e->cached[common] == tokens[common]) ++common;
    if (common == tokens.size()) common = tokens.size() - 1;      // always decode at least one token to get logits
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) { llama_memory_clear(mem, true); common = 0; }
    e->cached.assign(tokens.begin(), tokens.begin() + common);

    for (size_t pos = common; pos < tokens.size();) {
        if (e->stop) return 0;
        const int chunk = (int) std::min<size_t>(256, tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(tokens.data() + pos, chunk);
        if (llama_decode(e->ctx, batch) != 0) { llama_memory_clear(mem, true); e->cached.clear(); return -6; }
        e->cached.insert(e->cached.end(), tokens.begin() + pos, tokens.begin() + pos + chunk);
        pos += chunk;
    }

    // 4. Sample: mild temperature, repetition penalty (small models loop), top-p.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.15f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string pending;
    int generated = 0;
    llama_token tok = 0;
    while (generated < maxTokens && !e->stop) {
        tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        char piece[256];
        const int m = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (m > 0) pending.append(piece, m);
        ++generated;
        const size_t ready = complete_utf8(pending);
        if (ready > 0) {
            if (!emit(env, sink, onBytes, pending.substr(0, ready))) { e->stop = true; }
            pending.erase(0, ready);
        }
        if ((int) e->cached.size() + 1 >= nCtx) break;
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, next) != 0) break;
        e->cached.push_back(tok);
    }
    if (!pending.empty()) emit(env, sink, onBytes, pending);
    llama_sampler_free(smpl);
    // The answer stays in the cache; the next turn keeps whatever still matches the re-formatted prompt.
    return generated;
}
