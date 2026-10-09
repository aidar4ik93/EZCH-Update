package com.example.ezchupdate.install

import android.content.pm.PackageInstaller
import org.junit.Assert.*
import org.junit.Test

class InstallFailureMessageTest {
    @Test fun verificationFailureExplainsSecurityAndPreservesAndroidReason() {
        val reason = "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed"
        val message = InstallFailureMessage.describe(PackageInstaller.STATUS_FAILURE_BLOCKED, reason)
        assertTrue(message.contains("проверка приложения разработчиком"))
        assertTrue(message.contains(reason))
    }

    @Test fun administrativeBlockDoesNotClaimPlayProtectWasResponsible() {
        val message = InstallFailureMessage.describe(PackageInstaller.STATUS_FAILURE_BLOCKED, "Blocked by administrator")
        assertTrue(message.contains("ограничения устройства"))
        assertFalse(message.contains("Play Защиты"))
        assertTrue(message.contains("Blocked by administrator"))
    }

    @Test fun cancellationAllowsRetryWithoutSuggestingRemoval() {
        val message = InstallFailureMessage.describe(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED: User rejected permissions")
        assertTrue(message.contains("Установка отменена"))
        assertTrue(message.contains("повторить попытку"))
    }

    @Test fun missingDetailStillExplainsStorageFailure() {
        val message = InstallFailureMessage.describe(PackageInstaller.STATUS_FAILURE_STORAGE, null)
        assertTrue(message.contains("Освободите память"))
        assertFalse(message.contains("null"))
    }

    @Test fun unrecognizedFailureRetainsDiagnostic() {
        assertTrue(InstallFailureMessage.describe(99, "Unexpected installer error").endsWith("Unexpected installer error"))
    }
}
