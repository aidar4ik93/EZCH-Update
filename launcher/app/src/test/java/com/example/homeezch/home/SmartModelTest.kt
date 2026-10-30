package com.example.homeezch.home

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartModelTest {
    private val yandex = JSONObject("""{"rooms":[{"id":"r1","name":"Гостиная"}],
      "devices":[
        {"id":"l1","name":"Люстра","room":"r1","type":"devices.types.light","capabilities":[
          {"type":"devices.capabilities.on_off","state":{"instance":"on","value":true}},
          {"type":"devices.capabilities.range","parameters":{"instance":"brightness"},"state":{"instance":"brightness","value":60}}]},
        {"id":"ac1","name":"Кондиционер","room":"r1","type":"devices.types.thermostat.ac","capabilities":[
          {"type":"devices.capabilities.on_off","state":{"instance":"on","value":false}},
          {"type":"devices.capabilities.range","parameters":{"instance":"temperature"},"state":{"instance":"temperature","value":23}}]},
        {"id":"ir1","name":"Пульт ресивера","type":"devices.types.other","capabilities":[
          {"type":"devices.capabilities.custom.button","parameters":{"instance":"btn_vol_up","instance_name":"Громче"}}]},
        {"id":"v1","name":"Робот","room":"r1","type":"devices.types.vacuum_cleaner","state":"offline","capabilities":[]}],
      "scenarios":[{"id":"s1","name":"Кино","is_active":true}]}""")

    @Test fun yandexDevicesRoomsAndIrButtons() {
        val d = YandexMapper.parse(yandex)
        val light = d.first { it.id == "l1" }
        assertEquals(DeviceType.LIGHT, light.type); assertEquals(true, light.on); assertEquals(60, light.brightness); assertEquals("Гостиная", light.room)
        assertEquals(23.0, d.first { it.id == "ac1" }.temperature!!, 0.01)
        val remote = d.first { it.id == "ir1" }
        assertEquals(DeviceType.REMOTE, remote.type); assertEquals("btn_vol_up", remote.buttons["Громче"])
        assertFalse(d.first { it.id == "v1" }.online)
        assertTrue(d.any { it.type == DeviceType.SCENE && it.name == "Кино" })
    }
    @Test fun yandexActionsMatchApi() {
        val light = YandexMapper.parse(yandex).first { it.id == "l1" }
        val a = YandexMapper.action(light, DeviceCommand.Brightness(30))!!.getJSONArray("devices").getJSONObject(0)
        assertEquals("l1", a.getString("id"))
        val cap = a.getJSONArray("actions").getJSONObject(0)
        assertEquals("devices.capabilities.range", cap.getString("type"))
        assertEquals(30, cap.getJSONObject("state").getInt("value"))
        val btn = YandexMapper.action(light, DeviceCommand.Button("btn_vol_up"))!!.getJSONArray("devices").getJSONObject(0).getJSONArray("actions").getJSONObject(0)
        assertEquals("devices.capabilities.custom.button", btn.getString("type")); assertEquals("btn_vol_up", btn.getJSONObject("state").getString("instance"))
    }
    @Test fun tuyaStatusAndCommands() {
        val list = TuyaMapper.parse(JSONArray("""[
          {"id":"t1","name":"Лампа","category":"dj","online":true,"status":[{"code":"switch_led","value":true},{"code":"bright_value_v2","value":500}]},
          {"id":"t2","name":"Розетка","category":"cz","online":false,"status":[{"code":"switch_1","value":false}]},
          {"id":"hub","name":"ИК-хаб","category":"wnykq","online":true,"status":[]}]"""))
        val lamp = list.first { it.id == "t1" }
        assertEquals(DeviceType.LIGHT, lamp.type); assertEquals(true, lamp.on); assertEquals(50, lamp.brightness)
        assertEquals("switch_1", list.first { it.id == "t2" }.powerCode)
        assertEquals("hub", list.first { it.id == "hub" }.infrared)
        val cmd = TuyaMapper.commands(lamp, DeviceCommand.Power(false))!!.getJSONArray("commands").getJSONObject(0)
        assertEquals("switch_led", cmd.getString("code")); assertFalse(cmd.getBoolean("value"))
    }
    @Test fun tuyaSignatureShape() {
        val sts = TuyaMapper.stringToSign("get", TuyaMapper.sha256(""), "/v1.0/token?grant_type=1")
        assertEquals("GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n\n/v1.0/token?grant_type=1", sts)
        val s = TuyaMapper.sign("secret", "id", "", "1", sts)
        assertEquals(64, s.length); assertEquals(s.uppercase(), s)
    }
    @Test fun voiceTargetsAcrossEcosystems() {
        val d = YandexMapper.parse(yandex)
        assertEquals(listOf("l1"), SmartMatcher.find("свет гостиная", d).map { it.id })
        assertEquals(listOf("ac1"), SmartMatcher.find("кондиционер", d).map { it.id })
        assertEquals(listOf("v1"), SmartMatcher.find("пылесос", d).map { it.id })
        assertEquals(listOf("ir1"), SmartMatcher.find("пульт ресивера", d).map { it.id })
        assertTrue(SmartMatcher.find("чайник", d).isEmpty())
    }
}
