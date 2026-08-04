package com.loooong.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun comparesSemanticVersionsNumerically() {
        assertTrue(isVersionNewer("v0.4.0", "0.3.2"))
        assertTrue(isVersionNewer("v0.10.0", "0.9.9"))
        assertFalse(isVersionNewer("v0.3.2", "0.3.2"))
        assertFalse(isVersionNewer("v0.3.1", "0.3.2"))
    }

    @Test
    fun ignoresVersionPrefixAndSuffix() {
        assertTrue(isVersionNewer("v1.2.4", "1.2.3-debug"))
        assertFalse(isVersionNewer("1.2.3", "v1.2.3"))
    }

    @Test
    fun parsesReleaseAndSelectsApkAsset() {
        val release = UpdateChecker.parseRelease(
            """
            {
              "tag_name": "v0.4.0",
              "name": "阅读 0.4.0",
              "body": "Form editing and settings",
              "html_url": "https://github.com/example/releases/tag/v0.4.0",
              "assets": [
                {
                  "name": "source.zip",
                  "content_type": "application/zip",
                  "browser_download_url": "https://example.com/source.zip"
                },
                {
                  "name": "pdfinspector-0.4.0.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://example.com/app.apk"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("v0.4.0", release.tagName)
        assertEquals("阅读 0.4.0", release.name)
        assertEquals("https://example.com/app.apk", release.downloadUrl)
    }
}
