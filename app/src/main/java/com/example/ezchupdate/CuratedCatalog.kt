package com.example.ezchupdate

import com.example.ezchupdate.data.RemoteApp

/** Product navigation is independent of Drive filenames. Missing APKs stay honest, visible cards. */
internal object CuratedCatalog {
    data class Entry(val packageName: String, val name: String, val section: String)
    val entries = listOf(
        Entry("limehd.ru.lite", "Лайт HD TV", "Фильмы и ТВ"),
        Entry("com.play.pandafref", "Panda Vision", "Фильмы и ТВ"),
        Entry("io.lift.app", "Lift", "Фильмы и ТВ"),
        Entry("mobi.zona", "Zona", "Фильмы и ТВ"),
        Entry("ru.tiardev.kinotrend", "KinoTrend", "Фильмы и ТВ"),
        Entry("ru.twicker.serialtrend", "SerialTrend", "Фильмы и ТВ"),
        Entry("top.rootu.prisma", "Prisma", "Фильмы и ТВ"),
        Entry("ru.vokino.web", "VoKino", "Фильмы и ТВ"),
        Entry("ru.yourok.num", "NUM", "Фильмы и ТВ"),
        Entry("com.google.android.youtube.tv", "YouTube", "Видеосервисы"),
        Entry("ru.rutube.app.tv", "RUTUBE", "Видеосервисы"),
        Entry("com.vk.tv", "VK Видео", "Видеосервисы"),
        Entry("com.alphainventor.filemanager", "Файловый менеджер +", "Инструменты"),
        Entry("com.example.homeezch.usb", "EZCH Launcher", "Инструменты"),
        Entry("com.example.ezchupdate.tvtest", "EZCH Update", "Инструменты"),
        Entry("org.amnezia.vpn", "AmneziaVPN", "Инструменты"),
        Entry("io.github.romanvht.byedpi", "ByeByeDPI", "Инструменты"),
        Entry("ru.yourok.torrserve", "TorrServe MatriX", "Инструменты"),
        Entry("net.gtvbox.vimuhd", "Vimu", "Инструменты"),
        Entry("jp.snowlife01.android.appkiller2", "Task Killer", "Инструменты")
    )
    fun reason(packageName: String): String = when (packageName) {
        "com.play.pandafref", "jp.snowlife01.android.appkiller2" -> "APK этой версии на карантине до проверки. Основание первоначальной блокировки в истории проекта не описано. Установка отключена."
        else -> "В проверенном каталоге пока нет APK. Установка появится после проверки источника и подписи."
    }
    fun officialUrl(packageName: String): String? = when (packageName) {
        "com.google.android.youtube.tv", "jp.snowlife01.android.appkiller2" -> "https://play.google.com/store/apps/details?id=$packageName"
        else -> null
    }
    fun decorate(apps: List<RemoteApp>, ownPackage: String): List<RemoteApp> {
        val primary = entries.map { entry ->
            val packageName = if (entry.packageName == "com.example.ezchupdate.tvtest" && ownPackage == "com.example.ezchupdate") ownPackage else entry.packageName
            apps.firstOrNull { it.packageName == packageName }?.copy(name = entry.name)
                ?: RemoteApp(entry.name, packageName, 0, "Нет проверенного APK", "", "", installable = false)
        }
        val identities = primary.map { it.packageName }.toSet()
        return primary + apps.filter { it.packageName !in identities &&
            it.packageName !in setOf("com.example.ezchupdate", "com.example.ezchupdate.tvtest") }
    }
    fun section(packageName: String): String = entries.firstOrNull { it.packageName == packageName }?.section
        ?: if (packageName == "com.example.ezchupdate") "Инструменты" else "Приложения"
}
