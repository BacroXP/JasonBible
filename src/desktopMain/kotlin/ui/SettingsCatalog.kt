package ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import data.BibleCatalog
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager


// ---------------------------------------------------------------------------
// The settings catalog
//
// Every section and every row on the Settings screen is declared here, once,
// as data. SettingsScreen only knows how to DRAW the controls; it has no list
// of settings of its own.
//
// To add a SETTING: append one row to a section's `rows` list below — e.g.
//     toggleRow(
//         id = "bible.verse-numbers",
//         title = "Verse numbers",
//         description = "Shows the verse number before each verse",
//         checked = SettingsManager.showVerseNumbers,
//         onChange = { SettingsManager.showVerseNumbers = it }
//     )
// Search, keyboard focus and layout all follow automatically: the row's
// title, description, help text and keywords are what Ctrl+F matches.
//
// To add a SECTION: add a private `fun xSection(): SettingsSectionSpec` next
// to the others and append it to [settingsSections]. Numbers ("01", "02", …)
// are derived from the list position, so nothing needs renumbering.
//
// To add a new KIND of control: add a [SettingControl] case plus a branch in
// `SettingsRowView` (SettingsScreen.kt) and a `xRow(...)` helper at the
// bottom of this file.
// ---------------------------------------------------------------------------


/** What a settings row renders. One case per kind of control. */
sealed interface SettingControl {

    data class Toggle(
        val checked: Boolean,
        val onChange: (Boolean) -> Unit
    ) : SettingControl

    data class Slider(
        val value: Float,
        val range: ClosedFloatingPointRange<Float>,
        val valueLabel: String,
        val onChange: (Float) -> Unit
    ) : SettingControl

    /** Slider over whole numbers (search thresholds). */
    data class IntSlider(
        val value: Int,
        val range: IntRange,
        val valueLabel: String,
        val onChange: (Int) -> Unit
    ) : SettingControl

    data class Dropdown(
        val value: String,
        val options: List<String>,
        val onSelect: (String) -> Unit
    ) : SettingControl

    data class Action(
        val label: String,
        val icon: ImageVector? = null,
        val onClick: () -> Unit
    ) : SettingControl

    /** A read-only value (a path, the active translation, …). */
    data class Info(
        val value: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    ) : SettingControl

    /** Escape hatch for a one-off UI that doesn't fit a control above. */
    data class Custom(val content: @Composable () -> Unit) : SettingControl
}


/** One row on the Settings screen. */
data class SettingRowSpec(
    val id: String,
    val title: String,
    val description: String = "",
    /** Rendered below the control (longer explanatory copy). */
    val help: String = "",
    /** Extra words Ctrl+F should match, beyond title / description / help. */
    val keywords: List<String> = emptyList(),
    val control: SettingControl
)


/** One numbered section of the Settings screen. */
data class SettingsSectionSpec(
    val id: String,
    val title: String,
    val summary: String,
    val keywords: List<String> = emptyList(),
    val rows: List<SettingRowSpec>
)


/** A section that survived the search filter, with its display number. */
data class VisibleSection(
    val section: SettingsSectionSpec,
    val number: String,
    val rows: List<SettingRowSpec>
)


/** "01", "02", … for the section at [index]. */
fun sectionNumber(index: Int): String = (index + 1).toString().padStart(2, '0')


/**
 * Splits the catalog into what the screen should render for [query].
 *
 * A blank query shows every section with every row. Otherwise a section whose
 * own title / summary / keywords match is shown whole (typing "Sound" reveals
 * every sound row), and a section that only matches through its rows shows
 * just those rows. Sections with no match disappear.
 */
fun filterSettingsSections(
    sections: List<SettingsSectionSpec>,
    query: String
): List<VisibleSection> {
    val q = query.trim()
    return sections.mapIndexedNotNull { index, section ->
        if (q.isEmpty()) {
            VisibleSection(section, sectionNumber(index), section.rows)
        } else {
            val sectionHit = section.title.matches(q) ||
                section.summary.matches(q) ||
                section.keywords.any { it.matches(q) }
            val rows = if (sectionHit) section.rows else section.rows.filter { it.matches(q) }
            if (rows.isEmpty()) {
                null
            } else {
                VisibleSection(section, sectionNumber(index), rows)
            }
        }
    }
}


