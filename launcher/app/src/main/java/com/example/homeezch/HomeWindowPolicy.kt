package com.example.homeezch

/** A launcher package also owns settings, account and recommendation windows. */
internal fun isResolvedHomeWindow(packageName: String, windowClass: String?, homeClasses: Set<String>): Boolean {
    if (windowClass.isNullOrBlank()) return false
    fun fullName(name: String) = if (name.startsWith('.')) packageName + name else name
    return homeClasses.any { fullName(it) == fullName(windowClass) }
}
