package com.example.homeezch.ai

internal fun resultTitle(text: String) = normalize(text).replace(Regex("^\\d+[.)]\\s*"), "")
private fun titleWords(text: String) = resultTitle(text).split(Regex("[^\\p{L}\\p{N}]+"))
    .filter(String::isNotBlank).map { word ->
        if (word.matches(Regex("[а-я]{4,}"))) word.replace(Regex("(?:иями|ями|ого|ему|ому|ыми|ими|ую|ая|ое|ые|ой|ий|ей|ов|ам|ах|ях|а|у|ы|и|е|я)$"), "")
            .takeIf { it.length >= 3 } ?: word else word
    }
internal fun exactResultTitle(text: String, query: String) = titleWords(text) == titleWords(query)
internal fun relevantResultTitle(text: String, query: String): Boolean {
    val title = titleWords(text)
    val words = titleWords(query)
    return words.isNotEmpty() && words.all { it in title }
}
internal val searchLabels = setOf("поиск", "search", "найти", "поиск фильмов", "поиск каналов", "поиск телеканала", "поиск видео", "искать")
internal fun uiLabel(text: String) = normalize(text).trimEnd('.', '…', ':')
internal val watchLabels = setOf("смотреть", "смотреть онлайн", "воспроизвести", "play", "watch", "начать просмотр", "смотреть фильм",
    "продолжить просмотр", "продолжить", "смотреть сериал", "смотреть бесплатно", "▶ смотреть", "играть")
/** R29: «Смотреть 1 сезон», «▶ Смотреть онлайн», «Смотреть (HD)» — but never «Смотреть трейлер» / «смотреть позже». */
internal fun isWatchLabel(text: String): Boolean {
    val t = uiLabel(text).trimStart('▶', ' ')
    if (t in watchLabels) return true
    return (t.startsWith("смотреть") || t.startsWith("play") || t.startsWith("воспроизвести")) &&
        !Regex("трейлер|позже|тизер|trailer|later|избранн").containsMatchIn(t) && t.length <= 30
}
