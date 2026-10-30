package com.example.homeezch.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/** Recently launched apps (component names only, newest first). */
object RecentApps {
    private fun prefs(context: Context) = context.getSharedPreferences("eva_recent_v1", Context.MODE_PRIVATE)
    fun record(context: Context, component: String) {
        val list = (listOf(component) + prefs(context).getString("list", "").orEmpty().split('\n').filter { it.isNotBlank() && it != component }).take(10)
        prefs(context).edit().putString("list", list.joinToString("\n")).apply()
    }
    fun last(context: Context, installed: List<AppTarget>): AppTarget? =
        prefs(context).getString("list", "").orEmpty().split('\n').firstNotNullOfOrNull { c -> installed.firstOrNull { it.component == c } }
}

internal object EvaTvSkills {
    /** HDMI/AV/TV via the public TvInputManager + TvContract passthrough URI. Honest when the TV has no such input. */
    fun switchInput(context: Context, action: Action.SwitchInput): Outcome {
        val sources = com.example.homeezch.scanTvSources(context).entries
        val kind = when (action.kind) { "hdmi" -> com.example.homeezch.TvSourceKind.HDMI; "av" -> com.example.homeezch.TvSourceKind.AV; else -> com.example.homeezch.TvSourceKind.TV }
        val candidates = sources.filter { it.kind == kind && !it.id.startsWith("manual-") }
        if (candidates.isEmpty()) return Outcome(false, when (action.kind) {
            "tv" -> "На этом устройстве нет системного ТВ-тюнера"
            else -> "Входы ${action.kind.uppercase()} недоступны: приставка не сообщает о них Android (это нормально для ТВ-боксов)"
        })
        val number = action.number
        val source = number?.let { n -> candidates.getOrNull(n - 1) } ?: candidates.firstOrNull { it.connected } ?: candidates.first()
        if (number != null && number > candidates.size) return Outcome(false, "Входа ${action.kind.uppercase()} $number нет, доступно: ${candidates.size}")
        val opened = com.example.homeezch.SettingsRouter.source(context, source)
        return Outcome(opened, if (opened) "Переключаю на ${source.label}" else "Прошивка не открыла ${source.label}")
    }

    /** Next/previous channel through the playing app's media session (live TV apps expose skip actions). */
    fun channelStep(context: Context, delta: Int): Outcome {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return Outcome(false, "Медиасессии недоступны")
        val sessions = try { manager.getActiveSessions(ComponentName(context, EzchMediaListener::class.java)) }
            catch (_: SecurityException) { return Outcome(false, "Разрешите Еве доступ к медиасессиям в её настройках, чтобы переключать каналы") }
        val player = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
            ?: return Outcome(false, "Сейчас ничего не воспроизводится — откройте ТВ-приложение")
        val needed = if (delta > 0) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (((player.playbackState?.actions ?: 0L) and needed) == 0L) return Outcome(false, "Это приложение не даёт переключать каналы извне")
        if (delta > 0) player.transportControls.skipToNext() else player.transportControls.skipToPrevious()
        return Outcome(true, if (delta > 0) "Следующий канал" else "Предыдущий канал")
    }

    private val riddles = listOf("Зимой и летом одним цветом?" to "ёлка", "Висит груша — нельзя скушать?" to "лампочка",
        "Без окон, без дверей, полна горница людей?" to "огурец", "Не лает, не кусает, а в дом не пускает?" to "замок",
        "Что можно увидеть с закрытыми глазами?" to "сон", "Чем больше из неё берёшь, тем больше она становится?" to "яма",
        "Сидит дед, во сто шуб одет?" to "лук", "Кто приходит, кто уходит, все её за ручку водят?" to "дверь")
    fun riddle(): Outcome {
        val r = riddles[((System.currentTimeMillis() / 1000) % riddles.size).toInt()]
        return Outcome(true, "Давай! Загадка: ${r.first} Подумай… Ответ: ${r.second}")
    }
}
