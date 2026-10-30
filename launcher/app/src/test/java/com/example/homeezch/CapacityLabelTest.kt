package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class CapacityLabelTest {
    @Test fun inaccessibleCapacityDoesNotClaimZeroOrInventSize() {
        assertEquals("Объём недоступен", capacityLabel(0, 0))
        assertEquals("Объём недоступен", capacityLabel(10, 11))
        assertEquals("Объём недоступен", capacityLabel(10, -1))
    }
    @Test fun validStorageUsesRussianLabelAndActualByteUnits() {
        assertEquals("Свободно 1,5 из 4,0 ГБ", capacityLabel(4L * 1024 * 1024 * 1024, LITE_RAM_LIMIT))
    }
}
