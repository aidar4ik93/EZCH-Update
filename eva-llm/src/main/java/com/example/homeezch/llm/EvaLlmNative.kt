package com.example.homeezch.llm

/** JNI bridge to llama.cpp (src/main/cpp/eva_llm.cpp). Used only inside the ":llm" process. */
object EvaLlmNative {
    /** Receives generated UTF-8 bytes; return false to stop generation. */
    interface Sink { fun onBytes(bytes: ByteArray): Boolean }

    @Volatile var loaded = false; private set
    @Volatile var loadError: String? = null; private set

    fun ensureLibrary(): Boolean {
        if (loaded) return true
        return try { System.loadLibrary("eva_llm"); loaded = true; true }
        catch (e: Throwable) { loadError = e.javaClass.simpleName + ": " + e.message; false }
    }

    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun generate(handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float, sink: Sink): Int
}
