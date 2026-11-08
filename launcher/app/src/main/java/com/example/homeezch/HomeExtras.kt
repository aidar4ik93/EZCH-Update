package com.example.homeezch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll

/** Claude r51: uses GPT's existing media-session listener (ai.EzchMediaListener, «EVA · управление плеером»).
 *  Once the owner allows notification access for it, HOME can show what plays and control it. */
internal object NowPlaying {
    fun component(context: Context) = ComponentName(context, com.example.homeezch.ai.EzchMediaListener::class.java)
    fun allowed(context: Context): Boolean = runCatching {
        android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == component(context) }
    }.getOrDefault(false)
    /** Opens the system screen; some TV firmwares do not have it (then the adb line in the EVA help applies). */
    fun openPermission(context: Context): Boolean = runCatching {
        context.startActivity(Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
    fun controllers(context: Context): List<MediaController> = runCatching {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context))
    }.getOrDefault(emptyList())
    /** The session worth showing: playing first, then paused with a title; never EZCH itself. */
    fun pick(list: List<MediaController>, own: String): MediaController? =
        list.filter { it.packageName != own && it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().isNotBlank() }
            .sortedByDescending { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }
            .firstOrNull()
}

private data class NowPlayingInfo(val title: String, val subtitle: String, val playing: Boolean, val packageName: String)

private fun art(metadata: MediaMetadata?): Bitmap? = metadata?.let {
    it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
}

/** «Сейчас играет»: cover, title and ⏮ ⏯ ⏭ for whatever plays (YouTube, кинотеатры, музыка). Hidden when nothing plays
 *  or when the permission is not given; listens to the system only while HOME is on screen. */
@Composable
internal fun NowPlayingCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var info by remember { mutableStateOf<NowPlayingInfo?>(null) }
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!NowPlaying.allowed(context)) { controller = null; info = null; return@repeatOnLifecycle }
            val manager = context.getSystemService(MediaSessionManager::class.java)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            var current: MediaController? = null
            var callback: MediaController.Callback? = null
            fun refresh() {
                val chosen = NowPlaying.pick(NowPlaying.controllers(context), context.packageName)
                if (chosen?.sessionToken != current?.sessionToken) {
                    callback?.let { cb -> runCatching { current?.unregisterCallback(cb) } }
                    current = chosen
                    callback = chosen?.let { c ->
                        object : MediaController.Callback() {
                            override fun onPlaybackStateChanged(state: PlaybackState?) { refresh() }
                            override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
                            override fun onSessionDestroyed() { refresh() }
                        }.also { cb -> runCatching { c.registerCallback(cb, main) } }
                    }
                }
                controller = chosen
                val meta = chosen?.metadata
                info = chosen?.let { c ->
                    val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString() }.getOrDefault("")
                    val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    NowPlayingInfo(meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                        listOfNotNull(artist?.takeIf { it.isNotBlank() }, label.takeIf { it.isNotBlank() }).joinToString(" · "),
                        c.playbackState?.state == PlaybackState.STATE_PLAYING, c.packageName)
                }
                val bitmap = art(meta)
                cover = bitmap?.let { b ->
                    // A small cover only: some players hand over 1080p artwork.
                    val side = 96
                    runCatching { Bitmap.createScaledBitmap(b, side, (side * b.height / b.width.coerceAtLeast(1)).coerceIn(1, side * 2), true).asImageBitmap() }.getOrNull()
                }
            }
            val sessions = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
            try {
                runCatching { manager.addOnActiveSessionsChangedListener(sessions, NowPlaying.component(context), main) }
                refresh()
                kotlinx.coroutines.awaitCancellation()
            } finally {
                runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
                callback?.let { cb -> runCatching { current?.unregisterCallback(cb) } }
            }
        }
    }
    val now = info ?: return
    val c = controller ?: return
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.background(Color(0xCC10203A), shape).border(1.dp, Color(0x5572C7FF), shape)
        .padding(horizontal = 10.dp, vertical = 6.dp).testTag("now-playing"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val image = cover
        if (image != null) Image(image, null, Modifier.size(48.dp).background(Color.Black, RoundedCornerShape(8.dp)))
        else Box(Modifier.size(48.dp).background(Color(0x3372C7FF), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text("♪", color = Color.White, fontSize = 22.sp)
        }
        Column(Modifier.widthIn(max = 220.dp)) {
            Text("Сейчас играет", color = Gold, fontSize = 10.sp)
            Text(now.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now.subtitle.isNotBlank()) Text(now.subtitle, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MediaButton("⏮", "Назад") { runCatching { c.transportControls.skipToPrevious() } }
        MediaButton(if (now.playing) "⏸" else "▶", if (now.playing) "Пауза" else "Играть") {
            runCatching { if (now.playing) c.transportControls.pause() else c.transportControls.play() }
        }
        MediaButton("⏭", "Вперёд") { runCatching { c.transportControls.skipToNext() } }
    }
}

@Composable
private fun MediaButton(glyph: String, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.size(38.dp).background(if (focused) Color.White else Color(0x2272C7FF), RoundedCornerShape(19.dp))
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Text(glyph, color = if (focused) Color(0xFF10203A) else Color.White, fontSize = 16.sp)
    }
}

/** «Продолжить»: the last apps opened from EZCH (RecentApps), one press away. Up to four, only installed ones. */
@Composable
internal fun ContinueStrip(apps: List<LaunchApp>, onLaunch: (LaunchApp) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var recent by remember { mutableStateOf<List<LaunchApp>>(emptyList()) }
    LaunchedEffect(apps) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val byComponent = apps.filter { it.component != null }.associateBy { it.component!!.flattenToString() }
            recent = withContext(Dispatchers.IO) { com.example.homeezch.ai.RecentApps.list(context) }
                .mapNotNull(byComponent::get).filter { it.component?.packageName != context.packageName }.take(3)
        }
    }
    if (recent.isEmpty()) return
    Row(modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).testTag("continue-strip"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        Text("Продолжить", color = Muted, fontSize = 11.sp)
        recent.forEach { app ->
            var focused by remember(app) { mutableStateOf(false) }
            val shape = RoundedCornerShape(14.dp)
            Text("▶ ${app.name}", color = if (focused) Color(0xFF10203A) else Color.White, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp).background(if (focused) Color.White else Color(0x8810203A), shape)
                    .border(1.dp, if (focused) Color.White else Color(0x5572C7FF), shape)
                    .onFocusChanged { focused = it.isFocused }.clickable { onLaunch(app) }
                    .padding(horizontal = 12.dp, vertical = 7.dp).testTag("continue-${app.component?.packageName}"))
        }
    }
}

