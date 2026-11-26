package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * The Settings screen is generated from the catalog, so these guard the two
 * things the screen relies on: the filter can find rows by any of their
 * searchable text, and the numbering / ids stay sane as sections are added.
 */
class SettingsCatalogTest {

    private fun toggle(id: String, title: String, description: String = "", keywords: List<String> = emptyList()) =
        SettingRowSpec(
            id = id,
            title = title,
            description = description,
            keywords = keywords,
            control = SettingControl.Toggle(checked = false, onChange = {})
        )

    private fun sampleSections() = listOf(
        SettingsSectionSpec(
            id = "style",
            title = "Style",
            summary = "Look and feel",
            rows = listOf(
                toggle("style.dark", "Dark mode", "Night palette"),
                toggle("style.fullscreen", "Fullscreen", "Maximized window"),
                toggle("style.sound", "Sound effects", keywords = listOf("audio", "sfx"))
            )
        ),
        SettingsSectionSpec(
            id = "bible",
            title = "Bible",
            summary = "Reading width",
            rows = listOf(toggle("bible.width", "Bible max width", "How wide"))
        )
    )

    @Test
    fun blankQueryShowsEverythingInOrder() {
        val visible = filterSettingsSections(sampleSections(), "")
        assertEquals(listOf("style", "bible"), visible.map { it.section.id })
        assertEquals(listOf("01", "02"), visible.map { it.number })
        assertEquals(3, visible.first().rows.size)
    }

    @Test
    fun sectionTitleMatchKeepsEveryRowOfThatSection() {
        val visible = filterSettingsSections(sampleSections(), "style")
        assertEquals(1, visible.size)
        assertEquals(3, visible.single().rows.size)
        // Numbering follows the catalog position, not the filtered list.
        assertEquals("01", visible.single().number)
    }

    @Test
    fun rowMatchKeepsOnlyThatRow() {
        val visible = filterSettingsSections(sampleSections(), "maximized")
        assertEquals(1, visible.size)
        assertEquals(listOf("style.fullscreen"), visible.single().rows.map { it.id })
    }

    @Test
    fun keywordsAndDescriptionsAreSearchable() {
        assertEquals(
            listOf("style.sound"),
            filterSettingsSections(sampleSections(), "sfx").flatMap { it.rows }.map { it.id }
        )
        assertEquals(
            listOf("bible.width"),
            filterSettingsSections(sampleSections(), "wide").flatMap { it.rows }.map { it.id }
        )
    }

    @Test
    fun noMatchShowsNothing() {
        assertTrue(filterSettingsSections(sampleSections(), "zzzz").isEmpty())
    }

    @Test
    fun realCatalogIsNumberedAndUnique() {
        val sections = settingsSections()
        assertEquals(
            listOf("Style", "Audio", "Translation", "Notes", "Bible"),
            sections.map { it.title }
        )
        assertEquals(
            listOf("01", "02", "03", "04", "05"),
            filterSettingsSections(sections, "").map { it.number }
        )
        assertTrue(sections.all { it.rows.isNotEmpty() }, "every section needs at least one setting")

        val rowIds = sections.flatMap { section -> section.rows.map { it.id } }
        assertEquals(rowIds.size, rowIds.toSet().size, "duplicate row ids: $rowIds")
        val sectionIds = sections.map { it.id }
        assertEquals(sectionIds.size, sectionIds.toSet().size)
    }

    @Test
    fun globalSearchPrefillFindsBothThresholds() {
        // Navigation pre-fills the Settings filter with "threshold" when the
        // global search hints at it, so those two rows must stay matchable.
        val ids = filterSettingsSections(settingsSections(), "threshold")
            .flatMap { it.rows }
            .map { it.id }
        assertTrue("bible.book-threshold" in ids, "ids were $ids")
        assertTrue("bible.chapter-threshold" in ids, "ids were $ids")
    }
}
