package com.example.homeezch.ai

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs

/** One EVA AI Core per process: the home panel and the system assistant share dialog and memory. */
internal object EvaBrain {
    @Volatile private var instance: EvaCore? = null
    fun core(context: Context): EvaCore = instance ?: synchronized(this) {
        instance ?: EvaCore(EvaMemory(SharedPrefsEvaMemoryStore(context.applicationContext))).also { core ->
            val app = context.applicationContext
            core.chatEnabled = { GigaChatSettings.enabled(app) || EvaLocalBrain.available(app) }
            instance = core
        }
    }
}

/** EVA personal memory lives only on this TV (ТЗ §17); never uploaded, cleared by «забудь всё». */
internal class SharedPrefsEvaMemoryStore(context: Context) : EvaMemoryStore {
    private val prefs = context.getSharedPreferences("eva_memory_v1", Context.MODE_PRIVATE)
    override fun read(): Map<String, String> = prefs.all.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    override fun write(values: Map<String, String>) {
        val editor = prefs.edit().clear()
        values.forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
    }
}

/** Android side of EVA self-diagnosis and self-setup: public APIs only, read-only unless the owner agreed. */
internal object EvaDiagnostics {
    private const val MB = 1024L * 1024L

    /** Call off the main thread: StatFs and PSS can take tens of milliseconds on a weak TV. */
    fun run(context: Context, topic: String): Outcome =
        if (topic == "network") EvaHealth.network(network(context)) else EvaHealth.performance(performance(context))

    private fun performance(context: Context): HealthSnapshot {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val pss = runCatching { android.os.Debug.getPss() / 1024L }.getOrNull()
        return HealthSnapshot(totalRamMb = info.totalMem / MB, availRamMb = info.availMem / MB, lowMemory = info.lowMemory,
            freeStorageMb = (stat?.availableBytes ?: 0L) / MB, totalStorageMb = (stat?.totalBytes ?: 0L) / MB, launcherPssMb = pss)
    }

    private fun network(context: Context): HealthSnapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val transport = when {
            caps == null -> null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "кабель"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильную сеть"
            else -> "сеть"
        }
        return HealthSnapshot(0, 0, false, 0, 0, online = caps != null,
            validatedInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = transport, downKbps = caps?.linkDownstreamBandwidthKbps)
    }

    /** First-install times of launchable apps (system apps report their build date and fall out naturally). */
    fun installTimes(context: Context, apps: List<AppTarget>): List<Pair<AppTarget, Long>> = apps.mapNotNull { app ->
        val pkg = app.component.substringBefore('/')
        @Suppress("DEPRECATION")
        runCatching { app to context.packageManager.getPackageInfo(pkg, 0).firstInstallTime }.getOrNull()
    }

    fun openRecent(context: Context, app: AppTarget): Outcome {
        val component = ComponentName.unflattenFromString(app.component) ?: return Outcome(false, "Некорректное приложение")
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        RecentApps.record(context, app.component)
        return Outcome(true, "Открываю ${app.label} — его установили недавно")
    }

    /**
     * «Сделай себя основным помощником»: EVA opens the system choice, the owner picks «Ai EvA» himself
     * (settings are never changed behind his back). «Без пульта»: turns on hands-free «Ева» after the
     * spoken confirmation; the HOME wake listener starts as soon as the EVA panel closes.
     */
    fun assistantSetup(context: Context, handsFree: Boolean): Outcome {
        if (handsFree) {
            if (!EvaEngine.hasMicPermission(context))
                return Outcome(false, "Нужно разрешение на микрофон: нажмите на сферу Евы на рабочем столе, разрешите доступ и повторите команду")
            EvaEngine.setHandsFree(context, true)
            return Outcome(true, "Готово: отзываюсь на «Ева» без пульта, пока открыт рабочий стол EZCH. Выключить можно в настройках Евы")
        }
        if (AndroidCapabilityProbe(context).roleHeld()) return Outcome(true, "Я уже назначена основным помощником")
        val opened = com.example.homeezch.SettingsRouter.open(context, listOf(
            Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_SETTINGS)))
        return Outcome(opened, if (opened) "Открываю выбор помощника: выберите «Ai EvA» — после этого кнопка микрофона будет звать меня"
            else "Прошивка не открыла настройки помощника. Откройте Настройки → Приложения → Приложения по умолчанию → Помощник")
    }
}
