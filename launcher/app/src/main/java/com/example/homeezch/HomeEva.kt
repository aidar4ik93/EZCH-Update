package com.example.homeezch

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.homeezch.ai.*
import kotlinx.coroutines.delay

/** Compact foreground fallback works without granting accessibility access. */
@Composable
internal fun HomeEva(request: Int, cardWidth: Dp, onNavigation: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val navigate by rememberUpdatedState(onNavigation)
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(false) }
    var activity by remember { mutableLongStateOf(0L) }
    var session by remember { mutableIntStateOf(0) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<VoicePanel?>(null) }
    var caption by remember { mutableStateOf("") }
    /** Hands-free heard «Ева»: the resting orb lights up until the command panel takes over. */
    var wakeHeard by remember { mutableStateOf(false) }
    val externalPanel by EvaAppService.panelVisibility.collectAsState()
    fun open() {
        com.example.homeezch.ai.VoiceTiming.mark(com.example.homeezch.ai.VoiceTiming.Phase.PANEL_REQUESTED)
        // The wake recognizer must release its microphone before the command recognizer starts.
        EvaEngine.release {
            if (resumed) {
                activity = android.os.SystemClock.elapsedRealtime()
                if (EvaAppService.current != null) EvaAppService.current?.showMiniPanel()
                else { visible = true; session++ }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingStart = true
        else android.widget.Toast.makeText(context, "Для голосовых команд разрешите микрофон в настройках EVA", android.widget.Toast.LENGTH_LONG).show()
    }
    fun ask() {
        if (EvaEngine.hasMicPermission(context)) pendingStart = true
        else runCatching { permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    LaunchedEffect(request) { if (request > 0) ask() }
    LaunchedEffect(resumed, pendingStart) {
        if (resumed && pendingStart) { pendingStart = false; open() }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE) {
                // Only free the microphone here; the model stays loaded so returning to HOME does not
                // re-read ~50 MB and stall navigation. MainActivity.onTrimMemory releases it when hidden.
                resumed = false; panel?.pause(); visible = false; pendingText = null; EvaEngine.stop()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); EvaEngine.release() }
    }
    val wakeCommand = remember {
        { text: String ->
            wakeHeard = false
            EvaEngine.release {
                if (resumed) {
                    activity = android.os.SystemClock.elapsedRealtime()
                    val service = EvaAppService.current
                    if (service != null) service.submitCommand(text)
                    else { pendingText = text.takeIf { it.isNotBlank() }; visible = true; session++ }
                }
            }
        }
    }
    // Text from the phone page (📱 Телефон) goes through the same path as a spoken command.
    val inboxText by com.example.homeezch.phone.EvaInbox.text.collectAsState()
    LaunchedEffect(inboxText, resumed) {
        if (resumed && inboxText != null) com.example.homeezch.phone.EvaInbox.take()?.let { wakeCommand(it) }
    }
    DisposableEffect(wakeCommand) {
        EvaEngine.attach(wakeCommand) { message ->
            if (message.startsWith("Я слушаю")) wakeHeard = true
            if (message.contains("недоступ") || message.contains("занят"))
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        onDispose { EvaEngine.detach(wakeCommand) }
    }
    LaunchedEffect(resumed, visible, request, externalPanel) {
        if (resumed && !visible && !externalPanel) {
            delay(2000) // let HOME finish its first frames before the wake listener starts
            EvaEngine.startWake(context)
        } else EvaEngine.stop()
    }
    // Warm the shared speech engine while HOME is idle, so EVA's first answer is not lost or late.
    LaunchedEffect(resumed) {
        if (resumed && com.example.homeezch.ai.AiPreferences.enabled(context) &&
            com.example.homeezch.ai.AiPreferences.store(context).getBoolean("tts", true)) {
            delay(500)
            com.example.homeezch.ai.EvaTts.warm(context)
        }
    }
    // «Ева» without a following command: the engine goes back to sleep, so must the orb (no endless animation).
    LaunchedEffect(wakeHeard) { if (wakeHeard) { delay(8_000); wakeHeard = false } }
    LaunchedEffect(visible, activity) {
        if (visible) { delay(30_000); visible = false }
    }
    if (visible) {
        Column(horizontalAlignment = Alignment.End) {
        if (caption.isNotBlank()) Text(caption, color = Color.White, fontSize = 12.sp, maxLines = 3,
            modifier = Modifier.widthIn(max = 260.dp).background(Color(0xDD0A1020)).padding(8.dp))
        AndroidView(factory = { ctx ->
            VoicePanel(ctx, homeResult = { navigate(it) }, close = { visible = false }, mini = true,
                onActivity = { activity = android.os.SystemClock.elapsedRealtime() },
                onStatusText = { caption = it }).also { panel = it }.view
        }, modifier = Modifier.size(cardWidth / 1.5f))
        }
        // Capture the panel belonging to this effect key. The AndroidView factory writes panel
        // before the effect with the old null key runs; reading mutable panel inside that effect
        // could start listening twice, and the second listen() immediately finishes recognition.
        val activePanel = panel
        LaunchedEffect(activePanel, session) {
            val target = activePanel ?: return@LaunchedEffect
            val text = pendingText
            pendingText = null
            if (text != null) target.submit(text) else target.listen()
        }
        DisposableEffect(Unit) { onDispose { panel?.destroy(); panel = null } }
    } else if (!externalPanel) {
        // The compact animated sphere is always present on HOME (not only after a button press).
        // GPT R27 on code 100: «сферу не могу выбрать» — the sphere is now a remote target: arrows reach it,
        // OK starts listening exactly like the microphone button (or asks for the microphone first).
        LaunchedEffect(visible) { wakeHeard = false }
        var orbFocused by remember { mutableStateOf(false) }
        Box(Modifier.size(cardWidth / 1.5f)
            .graphicsLayer { val k = if (orbFocused) 1.08f else 1f; scaleX = k; scaleY = k }
            .border(if (orbFocused) 3.dp else 0.dp, if (orbFocused) Color.White else Color.Transparent, CircleShape)
            .onFocusChanged { orbFocused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { ask() }
            .semantics { contentDescription = "Ai EvA — нажмите ОК и скажите команду" }
            .testTag("eva-sphere")) {
            AndroidView(factory = { ctx -> EvaMiniView(ctx) }, update = { orb -> orb.busy = wakeHeard || orbFocused },
                modifier = Modifier.fillMaxSize())
        }
    }
}
