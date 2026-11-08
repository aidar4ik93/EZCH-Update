package com.example.homeezch.ai.power

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.example.homeezch.ai.Action
import com.example.homeezch.ai.EvaAppService
import com.example.homeezch.ai.Outcome
import com.example.homeezch.home.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class EvaDeviceAdminReceiver : DeviceAdminReceiver()

/** No global lateinit holder: also works when Android starts only the alarm receiver. */
class PowerController(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("eva_power", Context.MODE_PRIVATE)
    data class Capabilities(val accessibility: Boolean, val admin: Boolean, val root: Boolean, val infrared: Boolean)
    fun capabilities() = Capabilities(EvaAppService.current != null,
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(context, EvaDeviceAdminReceiver::class.java)),
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { java.io.File(it).exists() },
        prefs.getString("ir_device", "").orEmpty().isNotEmpty())

    suspend fun execute(action: Action.Power): Outcome = if (!com.example.homeezch.ai.LocalActionRegistry.allowed(action))
        Outcome(false, "Некорректная команда питания") else when (action.op) {
        "timer" -> {
            val minutes = action.minutes
            if (minutes == null || minutes !in 1..1440) Outcome(false, "Таймер можно поставить от 1 минуты до 24 часов")
            else if (prefs.getBoolean("ir_mode", false) && !capabilities().infrared) Outcome(false, "Сначала выберите телевизор в Настройки → Питание → ИК-пульт")
            else if (!prefs.getBoolean("ir_mode", false) && !capabilities().let { it.accessibility || it.admin || (it.root && prefs.getBoolean("root_fallback", false)) }) Outcome(false, "Для таймера сна включите Ai EvA в специальных возможностях: Настройки → Питание")
            else SleepTimer.set(context, minutes)
        }
        "cancel_timer" -> { SleepTimer.cancel(context); Outcome(true, "Таймер выключения отменён") }
        "timer_left" -> SleepTimer.minutesLeft(context).let { Outcome(true, if (it == null) "Таймер выключения не установлен" else "До выключения примерно $it мин.") }
        "wake" -> {
            if (prefs.getBoolean("ir_mode", false)) infrared(true)
            else Outcome(false, "Из сна Ева не слышит голос. Включите телевизор пультом. Для управления внешним телевизором выберите ИК-пульт в настройках питания")
        }
        "shutdown", "sleep" -> powerOff()
        "reboot" -> {
            val service = EvaAppService.current
            if (service != null) {
                // Destroying the panel here cancels the very coroutine that must click the menu.
                EvaPowerAccessibility.reboot(service)
            } else if (root("reboot")) Outcome(true, "Команда перезагрузки отправлена")
            else Outcome(false, "Для перезагрузки включите службу Ai EvA в специальных возможностях: Настройки → Питание")
        }
        else -> Outcome(false, "Неизвестная команда питания")
    }

    suspend fun powerOff(): Outcome {
        // A failed IR command must NEVER fall through and put the listening box to sleep.
        if (prefs.getBoolean("ir_mode", false)) return infrared(false)
        val service = EvaAppService.current
        if (service != null && android.os.Build.VERSION.SDK_INT >= 28 &&
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
            return Outcome(true, "Команда сна отправлена. Разбудить телевизор можно пультом")
        val admin = context.getSystemService(DevicePolicyManager::class.java)
        if (capabilities().admin && runCatching { admin.lockNow(); true }.getOrDefault(false)) return Outcome(true, "Экран заблокирован. Включите его пультом")
        if (root("input keyevent 223")) return Outcome(true, "Команда сна отправлена")
        return Outcome(false, "Для сна включите службу Ai EvA или администратора в Настройки → Питание")
    }

    private suspend fun infrared(on: Boolean): Outcome {
        val key = prefs.getString("ir_device", "").orEmpty()
        if (key.isBlank()) return Outcome(false, "Сначала выберите телевизор в Настройки → Питание → ИК-пульт. Приставка остаётся включённой")
        val sent = withTimeoutOrNull(7000) {
            val devices = SmartHub.devices(context)
            val device = devices.firstOrNull { "${it.provider}|${it.id}" == key && eligible(it) } ?: return@withTimeoutOrNull false
            SmartHub.send(context, device, DeviceCommand.Power(on))
        } == true
        return if (sent) Outcome(true, "Команда ${if (on) "включения" else "выключения"} отправлена телевизору. Приставка остаётся включённой")
            else Outcome(false, "Телевизор не подтвердил команду ИК-пульта. Проверьте выбранное устройство и подключение в «Умном доме». Приставка остаётся включённой")
    }

    /** Optional fallback, off by default. Never probe root by launching su from a status screen. */
    private suspend fun root(command: String): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.getBoolean("root_fallback", false)) return@withContext false
        var process: Process? = null
        try {
            process = ProcessBuilder("su", "-c", command).redirectOutput(java.io.File("/dev/null")).redirectError(java.io.File("/dev/null")).start()
            process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) { false } finally { process?.destroyForcibly() }
    }
    companion object {
        // Do not offer sockets, arbitrary learned toggle buttons, or the IR hub itself as a TV.
        fun eligible(d: SmartDevice) = d.type == DeviceType.TV && d.canPower
    }
}
