package com.example.homeezch
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
class HomeButtonService : AccessibilityService() {
    private var held = false
    private var sent = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun open(menu: Boolean) = runCatching {
        startActivity(Intent(this, MainActivity::class.java).putExtra("ezch.home.menu",menu)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
    private val longHome = Runnable { if (held && !sent) { sent = true; open(true) } }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { handler.removeCallbacks(longHome); held = false }
    override fun onDestroy() { handler.removeCallbacks(longHome); super.onDestroy() }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            held = true; sent = false; handler.postDelayed(longHome,600); return true
        }
        if (held && event.action == KeyEvent.ACTION_DOWN && event.isLongPress && !sent) {
            handler.removeCallbacks(longHome); sent = true; open(true)
        }
        val consumed = held
        if (event.action == KeyEvent.ACTION_UP || event.isCanceled) {
            handler.removeCallbacks(longHome)
            if (held && !sent && !event.isCanceled) open(false)
            held = false
        }
        return consumed
    }
}
