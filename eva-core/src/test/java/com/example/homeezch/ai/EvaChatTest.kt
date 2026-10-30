package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaChatTest {
    private var now = 1_000_000L
    private val ctx = EvaChat.Context("воскресенье, 25 октября, 23:40", "Казань", "YouTube", listOf("YouTube", "Кинопоиск"))

    @Test fun requestCarriesPersonaHistoryAndFunctions() {
        val json = EvaChat.request("GigaChat-2-Pro", ctx, listOf("user" to "привет", "assistant" to "Привет!"), "расскажи анекдот")
        val root = EvaJson.parse(json) as Map<*, *>
        assertEquals("GigaChat-2-Pro", root["model"])
        assertEquals("auto", root["function_call"])
        val messages = root["messages"] as List<*>
        assertEquals(4, messages.size)
        val system = (messages[0] as Map<*, *>)["content"] as String
        assertTrue(system.contains("Ева") && system.contains("Казань") && system.contains("Кинопоиск"))
        assertEquals("расскажи анекдот", (messages[3] as Map<*, *>)["content"])
        val names = (root["functions"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertTrue(names.containsAll(listOf("tv_volume", "open_app", "play_on_youtube", "switch_input", "weather")))
    }

    @Test fun textAnswerIsCleanedForSpeech() {
        val reply = """{"choices":[{"message":{"role":"assistant","content":"**Конечно!** Вот идея:\n- посмотрите «Интерстеллар» 🚀\nСсылка: https://example.com"},"finish_reason":"stop"}]}"""
        val r = EvaChat.parse(reply) as EvaChat.Result.Say
        assertFalse(r.text.contains("*") || r.text.contains("http") || r.text.contains("🚀") || r.text.contains("\n"))
        assertTrue(r.text.startsWith("Конечно!"))
    }

    @Test fun functionCallBecomesValidatedActions() {
        fun call(name: String, args: String) = EvaChat.parse("""{"choices":[{"message":{"role":"assistant","content":"","function_call":{"name":"$name","arguments":$args}},"finish_reason":"function_call"}]}""")
        assertEquals(listOf(Action.Volume(percent = 20)), (call("tv_volume", """{"percent":20}""") as EvaChat.Result.Act).actions)
        assertEquals(listOf(Action.PlayOn("youtube", "рамштайн", true)), (call("play_on_youtube", """{"query":"рамштайн"}""") as EvaChat.Result.Act).actions)
        assertEquals(listOf(Action.SwitchInput("hdmi", 2)), (call("switch_input", """{"kind":"hdmi","number":2}""") as EvaChat.Result.Act).actions)
        assertEquals(listOf(Action.Launch("Кинопоиск"), Action.Search("Интерстеллар")),
            (call("search_in_app", """{"app":"Кинопоиск","query":"Интерстеллар"}""") as EvaChat.Result.Act).actions)
        // Arguments as a JSON string (OpenAI style) are accepted too.
        assertEquals(listOf(Action.Media(false)), (call("tv_media", "\"{\\\"action\\\":\\\"pause\\\"}\"") as EvaChat.Result.Act).actions)
        // Unknown function or invalid values never become actions.
        assertTrue(call("format_disk", "{}") is EvaChat.Result.Failed || call("format_disk", "{}") is EvaChat.Result.Say)
        assertTrue(call("switch_input", """{"kind":"scart"}""") is EvaChat.Result.Say)
    }

    @Test fun errorsAreExplained() {
        assertTrue(EvaChat.parse("""{"status":402,"message":"Payment Required"}""") is EvaChat.Result.Failed)
        assertTrue(EvaChat.parse("not json") is EvaChat.Result.Failed)
    }

    @Test fun sessionKeepsShortRecentHistory() {
        val s = EvaChatSession({ now }, maxMessages = 4)
        repeat(6) { s.add("user", "вопрос $it"); s.add("assistant", "ответ $it?") }
        assertEquals(4, s.history().size)
        assertTrue(s.awaitsReply())
        now += 10 * 60_000L
        assertTrue(s.history().isEmpty())
    }

    @Test fun coreSendsOnlyUnknownPhrasesToChat() {
        val core = EvaCore(clock = { now }).apply { chatEnabled = { true } }
        assertTrue(core.handle("громкость 20") is EvaReply.Execute)
        assertTrue(core.handle("который час") is EvaReply.Say)
        assertTrue(core.handle("кто такой пушкин") is EvaReply.Chat)
        assertTrue(core.handle("расскажи анекдот") is EvaReply.Chat)
        // GigaChat asked a question: «да» continues the conversation instead of «Хорошо. Что сделать?».
        core.chat.add("user", "посоветуй фильм"); core.chat.add("assistant", "Могу включить «Интерстеллар». Включить?")
        assertTrue(core.handle("да") is EvaReply.Chat)
        assertTrue(core.handle("громче") is EvaReply.Execute)
        val offline = EvaCore(clock = { now })
        assertTrue(offline.handle("кто такой пушкин") is EvaReply.Say)
    }

    @Test fun jsonRoundTrip() {
        val v = mapOf("a" to listOf(1, true, null, "x\"y\nЖ"), "b" to 2.5)
        assertEquals("""{"a":[1,true,null,"x\"y\nЖ"],"b":2.5}""", EvaJson.write(v))
        assertEquals(mapOf("a" to listOf(1.0, true, null, "x\"y\nЖ"), "b" to 2.5), EvaJson.parse(EvaJson.write(v)))
    }
}
