package com.example.homeezch

internal data class AppShelf(val id: String, val title: String, val appKeys: List<String>)

internal fun orderedKeys(saved: List<String>, available: List<String>): List<String> {
    val allowed = available.toSet()
    return (saved.filter { it in allowed } + available).distinct()
}
