package com.example.homeezch.ai

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * «Включи рамштайн»: finds the first matching video on YouTube's public results page (no API key, no
 * account) and opens it in the YouTube app, which starts playing at once. If the page cannot be read
 * (no internet, blocked), the YouTube app opens its own search results for the same words.
 */
internal object YouTubePlay {
    private val client by lazy {
        // R29: on the owner's network youtube.com answers slowly (8 s was cut off on Homatics) — allow 20 s.
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS).build()
    }
    private val PACKAGES = listOf("com.google.android.youtube.tv", "com.google.android.youtube", "com.google.android.youtube.googletv")

    /** R29: why a source gave no video (HTTP code, size or error) — goes to /diag. */
    private fun why(source: String, detail: String) = com.example.homeezch.EzchDiag.log("YOUTUBE", "$source: $detail")
    private fun installed(context: Context) = PACKAGES.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }

    suspend fun play(context: Context, action: Action.PlayOn): Outcome {
        val pkg = installed(context) ?: return Outcome(false, "YouTube не установлен")
        if (action.autoplay) {
            val started = System.currentTimeMillis()
            // R30: on the owner's network the web lookups did not answer the TV (26 s, all sources), so they get
            // one short attempt; then the YouTube app itself finds the video and Eva presses OK like the remote.
            // Detached lookups: a blocking HTTP call cannot be cancelled, but waiting for it can.
            val io = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
            val a = io.async { firstVideo(action.query) }; val b = io.async { viaBing(action.query) }
            val id = kotlinx.coroutines.withTimeoutOrNull(7_000) { a.await() ?: b.await() }
            com.example.homeezch.EzchDiag.log("YOUTUBE", "поиск «${action.query}»: ${if (id != null) "видео найдено" else "сайт не ответил — запуск через приложение"} за ${System.currentTimeMillis() - started} мс")
            if (id != null) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))
                    .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                return Outcome(true, "Включаю «${action.query}» на YouTube")
            }
            openResults(context, pkg, action.query)
            val service = EvaAppService.current
            if (service != null && android.os.Build.VERSION.SDK_INT >= 33) {
                // Checked on Homatics (29.10): the results page opens with the first result in focus; OK starts it
                // (a video plays at once; an artist's channel starts its mix/trailer).
                kotlinx.coroutines.delay(6_000)
                service.pressOk()
                repeat(16) {
                    kotlinx.coroutines.delay(500)
                    if (AndroidMediaAdapter(context).isPlaying(pkg) == true) return Outcome(true, "Включаю «${action.query}» на YouTube")
                }
                if (AndroidMediaAdapter(context).isPlaying(pkg) == false) { service.pressOk(); kotlinx.coroutines.delay(3_000) }
                com.example.homeezch.EzchDiag.log("YOUTUBE", "результаты открыты, нажат ОК на первом")
                return Outcome(true, "Включаю первый результат «${action.query}» на YouTube")
            }
            return Outcome(true, "Нашла «${action.query}» на YouTube. Чтобы Ева сама включала видео, включите «Ai EvA» в Спец. возможностях")
        }
        openResults(context, pkg, action.query)
        return Outcome(true, "Ищу «${action.query}» на YouTube")
    }

    /** YouTube for TV has no Android search screen; its own results page opens by the web results link. */
    private fun openResults(context: Context, pkg: String, query: String) {
        val url = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun firstVideo(query: String): String? = runCatching {
        val url = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}&sp=EgIQAQ%253D%253D&hl=ru"
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI").get().build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) { why("youtube.com", "HTTP ${r.code}"); return@use null }
            val body = r.body ?: return@use null
            // The first results appear early in the page; 1.5 MB is plenty and bounds memory.
            val bytes = body.byteStream().readNBytesCompat(1_500_000)
            EvaMedia.firstVideoId(String(bytes, Charsets.UTF_8)).also { if (it == null) why("youtube.com", "видео в ответе нет, ${bytes.size} байт") }
        }
    }.onFailure { why("youtube.com", it.javaClass.simpleName + " " + (it.message ?: "").take(80)) }.getOrNull()

    /**
     * R29: on the owner's network youtube.com itself may not answer the TV. Bing's video search lists YouTube links,
     * so the first one gives a video id without opening youtube.com.
     */
    private fun viaBing(query: String): String? = runCatching {
        val url = "https://www.bing.com/videos/search?q=${URLEncoder.encode("$query youtube", "UTF-8")}&setlang=ru"
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
            .header("Accept-Language", "ru-RU,ru;q=0.9").get().build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) { why("bing", "HTTP ${r.code}"); return@use null }
            val html = String(r.body?.byteStream()?.readNBytesCompat(1_500_000) ?: return@use null, Charsets.UTF_8)
            EvaMedia.youtubeIdInLinks(html).also { if (it == null) why("bing", "ссылок YouTube нет, ${html.length} символов") }
        }
    }.onFailure { why("bing", it.javaClass.simpleName + " " + (it.message ?: "").take(80)) }.getOrNull()

    /** Last resort: a public Invidious mirror (JSON search API, no key). */
    private fun viaInvidious(query: String): String? = runCatching {
        for (host in listOf("https://inv.nadeko.net", "https://yewtu.be", "https://invidious.nerdvpn.de")) {
            val request = Request.Builder().url("$host/api/v1/search?type=video&q=${URLEncoder.encode(query, "UTF-8")}").get().build()
            val id = runCatching { client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) { why(host, "HTTP ${r.code}"); null } else Regex("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"").find(r.body?.string().orEmpty())?.groupValues?.get(1)
            } }.onFailure { why(host, it.javaClass.simpleName) }.getOrNull()
            if (id != null) return@runCatching id
        }
        null
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    /** Without the app-control service: hand the query to the app's own Android search screen, if it has one. */
    fun searchInApp(context: Context, packageName: String, query: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_SEARCH).setPackage(packageName).putExtra(SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) return@runCatching false
        context.startActivity(intent); true
    }.getOrDefault(false)
}
