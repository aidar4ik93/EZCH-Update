package com.example.homeezch

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager

/**
 * R31 first-run setup (owner 30.10.2026, «как у Snow»): one clear screen with large step cards.
 * Order: main launcher → EZCH service → Ai EvA service → Eva and microphone. Each step is checked against
 * the real state of the TV after every return from a system screen; finished steps are skipped.
 * Android confirmations stay the owner's: the wizard only opens the right system screen and says what to press.
 */
internal enum class SetupStep { HOME, HOME_SERVICE, EVA_SERVICE, EVA_VOICE }

internal enum class StepState { DONE, COVERED, WAITING, TODO, SKIPPED }

internal data class SetupFacts(
    val effectiveHome: Boolean,
    val homeServiceEnabled: Boolean,
    val homeServiceConnected: Boolean,
    val evaServiceEnabled: Boolean,
    val evaServiceConnected: Boolean,
    val evaOn: Boolean,
    val micGranted: Boolean,
    val micTakeover: Boolean,
)

internal fun stepState(step: SetupStep, facts: SetupFacts, skipped: Set<SetupStep> = emptySet()): StepState = when (step) {
    SetupStep.HOME -> when {
        facts.effectiveHome -> StepState.DONE
        // Firmware keeps its own HOME, but the connected EZCH service brings HOME back to EZCH.
        facts.homeServiceConnected -> StepState.COVERED
        step in skipped -> StepState.SKIPPED
        else -> StepState.TODO
    }
    SetupStep.HOME_SERVICE -> when {
        facts.homeServiceEnabled && facts.homeServiceConnected -> StepState.DONE
        facts.homeServiceEnabled -> StepState.WAITING
        step in skipped -> StepState.SKIPPED
        else -> StepState.TODO
    }
    SetupStep.EVA_SERVICE -> when {
        facts.evaServiceEnabled && facts.evaServiceConnected -> StepState.DONE
        facts.evaServiceEnabled -> StepState.WAITING
        step in skipped -> StepState.SKIPPED
        else -> StepState.TODO
    }
    SetupStep.EVA_VOICE -> when {
        facts.evaOn && facts.micGranted && facts.micTakeover -> StepState.DONE
        step in skipped -> StepState.SKIPPED
        else -> StepState.TODO
    }
}

internal val StepState.finished: Boolean get() = this == StepState.DONE || this == StepState.COVERED || this == StepState.SKIPPED

/** The step «Настроить EZCH» should take next, or null when everything is done or skipped. */
internal fun nextSetupStep(facts: SetupFacts, skipped: Set<SetupStep>): SetupStep? =
    SetupStep.values().firstOrNull { !stepState(it, facts, skipped).finished }

internal fun setupDoneCount(facts: SetupFacts, skipped: Set<SetupStep> = emptySet()): Int =
    SetupStep.values().count { stepState(it, facts, skipped).let { s -> s == StepState.DONE || s == StepState.COVERED } }

/** The first-run screen opens by itself once per install, never after «Позже», never when nothing is left. */
internal fun shouldAutoOpenWizard(autoShown: Boolean, later: Boolean, finished: Boolean, next: SetupStep?): Boolean =
    !autoShown && !later && !finished && next != null

/**
 * The microphone reminder on the desktop: only for someone who went through the setup, wanted Eva on the mic button,
 * and later lost both services (e.g. after a firmware update). Never after «Позже», «Нет» or a skipped voice step.
 */
internal fun shouldShowMicHint(wizardFinished: Boolean, later: Boolean, declined: Boolean, voiceSkipped: Boolean,
                               evaOn: Boolean, anyServiceConnected: Boolean): Boolean =
    wizardFinished && !later && !declined && !voiceSkipped && evaOn && !anyServiceConnected

internal object SetupWizard {
    const val PREFS = "home_setup"
    const val AUTO_SHOWN = "onboarding_v2_auto_shown"
    const val LATER = "onboarding_v2_later"
    const val FINISHED = "onboarding_v2_done"
    const val SKIPPED = "onboarding_v2_skipped"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun skipped(context: Context): Set<SetupStep> = prefs(context).getStringSet(SKIPPED, emptySet()).orEmpty()
        .mapNotNull { runCatching { SetupStep.valueOf(it) }.getOrNull() }.toSet()

    fun setSkipped(context: Context, step: SetupStep, value: Boolean) {
        val now = skipped(context).let { if (value) it + step else it - step }
        prefs(context).edit().putStringSet(SKIPPED, now.map { it.name }.toSet()).apply()
    }

    private fun serviceEnabled(context: Context, cls: Class<*>): Boolean {
        val own = ComponentName(context, cls)
        return runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
                .any { ComponentName.unflattenFromString(it.id) == own }
        }.getOrDefault(false)
    }

    fun facts(context: Context): SetupFacts {
        val homeOn = serviceEnabled(context, HomeButtonService::class.java)
        val evaOn = serviceEnabled(context, com.example.homeezch.ai.EvaAppService::class.java)
        return SetupFacts(
            effectiveHome = HomeRole.isHome(context),
            homeServiceEnabled = homeOn,
            homeServiceConnected = homeOn && HomeButtonService.connected,
            evaServiceEnabled = evaOn,
            evaServiceConnected = evaOn && com.example.homeezch.ai.EvaAppService.current != null,
            evaOn = com.example.homeezch.ai.AiPreferences.enabled(context),
            micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            micTakeover = com.example.homeezch.ai.AiPreferences.store(context).getBoolean("mic_takeover", true),
        )
    }

    fun progressLabel(context: Context): String {
        val f = facts(context); val s = skipped(context)
        return if (nextSetupStep(f, s) == null) "Готово" else "${setupDoneCount(f, s)} из ${SetupStep.values().size}"
    }

    fun micHintNeeded(context: Context): Boolean {
        val p = prefs(context)
        val f = facts(context)
        return shouldShowMicHint(p.getBoolean(FINISHED, false), p.getBoolean(LATER, false),
            com.example.homeezch.ai.AiPreferences.store(context).getBoolean("mic_hint_declined", false),
            SetupStep.EVA_VOICE in skipped(context), f.evaOn, HomeButtonService.connected || f.evaServiceConnected)
    }

    fun open(context: Context, firstRun: Boolean = false): Boolean = SettingsRouter.open(context,
        listOf(android.content.Intent(context, HomeSetupActivity::class.java).putExtra("first_run", firstRun)))
}