private fun String.matches(query: String): Boolean =
    contains(query, ignoreCase = true)


private fun SettingRowSpec.matches(query: String): Boolean {
    if (title.matches(query) || description.matches(query) || help.matches(query)) return true
    if (keywords.any { it.matches(query) }) return true
    val extra = control.searchText()
    return extra.isNotEmpty() && extra.matches(query)
}


/** Text a control contributes to the search (its options / value). */
private fun SettingControl.searchText(): String = when (this) {
    is SettingControl.Toggle -> ""
    is SettingControl.Slider -> valueLabel
    is SettingControl.IntSlider -> valueLabel
    is SettingControl.Dropdown -> options.joinToString(" ") + " " + value
    is SettingControl.Action -> label
    is SettingControl.Info -> value + " " + actionLabel.orEmpty()
    is SettingControl.Custom -> ""
}


/**
 * The whole Settings screen as data, in display order. Values are read from
 * SettingsManager on every call (the screen rebuilds this list per
 * composition), so a toggle immediately re-reads its own new value.
 */
fun settingsSections(): List<SettingsSectionSpec> {
    val languageOptions = BibleCatalog.languages
    // Language shown for the current translation when the saved language
    // name is stale (e.g. the translation moved to another language group).
    val selectedLanguage = if (BibleCatalog.entriesForLanguage(SettingsManager.language).isNotEmpty()) {
        SettingsManager.language
    } else {
        BibleCatalog.entryFor(SettingsManager.translation)?.languageName
            ?: languageOptions.firstOrNull()
            ?: ""
    }
    val translationEntries = BibleCatalog.entriesForLanguage(selectedLanguage)
    val translationOptions = translationEntries.map { it.displayName }
    val selectedTranslation = BibleCatalog.entryFor(SettingsManager.translation)?.displayName
        ?: translationOptions.firstOrNull()
        ?: ""

    return listOf(
        styleSection(),
        audioSection(),
        translationSection(
            languageOptions = languageOptions,
            selectedLanguage = selectedLanguage,
            translationOptions = translationOptions,
            selectedTranslation = selectedTranslation
        ),
        notesSection(),
        bibleSection()
    )
}


private fun styleSection(): SettingsSectionSpec = SettingsSectionSpec(
    id = "style",
    title = "Style",
    summary = "Theme, window behaviour and the accent palette.",
    keywords = listOf("appearance", "theme", "look", "colors"),
    rows = buildList {
        add(
            toggleRow(
                id = "style.dark-mode",
                title = "Dark mode",
                description = if (SettingsManager.darkMode) {
                    "Uses the navy night palette"
                } else {
                    "Uses the cream paper palette"
                },
                keywords = listOf("light", "theme", "night", "paper"),
                checked = SettingsManager.darkMode,
                onChange = { SettingsManager.darkMode = it }
            )
        )
        add(
            toggleRow(
                id = "style.fullscreen",
                title = "Fullscreen",
                description = if (SettingsManager.fullScreen) {
                    "Starts the app maximized to the whole screen"
                } else {
                    "Starts the app in a window"
                },
                keywords = listOf("maximized", "window", "start"),
                checked = SettingsManager.fullScreen,
                onChange = { SettingsManager.fullScreen = it }
            )
        )
        add(
            SettingRowSpec(
                id = "style.color-style",
                title = "Color style",
                description = "The accent palette every screen is drawn with.",
                keywords = listOf("accent", "normal", "saturated", "gray", "custom"),
                control = SettingControl.Custom { ColorStylePicker() }
            )
        )
    }
)


