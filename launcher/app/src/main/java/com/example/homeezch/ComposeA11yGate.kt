package com.example.homeezch

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat

/**
 * Claude r84: EZCH's own accessibility services (Eva app control, Home/mic button) are enabled on the TV. Compose treats
 * ANY enabled service as a screen reader and rebuilds/diffs the whole desktop semantics tree on the main thread after
 * every focus move (Homatics trace: ~45 % of the launcher's main-thread time, between frames → jank).
 *
 * Our services never read the launcher's own window (EvaAppService skips our package), so while every enabled service
 * belongs to EZCH and touch exploration is off, the desktop's Compose accessibility bridge is told that no service is
 * listening. A real screen reader (TalkBack or any foreign service) restores normal behaviour on the next resume or
 * touch-exploration change. Desktop window only; dialogs, Settings and Smart Home keep full accessibility.
 */
internal object ComposeA11yGate {
    @Volatile var lastState: String = "unset"; private set

    /** True when every enabled accessibility service is one of ours and no screen reader is active. */
    internal fun onlyOwnServices(ownPackage: String, enabled: List<String>, touchExploration: Boolean): Boolean =
        !touchExploration && enabled.isNotEmpty() && enabled.all { it.substringBefore('/') == ownPackage }

    /** Screen readers speak, braille or touch-explore; key mappers and our own services do none of that. */
    internal const val READER_FEEDBACK = AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE or
        AccessibilityServiceInfo.FEEDBACK_AUDIBLE or AccessibilityServiceInfo.FEEDBACK_HAPTIC
    internal fun noScreenReader(ownPackage: String, services: List<Pair<String, Int>>, touchExploration: Boolean): Boolean =
        !touchExploration && services.isNotEmpty() && services.all { (id, feedback) ->
            id.substringBefore('/') == ownPackage || feedback and READER_FEEDBACK == 0
        }

    fun apply(root: View) {
        val am = root.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
        val services = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { (it.id ?: "?") to it.feedbackType }
        }.getOrDefault(emptyList())
        val enabled = services.map { "${it.first}#${it.second}" }
        val quiet = am.isEnabled && noScreenReader(root.context.packageName, services, am.isTouchExplorationEnabled)
        val views = composeViews(root)
        if (views.isEmpty()) lastState = "no-compose-view"
        views.forEach { view -> runCatching { set(view, am, quiet) }.onFailure { lastState = "error ${it.javaClass.simpleName}" } }
        lastState += " enabled=${am.isEnabled} touch=${am.isTouchExplorationEnabled} services=$enabled views=${views.size}"
    }

    private fun set(view: View, am: AccessibilityManager, quiet: Boolean) {
        val delegate = ViewCompat.getAccessibilityDelegate(view) ?: return
        // The delegate class and this field keep their names in release (proguard-rules.pro, r84).
        val field = runCatching { delegate.javaClass.getDeclaredField("_enabledServices") }.getOrNull()
            ?.apply { isAccessible = true } ?: run { lastState = "no-field ${delegate.javaClass.name}"; return }
        val listener = delegate as? AccessibilityManager.AccessibilityStateChangeListener
        if (quiet) {
            // The delegate re-reads the service list when accessibility state changes; detach that one listener
            // (touch-exploration listener stays, so turning on TalkBack still wakes it up) and report no services.
            listener?.let { am.removeAccessibilityStateChangeListener(it) }
            field.set(delegate, emptyList<AccessibilityServiceInfo>())
            lastState = "quiet"
        } else {
            listener?.let { am.removeAccessibilityStateChangeListener(it); am.addAccessibilityStateChangeListener(it) }
            field.set(delegate, null) // re-read lazily: normal Compose behaviour
            lastState = "normal"
        }
    }

    private fun composeViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            // Type check, not a class name: R8 renames library classes in the release APK.
            if (v is androidx.compose.ui.platform.ViewRootForTest) { out += v; return }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }
}
