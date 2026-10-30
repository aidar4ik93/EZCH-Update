package com.example.homeezch.ai

/** Read-only device facts collected by the launcher through public Android APIs. */
data class HealthSnapshot(
    val totalRamMb: Long, val availRamMb: Long, val lowMemory: Boolean,
    val freeStorageMb: Long, val totalStorageMb: Long,
    val launcherPssMb: Long? = null,
    val online: Boolean? = null, val validatedInternet: Boolean? = null,
    val transport: String? = null, val downKbps: Int? = null
)

/**
 * EVA self-diagnosis rules (ТЗ §5.1, «Ева, телевизор тормозит»). Turns measurements into a short,
 * honest spoken report plus only safe, reversible suggestions. Never claims a fix it did not make.
 */
object EvaHealth {
    fun performance(s: HealthSnapshot): Outcome {
        if (s.totalRamMb <= 0) return Outcome(false, "Не удалось прочитать состояние памяти телевизора")
        val ramPct = (s.availRamMb * 100 / s.totalRamMb).toInt()
        val problems = mutableListOf<String>(); val advice = mutableListOf<String>()
        if (s.lowMemory || ramPct < 15) {
            problems += "почти закончилась оперативная память (свободно ${s.availRamMb} из ${s.totalRamMb} МБ)"
            advice += "скажите «очисти кэш», чтобы закрыть фоновые приложения"
        }
        if (s.totalStorageMb > 0 && (s.freeStorageMb < 500 || s.freeStorageMb * 100 / s.totalStorageMb < 10)) {
            problems += "мало места на накопителе (свободно ${mb(s.freeStorageMb)})"
            advice += "удалите ненужные приложения в настройках — скажите «открой настройки приложений»"
        }
        if (s.totalRamMb <= 1600) advice += "на этом телевизоре лучше оставить облегчённый режим Lite без живых обоев"
        s.launcherPssMb?.takeIf { it > 150 }?.let { problems += "рабочий стол EZCH занимает $it МБ — это больше нормы" }
        val head = if (problems.isEmpty()) "Проверила: памяти свободно ${s.availRamMb} из ${s.totalRamMb} МБ, на диске ${mb(s.freeStorageMb)}. Явных причин тормозов не вижу"
            else "Нашла: " + problems.joinToString("; ")
        val tail = if (advice.isEmpty()) "" else ". Совет: " + advice.joinToString("; ")
        return Outcome(true, head + tail)
    }

    fun network(s: HealthSnapshot): Outcome = when {
        s.online == false -> Outcome(true, "Интернета нет: телевизор не подключён к сети. Скажите «открой настройки сети»")
        s.validatedInternet == false -> Outcome(true, "Сеть ${s.transport ?: ""} подключена, но выхода в интернет нет. Перезагрузите роутер или проверьте оплату у провайдера")
        s.online == null -> Outcome(false, "Не удалось проверить сеть")
        else -> {
            val speed = s.downKbps?.takeIf { it > 0 }?.let { ", оценка канала около ${it / 1000} Мбит/с" }.orEmpty()
            val weak = (s.downKbps ?: Int.MAX_VALUE) < 8000
            Outcome(true, "Интернет работает через ${s.transport ?: "сеть"}$speed" +
                if (weak) ". Для 4K этого может не хватать — выберите качество 1080p или подключите кабель" else ". Если видео тормозит, причина скорее в сервере фильма — попробуйте другой источник")
        }
    }

    /** «приложение, которое я вчера установил»: newest install within [days]; null when none. */
    fun recentInstall(installed: List<Pair<AppTarget, Long>>, now: Long, days: Int): AppTarget? =
        installed.filter { (_, at) -> at in (now - days * 86_400_000L)..now }.maxByOrNull { it.second }?.first

    private fun mb(value: Long) = if (value >= 1024) "%.1f ГБ".format(java.util.Locale.ROOT, value / 1024.0).replace('.', ',') else "$value МБ"
}