private fun audioSection(): SettingsSectionSpec = SettingsSectionSpec(
    id = "audio",
    title = "Audio",
    summary = "The interface sounds and how loud they play.",
    keywords = listOf("sound", "sfx", "volume", "click", "hover", "audio"),
    rows = buildList {
        add(
            toggleRow(
                id = "audio.sound-effects",
                title = "Sound effects",
                description = if (SettingsManager.soundEffectsEnabled) {
                    "Plays hover / click / open-close sfx"
                } else {
                    "All sound effects muted"
                },
                keywords = listOf("mute", "silent", "sfx"),
                checked = SettingsManager.soundEffectsEnabled,
                onChange = { enabled ->
                    SoundManager.play(SoundEvent.Click)
                    SettingsManager.soundEffectsEnabled = enabled
                }
            )
        )
        // The volume slider only makes sense while sound is on, so it is
        // simply not part of the catalog when the effects are muted.
        if (SettingsManager.soundEffectsEnabled) {
            add(
                sliderRow(
                    id = "audio.volume",
                    title = "Master volume",
                    description = "How loud the hover and click sounds play.",
                    keywords = listOf("loudness", "level"),
                    value = SettingsManager.soundVolume.toFloat(),
                    range = 0f..100f,
                    valueLabel = "${SettingsManager.soundVolume}%",
                    onChange = { SettingsManager.soundVolume = it.toInt() }
                )
            )
        }
        add(
            SettingRowSpec(
                id = "audio.test-sound",
                title = "Test the click sound",
                description = "Plays one click at the current volume.",
                keywords = listOf("preview", "try", "play"),
                control = SettingControl.Action(
                    label = "Play sound",
                    icon = RibbonIcons.Sound,
                    onClick = { SoundManager.play(SoundEvent.Click) }
                )
            )
        )
    }
)


private fun translationSection(
    languageOptions: List<String>,
    selectedLanguage: String,
    translationOptions: List<String>,
    selectedTranslation: String
): SettingsSectionSpec = SettingsSectionSpec(
    id = "translation",
    title = "Translation",
    summary = "Which Bible text the reader and every reference open.",
    keywords = listOf("bible", "language", "version", "text"),
    rows = listOf(
        dropdownRow(
            id = "translation.language",
            title = "Language",
            description = "Narrows the translation list below.",
            keywords = listOf("locale", "tongue"),
            value = selectedLanguage,
            options = languageOptions,
            onSelect = { selected ->
                SoundManager.play(SoundEvent.Click)
                SettingsManager.language = selected
                // Keep the two dropdowns in agreement: the language switch
                // also selects that language's first translation.
                BibleCatalog.entriesForLanguage(selected)
                    .firstOrNull()
                    ?.let { SettingsManager.translation = it.moduleId }
            }
        ),
        dropdownRow(
            id = "translation.translation",
            title = "Translation",
            description = "The text opened by the Bible screen, search and references.",
            keywords = listOf("version", "module"),
            value = selectedTranslation,
            options = translationOptions,
            onSelect = { selected ->
                SoundManager.play(SoundEvent.Click)
                BibleCatalog.entriesForLanguage(selectedLanguage)
                    .find { it.displayName == selected }
                    ?.let { SettingsManager.translation = it.moduleId }
            }
        ),
        SettingRowSpec(
            id = "translation.current",
            title = "Active pair",
            keywords = listOf("current", "selected"),
            control = SettingControl.Info(
                value = "$selectedLanguage · $selectedTranslation"
            )
        )
    )
)


