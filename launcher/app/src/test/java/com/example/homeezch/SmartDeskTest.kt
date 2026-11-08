package com.example.homeezch

import com.example.homeezch.home.DeviceCommand
import com.example.homeezch.home.DeviceType
import com.example.homeezch.home.SmartDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartDeskTest {
    private fun device(type: DeviceType, on: Boolean?, canPower: Boolean = true) =
        SmartDevice("yandex", "id-1", "Тест", "Гостиная", type, on = on, canPower = canPower)

    @Test fun lightTogglesPower() {
        assertEquals(DeviceCommand.Power(false), SmartDesk.toggle(device(DeviceType.LIGHT, true)))
        assertEquals(DeviceCommand.Power(true), SmartDesk.toggle(device(DeviceType.LIGHT, false)))
        assertEquals(DeviceCommand.Power(true), SmartDesk.toggle(device(DeviceType.AC, null)))
    }
    @Test fun vacuumStartsOrDocks() {
        assertEquals(DeviceCommand.Vacuum("start"), SmartDesk.toggle(device(DeviceType.VACUUM, false)))
        assertEquals(DeviceCommand.Vacuum("dock"), SmartDesk.toggle(device(DeviceType.VACUUM, true)))
    }
    @Test fun sceneRunsAndSensorsOnlyShow() {
        assertEquals(DeviceCommand.Power(true), SmartDesk.toggle(device(DeviceType.SCENE, null)))
        assertNull(SmartDesk.toggle(device(DeviceType.SENSOR, null)))
        assertNull(SmartDesk.toggle(device(DeviceType.REMOTE, null)))
        assertNull(SmartDesk.toggle(device(DeviceType.SOCKET, false, canPower = false)))
    }
    @Test fun keyIsProviderAndId() = assertEquals("yandex:id-1", SmartDesk.key(device(DeviceType.LIGHT, true)))
}
