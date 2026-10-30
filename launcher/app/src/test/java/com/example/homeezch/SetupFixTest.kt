package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class SetupFixTest {
    @Test fun forecastDenialIsDifferentFromNoInternetOrTimeout() {
        assertTrue(weatherFailure(WeatherHttpException(403)).contains("403"))
        assertTrue(weatherFailure(UnknownHostException()).contains("DNS"))
        assertTrue(weatherFailure(SocketTimeoutException()).contains("не ответил"))
        assertTrue(weatherFailure(SSLException("test")).contains("дату"))
    }
}