private fun notesSection(): SettingsSectionSpec = SettingsSectionSpec(
    id = "notes",
    title = "Notes",
    summary = "Where your notes live and how the editor lays them out.",
    keywords = listOf("editor", "folder", "files", "storage"),
    rows = buildList {
        add(
            SettingRowSpec(
                id = "notes.folder",
                title = "Save location",
                description = "Every note is a plain .note file in this folder. Changing " +
                    "it moves the existing notes along.",
                keywords = listOf(
                    "path", "directory", "where", "storage", "open", "move", "relocate"
                ),
                // Custom instead of Info: the row owns the folder picker and
                // the one-line result message (moved / left behind / error).
                control = SettingControl.Custom { NotesFolderSetting() }
            )
        )
        add(
            sliderRow(
                id = "notes.editor-width",
                title = "Editor max width",
                description = "How wide the notes editor card may grow.",
                value = SettingsManager.editorMaxWidth.value,
                range = 480f..1800f,
                valueLabel = "${SettingsManager.editorMaxWidth.value.toInt()} dp",
                onChange = { SettingsManager.editorMaxWidth = it.dp }
            )
        )
        add(
            toggleRow(
                id = "notes.copy-translation",
                title = "Include translation in copies",
                description = "Adds the translation name to copied verses, chapters and " +
                    "ranges (e.g. “(Luther Bible 1912)”)",
                keywords = listOf("clipboard", "paste", "cite", "source"),
                checked = SettingsManager.copyWithTranslationName,
                onChange = { SettingsManager.copyWithTranslationName = it }
            )
        )
    }
)


private fun bibleSection(): SettingsSectionSpec = SettingsSectionSpec(
    id = "bible",
    title = "Bible",
    summary = "Reading width and how search results are grouped.",
    keywords = listOf("reader", "search", "results", "threshold"),
    rows = listOf(
        sliderRow(
            id = "bible.max-width",
            title = "Bible max width",
            description = "How wide the reading card may grow on a large window.",
            value = SettingsManager.bibleMaxWidth.value,
            range = 480f..1800f,
            valueLabel = "${SettingsManager.bibleMaxWidth.value.toInt()} dp",
            onChange = { SettingsManager.bibleMaxWidth = it.dp }
        ),
        intSliderRow(
            id = "bible.book-threshold",
            title = "Book threshold",
            description = "A book is listed as a book once the term appears this often in it.",
            help = "Below this count its matches drop to lone chapters and verses.",
            keywords = listOf("search", "matches", "grouping"),
            value = SettingsManager.searchBookThreshold,
            range = 1..30,
            valueLabel = "${SettingsManager.searchBookThreshold} matches",
            onChange = { SettingsManager.searchBookThreshold = it }
        ),
        intSliderRow(
            id = "bible.chapter-threshold",
            title = "Chapter threshold",
            description = "A chapter is listed as a chapter once the term appears this often in it.",
            help = "Below this count its verses are listed one by one.",
            keywords = listOf("search", "matches", "grouping"),
            value = SettingsManager.searchChapterThreshold,
            range = 1..30,
            valueLabel = "${SettingsManager.searchChapterThreshold} matches",
            onChange = { SettingsManager.searchChapterThreshold = it }
        )
    )
)


// ---------------------------------------------------------------------------
// Row builders — one line per setting.
// ---------------------------------------------------------------------------

private fun toggleRow(
    id: String,
    title: String,
    description: String = "",
    keywords: List<String> = emptyList(),
    checked: Boolean,
    onChange: (Boolean) -> Unit
): SettingRowSpec = SettingRowSpec(
    id = id,
    title = title,
    description = description,
    keywords = keywords,
    control = SettingControl.Toggle(checked, onChange)
)


private fun sliderRow(
    id: String,
    title: String,
    description: String = "",
    keywords: List<String> = emptyList(),
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit
): SettingRowSpec = SettingRowSpec(
    id = id,
    title = title,
    description = description,
    keywords = keywords,
    control = SettingControl.Slider(value, range, valueLabel, onChange)
)


private fun intSliderRow(
    id: String,
    title: String,
    description: String = "",
    help: String = "",
    keywords: List<String> = emptyList(),
    value: Int,
    range: IntRange,
    valueLabel: String,
    onChange: (Int) -> Unit
): SettingRowSpec = SettingRowSpec(
    id = id,
    title = title,
    description = description,
    help = help,
    keywords = keywords,
    control = SettingControl.IntSlider(value, range, valueLabel, onChange)
)


private fun dropdownRow(
    id: String,
    title: String,
    description: String = "",
    keywords: List<String> = emptyList(),
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit
): SettingRowSpec = SettingRowSpec(
    id = id,
    title = title,
    description = description,
    keywords = keywords,
    control = SettingControl.Dropdown(value, options, onSelect)
)
