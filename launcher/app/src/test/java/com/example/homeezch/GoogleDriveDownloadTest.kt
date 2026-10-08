package com.example.homeezch

import com.example.homeezch.data.GoogleDriveDownload
import org.junit.Assert.*
import org.junit.Test

class GoogleDriveDownloadTest {
    private val id = "1MlZxRbHIgDaFsV18bWnMq5bkjs-N6hDA"
    private val url = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
    private fun form(targetId: String = id, action: String = "https://drive.usercontent.google.com/download") = """
        <form id="download-form" action="$action" method="get">
        <input type="hidden" name="id" value="$targetId">
        <input value="download" name="export" type="hidden">
        <input type="hidden" name="confirm" value="t">
        <input type="hidden" name="uuid" value="test-token">
        </form>
    """.trimIndent()
    @Test fun fileLinkBecomesPublicDownloadAndGithubIsPreserved() {
        assertEquals(url, GoogleDriveDownload.normalize("https://drive.google.com/file/d/$id/view?usp=sharing"))
        assertEquals(url, GoogleDriveDownload.normalize("https://drive.google.com/uc?id=$id&export=download"))
        assertEquals("https://github.com/a/b.apk", GoogleDriveDownload.normalize("https://github.com/a/b.apk"))
    }
    @Test fun confirmationRemainsBoundToSelectedApk() {
        assertEquals(url + "&uuid=test-token", GoogleDriveDownload.confirmationUrl(form(), url))
    }
    @Test fun changedFileOrDownloadHostCannotRedirectInstallation() {
        for (page in listOf(form("another-file-id"), form(action="https://example.com/download"), form(action="http://drive.usercontent.google.com/download"))) {
            assertTrue(runCatching { GoogleDriveDownload.confirmationUrl(page, url) }.isFailure)
        }
    }
    @Test fun quotaAndLoginPagesAreReportedAsErrors() {
        assertTrue(runCatching { GoogleDriveDownload.confirmationUrl("<html>Quota exceeded</html>", url) }.isFailure)
        assertTrue(runCatching { GoogleDriveDownload.normalize("https://drive.google.com/drive/folders/$id") }.isFailure)
    }
}
