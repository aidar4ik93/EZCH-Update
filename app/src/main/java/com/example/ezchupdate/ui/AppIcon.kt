package com.example.ezchupdate.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
internal fun AppIcon(app: RemoteApp, size: Dp) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(null, app.packageName, app.iconUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(144, 144).asImageBitmap() }.getOrNull()
                ?: runCatching { context.assets.open("app-icons/${app.packageName}.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
                ?: app.iconUrl?.let { iconUrl -> runCatching { downloadIcon(iconUrl) }.getOrNull() }
        }
    }
    val shape = RoundedCornerShape(size * 0.22f)
    if (bitmap != null) {
        Image(bitmap!!, contentDescription = null, modifier = Modifier.size(size).clip(shape), contentScale = ContentScale.Fit)
    } else if (app.packageName == "com.spocky.projengmenu") {
        Image(painterResource(R.drawable.ic_projectivy), contentDescription = null, modifier = Modifier.size(size).clip(shape), contentScale = ContentScale.Fit)
    } else {
        val palette = listOf(Color(0xFF157DBA) to Color(0xFF064272), Color(0xFF8055D7) to Color(0xFF3F2187), Color(0xFF18A7AE) to Color(0xFF075568), Color(0xFFC57B32) to Color(0xFF755025))
        val colors = palette[(app.packageName.hashCode().toLong().let { if (it < 0) -it else it } % palette.size).toInt()]
        Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(colors.first, colors.second))), contentAlignment = Alignment.Center) {
            Text(app.name.take(2).uppercase(), color = Color.White, fontSize = (size.value * 0.33f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun downloadIcon(iconUrl: String): ImageBitmap? {
    val url = URL(iconUrl)
    require(url.protocol == "https")
    val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 5_000 }
    return try {
        if (connection.responseCode !in 200..299 || connection.contentLengthLong > 1_048_576L) return null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1_048_576) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096) return null
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 384) sample *= 2
            inSampleSize = sample
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } finally { connection.disconnect() }
}
