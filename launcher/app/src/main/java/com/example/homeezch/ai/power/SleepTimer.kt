package com.example.homeezch.ai.power

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.example.homeezch.EzchDiag
import com.example.homeezch.ai.Outcome
import kotlinx.coroutines.*
import java.util.UUID

/** Persisted alarm identity prevents a queued old/cancelled alarm from putting the TV to sleep. */
object SleepTimer {
    private fun prefs(c: Context) = c.getSharedPreferences("eva_power", Context.MODE_PRIVATE)
    private fun alarm(c: Context) = c.getSystemService(AlarmManager::class.java)
    private fun boot(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun pending(c: Context, token: String = "") = PendingIntent.getBroadcast(c, 4201,
        Intent(c, SleepTimerReceiver::class.java).putExtra("token", token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized fun set(c: Context, minutes: Int): Outcome {
        if (minutes !in 1..1440) return Outcome(false, "Таймер: от 1 минуты до 24 часов")
        cancel(c)
        val token = UUID.randomUUID().toString()
        val at = SystemClock.elapsedRealtime() + minutes * 60_000L
        if (!prefs(c).edit().putString("timer_token", token).putLong("timer_at", at).putInt("timer_boot", boot(c)).commit())
            return Outcome(false, "Не удалось сохранить таймер")
        val pi = pending(c, token)
        var exact = android.os.Build.VERSION.SDK_INT < 31 || alarm(c).canScheduleExactAlarms()
        try {
            if (exact) {
                try { alarm(c).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
                catch (_: SecurityException) { exact = false; alarm(c).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            } else alarm(c).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (_: Exception) { cancel(c); return Outcome(false, "Android не разрешил поставить таймер") }
        return Outcome(true, "Таймер выключения: $minutes мин." + if (exact) "" else " Android может задержать выключение. Для точного времени разрешите таймеры в Настройки → Питание")
    }
    @Synchronized fun cancel(c: Context) {
        prefs(c).edit().remove("timer_token").remove("timer_at").remove("timer_boot").commit()
        alarm(c).cancel(pending(c))
    }
    @Synchronized fun minutesLeft(c: Context): Long? {
        val p = prefs(c)
        if (p.getString("timer_token", null) == null) return null
        if (p.getInt("timer_boot", -2) != boot(c)) { cancel(c); return null }
        val ms = p.getLong("timer_at", 0) - SystemClock.elapsedRealtime()
        return ((ms.coerceAtLeast(0) + 59_999) / 60_000)
    }
    @Synchronized fun consume(c: Context, token: String?): Boolean {
        val p = prefs(c)
        if (token.isNullOrBlank() || token != p.getString("timer_token", null) || p.getInt("timer_boot", -2) != boot(c) || SystemClock.elapsedRealtime() < p.getLong("timer_at", Long.MAX_VALUE)) return false
        return p.edit().remove("timer_token").remove("timer_at").remove("timer_boot").commit()
    }
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SleepTimer.consume(context, intent.getStringExtra("token"))) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            // Claude r51: the sound fades out over ~8 s before the TV goes to sleep, and the old volume is put back
            // afterwards, so the next morning does not start silent.
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val stream = android.media.AudioManager.STREAM_MUSIC
            val before = runCatching { audio.getStreamVolume(stream) }.getOrDefault(-1)
            try {
                if (before > 0) for (step in 1..16) {
                    runCatching { audio.setStreamVolume(stream, before * (16 - step) / 16, 0) }
                    delay(500)
                }
                val outcome = withTimeout(8000) { PowerController(context.applicationContext).powerOff() }
                EzchDiag.log("POWER:TIMER", outcome.toString())
            } catch (e: Exception) { EzchDiag.error("POWER:TIMER", "Таймер не выполнил выключение", e) }
            finally {
                if (before > 0) { delay(1500); runCatching { audio.setStreamVolume(stream, before, 0) } }
                result.finish()
            }
        }
    }
}
