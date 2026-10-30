package com.example.homeezch.home

import android.content.Context
import android.content.Intent
import com.example.homeezch.ai.Action
import com.example.homeezch.ai.Outcome
import com.example.homeezch.ai.SmartHomeExecutor

/**
 * Ai EvA voice → any connected ecosystem. «включи свет в гостиной», «кондиционер на 22»,
 * «запусти уборку», «выключи телевизор» (Яндекс Пульт). Locks never open by voice.
 * With only Home Assistant connected the original, state-confirming HA executor is used.
 */
object SmartVoice {
    suspend fun execute(context: Context, action: Action.Home): Outcome {
        if (action.op == "panel") {
            context.startActivity(Intent(context, com.example.homeezch.ai.SmartHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Outcome(true, "Открываю «Умный дом»")
        }
        if (action.op == "unlock" || action.op == "lock") return Outcome(false, "Замки голосом не управляются — откройте «Умный дом» и подтвердите")
        val cloud = SmartHub.providers.any { it.id != "ha" && it.connected(context) }
        if (!cloud && action.op != "set_temp" && !action.target.contains("пылесос")) return SmartHomeExecutor(context).execute(action)
        val devices = SmartHub.devices(context)
        if (devices.isEmpty()) return Outcome(false, "Умный дом не ответил. Проверьте подключение в «Умном доме»")
        when (action.op) {
            "scene" -> {
                val scene = devices.filter { it.type == DeviceType.SCENE }.firstOrNull { it.name.lowercase().contains(action.target.lowercase()) }
                    ?: return Outcome(false, "Сценарий «${action.target}» не найден")
                return result(SmartHub.send(context, scene, DeviceCommand.Power(true)), "Запускаю «${scene.name}»")
            }
            "status" -> {
                val on = devices.filter { it.on == true && it.type != DeviceType.SCENE }
                return Outcome(true, if (on.isEmpty()) "Всё выключено" else "Включено: " + on.take(6).joinToString { it.name })
            }
            "temperature" -> {
                val t = devices.firstOrNull { it.type == DeviceType.SENSOR && it.reading?.contains("°") == true }
                    ?: devices.firstOrNull { it.type == DeviceType.AC && it.temperature != null }
                return if (t != null) Outcome(true, "Дома ${t.reading ?: "${t.temperature?.toInt()}°"}") else Outcome(false, "Датчик температуры не найден")
            }
        }
        val targets = SmartMatcher.find(action.target, devices)
        if (targets.isEmpty()) return Outcome(false, "Не нашла «${action.target}» в умном доме")
        var ok = 0
        for (d in targets.take(8)) {
            val command = when (action.op) {
                "on" -> if (d.type == DeviceType.VACUUM) DeviceCommand.Vacuum("start") else DeviceCommand.Power(true)
                "off" -> if (d.type == DeviceType.VACUUM) DeviceCommand.Vacuum("dock") else DeviceCommand.Power(false)
                "toggle" -> DeviceCommand.Power(d.on != true)
                "brightness" -> DeviceCommand.Brightness(action.value ?: 50)
                "set_temp" -> DeviceCommand.Temperature(action.value ?: 22)
                else -> return Outcome(false, "Команда не поддерживается")
            }
            if (SmartHub.send(context, d, command)) ok++
        }
        val what = targets.first().type.title.lowercase()
        val done = when (action.op) {
            "on" -> if (targets.first().type == DeviceType.VACUUM) "Пылесос начал уборку" else "Включила: ${targets.joinToString { it.name }}"
            "off" -> if (targets.first().type == DeviceType.VACUUM) "Пылесос едет на базу" else "Выключила: ${targets.joinToString { it.name }}"
            "brightness" -> "Яркость ${action.value} %"
            "set_temp" -> "${targets.first().name}: ${action.value}°"
            else -> "Готово"
        }
        return if (ok == 0) Outcome(false, "$what: устройство не подтвердило команду") else Outcome(true, done)
    }
    private fun result(ok: Boolean, text: String) = Outcome(ok, if (ok) text else "Не удалось выполнить")
}
