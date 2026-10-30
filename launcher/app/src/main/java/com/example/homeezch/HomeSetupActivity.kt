package com.example.homeezch

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.homeezch.ai.AiPreferences
import com.example.homeezch.ai.AndroidCapabilityProbe
import com.example.homeezch.ai.AssistantTakeover
import com.example.homeezch.ai.EvaAppService
import kotlinx.coroutines.delay

/**
 * «Настроить EZCH» — first-run and settings screen (R31). Every privileged change is the owner's own press in
 * an Android window; this screen only explains, opens the exact system screen and re-checks the result.
 */
class HomeSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val firstRun = intent.getBooleanExtra("first_run", false)
        setContent {
            WzSetupScreen(firstRun) { finished ->
                SetupWizard.prefs(this).edit().putBoolean("setup_reviewed_v24", true).apply {
                    if (finished) putBoolean(SetupWizard.FINISHED, true).putBoolean(SetupWizard.LATER, false)
                    else putBoolean(SetupWizard.LATER, true)
                }.apply()
                finish()
            }
        }
    }
}

private val WzBg = Brush.linearGradient(listOf(Color(0xFF07111F), Color(0xFF0F2138), Color(0xFF1A1236)))
private val WzCardBg = Color(0xFF13233A)
private val WzCardFocus = Color(0xFF1E3A5F)
private val WzAccent = Color(0xFF4DA3FF)
private val WzOk = Color(0xFF3DDC97)
private val WzWarn = Color(0xFFFFC857)
private val WzMuted = Color(0xFFAFC0D6)

private data class WzStepText(val icon: String, val title: String, val why: String, val press: String)

private fun WzstepText(step: SetupStep) = when (step) {
    SetupStep.HOME -> WzStepText("⌂", "Основной лаунчер",
        "Кнопка «Домой» на пульте будет открывать рабочий стол EZCH.",
        "Откроется окно Android. Выберите «EZCH» и подтвердите выбор.")
    SetupStep.HOME_SERVICE -> WzStepText("◎", "Служба EZCH",
        "Кнопки «Домой» и микрофона работают из любого приложения.",
        "Откроются «Спец. возможности». Выберите «EZCH Launcher — кнопка Home» → Включить → ОК.")
    SetupStep.EVA_SERVICE -> WzStepText("✦", "Служба Ai EvA",
        "Ева управляет приложениями: ищет фильм, нажимает «Смотреть», появляется поверх приложения.",
        "Откроются «Спец. возможности». Выберите «Ai EvA — управление приложениями» → Включить → ОК.")
    SetupStep.EVA_VOICE -> WzStepText("🎤", "Ева и микрофон",
        "Голосовые команды с кнопки микрофона на пульте.",
        "Android спросит доступ к микрофону — нажмите «Разрешить».")
}

private fun WzstateLabel(state: StepState) = when (state) {
    StepState.DONE -> "Готово"
    StepState.COVERED -> "Через службу EZCH"
    StepState.WAITING -> "Ждём Android…"
    StepState.TODO -> "Нужно включить"
    StepState.SKIPPED -> "Пропущено"
}

