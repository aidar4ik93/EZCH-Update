package com.example.homeezch

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

private fun canonicalService(value: String): String {
    val pieces = value.split('/', limit = 2)
    if (pieces.size != 2) return value
    return pieces[0] + "/" + if (pieces[1].startsWith('.')) pieces[0] + pieces[1] else pieces[1]
}
internal fun mergeHomeServices(current: String?, own: String, replaced: Set<String> = emptySet()): String =
    current.orEmpty().split(':').filter { it.isNotBlank() && it != "null" && canonicalService(it) !in replaced.map(::canonicalService) }.toMutableList().apply {
        if (none { canonicalService(it) == canonicalService(own) }) add(own)
    }.joinToString(":")
