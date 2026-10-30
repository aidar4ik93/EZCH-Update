package com.example.homeezch.ai

/** Pure recognition rules shared with the on-device wake listener. */
object EvaSpeechText {
    fun wake(text: String): Boolean = normalize(text).split(Regex("[^\\p{L}]+"))
        .any { it in setOf("ева", "эва", "ива", "eva") }
    fun clean(text: String): String = normalize(text).replace("[unk]", " ")
        .replace(Regex("^(?:ева|эва|ива|eva)(?:[ ,]+|$)"), "")
        .replace(Regex("(?<!\\p{L})(?:пожалуйста|давай-ка)(?!\\p{L})"), " ")
        .replace(Regex("\\s+"), " ").trim()
        // «Ева, пожалуйста, сделай тише» leaves a dangling comma once the filler is gone.
        .trimStart(',', ' ', '.', '!', '?', ':', ';', '-').trim()
}