@Composable
private fun WzSetupScreen(firstRun: Boolean, close: (finished: Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var facts by remember { mutableStateOf(SetupWizard.facts(context)) }
    var skipped by remember { mutableStateOf(SetupWizard.skipped(context)) }
    var resumed by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<SetupStep?>(null) }
    var notice by remember { mutableStateOf("") }
    val attempts = remember { mutableStateMapOf<SetupStep, Int>() }
    val main = remember { FocusRequester() }
    val next = nextSetupStep(facts, skipped)
    val done = setupDoneCount(facts, skipped)
    val total = SetupStep.values().size

    fun refresh() { facts = SetupWizard.facts(context); skipped = SetupWizard.skipped(context) }
    fun afterReturn() {
        val step = pending ?: return
        refresh()
        val state = stepState(step, facts, skipped)
        notice = when {
            state == StepState.DONE || state == StepState.COVERED -> "✓ «${WzstepText(step).title}» — готово." +
                (nextSetupStep(facts, skipped)?.let { " Дальше: «${WzstepText(it).title}»." } ?: " Всё настроено.")
            state == StepState.WAITING -> "Служба включена, Android подключает её — подождите пару секунд."
            step == SetupStep.HOME && HomeRole.holdsRole(context) ->
                "Android принял выбор, но кнопка «Домой» по-прежнему открывает другой экран. Включите «Службу EZCH» — она вернёт в EZCH."
            else -> "«${WzstepText(step).title}» пока не включено. Можно повторить или пропустить шаг."
        }
        if (state != StepState.WAITING) pending = null
        runCatching { main.requestFocus() }
    }
    val homeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterReturn() }
    val micRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterReturn() }

    fun openAccessibility(cls: Class<*>) {
        val service = ComponentName(context, cls).flattenToString()
        SettingsRouter.open(context, listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra("android.intent.extra.COMPONENT_NAME", service),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
    }
    fun run(step: SetupStep) {
        if (step in skipped) { SetupWizard.setSkipped(context, step, false); refresh() }
        pending = step; attempts[step] = (attempts[step] ?: 0) + 1; notice = ""
        when (step) {
            SetupStep.HOME -> {
                // ROLE_HOME needs a calling Activity; NEW_TASK would lose the result (R26).
                val intent = HomeRole.requestIntent(context)
                if (intent == null) {
                    notice = "Эта прошивка не даёт выбрать лаунчер. Включите «Службу EZCH» — она вернёт в EZCH по кнопке «Домой»."
                    pending = null
                } else runCatching { homeRequest.launch(Intent(intent).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }) }
                    .onFailure { notice = "Системный выбор лаунчера недоступен. Включите «Службу EZCH»."; pending = null }
            }
            SetupStep.HOME_SERVICE -> openAccessibility(HomeButtonService::class.java)
            SetupStep.EVA_SERVICE -> openAccessibility(EvaAppService::class.java)
            SetupStep.EVA_VOICE -> {
                AiPreferences.store(context).edit().putBoolean("enabled", true).putBoolean("mic_hint_declined", false).apply()
                AssistantTakeover.setEnabled(context, true)
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    runCatching { micRequest.launch(Manifest.permission.RECORD_AUDIO) }.onFailure { afterReturn() }
                else afterReturn()
            }
        }
    }
    fun skip(step: SetupStep) {
        SetupWizard.setSkipped(context, step, true); refresh()
        if (step == SetupStep.EVA_VOICE) AiPreferences.store(context).edit().putBoolean("mic_hint_declined", true).apply()
        notice = "«${WzstepText(step).title}» пропущено. Вернуться можно здесь же: Настройки → Настроить EZCH."
        runCatching { main.requestFocus() }
    }

    BackHandler { close(nextSetupStep(facts, skipped) == null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; if (pending != null && pending != SetupStep.HOME && pending != SetupStep.EVA_VOICE) afterReturn() else refresh() }
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // An accessibility service connects a moment after Settings closes: re-check while this screen is visible.
    LaunchedEffect(resumed) {
        while (resumed) {
            delay(1000)
            val before = facts
            refresh()
            if (pending != null && before != facts) afterReturn()
        }
    }
    LaunchedEffect(Unit) { runCatching { main.requestFocus() } }

    Box(Modifier.fillMaxSize().background(WzBg).padding(horizontal = 48.dp, vertical = 32.dp).testTag("setup-wizard")) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // Left: what this is, progress, one main button.
            Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text("EZCH", color = WzAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Spacer(Modifier.height(10.dp))
                Text(if (next == null) "Всё готово" else if (firstRun) "Добро пожаловать!" else "Настройка EZCH",
                    color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp)
                Spacer(Modifier.height(10.dp))
                Text(if (next == null) "EZCH — основной экран, Ева слушает кнопку микрофона. Изменить можно в настройках."
                    else "Четыре коротких шага. Каждый открывает нужный экран Android и подсказывает, что нажать. Готовые шаги пропускаются.",
                    color = WzMuted, fontSize = 16.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(22.dp))
                Text("Готово $done из $total", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(done / total.toFloat()).fillMaxHeight().background(WzOk))
                }
                Spacer(Modifier.height(26.dp))
                WzBigButton(
                    text = when {
                        next == null -> "Готово — к рабочему столу"
                        done == 0 && skipped.isEmpty() -> "Настроить EZCH"
                        else -> "Продолжить: ${WzstepText(next).title}"
                    },
                    primary = true, modifier = Modifier.focusRequester(main).testTag("setup-main"),
                ) { if (next == null) close(true) else run(next) }
                if (next != null) {
                    Spacer(Modifier.height(12.dp))
                    WzBigButton("Позже", primary = false, modifier = Modifier.testTag("setup-later")) { close(false) }
                    Spacer(Modifier.height(14.dp))
                    Text(WzstepText(next).press, color = WzWarn, fontSize = 15.sp, lineHeight = 21.sp)
                }
                if (notice.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(notice, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.testTag("setup-notice"))
                }
            }
            // Right: the steps as large cards with their real state.
            Column(Modifier.weight(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                SetupStep.values().forEachIndexed { i, step ->
                    WzStepCard(i + 1, step, stepState(step, facts, skipped), current = step == next,
                        onOpen = { run(step) }, onSkip = { skip(step) })
                }
                val restricted = (attempts[SetupStep.HOME_SERVICE] ?: 0) + (attempts[SetupStep.EVA_SERVICE] ?: 0) > 0 &&
                    (!facts.homeServiceEnabled && SetupStep.HOME_SERVICE !in skipped || !facts.evaServiceEnabled && SetupStep.EVA_SERVICE !in skipped)
                if (restricted) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Служба не включается или пишет «Ограниченная настройка»? Откройте свойства EZCH → ⋮ → «Разрешить ограниченные настройки», затем повторите шаг.",
                        color = WzMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                    WzSmallButton("Свойства EZCH") { SettingsRouter.appDetails(context) }
                }
                val canInstall = remember(facts) { runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false) }
                if (!canInstall) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("По желанию: разрешить EZCH устанавливать свои обновления.", color = WzMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    WzSmallButton("Разрешить") { SettingsRouter.installPermission(context) }
                }
                if (facts.evaOn && SetupStep.EVA_VOICE !in skipped) {
                    val probe = remember { AndroidCapabilityProbe(context) }
                    if (probe.roleAvailable() && !probe.roleHeld()) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("По желанию: назначить Еву системным помощником (Android попросит подтвердить).",
                            color = WzMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        WzSmallButton("Назначить") { probe.requestRole(activity) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WzStepCard(number: Int, step: SetupStep, state: StepState, current: Boolean, onOpen: () -> Unit, onSkip: () -> Unit) {
    val text = WzstepText(step)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val finished = state == StepState.DONE || state == StepState.COVERED
    val chip = when (state) { StepState.DONE, StepState.COVERED -> WzOk; StepState.WAITING -> WzWarn; StepState.SKIPPED -> WzMuted; StepState.TODO -> WzAccent }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f)
            .graphicsLayer { val k = if (focused) 1.02f else 1f; scaleX = k; scaleY = k }
            .clip(shape).background(if (focused) WzCardFocus else WzCardBg)
            .border(if (focused) 3.dp else if (current) 1.5.dp else 1.dp,
                if (focused) Color.White else if (current) WzAccent else Color(0xFF2A3D57), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("setup-step-${step.name.lowercase()}"),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(if (finished) WzOk else Color(0x334DA3FF)), contentAlignment = Alignment.Center) {
                Text(if (finished) "✓" else number.toString(), color = if (finished) Color(0xFF06241A) else Color.White,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("${text.icon}  ${text.title}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(text.why, color = WzMuted, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2)
            }
            Spacer(Modifier.width(12.dp))
            Text(WzstateLabel(state), color = Color(0xFF0A1422), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(chip).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        if (!finished && state != StepState.SKIPPED) WzSmallButton("Пропустить", onSkip)
    }
}

@Composable
private fun WzBigButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().height(58.dp)
        .graphicsLayer { val k = if (focused) 1.03f else 1f; scaleX = k; scaleY = k }
        .clip(shape)
        .background(when { focused && primary -> Color(0xFF5CB0FF); primary -> Color(0xFF2E7DD7); focused -> Color(0xFF3B5578); else -> Color(0xFF233650) })
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun WzSmallButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.height(44.dp).clip(shape).background(if (focused) Color(0xFF3B5578) else Color(0xFF1C2D45))
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF2A3D57), shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 15.sp, maxLines = 1)
    }
}
