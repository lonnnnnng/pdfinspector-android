package com.loooong.reader

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineUpdaterTest {

    @Test
    fun calculatesBoundedDownloadProgress() {
        assertEquals(25, updateProgressPercent(25L, 100L))
        assertEquals(100, updateProgressPercent(150L, 100L))
        assertNull(updateProgressPercent(0L, -1L))
    }

    @Test
    fun mapsActionableDownloadFailuresToChineseMessages() {
        assertEquals(
            "下载失败：设备存储空间不足",
            updateDownloadFailureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE),
        )
        assertEquals(
            "下载失败：服务器返回 HTTP 404",
            updateDownloadFailureMessage(404),
        )
    }

    @Test
    fun keepsApkAssetNameAndFallsBackToSafeVersionName() {
        assertEquals(
            "pdfinspector-0.6.0.apk",
            updateApkFileName(
                "https://github.com/example/releases/download/v0.6.0/pdfinspector-0.6.0.apk?download=1",
                "v0.6.0",
            ),
        )
        assertEquals(
            "pdfinspector-v0.6.0-beta_1.apk",
            updateApkFileName("https://example.com/download/latest", "v0.6.0-beta+1"),
        )
    }
}
