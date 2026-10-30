package com.example.homeezch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * The setup screen opens by itself at most once per install, and never once HOME already works.
 * Claude r23: before this it reopened on every HOME start until «К рабочему столу» was pressed and
 * install permission was granted, so leaving it with HOME/Back or a firmware that routes HOME through
 * the service brought it back again and again. It stays reachable from the settings tile.
 */
internal fun shouldAutoOpenSetup(reviewed: Boolean, autoShown: Boolean, state: HomeSetupState): Boolean =
    !reviewed && !autoShown && !state.homeAvailable

/**
 * R25 first-run activation (owner's request 28.10.2026): on the first start the launcher asks, one
 * question at a time with «Да» / «Нет», whether to turn on Eva, make EZCH the main screen and give the
 * remote's microphone button to Eva. Every answer is the user's own: «Нет» simply skips the step and
 * nothing is enabled behind his back. Questions already satisfied on this TV are not asked at all.
 * Shown once per install; the progress is saved, so returning from a system screen continues it.
 */
internal enum class ActivationStep { EVA, HOME, MIC, DONE }

internal data class ActivationFacts(val evaOn: Boolean, val isHome: Boolean, val micReady: Boolean)

/** Next question to ask after [after] (null = from the start), skipping ones already satisfied. */
internal fun nextActivationStep(after: ActivationStep?, facts: ActivationFacts): ActivationStep {
    val order = ActivationStep.values().toList()
    var i = if (after == null) 0 else order.indexOf(after) + 1
    while (i < order.size) {
        val step = order[i]
        val needed = when (step) {
            ActivationStep.EVA -> !facts.evaOn
            ActivationStep.HOME -> !facts.isHome
            ActivationStep.MIC -> facts.evaOn && !facts.micReady
            ActivationStep.DONE -> true
        }
        if (needed) return step
        i++
    }
    return ActivationStep.DONE
}

private const val ACTIVATION_DONE = "activation_v1_done"

/**
 * R31 (owner 30.10.2026, «как у Snow»): the first start opens the full «Настроить EZCH» screen once — large step
 * cards instead of a chain of Yes/No dialogs. «Позже» or Back closes it for good; it stays in Settings.
 * The old one-question dialogs (ActivationStep above) are kept only as the tested ordering rule.
 */
@Composable
internal fun LaunchSetup(onReady: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = SetupWizard.prefs(context)
        val next = runCatching { nextSetupStep(SetupWizard.facts(context), SetupWizard.skipped(context)) }.getOrNull()
        if (shouldAutoOpenWizard(prefs.getBoolean(SetupWizard.AUTO_SHOWN, false), prefs.getBoolean(SetupWizard.LATER, false),
                prefs.getBoolean(SetupWizard.FINISHED, false), next)) {
            // Marked before opening: a crash or HOME press inside the wizard must not bring it back on every start.
            prefs.edit().putBoolean(SetupWizard.AUTO_SHOWN, true).putBoolean(ACTIVATION_DONE, true)
                .putBoolean("setup_auto_shown_v47", true).apply()
            SetupWizard.open(context, firstRun = true)
        }
        onReady()
    }
}
