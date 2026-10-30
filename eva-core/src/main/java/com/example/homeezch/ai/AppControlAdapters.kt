package com.example.homeezch.ai

/** Small selector profiles; package/version coverage is reported separately from implementation. */
data class AppControlAdapter(val packageName: String, val searchIds: Set<String>, val titleIds: Set<String> = emptySet(),
    val playerIds: Set<String> = emptySet(), val searchFromPlayerViaBack: Boolean = false,
    val submitLabels: Set<String> = emptySet())
object AppControlAdapters {
    private val generic = AppControlAdapter("", setOf("search", "action_search", "search_button", "search_badge"))
    val known = listOf(
        AppControlAdapter("ru.yourok.num", generic.searchIds, setOf("tvCardText")),
        AppControlAdapter("limehd.ru.lite", generic.searchIds + "sidebar_search", setOf("channel_name_text_view"),
            setOf("controls_container"), searchFromPlayerViaBack = true),
        AppControlAdapter("io.lift.app", generic.searchIds, submitLabels = setOf("искать")),
        AppControlAdapter("mobi.zona", generic.searchIds),
        AppControlAdapter("ru.vokino.web", generic.searchIds),
        AppControlAdapter("top.rootu.prisma", generic.searchIds),
        AppControlAdapter("ru.tiardev.kinotrend", generic.searchIds + "title_search"),
        AppControlAdapter("ru.twicker.serialtrend", generic.searchIds),
        AppControlAdapter("com.google.android.youtube.tv", generic.searchIds),
        AppControlAdapter("com.vk.tv", generic.searchIds)
    )
    fun forPackage(packageName: String) = known.firstOrNull { it.packageName == packageName } ?: generic
}
