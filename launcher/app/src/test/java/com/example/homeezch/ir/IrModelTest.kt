package com.example.homeezch.ir

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrModelTest {
    private fun b(id: String, name: String, f: IrFunction, phrases: List<String> = emptyList()) = IrButton(id, name, f, byteArrayOf(0x26, 0, 1, id.length.toByte()), phrases)
    private val tv = IrDevice("tv", "Телевизор Haier", "Гостиная", IrCategory.TV, "AA",
        listOf(b("p", "Питание", IrFunction.POWER), b("v+", "Громче", IrFunction.VOL_UP), b("m", "Без звука", IrFunction.MUTE), b("h", "HDMI 2", IrFunction.CUSTOM)))
    private val bar = IrDevice("bar", "Саундбар", "Гостиная", IrCategory.AUDIO, "AA",
        listOf(b("on", "Вкл", IrFunction.POWER_ON), b("off", "Выкл", IrFunction.POWER_OFF), b("up", "Громче", IrFunction.VOL_UP, listOf("сделай саундбар погромче"))))
    private val acKitchen = IrDevice("ac1", "Кондиционер", "Кухня", IrCategory.AC, "AA", listOf(b("acp", "Питание", IrFunction.POWER)))
    private val acBed = IrDevice("ac2", "Кондиционер", "Спальня", IrCategory.AC, "AA", listOf(b("acp2", "Питание", IrFunction.POWER)))
    private val data = IrData(
        listOf(IrControllerRec("broadlink", "AA", "192.168.1.60", 0x520D, "RM4C", "RM4C mini")),
        listOf(tv, bar, acKitchen, acBed),
        listOf(IrScenario("s", "Кино", listOf("кино", "включи кино"), listOf(IrStep("tv", "p"), IrStep("bar", "on", 1, 1000)))))

    @Test fun jsonRoundTripKeepsEverything() {
        val back = IrJson.read(JSONObject(IrJson.write(data).toString()))
        assertEquals(data, back)
    }

    @Test fun foreignFileIsRejected() {
        val e = runCatching { IrJson.read(JSONObject("{\"devices\":[]}")) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun importReplacesSameIdsAndAddsNew() {
        val imported = IrData(devices = listOf(tv.copy(name = "ТВ"), tv.copy(id = "new", name = "Проектор")))
        val merged = IrJson.merge(data, imported)
        assertEquals(5, merged.devices.size)
        assertEquals("ТВ", merged.device("tv")!!.name)
        assertEquals(1, merged.controllers.size)
    }

    private fun press(text: String) = (IrVoice.match(text, data) as? IrVoice.Match.Press)?.let { it.deviceId to it.buttonId }

    @Test fun voiceDeviceNamePlusAction() {
        assertEquals("bar" to "up", press("Ева, громче на саундбаре"))
        assertEquals("bar" to "off", press("выключи саундбар"))
        assertEquals("tv" to "p", press("включи телевизор haier"))       // only a power toggle → used for on
        assertEquals("tv" to "m", press("выключи звук на телевизоре хайер".replace("хайер", "haier")))
        assertEquals("tv" to "h", press("hdmi 2 на телевизоре haier"))     // a button by its own name
    }

    @Test fun voiceOwnPhrasesAndScenarios() {
        assertEquals("bar" to "up", press("сделай саундбар погромче"))
        assertEquals("s", (IrVoice.match("Ева, кино", data) as IrVoice.Match.Scenario).scenarioId)
        assertEquals("s", (IrVoice.match("запусти сценарий кино", data) as IrVoice.Match.Scenario).scenarioId)
    }

    @Test fun voiceRoomDisambiguates() {
        assertEquals("ac2" to "acp2", press("включи кондиционер в спальне"))
        assertEquals("ac1" to "acp", press("выключи кондиционер на кухне"))
    }

    @Test fun ordinaryEvaCommandsAreNotTaken() {
        for (t in listOf("выключи телевизор", "сделай громче", "открой ютуб", "включи свет в гостиной", "какая погода", "громче")) assertNull(t, IrVoice.match(t, data))
        assertNull(IrVoice.match("саундбар", data))           // a name without an action does nothing
        assertNull(IrVoice.match("перемотай саундбар", data)) // unknown action → falls through to Eva
    }
}
