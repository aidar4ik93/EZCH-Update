package com.example.homeezch.ai

import android.content.Context
import com.example.homeezch.MetWeather
import com.example.homeezch.WeatherAccess
import com.example.homeezch.WeatherReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal suspend fun spokenWeather(context: Context): Outcome = withContext(Dispatchers.IO) {
    val point = WeatherAccess.loadPoint(context)
        ?: return@withContext Outcome(false, "Выберите город в настройках погоды на рабочем столе.")
    val response = try { MetWeather.load(context, point) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withContext Outcome(false, "Погоду сейчас получить не удалось. Попробуйте позже.") }
    formatSpokenWeather(point.city, response.reading, response.status, System.currentTimeMillis())
}

/** Never present old or unavailable cached data as current weather. */
internal fun formatSpokenWeather(city: String, reading: WeatherReading?, status: String, now: Long): Outcome {
    if (reading == null || reading.temperature !in -100..70 || now - reading.fetchedAt !in 0..10_800_000L)
        return Outcome(false, "Сейчас нет актуальных данных о погоде. Попробуйте немного позже.")
    val temperature = reading.temperature
    val absolute = kotlin.math.abs(temperature)
    val degrees = when {
        absolute % 100 in 11..14 -> "градусов"
        absolute % 10 == 1 -> "градус"
        absolute % 10 in 2..4 -> "градуса"
        else -> "градусов"
    }
    val number = when { temperature > 0 -> "плюс $absolute"; temperature < 0 -> "минус $absolute"; else -> "ноль" }
    val saved = status.contains("Устаревшие", ignoreCase = true) || now - reading.fetchedAt > 3_600_000L
    val introduction = if (saved) "По сохранённым данным" else "Сейчас"
    val condition = MetWeather.condition(reading.condition).takeUnless { it == "Погода" }?.let { " $it." }.orEmpty()
    return Outcome(true, "$city. $introduction $number $degrees.$condition" + if (saved) " Обновить данные пока не удалось." else "")
}
