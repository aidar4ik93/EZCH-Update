package com.example.homeezch

/** [recommended]: safe on every tested TV (demo/feedback/ad tracking) — preselected; the rest the owner ticks himself. */
internal data class OptionalSystemPackage(val packageName: String, val title: String, val effect: String, val recommended: Boolean = false)
internal data class OptionalPackageState(val item: OptionalSystemPackage, val enabledState: Int, val enabled: Boolean)

/** Explicit optional modules only. A name containing "ads" is not proof that a system service is dispensable. */
internal object OptimizationPolicy {
    // R26 (owner 29.10.2026): universal catalog of ad / recommendation / demo modules from Haier, Google TV and
    // Android TV firmwares. Only packages that are present AND enabled on this TV are shown. Never listed: HOME,
    // TV input / tuner / MediaTek services, Play Services, DRM, WebView, Bluetooth, settings, voice search.
    val catalog = listOf(
        OptionalSystemPackage("fusion.android.tv.demo", "Демо-режим магазина (Haier)",
            "Витринный демо-ролик для магазинов. Дома не нужен, работает в фоне.", recommended = true),
        OptionalSystemPackage("com.google.android.feedback", "Отправка отзывов Google",
            "Служба отправки отчётов в Google. На работу ТВ не влияет.", recommended = true),
        OptionalSystemPackage("tv.anoki.acr.controller", "Слежение за просмотром для рекламы (ACR, Haier)",
            "Распознаёт, что идёт на экране, и отправляет данные для рекламы. Отключение не влияет на ТВ и HDMI.", recommended = true),
        OptionalSystemPackage("com.google.android.tvrecommendations", "Системные рекомендации Android TV",
            "Может убрать рекомендательные ряды старого Android TV. Изменение не удаляет рекламу внутри YouTube и других приложений."),
        OptionalSystemPackage("com.google.android.leanbacklauncher.recommendations", "Рекомендации старого главного экрана",
            "Ряды «Рекомендуем» штатного лаунчера Android TV. С EZCH на главном экране не нужны."),
        OptionalSystemPackage("com.nes.leanbacklauncher.partnercustomizer", "Партнёрские блоки главного экрана",
            "Необязательная конфигурация партнёрского оформления. Может изменить ряды и оформление штатного главного экрана."),
        OptionalSystemPackage("com.cltv.fast", "Бесплатные каналы с рекламой (Haier FAST)",
            "Приложение рекламных интернет-каналов. Эфирное ТВ и HDMI не затрагивает."),
        OptionalSystemPackage("com.google.android.apps.tv.dreamx", "Заставка Google TV с рекомендациями",
            "Фоновая заставка с подборками. После отключения будет обычная системная заставка или чёрный экран."),
        OptionalSystemPackage("com.google.android.backdrop", "Заставка-фотографии Android TV",
            "Заставка с фото из интернета; качает картинки в простое."),
    )
    private val known = catalog.map { it.packageName }.toSet()
    fun selectable(installed: List<OptionalPackageState>, protected: Set<String>) = installed.filter {
        it.item.packageName in known && it.item.packageName !in protected && it.enabled && it.enabledState in 0..1
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun header(fingerprint: String) = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("""[ "${'$'}(getprop ro.build.fingerprint)" = ${quote(fingerprint)} ] || { echo 'Wrong firmware/build'; exit 1; }""")
        appendLine("""[ "${'$'}(am get-current-user)" = '0' ] || { echo 'Only Android user 0 supported'; exit 1; }""")
    }
    fun script(selected: List<OptionalPackageState>, protected: Set<String>, fingerprint: String, restore: Boolean): String {
        require(selected.all { it.item.packageName in known && it.item.packageName.matches(Regex("[a-zA-Z0-9_.]+")) })
        require(selected.all { it.item.packageName !in protected && it.enabled && it.enabledState in 0..1 })
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# EZCH: owner-selected optional packages; no uninstall, clear-data or global settings changes.")
            append(header(fingerprint).substringAfter('\n'))
            for (state in selected.distinctBy { it.item.packageName }) {
                val pkg = state.item.packageName
                if (!restore) {
                    appendLine("""home="${'$'}(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)" """)
                    appendLine("""case "${'$'}home" in $pkg/*) echo 'Current HOME is protected'; exit 1;; esac""")
                }
                val operation = if (!restore) "disable-user" else if (state.enabledState == 0) "default-state" else "enable"
                appendLine("pm $operation --user 0 $pkg || exit 1")
            }
            appendLine("echo 'EZCH: готово'")
        }
    }

    val animationKeys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    private val scale = Regex("[0-9]{1,2}(\\.[0-9]{1,3})?")

    /**
     * Universal cleanup (R26): app caches, cached background processes and, if chosen, 0.5× animations.
     * No data is cleared and nothing is uninstalled. [previous] = animation values read before the change,
     * so the restore script returns exactly the owner's settings (null = the key was not set).
     */
    fun cleanup(fingerprint: String, animations: Boolean, previous: List<String?>, restore: Boolean): String {
        require(previous.size == animationKeys.size && previous.all { it == null || it.matches(scale) })
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# EZCH: universal cleanup — caches, background processes${if (animations) ", animations 0.5x" else ""}. No data cleared, nothing uninstalled.")
            append(header(fingerprint).substringAfter('\n'))
            if (!restore) {
                appendLine("echo 'Свободная память до:'; grep -E 'MemAvailable|SwapFree' /proc/meminfo")
                appendLine("pm trim-caches 999G")
                appendLine("am kill-all")
                if (animations) animationKeys.forEach { appendLine("settings put global $it 0.5") }
                appendLine("sleep 2")
                appendLine("echo 'Свободная память после:'; grep -E 'MemAvailable|SwapFree' /proc/meminfo")
            } else {
                appendLine("# Caches and processes return by themselves; only the animation speed is restored.")
                if (animations) animationKeys.zip(previous).forEach { (key, value) ->
                    appendLine(if (value == null) "settings delete global $key" else "settings put global $key $value")
                }
            }
            appendLine("echo 'EZCH: готово'")
        }
    }
}
