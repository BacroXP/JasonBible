package data

import testutil.TestEnv
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * Tests for the Settings screen's collapsible-section fold state in
 * [SettingsManager]. The state is SESSION-SCOPED (in-memory only, never
 * written to disk): every section starts closed on each app launch, and
 * once expanded a section stays expanded for the rest of the session so
 * reopening Settings restores the user's layout. The finally blocks
 * restore the default (collapsed) state so the shared singleton can't
 * leak an expanded section into other tests.
 */
class SettingsSectionsExpandedTest {

    companion object {
        init {
            TestEnv.homeDir
        }
    }

    @Test
    fun sectionsDefaultToCollapsed() {
        // A section with no toggle yet is closed — Settings opens with
        // every section collapsed on the first visit.
        assertFalse(SettingsManager.isSettingsSectionExpanded("appearance"))
        assertFalse(SettingsManager.isSettingsSectionExpanded("prefs"))
    }

    @Test
    fun expandedSectionStaysExpanded() {
        val sectionId = "appearance"
        try {
            SettingsManager.setSettingsSectionExpanded(sectionId, true)
            assertTrue(SettingsManager.isSettingsSectionExpanded(sectionId))
        } finally {
            SettingsManager.setSettingsSectionExpanded(sectionId, false)
        }
    }

    @Test
    fun collapsingARestoredSectionReturnsToCollapsed() {
        val sectionId = "sound"
        try {
            SettingsManager.setSettingsSectionExpanded(sectionId, true)
            assertTrue(SettingsManager.isSettingsSectionExpanded(sectionId))

            // Collapsing again restores the closed default.
            SettingsManager.setSettingsSectionExpanded(sectionId, false)
            assertFalse(SettingsManager.isSettingsSectionExpanded(sectionId))
        } finally {
            SettingsManager.setSettingsSectionExpanded(sectionId, false)
        }
    }

    @Test
    fun sectionsAreIndependent() {
        try {
            SettingsManager.setSettingsSectionExpanded("layout", true)
            assertTrue(SettingsManager.isSettingsSectionExpanded("layout"))
            // An unrelated section keeps its closed default.
            assertFalse(SettingsManager.isSettingsSectionExpanded("copy"))
        } finally {
            SettingsManager.setSettingsSectionExpanded("layout", false)
        }
    }
}
