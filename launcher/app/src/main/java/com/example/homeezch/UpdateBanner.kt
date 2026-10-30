package com.example.homeezch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What EZCH Update reported: installed apps with a newer verified version, and how many are already downloaded. */
internal data class UpdateSummary(val updaterPackage: String, val names: List<String>, val ready: Int) {
    val count get() = names.size
    val label: String get() = when {
        ready >= count -> "⬇ Обновления готовы: $count · Установить"
        ready > 0 -> "⬇ Обновления: $count (загружено $ready) · Установить"
        else -> "⬇ Доступны обновления: $count"
    }
}

internal object UpdateStatusReader {
    fun uri(updaterPackage: String): Uri = Uri.parse("content://$updaterPackage.status/updates")

    /** Null when EZCH Update is missing, too old to report status, or signed differently. */
    fun read(context: Context): UpdateSummary? {
        for (pkg in updaterPackages(context.packageName)) {
            val summary = runCatching {
                context.contentResolver.query(uri(pkg), null, null, null, null)?.use { c ->
                    val name = c.getColumnIndexOrThrow("name"); val ready = c.getColumnIndexOrThrow("ready")
                    val names = mutableListOf<String>(); var readyCount = 0
                    while (c.moveToNext()) { names += c.getString(name); if (c.getInt(ready) == 1) readyCount++ }
                    UpdateSummary(pkg, names, readyCount)
                }
            }.getOrNull()
            if (summary != null) return summary
        }
        return null
    }

    /** Opens EZCH Update straight into «install every update, one confirmation after another». */
    fun installAll(context: Context, updaterPackage: String) {
        val intent = Intent("com.example.ezchupdate.INSTALL_ALL")
            .setComponent(ComponentName(updaterPackage, "com.example.ezchupdate.MainActivity"))
            .putExtra("install_all", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { context.startActivity(intent) }
        catch (_: Exception) { if (!openUpdater(context)) Toast.makeText(context, "Не удалось открыть EZCH Update", Toast.LENGTH_LONG).show() }
    }
}

/** Live status: re-read when HOME resumes and whenever EZCH Update changes it (download finished, app installed). */
@Composable
internal fun rememberUpdateSummary(): UpdateSummary? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var summary by remember { mutableStateOf<UpdateSummary?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        updaterPackages(context.packageName).forEach { pkg ->
            runCatching { context.contentResolver.registerContentObserver(UpdateStatusReader.uri(pkg), true, observer) }
        }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    LaunchedEffect(revision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            summary = withContext(Dispatchers.IO) { UpdateStatusReader.read(context) }?.takeIf { it.count > 0 }
        }
    }
    return summary
}

/** A single focusable chip in the top bar; absent when there is nothing to update. */
@Composable
internal fun UpdateBanner(summary: UpdateSummary, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Text(summary.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.testTag("update-banner")
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF1E6FD9) else Color(0xCC0F3E73), shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF5DA9FF), shape)
            .clickable { UpdateStatusReader.installAll(context, summary.updaterPackage) }
            .padding(horizontal = 14.dp, vertical = 7.dp))
}

/**
 * Google TV sends the remote's microphone button to Google Assistant. EZCH can take it over only through
 * its accessibility service; until one is on, this chip says so and opens the right Android screen.
 */
@Composable
internal fun MicSetupHint(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var needed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // R31 (GPT R27: the hint came back after «Нет»): only for someone who finished «Настроить EZCH» with
            // Eva on the mic button and later lost both services. Never after «Позже», «Нет» or a skipped step.
            needed = runCatching { SetupWizard.micHintNeeded(context) }.getOrDefault(false)
            // The chip floats over the lower desktop rows: a reminder for each return to HOME, not a
            // permanent cover over the bottom-left cards (R16: taps on those cards opened Accessibility).
            if (needed) {
                kotlinx.coroutines.delay(HINT_VISIBLE_MS)
                while (focused) kotlinx.coroutines.delay(1_000)
                needed = false
            }
        }
    }
    if (!needed) return
    val shape = RoundedCornerShape(18.dp)
    Text("🎤 Кнопка микрофона больше не открывает Еву — нажмите, чтобы снова настроить", color = Color.White,
        fontSize = 12.sp, maxLines = 2,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF7A3FD9) else Color(0xCC3A1F66), shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFFB08CFF), shape)
            .clickable { SetupWizard.open(context) }
            .padding(horizontal = 14.dp, vertical = 7.dp))
}

private const val HINT_VISIBLE_MS = 20_000L


/** R28: progress of EVA's extended package (on-device model) on the desktop, so a running download is always visible. */
@Composable
internal fun EvaPackBanner(modifier: Modifier = Modifier) {
    val st by com.example.homeezch.ai.EvaModelDownloads.state.collectAsState()
    val s = st ?: return
    // A finished or failed download stays on the desktop for a minute, then disappears.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(5_000) } }
    if (!s.running && now - s.at > 60_000) return
    val shape = RoundedCornerShape(18.dp)
    val line = when {
        s.done -> "✓ Ева: расширенный пакет «${s.title}» установлен"
        s.error != null -> "⚠ Ева: пакет «${s.title}» — ${s.error}"
        else -> "⬇ Ева: скачиваю расширенный пакет «${s.title}» — ${s.percent} %"
    }
    androidx.compose.foundation.layout.Column(modifier.background(Color(0xCC12304F), shape).border(1.dp, Color(0xFF4FA3FF), shape)
        .padding(horizontal = 14.dp, vertical = 8.dp).testTag("eva-pack-banner")) {
        Text(line, color = Color.White, fontSize = 12.sp, maxLines = 2)
        if (s.running) androidx.compose.foundation.layout.Box(Modifier.padding(top = 6.dp).width(360.dp).height(6.dp)
            .background(Color(0x553A5A80), RoundedCornerShape(3.dp))) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(s.percent / 100f).height(6.dp)
                .background(Color(0xFF4FA3FF), RoundedCornerShape(3.dp)))
        }
    }
}
