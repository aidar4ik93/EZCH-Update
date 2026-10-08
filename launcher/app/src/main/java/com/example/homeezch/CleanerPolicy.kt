package com.example.homeezch

/** Fail-closed policy. This app currently trims ONLY its own in-memory artwork.
 * No automatic force-stop and no deletion of another application's cache.
 * Package names are not the only protection for forked/updated variants:
 * unknown packages are never approved for automatic stopping.
 */
internal object CleanerPolicy {
    private val protectedPackages = setOf(
        "io.github.romanvht.byedpi", "ru.yourok.torrserve", "com.example.homeezch"
    )
    fun mayAutomaticallyStop(packageName: String): Boolean = false
    fun isKnownProtected(packageName: String): Boolean = packageName in protectedPackages
}
