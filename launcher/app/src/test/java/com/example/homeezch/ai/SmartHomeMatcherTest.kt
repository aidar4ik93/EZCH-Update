package com.example.homeezch.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartHomeMatcherTest {
    private fun e(id: String, name: String, state: String = "off", attrs: JSONObject = JSONObject()) = HomeEntity(id, name, state, attrs)
    private val home = listOf(
        e("light.living_main", "Люстра гостиная"), e("light.living_lamp", "Торшер в гостиной"),
        e("light.bedroom", "Свет спальня"), e("switch.kitchen_socket", "Розетка кухня"), e("switch.kettle", "Чайник"),
        e("switch.hall_light", "Свет прихожая"), e("switch.tv_socket", "Розетка ТВ гостиная"),
        e("sensor.living_temp", "Температура гостиная", "22.5", JSONObject().put("device_class", "temperature")),
        e("sensor.outdoor_temp", "Температура улица", "-3", JSONObject().put("device_class", "temperature")),
        e("scene.evening", "Вечер"), e("script.movie", "Режим кино"), e("lock.front", "Входная дверь", "locked"))

    @Test fun lightInRoomFindsOnlyThatRoomsLights() {
        val found = SmartHomeMatcher.find("свет гостиная", home).map { it.id }.toSet()
        assertEquals(setOf("light.living_main", "light.living_lamp"), found)
    }
    @Test fun lightEverywhereIncludesLightSwitchesButNotSockets() {
        val found = SmartHomeMatcher.find("свет", home).map { it.id }.toSet()
        assertTrue("switch.hall_light" in found); assertFalse("switch.tv_socket" in found); assertFalse("switch.kettle" in found)
    }
    @Test fun socketAndKettle() {
        assertEquals(listOf("switch.kitchen_socket"), SmartHomeMatcher.find("розетку кухня", home).map { it.id })
        assertEquals(listOf("switch.kettle"), SmartHomeMatcher.find("чайник", home).map { it.id })
    }
    @Test fun homeTemperaturePrefersIndoor() {
        assertEquals("sensor.living_temp", SmartHomeMatcher.temperature("дом", home)?.id)
        assertEquals("sensor.outdoor_temp", SmartHomeMatcher.temperature("улица", home)?.id)
    }
    @Test fun scenesByName() {
        assertEquals("scene.evening", SmartHomeMatcher.scene("вечерний", home)?.id)
        assertEquals("script.movie", SmartHomeMatcher.scene("кино", home)?.id)
        assertNull(SmartHomeMatcher.scene("утренняя зарядка", home))
    }
}
