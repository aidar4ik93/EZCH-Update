package com.example.homeezch

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast

internal enum class RemoteAction(val label: String, val packages: List<String> = emptyList()) {
    SETTINGS("Настройки телевизора"),
    YOUTUBE("YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv", "com.google.android.youtube")),
    NETFLIX("Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
    PRIME("Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")),
    APPS("Управление приложениями"),
    EVA("Ai EvA — голосовой помощник")
}

/** Microphone / Assistant / Search keys of TV remotes (Haier, Homatics, Xiaomi, Google TV). */
internal val VOICE_KEYS = setOf(KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH)

internal val SYSTEM_VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

internal object RemoteButtons {
    @Volatile var learning = false
    private fun prefs(context: Context) = context.getSharedPreferences("remote_buttons_v1", Context.MODE_PRIVATE)
    fun identity(event: KeyEvent): RemoteButtonId? = event.device?.takeIf { !it.isVirtual }?.let {
        RemoteButtonId(it.descriptor, event.keyCode, event.scanCode)
    }
    fun assigned(context: Context, id: RemoteButtonId): RemoteAction? =
        prefs(context).getString(id.storageKey(), null)?.let { value -> RemoteAction.entries.firstOrNull { it.name == value } }
    fun save(context: Context, id: RemoteButtonId, action: RemoteAction): Boolean {
        if (!id.assignable()) return false
        val existing = assigned(context, id)
        if (existing != null && existing != action) return false
        val p = prefs(context)
        p.edit().apply {
            p.all.filterValues { it == action.name }.keys.forEach { remove(it) }
            putString(id.storageKey(), action.name)
        }.apply()
        return true
    }
    fun clear(context: Context) = prefs(context).edit().clear().apply()
    fun summary(context: Context) = RemoteAction.entries.joinToString("\n") { action ->
        "${action.label}: ${if (prefs(context).all.values.any { it == action.name }) "назначена" else "не назначена"}"
    }
    fun action(context: Context, event: KeyEvent, defaults: Boolean): RemoteAction? {
        if (learning || event.keyCode in SYSTEM_VOLUME_KEYS) return null
        val id = identity(event)
        return id?.let { assigned(context, it) } ?: when {
            defaults && event.keyCode == KeyEvent.KEYCODE_SETTINGS -> RemoteAction.SETTINGS
            event.keyCode == KeyEvent.KEYCODE_ALL_APPS -> RemoteAction.APPS
            // The remote's mic button opens Ai EvA instead of the stock assistant.
            event.keyCode in VOICE_KEYS && com.example.homeezch.ai.EvaAppService.current == null -> RemoteAction.EVA
            else -> null
        }
    }
    fun execute(context: Context, action: RemoteAction) {
        try { when (action) {
            RemoteAction.SETTINGS -> SettingsRouter.settings(context)
            RemoteAction.APPS -> SettingsRouter.open(context, listOf(Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)))
            // R27: over the current app, not by bringing the desktop forward (EvaEntry decides where).
            RemoteAction.EVA -> com.example.homeezch.ai.EvaEntry.open(context)
            else -> {
                val pm = context.packageManager
                for (pkg in action.packages) {
                    val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                    try {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    } catch (_: android.content.ActivityNotFoundException) {
                    } catch (_: SecurityException) { }
                }
                Toast.makeText(context, "${action.label}: приложение не установлено или его запуск недоступен", Toast.LENGTH_LONG).show()
            }
        }
        } catch (e: RuntimeException) {
            android.util.Log.w("EZCH.Remote", "Remote action $action unavailable", e)
            Toast.makeText(context, "Не удалось открыть ${action.label}", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Consume only a matched down/up pair; cancelled presses and repeats never launch twice. */
internal class RemoteButtonPress {
    private var pending: Pair<RemoteButtonId, RemoteAction>? = null
    fun reset() { pending = null }
    fun handle(context: Context, event: KeyEvent, defaults: Boolean): Boolean {
        if (event.keyCode in SYSTEM_VOLUME_KEYS) { reset(); return false }
        val id = RemoteButtons.identity(event) ?: RemoteButtonId("virtual-default", event.keyCode, event.scanCode)
        if (RemoteButtons.learning) { reset(); return false }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return pending?.first == id
            pending = null
            val action = RemoteButtons.action(context, event, defaults) ?: return false
            pending = id to action
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            val press = pending ?: return false
            pending = null
            if (press.first != id) return false
            if (!event.isCanceled && !RemoteButtons.learning) RemoteButtons.execute(context, press.second)
            return true
        }
        return false
    }
}
