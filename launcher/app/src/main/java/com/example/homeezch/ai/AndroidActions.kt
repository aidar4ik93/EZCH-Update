package com.example.homeezch.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.example.homeezch.MainActivity
import com.example.homeezch.UpdaterActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidActionExecutor(private val context: Context,
    private val navigate: (suspend (Action.Navigate) -> Outcome)? = null,
    private val chooseApp: suspend (List<AppTarget>) -> AppTarget? = { null }) : ActionExecutor {
    /** Package opened by the previous step of the same plan: «открой лифт, найди мстители». */
    private var lastLaunched: String? = null
    fun apps(): List<AppTarget> {
        val pm = context.packageManager
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { category ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).mapNotNull {
                val info = it.activityInfo
                if (!info.exported || !info.enabled || !info.applicationInfo.enabled || info.packageName == context.packageName ||
                    !AiProfilePolicy.appAllowed(context, info.packageName)) null
                else AppTarget(it.loadLabel(pm).toString(), ComponentName(info.packageName, info.name).flattenToString())
            }
        }.distinctBy { it.component }.sortedBy { it.label }
    }
    override suspend fun execute(action: Action): Outcome {
        if (!LocalActionRegistry.allowed(action)) return Outcome(false, "Команда отклонена")
        return try {
            when (action) {
                is Action.PlayRequest -> execute(routePlayRequest(action, withContext(Dispatchers.IO) { apps() }))
                Action.Cancel -> Outcome(false, "Отменено")
                Action.Dismiss -> Outcome(false, "Помощник закрыт")
                Action.Weather -> spokenWeather(context)
                Action.Updates -> {
                    if (!AiProfilePolicy.updatesAllowed(context)) return Outcome(false, "Откройте Update вручную через защищённые настройки владельца")
                    context.startActivity(Intent(context, UpdaterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Outcome(true, "Открытие EZCH Update запрошено")
                }
                is Action.Navigate -> if (navigate != null) navigate.invoke(action) else {
                    EvaAppService.current?.let { return it.execute(action) }
                    if (action.direction != Direction.HOME) Outcome(false, "Навигация доступна в EZCH HOME; управление чужим окном не подключено")
                    else { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Outcome(true, "Открытие HOME запрошено") }
                }
                is Action.Launch -> {
                    val profile = com.example.homeezch.Profiles.active(context)
                    val before = withContext(Dispatchers.IO) { apps() }
                    val target = when (val match = matchApp(action.name, before)) {
                        AppMatch.Missing -> return Outcome(false, "Не нашла приложение «${action.name}». Скажите название ещё раз")
                        is AppMatch.Unique -> match.app
                        is AppMatch.Ambiguous -> chooseApp(match.apps) ?: return Outcome(false, "Выбор приложения отменён")
                        is AppMatch.Suggested -> match.apps.singleOrNull() ?: chooseApp(match.apps) ?: return Outcome(false, "Выбор приложения отменён")
                    }
                    if (com.example.homeezch.Profiles.active(context) != profile) return Outcome(false, "Профиль изменился — повторите команду")
                    if (withContext(Dispatchers.IO) { apps() }.none { it.component == target.component }) return Outcome(false, "Приложение удалено или недоступно")
                    val component = ComponentName.unflattenFromString(target.component) ?: return Outcome(false, "Некорректное приложение")
                    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    RecentApps.record(context, target.component)
                    val service = EvaAppService.current
                    if (service == null) {
                        // No app-control service: the launch itself is done; a following search uses the app's Android search.
                        lastLaunched = component.packageName
                        return Outcome(true, "Открываю ${target.label}")
                    }
                    val confirmed = service.confirmWindow(component.packageName)
                    lastLaunched = component.packageName
                    Outcome(confirmed, if (confirmed) "Открываю ${target.label}" else "Открываю ${target.label}, но окно пока не появилось")
                }
                is Action.PlayOn -> YouTubePlay.play(context, action)
                is Action.Search -> EvaAppService.current?.execute(action) ?: run {
                    kotlinx.coroutines.delay(1200) // let the app open before handing it the query
                    val pkg = lastLaunched
                    if (pkg != null && YouTubePlay.searchInApp(context, pkg, action.query)) Outcome(true, "Ищу «${action.query}»")
                    else Outcome(false, "Чтобы Ева сама искала и включала фильмы внутри приложений, включите «Ai EvA — управление приложениями» в Специальных возможностях (Настройки Евы → кнопка «Управление приложениями»)")
                }
                // R27: «очисти память / мусор» works without the accessibility service — same as the optimizer's button.
                Action.ClearCache -> Outcome(true, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.example.homeezch.QuickCleanup.run(context) }.getOrElse { "Не удалось освободить память" }
                })
                is Action.PlayChannel, is Action.Choose, is Action.Quality, Action.Watch ->
                    EvaAppService.current?.execute(action) ?: Outcome(false, "Включите «Ai EvA — управление приложениями» в специальных возможностях Android")
                Action.Settings -> {
                    val opened = com.example.homeezch.SettingsRouter.settings(context)
                    Outcome(opened, if (opened) "Открываю настройки телевизора" else "Прошивка не открыла настройки телевизора")
                }
                is Action.OpenSettings -> {
                    val opened = when (action.section) {
                        "wifi" -> com.example.homeezch.SettingsRouter.wifi(context)
                        "bluetooth" -> com.example.homeezch.SettingsRouter.bluetooth(context)
                        "display" -> com.example.homeezch.SettingsRouter.display(context)
                        "sound" -> com.example.homeezch.SettingsRouter.sound(context)
                        "storage" -> com.example.homeezch.SettingsRouter.storage(context)
                        "apps" -> com.example.homeezch.SettingsRouter.open(context, listOf(Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)))
                        else -> com.example.homeezch.SettingsRouter.settings(context)
                    }
                    val title = mapOf("wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "display" to "изображения", "sound" to "звука",
                        "storage" to "памяти", "apps" to "приложений").getOrDefault(action.section, "телевизора")
                    Outcome(opened, if (opened) "Открываю настройки $title" else "Прошивка не открыла настройки $title")
                }
                is Action.SwitchInput -> EvaTvSkills.switchInput(context, action)
                is Action.ChannelStep -> EvaTvSkills.channelStep(context, action.delta)
                Action.ListApps -> {
                    val list = withContext(Dispatchers.IO) { apps() }
                    Outcome(true, if (list.isEmpty()) "Не нашла установленных приложений" else
                        "Установлено ${list.size}: ${list.take(12).joinToString { it.label }}${if (list.size > 12) " и другие" else ""}")
                }
                Action.LastApp -> {
                    val last = RecentApps.last(context, withContext(Dispatchers.IO) { apps() })
                        ?: return Outcome(false, "Я ещё не запускала приложения с рабочего стола EZCH")
                    val component = ComponentName.unflattenFromString(last.component) ?: return Outcome(false, "Некорректное приложение")
                    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Outcome(true, "Открываю ${last.label}")
                }
                Action.PlayGame -> EvaTvSkills.riddle()
                is Action.Power -> EvaAppService.current?.power(action.op)
                    ?: Outcome(false, "Чтобы перезагружать телевизор голосом, включите службу «Ai EvA» в Специальных возможностях (Настройки Евы → «Управление приложениями»)")
                is Action.Home -> com.example.homeezch.home.SmartVoice.execute(context, action)
                is Action.Volume -> volume(action)
                is Action.Media -> AndroidMediaAdapter(context).setPlaying(action.play)
                is Action.Diagnose -> withContext(Dispatchers.IO) { EvaDiagnostics.run(context, action.topic) }
                is Action.RecentInstall -> {
                    val installed = withContext(Dispatchers.IO) { EvaDiagnostics.installTimes(context, apps()) }
                    val app = EvaHealth.recentInstall(installed, System.currentTimeMillis(), action.days)
                        ?: return Outcome(false, if (action.days <= 3) "За последние дни новых приложений не устанавливали" else "За ${action.days} дней новых приложений не нашла")
                    EvaDiagnostics.openRecent(context, app)
                }
                is Action.AssistantSetup -> EvaDiagnostics.assistantSetup(context, action.handsFree)
                is Action.Optimize -> {
                    context.startActivity(Intent(context, com.example.homeezch.DeviceOptimizationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Outcome(true, "Открываю диагностику оборудования и план отключения необязательных модулей. Изменения ещё не выполнялись")
                }
            }
        } catch (_: SecurityException) { Outcome(false, "Android запретил действие")
          } catch (_: android.content.ActivityNotFoundException) { Outcome(false, "Приложение недоступно") }
    }
    private suspend fun volume(action: Action.Volume): Outcome {
        val audio = context.getSystemService(AudioManager::class.java) ?: return Outcome(false, "Звук недоступен")
        if (audio.isVolumeFixed) return Outcome(false, "Громкостью управляет телевизор или внешний ресивер")
        val stream = AudioManager.STREAM_MUSIC
        val before = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        // «Громче» on a TV with 100 volume steps used to move the volume by 1 %, which nobody hears.
        // One spoken step is about a tenth of the range; the system volume bar is shown so the owner sees it.
        val notch = maxOf(1, Math.round(max * 0.08).toInt())
        val expected = action.percent?.let { Math.round(max * it / 100.0).toInt() }
            ?: if (action.mute == null && action.step != 0) (before + notch * action.step).coerceIn(0, max) else null
        when {
            action.mute != null -> audio.adjustStreamVolume(stream, if (action.mute == true) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            expected != null -> {
                if (audio.isStreamMute(stream) && expected > 0) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                audio.setStreamVolume(stream, expected, AudioManager.FLAG_SHOW_UI)
            }
            else -> audio.adjustStreamVolume(stream, if (action.step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
        delay(120)
        val after = audio.getStreamVolume(stream)
        val verified = if (action.mute != null) audio.isStreamMute(stream) == action.mute
            else if (expected != null) after == expected || action.percent == null && (if (action.step > 0) after > before || before == max else after < before || before == 0)
            else if (action.step > 0) after > before || before == max else after < before || before == 0
        val percent = if (max == 0) 0 else Math.round(after * 100.0 / max).toInt()
        return Outcome(verified, if (verified) (if (action.mute == true) "Звук выключен" else "Громкость $percent") else "Телевизор не изменил громкость")
    }
}

class AndroidMediaAdapter(private val context: Context) : MediaAdapter {
    fun isPlaying(packageName: String): Boolean? = try {
        val sessions = context.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(context, EzchMediaListener::class.java))
            ?.filter { it.packageName == packageName }.orEmpty()
        if (sessions.isEmpty()) null else sessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    } catch (_: SecurityException) { null }
    override suspend fun setPlaying(play: Boolean): Outcome {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return Outcome(false, "MediaSession недоступна")
        val sessions = try { manager.getActiveSessions(ComponentName(context, EzchMediaListener::class.java)) }
            catch (_: SecurityException) { return Outcome(false, "Разрешите доступ к медиасессиям в настройках EVA") }
        val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val paused = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        val candidates = if (play) paused else playing
        if (candidates.size != 1) return Outcome(false, if (candidates.isEmpty()) "Нет подходящей медиасессии" else "Несколько плееров: выберите один вручную")
        val controller = candidates.single()
        val required = if (play) PlaybackState.ACTION_PLAY else PlaybackState.ACTION_PAUSE
        if (((controller.playbackState?.actions ?: 0L) and required) == 0L) return Outcome(false, "Плеер не поддерживает команду")
        if (play) controller.transportControls.play() else controller.transportControls.pause()
        val deadline = SystemClock.elapsedRealtime() + 1500
        do {
            delay(100)
            if (controller.playbackState?.state == if (play) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
                return Outcome(true, if (play) "Воспроизведение продолжено" else "Пауза")
        } while (SystemClock.elapsedRealtime() < deadline)
        return Outcome(false, "Команда отправлена плееру, результат не подтверждён")
    }
}
/** Required public MediaSession access; no notification contents are inspected or stored. */
class EzchMediaListener : android.service.notification.NotificationListenerService()
