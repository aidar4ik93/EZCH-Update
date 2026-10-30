package com.example.homeezch.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GigaChat (Сбер) — EVA's conversation brain, the model behind Салют ТВ.
 * The owner's authorization key is stored only encrypted with a non-exportable Android Keystore key.
 * Only the text of a free question and the short dialog go to GigaChat; never audio, the screen or personal memory.
 * TLS: GigaChat uses the Минцифры root (Russian Trusted Root CA), trusted for these hosts only (res/xml/eva_network_security.xml).
 */
internal object GigaChatSettings {
    val MODELS = listOf("GigaChat-2-Pro", "GigaChat-2", "GigaChat-2-Max")
    private fun prefs(context: Context) = context.getSharedPreferences("eva_gigachat_v1", Context.MODE_PRIVATE)
    fun hasKey(context: Context) = prefs(context).contains("key")
    fun key(context: Context): String? = prefs(context).getString("key", null)?.let(SecretBox::decrypt)
    fun model(context: Context): String = prefs(context).getString("model", MODELS.first()).takeIf { it in MODELS } ?: MODELS.first()
    fun consent(context: Context) = prefs(context).getBoolean("consent", false)
    fun enabled(context: Context) = consent(context) && hasKey(context)
    /** Saves a new key (blank = keep the old one). False when the Keystore could not encrypt it. */
    fun save(context: Context, key: String, model: String, consent: Boolean): Boolean {
        val clean = key.trim().removePrefix("Basic ").trim()
        val edit = prefs(context).edit().putString("model", model).putBoolean("consent", consent)
        if (clean.isNotEmpty()) edit.putString("key", SecretBox.encrypt(clean) ?: return false)
        edit.apply(); GigaChatClient.forgetToken(); return true
    }
    fun forget(context: Context) { prefs(context).edit().clear().apply(); GigaChatClient.forgetToken() }
}

/** AES-GCM with a non-exportable key in AndroidKeyStore. */
internal object SecretBox {
    private const val ALIAS = "ezch_eva_gigachat_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()
    fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

internal class GigaChatClient(private val context: Context) {
    companion object {
        private const val OAUTH = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
        /** api.giga.chat is the current host (from 17.07.2026); the old one still answers existing clients. */
        private val CHAT = listOf("https://api.giga.chat/v1/chat/completions", "https://gigachat.devices.sberbank.ru/api/v1/chat/completions")
        @Volatile private var token: String? = null
        @Volatile private var expiresAt = 0L
        fun forgetToken() { token = null; expiresAt = 0L }
    }

    private class HttpFailure(val code: Int, val body: String) : Exception("HTTP $code")

    private fun http(url: String, body: String, headers: Map<String, String>, readTimeout: Int = 25_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 8_000; c.readTimeout = readTimeout; c.doOutput = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpFailure(code, text.take(2000))
            return text
        } finally { c.disconnect() }
    }

    private fun accessToken(key: String, refresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        token?.takeIf { !refresh && now < expiresAt - 60_000 }?.let { return it }
        val reply = http(OAUTH, "scope=GIGACHAT_API_PERS", mapOf(
            "Authorization" to "Basic $key", "RqUID" to UUID.randomUUID().toString(),
            "Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"), readTimeout = 12_000)
        val root = EvaJson.parse(reply) as Map<*, *>
        val access = root["access_token"] as? String ?: throw HttpFailure(401, reply)
        val expiry = (root["expires_at"] as? Number)?.toLong() ?: (now + 25 * 60_000)
        token = access; expiresAt = if (expiry < 10_000_000_000L) expiry * 1000 else expiry
        return access
    }

    /** One conversation turn. Runs on IO; never throws. */
    suspend fun reply(text: String, history: List<Pair<String, String>>, chatContext: EvaChat.Context): EvaChat.Result = withContext(Dispatchers.IO) {
        val key = GigaChatSettings.key(context) ?: return@withContext EvaChat.Result.Failed("Ключ GigaChat не задан. Введите его в настройках Евы")
        val body = EvaChat.request(GigaChatSettings.model(context), chatContext, history, text)
        var lastError = "GigaChat недоступен"
        for (attempt in 0..1) for (url in CHAT) {
            try {
                val bearer = accessToken(key, refresh = attempt > 0)
                val reply = http(url, body, mapOf("Authorization" to "Bearer $bearer", "Content-Type" to "application/json",
                    "Accept" to "application/json", "X-Request-ID" to UUID.randomUUID().toString()))
                return@withContext EvaChat.parse(reply)
            } catch (e: HttpFailure) {
                com.example.homeezch.EzchDiag.log("GIGACHAT:ОШИБКА", "${e.code} ${e.body.take(200)}")
                lastError = when (e.code) {
                    400 -> if (e.body.contains("scope", true) || e.body.contains("credentials", true)) "Ключ GigaChat не подходит. Проверьте ключ авторизации в личном кабинете Сбера" else "GigaChat не принял запрос"
                    401 -> { forgetToken(); if (attempt == 0) continue else "Ключ GigaChat не подходит. Проверьте ключ авторизации в личном кабинете Сбера" }
                    402 -> "Закончился бесплатный лимит GigaChat. Проверьте баланс в личном кабинете Сбера"
                    404 -> "Модель ${GigaChatSettings.model(context)} недоступна. Выберите другую модель в настройках Евы"
                    429 -> "GigaChat просит подождать. Повторите вопрос через минуту"
                    else -> "GigaChat ответил ошибкой ${e.code}"
                }
                if (e.code in setOf(400, 402, 404, 429)) return@withContext EvaChat.Result.Failed(lastError)
            } catch (e: javax.net.ssl.SSLException) {
                com.example.homeezch.EzchDiag.log("GIGACHAT:ОШИБКА", "TLS ${url.substringBefore("/v1").substringBefore("/api")}: ${e.message?.take(160)}")
                lastError = "Не удалось защищённо подключиться к GigaChat"
            } catch (e: java.io.IOException) {
                com.example.homeezch.EzchDiag.log("GIGACHAT:ОШИБКА", "сеть: ${e.javaClass.simpleName} ${e.message?.take(120)}")
                lastError = "Нет связи с GigaChat. Проверьте интернет"
            } catch (e: Exception) {
                lastError = "GigaChat прислал непонятный ответ"
            }
        }
        EvaChat.Result.Failed(lastError)
    }

    /** «Сохранить и проверить» in EVA settings: one tiny request. */
    suspend fun check(): String = when (val r = reply("Скажи одним коротким предложением, что ты на связи.", emptyList(),
        EvaChat.Context(now = java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale("ru")).format(java.util.Date())))) {
        is EvaChat.Result.Say -> "GigaChat на связи: ${r.text}"
        is EvaChat.Result.Act -> "GigaChat на связи"
        is EvaChat.Result.Failed -> r.message
    }
}
