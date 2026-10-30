package com.example.homeezch.ai

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Android access belongs to the host. The scenario engine is shared by release and tests. */
interface UiEnvironment {
    val enabled: Boolean
    val profileId: String
    val activePackage: String?
    val globalActions: Set<Int>
    fun root(): AccessibilityNodeInfo?
    fun allowed(packageName: String): Boolean
    fun globalAction(action: Int): Boolean
    fun tap(bounds: Rect): Boolean
    fun isPlaying(packageName: String): Boolean?
    suspend fun openPackage(packageName: String): Boolean
    /** R29: the on-screen keyboard window (its «Искать» key submits WebView searches such as Lift). */
    fun imeRoot(): AccessibilityNodeInfo? = null
}
