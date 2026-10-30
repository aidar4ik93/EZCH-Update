package com.example.homeezch.ai

import android.content.Context
import android.os.SystemClock
import com.example.homeezch.Profiles
import com.example.homeezch.WorkspacePrefs
import java.util.UUID

/** Voice commands obey the active desktop's restrictions, including hidden apps. */
internal object AiProfilePolicy {
    fun updatesAllowed(context: Context): Boolean =
        Profiles.active(context) == "owner" && Profiles.guard(context) == null

    fun appAllowed(context: Context, packageName: String): Boolean {
        val workspace = WorkspacePrefs.load(context)
        if (packageName in workspace.hiddenApps) return false
        return Profiles.active(context) == "owner" || workspace.rows.any { packageName in it.appKeys }
    }
}

/** One use, in-process handoff after HomeScreen's existing PIN verification. */
internal object AiSettingsTicket {
    private data class Ticket(val expires: Long, val profile: String, val credential: String?)
    private val tickets = mutableMapOf<String, Ticket>()
    @Synchronized fun issue(context: Context): String {
        val now = SystemClock.elapsedRealtime()
        tickets.entries.removeAll { it.value.expires < now }
        return UUID.randomUUID().toString().also { tickets[it] = Ticket(now + 30_000, Profiles.active(context), Profiles.guard(context)?.credential) }
    }
    @Synchronized fun consume(context: Context, token: String?): Boolean {
        if (token == null) return false
        val ticket = tickets.remove(token) ?: return false
        return ticket.expires >= SystemClock.elapsedRealtime() && ticket.profile == Profiles.active(context) &&
            ticket.credential == Profiles.guard(context)?.credential
    }
}
