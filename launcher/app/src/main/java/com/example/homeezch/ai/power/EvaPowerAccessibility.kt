package com.example.homeezch.ai.power

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.homeezch.ai.Outcome
import kotlinx.coroutines.delay

/** A reboot request is already confirmed by EvaCore. Never click text inside media apps. */
object EvaPowerAccessibility {
    val REBOOT_LABELS = setOf("перезагрузить", "перезагрузка", "перезапустить", "restart", "reboot")
    private val confirmation = REBOOT_LABELS + setOf("ок", "ok", "да", "yes", "подтвердить")
    fun trustedPackage(name: String?) = name in setOf("android", "com.android.systemui")
    suspend fun reboot(service: AccessibilityService): Outcome {
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG))
            return Outcome(false, "Системное меню питания недоступно. Зажмите питание на пульте")
        if (!waitAndClick(service, REBOOT_LABELS, 3000))
            return Outcome(false, "В системном меню не найден пункт «Перезагрузить». Выберите его пультом")
        // The optional second dialog must be in the same trusted system surface, after a reboot item was clicked.
        delay(500)
        waitAndClick(service, confirmation, 1500, confirmationOnly = true)
        return Outcome(true, "Команда перезагрузки отправлена в системное меню")
    }
    private suspend fun waitAndClick(service: AccessibilityService, labels: Set<String>, timeout: Long, confirmationOnly: Boolean = false): Boolean {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            for (window in service.windows) {
                val root = window.root ?: continue
                if (!trustedPackage(root.packageName?.toString())) continue
                // A generic «OK/Да» in an unrelated system dialog is not reboot confirmation.
                if (confirmationOnly && find(root, REBOOT_LABELS, 0) == null) continue
                val node = find(root, labels, 0) ?: continue
                var candidate: AccessibilityNodeInfo? = node
                repeat(6) {
                    val c = candidate ?: return@repeat
                    if (!trustedPackage(c.packageName?.toString())) { candidate = null; return@repeat }
                    if (c.isEnabled && c.isVisibleToUser && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    candidate = c.parent
                }
            }
            delay(150)
        }
        return false
    }
    private fun find(node: AccessibilityNodeInfo, labels: Set<String>, depth: Int): AccessibilityNodeInfo? {
        if (depth > 20 || !trustedPackage(node.packageName?.toString())) return null
        val text = (node.text ?: node.contentDescription)?.toString()?.lowercase(java.util.Locale.ROOT)?.trim()?.trimEnd('?', '!')
        if (node.isEnabled && node.isVisibleToUser && text in labels) return node
        for (i in 0 until node.childCount.coerceAtMost(100)) node.getChild(i)?.let { find(it, labels, depth + 1)?.let { result -> return result } }
        return null
    }
}