/**
 * R53: «Умный дом» one press away from the desktop (replaces the «Продолжить» strip). A glass pill with a drawn house
 * glyph; opens «Умный дом» (Яндекс, Tuya, Home Assistant and the own IR remotes). No polling, nothing drawn per frame.
 */
@Composable
internal fun SmartHomeShortcut(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    val accent = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF2EE6C5), Color(0xFF5AA9FF), Color(0xFF9F8CFF)))
    Row(modifier.testTag("smart-home-shortcut")
        .graphicsLayer { val k = if (focused) 1.06f else 1f; scaleX = k; scaleY = k }
        .background(if (focused) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xEE2B4C7E), Color(0xEE3B2F7A)))
            else androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0x8810203A), Color(0x88201A44))), shape)
        .border(if (focused) 2.dp else 1.dp, if (focused) androidx.compose.ui.graphics.SolidColor(Color.White) else accent, shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable {
            runCatching { context.startActivity(Intent(context, com.example.homeezch.ai.SmartHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        .semantics { contentDescription = "Умный дом" }
        .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).background(accent, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                val w = size.width; val h = size.height
                val roof = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * .5f, h * .06f); lineTo(w * .96f, h * .46f); lineTo(w * .84f, h * .46f)
                    lineTo(w * .84f, h * .94f); lineTo(w * .16f, h * .94f); lineTo(w * .16f, h * .46f); lineTo(w * .04f, h * .46f); close()
                }
                drawPath(roof, Color.White)
                // Door and a small «signal» arc: a smart home, not just a house.
                drawRoundRect(Color(0xFF5AA9FF), topLeft = androidx.compose.ui.geometry.Offset(w * .41f, h * .60f),
                    size = androidx.compose.ui.geometry.Size(w * .18f, h * .34f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .04f))
                drawArc(Color(0xFF5AA9FF), 200f, 140f, false, topLeft = androidx.compose.ui.geometry.Offset(w * .33f, h * .36f),
                    size = androidx.compose.ui.geometry.Size(w * .34f, h * .26f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * .07f))
            }
        }
        Column {
            Text("Умный дом", color = Color.White, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text("свет · пульты · сценарии", color = if (focused) Color(0xFFD6E4FF) else Muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Claude r51: which smart-home devices sit on the desktop (chosen in Настройки → «Плитки умного дома»). */
internal object SmartDesk {
    private fun prefs(context: Context) = context.getSharedPreferences("smart_desk_v1", Context.MODE_PRIVATE)
    fun key(device: com.example.homeezch.home.SmartDevice) = "${device.provider}:${device.id}"
    fun pinned(context: Context): List<String> = prefs(context).getString("ids", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun setPinned(context: Context, keys: List<String>) = prefs(context).edit().putString("ids", keys.take(MAX).joinToString("\n")).apply()
    const val MAX = 6
    /** What one press of OK does on a tile; null = the tile only shows state (sensors, IR remotes). */
    fun toggle(device: com.example.homeezch.home.SmartDevice): com.example.homeezch.home.DeviceCommand? {
        val on = device.on == true
        return when (device.type) {
            com.example.homeezch.home.DeviceType.SCENE -> com.example.homeezch.home.DeviceCommand.Power(true)
            com.example.homeezch.home.DeviceType.VACUUM -> com.example.homeezch.home.DeviceCommand.Vacuum(if (on) "dock" else "start")
            com.example.homeezch.home.DeviceType.CURTAIN -> com.example.homeezch.home.DeviceCommand.Curtain(!on)
            com.example.homeezch.home.DeviceType.SENSOR, com.example.homeezch.home.DeviceType.REMOTE -> null
            else -> if (device.canPower) com.example.homeezch.home.DeviceCommand.Power(!on) else null
        }
    }
}

/** Smart-home tiles on the desktop: one press switches the light, AC, socket, starts/docks the vacuum, runs a scene.
 *  Shown only when the owner pinned devices; polls the hub once a minute and only while HOME is in front. */
@Composable
internal fun SmartDeskStrip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<com.example.homeezch.home.SmartDevice>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val pinned = SmartDesk.pinned(context)
                if (pinned.isEmpty() || !com.example.homeezch.home.SmartHub.anyConnected(context)) { devices = emptyList() }
                else {
                    fun order(list: List<com.example.homeezch.home.SmartDevice>) =
                        pinned.mapNotNull { k -> list.firstOrNull { SmartDesk.key(it) == k } }
                    if (devices.isEmpty()) devices = order(com.example.homeezch.home.SmartHub.cache)
                    val fresh = runCatching { com.example.homeezch.home.SmartHub.devices(context) }.getOrNull()
                    if (!fresh.isNullOrEmpty()) devices = order(fresh)
                }
                kotlinx.coroutines.delay(60_000)
            }
        }
    }
    if (devices.isEmpty()) return
    Row(modifier.testTag("smart-desk"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEach { device ->
            var focused by remember(device.id) { mutableStateOf(false) }
            val shape = RoundedCornerShape(14.dp)
            val accent = Color(device.type.color)
            val state = when {
                !device.online -> "нет связи"
                busy == SmartDesk.key(device) -> "…"
                device.reading != null -> device.reading.orEmpty()
                device.on == true -> "вкл"
                device.on == false -> "выкл"
                else -> ""
            }
            Row(Modifier.widthIn(max = 190.dp)
                .background(if (focused) Color.White else if (device.on == true) accent.copy(alpha = .30f) else Color(0x8810203A), shape)
                .border(1.dp, if (focused) Color.White else accent.copy(alpha = .6f), shape)
                .onFocusChanged { focused = it.isFocused }
                .clickable {
                    val command = SmartDesk.toggle(device) ?: return@clickable
                    val key = SmartDesk.key(device)
                    if (busy != null) return@clickable
                    busy = key
                    scope.launch {
                        val ok = runCatching { com.example.homeezch.home.SmartHub.send(context, device, command) }.getOrDefault(false)
                        if (ok && command is com.example.homeezch.home.DeviceCommand.Power && device.type != com.example.homeezch.home.DeviceType.SCENE)
                            devices = devices.map { if (SmartDesk.key(it) == key) it.copy(on = command.on) else it }
                        if (!ok) android.widget.Toast.makeText(context, "${device.name}: не удалось выполнить", android.widget.Toast.LENGTH_SHORT).show()
                        busy = null
                    }
                }
                .padding(horizontal = 10.dp, vertical = 6.dp).testTag("smart-${device.id}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(device.type.icon, fontSize = 14.sp)
                Text(device.name, color = if (focused) Color(0xFF10203A) else Color.White, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (state.isNotBlank()) Text(state, color = if (focused) Color(0xFF10203A) else accent, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** Settings → «Плитки умного дома»: tick up to six devices for the desktop. */
@Composable
internal fun SmartDeskPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf<List<com.example.homeezch.home.SmartDevice>?>(null) }
    var pinned by remember { mutableStateOf(SmartDesk.pinned(context)) }
    LaunchedEffect(Unit) {
        all = if (!com.example.homeezch.home.SmartHub.anyConnected(context)) emptyList()
        else runCatching { com.example.homeezch.home.SmartHub.devices(context) }.getOrNull().orEmpty()
    }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Плитки умного дома на рабочем столе") },
        text = {
            val list = all
            when {
                list == null -> Text("Загружаю устройства…")
                list.isEmpty() -> Text("Умный дом не подключён или не ответил. Подключите Яндекс, Tuya или Home Assistant в «Умном доме».")
                else -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Выбрано ${pinned.size} из ${SmartDesk.MAX}. ОК на устройстве — добавить или убрать.", color = Muted, fontSize = 12.sp)
                    list.filter { it.type != com.example.homeezch.home.DeviceType.SENSOR || it.reading != null }.forEach { device ->
                        val key = SmartDesk.key(device)
                        val checked = key in pinned
                        androidx.compose.material3.TextButton(onClick = {
                            pinned = if (checked) pinned - key else if (pinned.size < SmartDesk.MAX) pinned + key else pinned
                            SmartDesk.setPinned(context, pinned)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (checked) "☑" else "☐"}  ${device.type.icon} ${device.name}${if (device.room.isNotBlank()) " · ${device.room}" else ""}",
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Готово") } })
}
