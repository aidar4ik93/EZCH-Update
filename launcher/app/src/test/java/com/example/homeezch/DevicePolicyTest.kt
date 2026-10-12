package com.example.homeezch
import org.junit.Assert.*
import org.junit.Test
class DevicePolicyTest {
    @Test fun exactMemoryBoundaryAndUnknownMemoryUseLite() {
        assertTrue(isLiteMemory(0, false))
        assertTrue(isLiteMemory(1024L * 1024 * 1024, false))
        assertTrue(isLiteMemory(LITE_RAM_LIMIT, false))
        assertFalse(isLiteMemory(LITE_RAM_LIMIT + 1, false))
        assertTrue(isLiteMemory(4L * 1024 * 1024 * 1024, true))
        assertFalse(isLiteMemory(2L * 1024 * 1024 * 1024, false))
    }
}
