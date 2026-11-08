package com.example.homeezch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Claude r52: daily screen-time limit for a (child) desktop profile. Local and simple, like the profile PIN itself:
 * time counts while that profile is active and the TV is on; when it runs out HOME comes to the front with
 * «Время на сегодня закончилось», and only the owner's PIN extends it or switches back to the owner.
 * Not a replacement for Android parental controls (a child with adb or system settings can get around it).
 */
internal object KidsLimit {
    private fun prefs(c: Context) = c.getSharedPreferences("ezch_kids_limit_v1", Context.MODE_PRIVATE)
    private fun day(at: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(at))
    /** Gaps longer than this (TV off, process killed overnight) are not counted. */
    private const val MAX_GAP = 20 * 60_000L
    val CHOICES = listOf(0, 30, 60, 90, 120, 180)

    fun limitMinutes(c: Context, profile: String): Int = if (profile == "owner") 0 else prefs(c).getInt("limit_$profile", 0)
    fun setLimit(c: Context, profile: String, minutes: Int) {
        require(profile != "owner" && minutes in 0..24 * 60)
        prefs(c).edit().putInt("limit_$profile", minutes).apply(); schedule(c)
    }
    private fun usedKey(profile: String, at: Long) = "used_${profile}_${day(at)}"
    private fun bonusKey(profile: String, at: Long) = "bonus_${profile}_${day(at)}"

    /** Adds the time since the last mark to the active profile. Called on HOME resume/pause, screen on/off and the alarm. */
    @Synchronized fun mark(c: Context, counting: Boolean, now: Long = System.currentTimeMillis()) {
        val p = prefs(c)
        val profile = Profiles.active(c)
        val last = p.getLong("mark", 0L)
        val was = p.getBoolean("counting", false)
        val editor = p.edit()
        if (was && last in 1..now && now - last <= MAX_GAP && limitMinutes(c, profile) > 0 && p.getString("mark_profile", "") == profile) {
            val key = usedKey(profile, now)
            editor.putLong(key, p.getLong(key, 0L) + (now - last))
        }
        editor.putLong("mark", now).putBoolean("counting", counting).putString("mark_profile", profile).apply()
    }
    fun remainingMs(c: Context, now: Long = System.currentTimeMillis()): Long? {
        val profile = Profiles.active(c)
        val limit = limitMinutes(c, profile)
        if (limit <= 0) return null
        val p = prefs(c)
        val pending = if (p.getBoolean("counting", false) && p.getString("mark_profile", "") == profile)
            (now - p.getLong("mark", now)).coerceIn(0, MAX_GAP) else 0L
        return limit * 60_000L + p.getLong(bonusKey(profile, now), 0L) - p.getLong(usedKey(profile, now), 0L) - pending
    }
    fun expired(c: Context) = (remainingMs(c) ?: 1L) <= 0L
    fun extend(c: Context, minutes: Int) {
        val now = System.currentTimeMillis(); val profile = Profiles.active(c); val key = bonusKey(profile, now)
        prefs(c).edit().putLong(key, prefs(c).getLong(key, 0L) + minutes * 60_000L).apply(); schedule(c)
    }
    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 4302, Intent(c, KidsLimitReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    /** A check every ≤10 min while a limited profile is active (counts time spent in other apps), and one at the end.
     *  RTC (not _WAKEUP): a sleeping TV is not woken and that time is not counted. */
    fun schedule(c: Context) {
        val alarm = c.getSystemService(AlarmManager::class.java) ?: return
        val left = remainingMs(c)
        runCatching { alarm.cancel(pending(c)) }
        if (left == null) return
        val wait = left.coerceIn(0L, TICK) + if (left <= TICK) 15_000L else 0L
        runCatching { alarm.setWindow(AlarmManager.RTC, System.currentTimeMillis() + wait, 60_000L, pending(c)) }
    }
    private const val TICK = 10 * 60_000L
    fun summary(c: Context): String {
        val limited = Profiles.list(c).filter { limitMinutes(c, it.id) > 0 }
        return if (limited.isEmpty()) "нет" else limited.joinToString { "${it.name}: ${limitMinutes(c, it.id)} мин" }
    }
}

class KidsLimitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        KidsLimit.mark(context, power?.isInteractive == true)
        if (KidsLimit.expired(context)) runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).putExtra("ezch.kids.timeup", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } else KidsLimit.schedule(context)
    }
}

@androidx.compose.runtime.Composable
internal fun KidsTimeUp(profileName: String, onExtend: (Int) -> Unit, onOwner: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xF2050A14)).testTag("kids-time-up"),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                androidx.compose.material3.Text("⏰", fontSize = 56.sp)
                androidx.compose.material3.Text("Время на сегодня закончилось", color = androidx.compose.ui.graphics.Color.White, fontSize = 28.sp)
                if (profileName.isNotBlank()) androidx.compose.material3.Text("Профиль «$profileName». Завтра время начнётся заново.", color = Muted, fontSize = 15.sp)
                val first = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.compose.material3.Button(onClick = { onExtend(30) }, modifier = androidx.compose.ui.Modifier.focusRequester(first)) {
                    androidx.compose.material3.Text("Ещё 30 минут — PIN владельца")
                }
                androidx.compose.material3.OutlinedButton(onClick = onOwner) { androidx.compose.material3.Text("Профиль владельца — PIN") }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun KidsLimitDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var version by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val profiles = androidx.compose.runtime.remember(version) { Profiles.list(context).filter { it.id != "owner" } }
    val ownerPin = androidx.compose.runtime.remember { Profiles.list(context).any { it.id == "owner" && it.credential.isNotEmpty() } }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Лимит времени в день") },
        text = {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.heightIn(max = 440.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (!ownerPin) androidx.compose.material3.Text("Сначала задайте PIN владельца в «Профилях»: без него ребёнок сможет продлить время сам.",
                    color = Gold, fontSize = 13.sp)
                if (profiles.isEmpty()) androidx.compose.material3.Text("Нет профилей кроме владельца. Добавьте детский профиль в «Профилях».")
                profiles.forEach { profile ->
                    val current = KidsLimit.limitMinutes(context, profile.id)
                    androidx.compose.material3.Text(profile.name, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp)
                    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        KidsLimit.CHOICES.forEach { minutes ->
                            val label = if (minutes == 0) "без лимита" else if (minutes % 60 == 0) "${minutes / 60} ч" else "$minutes мин"
                            androidx.compose.material3.FilterChip(selected = current == minutes,
                                onClick = { KidsLimit.setLimit(context, profile.id, minutes); version++ },
                                label = { androidx.compose.material3.Text(label) })
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Готово") } })
}
