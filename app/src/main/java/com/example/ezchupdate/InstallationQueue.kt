package com.example.ezchupdate

import com.example.ezchupdate.data.RemoteApp

/** Advances only after a terminal, correlated installation result. */
class InstallationQueue(val apps: List<RemoteApp>, var index: Int = 0, var succeeded: Int = 0,
    val failures: LinkedHashMap<String, String> = linkedMapOf()) {
    init {
        require(apps.map { it.packageName }.distinct().size == apps.size)
        require(index in 0..apps.size)
        require(succeeded in 0..index)
    }
    val current: RemoteApp? get() = apps.getOrNull(index)
    val complete: Boolean get() = index == apps.size
    fun finish(packageName: String, error: String?) {
        check(current?.packageName == packageName) { "Result does not match the active app" }
        if (error == null) succeeded++ else failures[packageName] = error
        index++
    }
    companion object {
        fun create(apps: List<RemoteApp>, ownPackage: String) = InstallationQueue(apps.sortedBy { it.packageName == ownPackage })
    }
}
