package com.example.homeezch.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager

/**
 * One way to call Eva from anywhere (mic button, assistant session, ASSIST intent, takeover of Google Assistant).
 * GPT R27 on code 100: calling Eva inside an app threw the user out to the desktop, because without the
 * «Ai EvA» accessibility service every path started MainActivity. Now:
 *  - service connected → its small panel over the app (as before);
 *  - EZCH desktop on screen → the desktop's own sphere;
 *  - otherwise → a small floating Eva window over the app; closing it returns to the same app and place.
 */
object EvaEntry {
    internal enum class Route { SERVICE_PANEL, HOME_SPHERE, OVER_APP }

    internal fun route(serviceConnected: Boolean, homeVisible: Boolean): Route = when {
        serviceConnected -> Route.SERVICE_PANEL
        homeVisible -> Route.HOME_SPHERE
        else -> Route.OVER_APP
    }

    fun open(context: Context) {
        val service = EvaAppService.current
        val homeVisible = com.example.homeezch.phone.PhoneRemote.home?.get() != null
        when (route(service != null, homeVisible)) {
            Route.SERVICE_PANEL -> service?.showMiniPanel()
            Route.HOME_SPHERE -> runCatching {
                context.startActivity(Intent(context, com.example.homeezch.MainActivity::class.java).putExtra("ezch.voice.start", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            Route.OVER_APP -> runCatching {
                context.startActivity(Intent(context, EvaOverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            }.onFailure {
                // Last resort only: a firmware that refuses floating activities still gets Eva, on the desktop.
                runCatching {
                    context.startActivity(Intent(context, com.example.homeezch.MainActivity::class.java).putExtra("ezch.voice.start", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
        }
        com.example.homeezch.EzchDiag.log("EVA", "вызов: " + route(service != null, homeVisible).name)
    }
}

/** Small floating Eva window in the lower right corner over the current app (own task, no history). */
class EvaOverlayActivity : Activity() {
    private var panel: VoicePanel? = null
    private val handler = Handler(Looper.getMainLooper())
    private val idle = Runnable { finish() }
    private fun touch() { handler.removeCallbacks(idle); handler.postDelayed(idle, 30_000) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val metrics = resources.displayMetrics
        val tileDp = ((metrics.widthPixels / metrics.density - 148f) / 8f).coerceIn(88f, 125f) * com.example.homeezch.AppCardScale
        val side = (evaMiniSideDp(tileDp) * metrics.density).toInt().coerceAtLeast(1)
        val presenter = VoicePanel(this, close = ::finish, mini = true, onActivity = ::touch)
        panel = presenter
        setContentView(presenter.view)
        window.setLayout(side, side)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = maxOf(metrics.widthPixels * .05f, 24 * metrics.density).toInt(); y = (28 * metrics.density).toInt()
        }
        com.example.homeezch.EzchDiag.init(this, "Ева поверх приложения (служба Ai EvA не подключена)")
        touch()
        if (!AiPreferences.enabled(this)) { presenter.status("Ева выключена"); return }
        if (!EvaEngine.hasMicPermission(this)) { presenter.status("Разрешите микрофон: Настройки EZCH → Настроить EZCH"); return }
        EvaEngine.release { if (panel === presenter && !isFinishing) presenter.listen() }
    }

    override fun onDestroy() { handler.removeCallbacks(idle); panel?.destroy(); panel = null; super.onDestroy() }
}
