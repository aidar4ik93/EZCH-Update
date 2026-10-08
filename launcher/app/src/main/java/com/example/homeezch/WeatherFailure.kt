package com.example.homeezch

import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

internal class WeatherHttpException(val status: Int) : IOException("Weather HTTP $status")
internal fun weatherFailure(error: Exception): String = when (error) {
    is WeatherHttpException -> when (error.status) {
        429 -> "Погодный сервис занят · повторим через минуту"
        401, 403 -> "Погодный сервис отказал в доступе (HTTP ${error.status})"
        else -> "Ошибка погодного сервиса (HTTP ${error.status})"
    }
    is UnknownHostException -> "Нет связи с погодным сервисом · проверьте DNS/интернет"
    is SocketTimeoutException -> "Погодный сервис не ответил · повторим через минуту"
    is SSLException -> "Ошибка защищённого соединения · проверьте дату ТВ"
    else -> "Не удалось прочитать погоду · повторим через минуту"
}
