package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class SetupFixTest {
    private val own = "com.example.homeezch.qa/com.example.homeezch.HomeButtonService"
    @Test fun enabledServicesPreserveOtherOwners() {
        assertEquals("reader/reader.Service:remote/remote.Helper:$own", mergeHomeServices("reader/reader.Service:remote/remote.Helper", own))
    }
    @Test fun addingHomeTwiceDoesNotDuplicateIt() {
        assertEquals(own, mergeHomeServices(mergeHomeServices(null, own), own))
    }
    @Test fun equivalentShortNameIsRetainedWithoutDuplicate() {
        val short = "com.example.homeezch/com.example.homeezch.HomeButtonService"
        assertEquals("com.example.homeezch/.HomeButtonService", mergeHomeServices("com.example.homeezch/.HomeButtonService", short))
    }
    @Test fun missingSecureSettingBecomesOnlyOwnService() {
        assertEquals(own, mergeHomeServices("null", own))
    }
    @Test fun forecastDenialIsDifferentFromNoInternetOrTimeout() {
        assertTrue(weatherFailure(WeatherHttpException(403)).contains("403"))
        assertTrue(weatherFailure(UnknownHostException()).contains("DNS"))
        assertTrue(weatherFailure(SocketTimeoutException()).contains("не ответил"))
        assertTrue(weatherFailure(SSLException("test")).contains("дату"))
    }
    @Test fun migrationPreservesOtherServicesAndRemovesOnlyOldHome() {
        val next="com.example.homeezch.qa12/com.example.homeezch.HomeButtonService"
        assertEquals("reader/reader.Service:"+next,mergeHomeServices("reader/reader.Service:"+own,next,setOf(own)))
    }
}
