package data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * Tests for the update check's pure logic: version ordering and the
 * `releases/latest` JSON parse (which installer is picked for a platform,
 * and when a release counts as "newer"). No network — [AppUpdater]
 * parsing is fed captured fixtures.
 */
class AppUpdaterTest {

    private val latestJson = """
        {
          "tag_name": "v2.3.0",
          "name": "BibleApp v2.3.0",
          "html_url": "https://github.com/BacroXP/JasonBible/releases/tag/v2.3.0",
          "assets": [
            {
              "name": "bibleapp_2.3.0_amd64.deb",
              "browser_download_url": "https://example.test/bibleapp_2.3.0_amd64.deb"
            },
            {
              "name": "BibleApp-2.3.0.msi",
              "browser_download_url": "https://example.test/BibleApp-2.3.0.msi"
            },
            {
              "name": "BibleApp-2.3.0.dmg",
              "browser_download_url": "https://example.test/BibleApp-2.3.0.dmg"
            }
          ]
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // Version ordering
    // ------------------------------------------------------------------

    @Test
    fun newerPatchMinorAndMajorAreDetected() {
        assertTrue(AppUpdater.isNewerVersion("2.2.2", "2.2.1"))
        assertTrue(AppUpdater.isNewerVersion("2.3.0", "2.2.1"))
        assertTrue(AppUpdater.isNewerVersion("3.0.0", "2.9.9"))
        // Numeric, not lexicographic: 10 > 9.
        assertTrue(AppUpdater.isNewerVersion("2.2.10", "2.2.9"))
    }

    @Test
    fun sameAndOlderVersionsAreNotNewer() {
        assertFalse(AppUpdater.isNewerVersion("2.2.1", "2.2.1"))
        assertFalse(AppUpdater.isNewerVersion("2.2.0", "2.2.1"))
        assertFalse(AppUpdater.isNewerVersion("1.9.9", "2.0.0"))
        // A missing part counts as zero.
        assertFalse(AppUpdater.isNewerVersion("2.2", "2.2.0"))
    }

    @Test
    fun tagPrefixIsStripped() {
        assertEquals("2.3.0", AppUpdater.normalizeVersion("v2.3.0"))
        assertEquals("2.3.0", AppUpdater.normalizeVersion("V2.3.0"))
        assertEquals("2.3.0", AppUpdater.normalizeVersion("  2.3.0  "))
        assertTrue(AppUpdater.isNewerVersion("v2.3.0", "2.2.1"))
    }

    // ------------------------------------------------------------------
    // Release parsing
    // ------------------------------------------------------------------

    @Test
    fun newerReleasePicksThisPlatformsInstaller() {
        val linux = AppUpdater.parseLatestRelease(latestJson, "2.2.1", ".deb")
        assertNotNull(linux)
        assertEquals("2.3.0", linux.version)
        assertEquals("bibleapp_2.3.0_amd64.deb", linux.assetName)
        assertEquals(
            "https://example.test/bibleapp_2.3.0_amd64.deb",
            linux.assetUrl
        )
        assertEquals(
            "https://github.com/BacroXP/JasonBible/releases/tag/v2.3.0",
            linux.pageUrl
        )

        assertEquals(
            "BibleApp-2.3.0.msi",
            AppUpdater.parseLatestRelease(latestJson, "2.2.1", ".msi")?.assetName
        )
        assertEquals(
            "BibleApp-2.3.0.dmg",
            AppUpdater.parseLatestRelease(latestJson, "2.2.1", ".dmg")?.assetName
        )
    }

    @Test
    fun currentOrOlderReleaseYieldsNoUpdate() {
        assertNull(AppUpdater.parseLatestRelease(latestJson, "2.3.0", ".deb"))
        assertNull(AppUpdater.parseLatestRelease(latestJson, "2.4.0", ".deb"))
    }

    @Test
    fun releaseWithoutAssetForThisPlatformStillReportsTheVersion() {
        // A Linux user must still see that 2.3.0 exists even if the release
        // only carried a Windows installer — clicking then opens the page.
        val release = AppUpdater.parseLatestRelease("""
            {
              "tag_name": "v2.3.0",
              "html_url": "https://example.test/tag",
              "assets": [
                {"name": "BibleApp-2.3.0.msi", "browser_download_url": "https://example.test/a.msi"}
              ]
            }
        """.trimIndent(), "2.2.1", ".deb")
        assertNotNull(release)
        assertEquals("2.3.0", release.version)
        assertNull(release.assetUrl)
        assertNull(release.assetName)
    }

    @Test
    fun malformedOrEmptyResponseYieldsNoUpdate() {
        assertNull(AppUpdater.parseLatestRelease("not json", "2.2.1", ".deb"))
        assertNull(AppUpdater.parseLatestRelease("{}", "2.2.1", ".deb"))
        assertNull(AppUpdater.parseLatestRelease("""{"tag_name": "v2.3.0"}""", "2.0.0", ".deb")?.assetUrl)
    }
}
