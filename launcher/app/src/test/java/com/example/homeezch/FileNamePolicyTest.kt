package com.example.homeezch
import org.junit.Assert.*
import org.junit.Test
class FileNamePolicyTest {
    @Test fun preventsPathEscapesAndInvalidNames() {
        listOf("", ".", "..", "../a", "a/b", "a\\b", "a\u0000b", "a".repeat(201)).forEach {
            assertTrue(it, runCatching { FileOperations.checkedName(it) }.isFailure)
        }
        assertEquals("Фильм 2026.mp4", FileOperations.checkedName("Фильм 2026.mp4"))
    }
}
