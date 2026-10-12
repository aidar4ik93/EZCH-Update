package com.example.homeezch

import android.media.tv.TvInputManager
import org.junit.Assert.*
import org.junit.Test

class InputConnectionTest {
    @Test fun unknownOrMissingFirmwareStatusNeverConfirmsConnection() {
        assertEquals(false to "Статус неизвестен", inputConnection(null))
        assertEquals(false to "Статус неизвестен", inputConnection(-1))
        assertEquals(false to "Статус неизвестен", inputConnection(1000))
    }
    @Test fun confirmedStatesDistinguishStandbyAndDisconnected() {
        assertEquals(true to "Подключено", inputConnection(TvInputManager.INPUT_STATE_CONNECTED))
        assertEquals(true to "Ожидание", inputConnection(TvInputManager.INPUT_STATE_CONNECTED_STANDBY))
        assertEquals(false to "Не подключено", inputConnection(TvInputManager.INPUT_STATE_DISCONNECTED))
    }
}
