package com.example.ezchupdate

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.ezchupdate.data.HttpsConnection
import java.io.File
import java.security.MessageDigest

/** Installed Drawable takes precedence and handles adaptive/vector icons natively. */
@Composable internal fun AppIcon(row: AppRow, modifier: Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, row.app.packageName, row.installed?.versionCode, row.app.iconSha256) {
        value = withContext(Dispatchers.IO) {
            if (row.installed != null) runCatching {
                context.packageManager.getApplicationIcon(row.app.packageName).toBitmap(128, 128).asImageBitmap()
            }.getOrNull() else verifiedCatalogIcon(context, row) ?: runCatching {
                context.assets.open("app-icons/${row.app.packageName}.png").use {
                    android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val fallback = bundledIcon(row.app.packageName)
    when {
        icon != null -> Image(bitmap = icon!!, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
        fallback != null -> Image(painterResource(fallback), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
        else -> Box(modifier.background(Color(0xFF303038)), contentAlignment = Alignment.Center) { Text(row.app.name.take(1), color = CinemaRed) }
    }
}

private fun verifiedCatalogIcon(context: android.content.Context, row: AppRow): ImageBitmap? = runCatching {
    val hash = row.app.iconSha256 ?: return null
    val url = row.app.iconUrl ?: return null
    require(hash.matches(Regex("[a-f0-9]{64}")) && url == "https://raw.githubusercontent.com/aidar4ik93/EZCH-Update/main/catalog-icons/${row.app.sha256}.png")
    val directory = File(context.cacheDir, "icons").apply { mkdirs() }
    val cached = File(directory, "$hash.png")
    val bytes = if (cached.isFile && cached.length() <= 1024 * 1024) cached.readBytes() else {
        val connection = HttpsConnection.open(url)
        try {
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; check(output.size() + size <= 1024 * 1024); output.write(buffer, 0, size) }
            }
            output.toByteArray()
        } finally { connection.disconnect() }
    }
    check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == hash)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    check(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid icon")
    if (!cached.isFile) {
        val atomic = android.util.AtomicFile(cached)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
        // Bounded disk cache, oldest files removed first. Active images remain in Compose.
        directory.listFiles()?.sortedBy { it.lastModified() }?.dropLast(64)?.forEach { it.delete() }
    }
    bitmap.asImageBitmap()
}.getOrNull()

private fun bundledIcon(packageName: String): Int? = when (packageName) {
    "io.github.romanvht.byedpi" -> R.drawable.app_io_github_romanvht_byedpi
    "ru.tiardev.kinotrend" -> R.drawable.app_ru_tiardev_kinotrend
    "io.lift.app" -> R.drawable.app_io_lift_app
    "ru.yourok.num" -> R.drawable.app_ru_yourok_num
    "com.play.pandafref" -> R.drawable.app_com_play_pandafref
    "top.rootu.prisma" -> R.drawable.app_top_rootu_prisma
    "com.spocky.projengmenu" -> R.drawable.app_com_spocky_projengmenu
    "ru.vk.store.tv" -> R.drawable.app_ru_vk_store_tv
    "ru.rutube.app.tv" -> R.drawable.app_ru_rutube_app_tv
    "ru.twicker.serialtrend" -> R.drawable.app_ru_twicker_serialtrend
    "jp.snowlife01.android.appkiller2" -> R.drawable.app_jp_snowlife01_android_appkiller2
    "ru.yourok.torrserve" -> R.drawable.app_ru_yourok_torrserve
    "com.vk.tv" -> R.drawable.app_com_vk_tv
    "ru.vokino.web" -> R.drawable.app_ru_vokino_web
    "mobi.zona" -> R.drawable.app_mobi_zona
    "limehd.ru.lite" -> R.drawable.app_limehd_ru_lite
    "net.gtvbox.vimuhd" -> R.drawable.app_net_gtvbox_vimuhd
    "com.alphainventor.filemanager" -> R.drawable.app_com_alphainventor_filemanager
    else -> null
}
