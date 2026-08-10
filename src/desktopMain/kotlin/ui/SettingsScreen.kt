package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import data.BibleCatalog
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.delay
import ui.components.MaxWidthScaffold
import kotlin.time.Duration.Companion.milliseconds


// True when a settings row/section should stay visible given the search
// query: a blank query (or no search active) shows everything; otherwise
// the row's label / description must contain the trimmed query.
private fun settingsMatch(query: String, vararg terms: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return terms.any { it.contains(q, ignoreCase = true) }
}


@Composable
fun SettingsScreen(
    back: () -> Unit,
    // Pre-filled filter: when the global search's thresholds hint links
    // here, the query starts as "threshold" and the search bar is open,
    // so the list collapses to exactly the two Search sliders.
    initialSearchQuery: String = ""
) {
    val scrollState = rememberScrollState()

    // Ctrl+F search over the settings list (filter by label / description,
    // like the editor find bar and the Bible search). Session-only state:
    // opening focuses the field, Esc / Ctrl+F closes and clears the filter.
    var searchOpen by remember { mutableStateOf(initialSearchQuery.isNotEmpty()) }
    var searchQuery by remember { mutableStateOf(initialSearchQuery) }
    val searchFocusRequester = remember { FocusRequester() }
    // Guards the Ctrl+F toggle against OS key auto-repeat so holding the
    // combo doesn't flicker the field open and closed.
    var lastSearchToggleAt by remember { mutableStateOf(0L) }
    // Language / Translation dropdown open state — declared up here (above
    // the shortcut handler) so the handler can defer Esc to an open menu
    // instead of swallowing it to close the search bar.
    var languageMenuOpen by remember { mutableStateOf(false) }
    var translationMenuOpen by remember { mutableStateOf(false) }

    // Fold state for each collapsible section, held in SettingsManager
    // (session-scoped, in-memory only). Every section starts CLOSED on
    // each app launch; once the user expands one it stays open for the
    // rest of the session, so leaving and reopening Settings restores
    // the user's layout. While a search is active every VISIBLE section
    // is forced open (expanded), so matched rows are never hidden
    // behind a fold.
    val appearanceExpanded = SettingsManager.isSettingsSectionExpanded("appearance")
    val layoutExpanded = SettingsManager.isSettingsSectionExpanded("layout")
    val soundExpanded = SettingsManager.isSettingsSectionExpanded("sound")
    val copyExpanded = SettingsManager.isSettingsSectionExpanded("copy")
    val searchSectionExpanded = SettingsManager.isSettingsSectionExpanded("search")
    val prefsExpanded = SettingsManager.isSettingsSectionExpanded("prefs")
    val searching = searchQuery.isNotBlank()

    // Focus the search field as soon as it appears (the short delay lets
    // it attach to the composition first).
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(50.milliseconds)
            searchFocusRequester.requestFocus()
        }
    }

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
    }

    // Ctrl+F toggles the search bar from anywhere in the screen; while
    // the bar is open it owns the keyboard (Esc / Ctrl+F close it).
    // Attached on the outer Box so events bubble up from any focused
    // descendant (switches, sliders, dropdowns).
    val settingsShortcutHandler:
        (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) {
            return@handler false
        }
        if (searchOpen) {
            return@handler when {
                // While a Language/Translation dropdown is open, Esc must
                // reach the menu (to dismiss it); only once no menu is open
                // does Esc close the search bar.
                event.key == Key.Escape -> {
                    if (languageMenuOpen || translationMenuOpen) {
                        false
                    } else {
                        closeSearch()
                        true
                    }
                }

                event.isCtrlPressed && event.key == Key.F -> {
                    val now = System.currentTimeMillis()
                    if (now - lastSearchToggleAt > 250) {
                        lastSearchToggleAt = now
                        closeSearch()
                    }
                    true
                }

                else -> false
            }
        }
        if (event.isCtrlPressed && event.key == Key.F) {
            val now = System.currentTimeMillis()
            if (now - lastSearchToggleAt > 250) {
                lastSearchToggleAt = now
                SoundManager.play(SoundEvent.Click)
                searchOpen = true
            }
            true
        }
        // Esc with no dropdown / search bar open returns Home (mirroring
        // the other full-screen screens).
        else if (event.key == Key.Escape) {
            if (languageMenuOpen || translationMenuOpen) {
                false
            } else {
                SoundManager.play(SoundEvent.Click)
                back()
                true
            }
        } else {
            false
        }
    }

    // Real Bible picker: languages and translations are discovered from
    // the bundled module files at runtime (BibleCatalog), not hardcoded.
    val languageOptions = BibleCatalog.languages
    // Language shown in the dropdown; falls back to the language of the
    // currently-selected translation if the saved language name is stale.
    val selectedLanguage = remember(SettingsManager.language, SettingsManager.translation) {
        if (BibleCatalog.entriesForLanguage(SettingsManager.language).isNotEmpty()) {
            SettingsManager.language
        } else {
            BibleCatalog.entryFor(SettingsManager.translation)?.languageName
                ?: languageOptions.firstOrNull()
                ?: ""
        }
    }
    val translationEntries = remember(selectedLanguage) {
        BibleCatalog.entriesForLanguage(selectedLanguage)
    }
    val translationOptions = translationEntries.map { it.displayName }
    val selectedTranslation = remember(SettingsManager.translation) {
        BibleCatalog.entryFor(SettingsManager.translation)?.displayName
            ?: translationOptions.firstOrNull()
            ?: ""
    }


    // Search-filtered visibility for each row and section, computed once
    // per composition. A section shows when its own title matches OR any
    // of its rows matches; when the title matches, the whole section is
    // revealed (searching "Sound" shows every sound setting). Blank query
    // (settingsMatch) shows everything.
    val appearanceAll = settingsMatch(searchQuery, "Appearance")
    val darkModeRow = settingsMatch(
        searchQuery,
        "Dark mode",
        "Uses a darker color scheme",
        "Uses a lighter color scheme"
    )
    val fullscreenRow = settingsMatch(
        searchQuery,
        "Fullscreen",
        "Starts the app maximized to the whole screen",
        "Starts the app in a window"
    )
    val colorStyleRow = settingsMatch(
        searchQuery,
        "Color style",
        "Normal",
        "Saturated",
        "Gray",
        "Custom",
        "accent",
        "accent color"
    )
    val showAppearance = appearanceAll || darkModeRow || fullscreenRow || colorStyleRow

    val layoutAll = settingsMatch(searchQuery, "Layout")
    val bibleWidthRow = settingsMatch(searchQuery, "Bible max width")
    val editorWidthRow = settingsMatch(searchQuery, "Editor max width")
    val showLayout = layoutAll || bibleWidthRow || editorWidthRow

    val soundAll = settingsMatch(searchQuery, "Sound")
    val soundEffectsRow = settingsMatch(
        searchQuery,
        "Sound effects",
        "Plays hover",
        "All sound effects muted"
    )
    // The Master volume slider is only rendered while sound effects are
    // enabled, so a search for "volume" with them muted must not reveal an
    // empty Sound section header.
    val volumeRow = settingsMatch(searchQuery, "Master volume") &&
        SettingsManager.soundEffectsEnabled
    val testClickRow = settingsMatch(searchQuery, "Test click sound")
    val showSound = soundAll || soundEffectsRow || volumeRow || testClickRow

    val copyAll = settingsMatch(searchQuery, "Copy")
    val copyTranslationRow = settingsMatch(
        searchQuery,
        "Include translation in copies",
        "Adds the translation name"
    )
    val showCopy = copyAll || copyTranslationRow

    // Global-search result thresholds: a book is shown at book level once
    // the term appears at least N times in it; a chapter at chapter level
    // once it appears at least N times in that chapter. Below those
    // borders the results drill down to lone chapters / verses.
    val searchAll = settingsMatch(searchQuery, "Search")
    val bookThresholdRow = settingsMatch(
        searchQuery,
        "Book threshold",
        "Book result",
        "Book matches"
    )
    val chapterThresholdRow = settingsMatch(
        searchQuery,
        "Chapter threshold",
        "Chapter result",
        "Chapter matches"
    )
    val showSearch = searchAll || bookThresholdRow || chapterThresholdRow

    val prefsAll = settingsMatch(searchQuery, "Bible preferences")
    val languageRow = settingsMatch(searchQuery, "Language")
    val translationRow = settingsMatch(searchQuery, "Translation")
    // The "Current: …" line is searchable too (by its label or the actual
    // language / translation names), so typing "current" finds it instead
    // of showing a dead "No settings match" state.
    val currentRow = settingsMatch(
        searchQuery,
        "Current",
        selectedLanguage,
        selectedTranslation
    )
    val showPrefs = prefsAll || languageRow || translationRow || currentRow

    val nothingShown = searchQuery.isNotBlank() &&
        !(showAppearance || showLayout || showSound || showCopy || showSearch || showPrefs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(settingsShortcutHandler)
    ) {
        // Compact top bar with the back button and the title, mirroring
        // the other full-screen screens (Statistics, Collections, …).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Icon back button: a tappable arrow with the app's hover /
            // click sounds and a subtle hover tint (like the collapsible
            // section headers) signaling it's interactive.
            val backHover = remember { MutableInteractionSource() }
            val isBackHovered by backHover.collectIsHoveredAsState()
            LaunchedEffect(isBackHovered) {
                if (isBackHovered) {
                    delay(60.milliseconds)
                    SoundManager.play(SoundEvent.Hover)
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isBackHovered) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .hoverable(backHover)
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        back()
                    }
            ) {
                Icon(
                    imageVector = RibbonIcons.Back,
                    contentDescription = "Back",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Icon(
                imageVector = RibbonIcons.Settings,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        // Full-height scrollable settings on a wider centered card.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            MaxWidthScaffold(compact = false, maxWidth = 760.dp) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(20.dp)
                    ) {
                    if (searchOpen) {
                        SettingsSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onClose = ::closeSearch,
                            focusRequester = searchFocusRequester
                        )
                    }

                    if (nothingShown) {
                        Text(
                            text = "No settings match \"${searchQuery.trim()}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showAppearance) {
                        SettingsSection(
                            title = "Appearance",
                            expanded = appearanceExpanded || searching,
                            onToggle = {
                                if (!searching) {
                                    SettingsManager.setSettingsSectionExpanded(
                                        "appearance",
                                        !appearanceExpanded
                                    )
                                }
                            }
                        ) {
                        if (appearanceAll || darkModeRow) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text("Dark mode")
                                    Text(
                                        if (SettingsManager.darkMode) {
                                            "Uses a darker color scheme"
                                        } else {
                                            "Uses a lighter color scheme"
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Switch(
                                    checked = SettingsManager.darkMode,
                                    onCheckedChange = { enabled ->
                                        SettingsManager.darkMode = enabled
                                    }
                                )
                            }
                        }

                        if (appearanceAll || fullscreenRow) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text("Fullscreen")
                                    Text(
                                        if (SettingsManager.fullScreen) {
                                            "Starts the app maximized to the whole screen"
                                        } else {
                                            "Starts the app in a window"
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Switch(
                                    checked = SettingsManager.fullScreen,
                                    onCheckedChange = { enabled ->
                                        SettingsManager.fullScreen = enabled
                                    }
                                )
                            }
                        }

                        if (appearanceAll || colorStyleRow) {
                            ColorStyleSetting()
                        }
                        }
                    }

                    if (showLayout) {
                        SettingsSection(
                            title = "Layout",
                            expanded = layoutExpanded || searching,
                            onToggle = {
                                if (!searching) {
                                    SettingsManager.setSettingsSectionExpanded(
                                        "layout",
                                        !layoutExpanded
                                    )
                                }
                            }
                        ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (layoutAll || bibleWidthRow) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Bible max width")
                                    Text("${SettingsManager.bibleMaxWidth.value.toInt()} dp")
                                }
                                Slider(
                                    value = SettingsManager.bibleMaxWidth.value,
                                    onValueChange = { value ->
                                        SettingsManager.bibleMaxWidth = value.dp
                                    },
                                    valueRange = 480f..1800f,
                                    steps = 0,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (layoutAll || editorWidthRow) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Editor max width")
                                    Text("${SettingsManager.editorMaxWidth.value.toInt()} dp")
                                }
                                Slider(
                                    value = SettingsManager.editorMaxWidth.value,
                                    onValueChange = { value ->
                                        SettingsManager.editorMaxWidth = value.dp
                                    },
                                    valueRange = 480f..1800f,
                                    steps = 0,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        }
                    }

                    if (showSound) {
                        SettingsSection(
                            title = "Sound",
                            expanded = soundExpanded || searching,
                            onToggle = {
                                if (!searching) {
                                    SettingsManager.setSettingsSectionExpanded(
                                        "sound",
                                        !soundExpanded
                                    )
                                }
                            }
                        ) {
                        if (soundAll || soundEffectsRow) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Sound effects")
                                    Text(
                                        if (SettingsManager.soundEffectsEnabled) {
                                            "Plays hover / click / open-close sfx"
                                        } else {
                                            "All sound effects muted"
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Switch(
                                    checked = SettingsManager.soundEffectsEnabled,
                                    onCheckedChange = { enabled ->
                                        SoundManager.play(SoundEvent.Click)
                                        SettingsManager.soundEffectsEnabled = enabled
                                    }
                                )
                            }
                        }

                        if (soundAll || volumeRow) {
                            if (SettingsManager.soundEffectsEnabled) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Master volume")
                                        Text("${SettingsManager.soundVolume}%")
                                    }
                                    Slider(
                                        value = SettingsManager.soundVolume.toFloat(),
                                        onValueChange = { value ->
                                            SettingsManager.soundVolume = value.toInt()
                                        },
                                        valueRange = 0f..100f,
                                        steps = 0,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        if (soundAll || testClickRow) {
                            Button(
                                onClick = { SoundManager.play(SoundEvent.Click) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = RibbonIcons.Sound,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Test click sound",
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        }
                    }

                    if (showCopy) {
                        SettingsSection(
                            title = "Copy",
                            expanded = copyExpanded || searching,
                            onToggle = {
                                if (!searching) {
                                    SettingsManager.setSettingsSectionExpanded(
                                        "copy",
                                        !copyExpanded
                                    )
                                }
                            }
                        ) {
                        if (copyAll || copyTranslationRow) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text("Include translation in copies")
                                    Text(
                                        "Adds the translation name to copied verses, " +
                                            "chapters and ranges (e.g. “(Luther Bible 1912)”)",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Switch(
                                    checked = SettingsManager.copyWithTranslationName,
                                    onCheckedChange = { enabled ->
                                        SettingsManager.copyWithTranslationName = enabled
                                    }
                                )
                            }
                        }
                        }
                    }

                    if (showSearch) {
                        SettingsSection(
                            title = "Search",
                            expanded = searchSectionExpanded || searching,
                            onToggle = {
                                if (!searching) {
                                    SettingsManager.setSettingsSectionExpanded(
                                        "search",
                                        !searchSectionExpanded
                                    )
                                }
                            }
                        ) {
                        // Promotion borders for the global search results:
                        // a book shown as a book once the term appears at
                        // least N times in it; below that, its matches drill
                        // down to the "Chapters & verses" list at the end.
                        if (searchAll || bookThresholdRow) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Book threshold")
                                    Text("${SettingsManager.searchBookThreshold} matches")
                                }
                                IntStepperSlider(
                                    value = SettingsManager.searchBookThreshold,
                                    onValueChange = { SettingsManager.searchBookThreshold = it }
                                )
                                Text(
                                    "A book is shown in the results once the term appears " +
                                        "at least this many times; below it, its matches " +
                                        "appear as lone chapters / verses.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Same border at chapter level, applied to the
                        // drill-down list: below it a chapter's matches are
                        // listed as individual verses instead of a chapter.
                        if (searchAll || chapterThresholdRow) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Chapter threshold")
                                    Text("${SettingsManager.searchChapterThreshold} matches")
                                }
                                IntStepperSlider(
                                    value = SettingsManager.searchChapterThreshold,
                                    onValueChange = { SettingsManager.searchChapterThreshold = it }
                                )
                                Text(
                                    "A chapter in the drill-down list is shown as a chapter " +
                                        "once the term appears at least this many times in it; " +
                                        "below that, its verses are listed individually.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        }
                    }

                    if (showPrefs) {
                        SettingsSection(
                            title = "Bible preferences",
                            expanded = prefsExpanded || searching,
                            onToggle = {
                                if (!searching) {
                                    SettingsManager.setSettingsSectionExpanded(
                                        "prefs",
                                        !prefsExpanded
                                    )
                                }
                            }
                        ) {
                        if (prefsAll || languageRow) {
                            DropdownSettingRow(
                                label = "Language",
                                value = selectedLanguage,
                                expanded = languageMenuOpen,
                                onExpandedChange = { languageMenuOpen = it },
                                options = languageOptions,
                                onOptionSelected = { selected ->
                                    SoundManager.play(SoundEvent.Click)
                                    SettingsManager.language = selected
                                    // Auto-select the first translation of the new
                                    // language so the two dropdowns never disagree.
                                    BibleCatalog.entriesForLanguage(selected)
                                        .firstOrNull()
                                        ?.let { SettingsManager.translation = it.moduleId }
                                    languageMenuOpen = false
                                }
                            )
                        }

                        if (prefsAll || translationRow) {
                            DropdownSettingRow(
                                label = "Translation",
                                value = selectedTranslation,
                                expanded = translationMenuOpen,
                                onExpandedChange = { translationMenuOpen = it },
                                options = translationOptions,
                                onOptionSelected = { selected ->
                                    SoundManager.play(SoundEvent.Click)
                                    BibleCatalog.entriesForLanguage(selectedLanguage)
                                        .find { it.displayName == selected }
                                        ?.let { SettingsManager.translation = it.moduleId }
                                    translationMenuOpen = false
                                }
                            )
                        }

                        // (showPrefs above already guarantees this section is
                        // visible, so the row renders unconditionally.)
                        Text(
                            text = "Current: $selectedLanguage · $selectedTranslation",
                            style = MaterialTheme.typography.bodySmall
                        )
                        }
                    }

                    }

                    if (scrollState.maxValue > 0) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(12.dp)
                        )
                    }
                }
            }
        }
    }
}


/**
 * One fold-out section of the settings screen: a tappable header (title +
 * chevron) that expands / collapses the section body with a slide-and-
 * fade animation. While a settings search is active the caller passes
 * `expanded = true` for every visible section (via `|| searching`), so
 * matched rows are never hidden behind a fold. The header plays the
 * app's hover / click sounds.
 */
@Composable
private fun SettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // A subtle tint on hover signals the header is tappable (the
            // chevron already hints it; the tint + hover sound complete
            // the affordance, matching the app's other interactive rows).
            .background(
                if (isHovered) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    Color.Transparent
                }
            )
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onToggle()
            }
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) RibbonIcons.ChevronDown else RibbonIcons.ChevronRight,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
        ) {
            content()
        }
    }
}


/**
 * Color style picker: four selectable chips (Normal / Saturated / Gray /
 * Custom), each showing its palette's primary hue, plus — when Custom is
 * selected — a row of accent seed swatches. Selecting a style re-themes
 * the whole window instantly (the scheme is derived in ui.AppTheme from
 * the persisted key / seed).
 */
@Composable
private fun ColorStyleSetting() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Color style")
        Text(
            "Changes the app's accent colors. Normal is the default scheme; " +
                "Saturated is more vivid; Gray is monochrome; Custom uses the " +
                "accent color you pick below.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            AppColorStyle.entries.forEach { style ->
                val selected = AppColorStyle.fromKey(SettingsManager.colorStyle) == style
                val preview = stylePrimaryColor(
                    style = style,
                    dark = SettingsManager.darkMode,
                    customAccent = SettingsManager.customAccentColor
                )
                ColorStyleChip(
                    label = style.label,
                    color = preview,
                    selected = selected,
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        SettingsManager.colorStyle = style.key
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (AppColorStyle.fromKey(SettingsManager.colorStyle) == AppColorStyle.CUSTOM) {
            Text(
                "Accent color",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ACCENT_SEEDS.forEach { seedArgb ->
                    val selected = SettingsManager.customAccentColor == seedArgb
                    val accentColor = Color(seedArgb)
                    val borderColor = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(PillShape)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = borderColor,
                                shape = PillShape
                            )
                            .clickable {
                                SoundManager.play(SoundEvent.Click)
                                SettingsManager.customAccentColor = seedArgb
                            }
                            .padding(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(PillShape)
                                .background(accentColor)
                        )
                    }
                }
            }
        }
    }
}


/** One selectable color-style chip: a primary-hue dot + label. */
@Composable
private fun ColorStyleChip(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(PillShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}


@Composable
private fun DropdownSettingRow(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label)

        Box {
            Button(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = value,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = RibbonIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                // Scrollable so long option lists (60+ languages, ~90
                // translations) never overflow the window height. NOTE:
                // `heightIn` must come BEFORE `verticalScroll` — with the
                // scroll modifier outermost, a DropdownMenu popup measures
                // its content with infinite max height during dismissal
                // (e.g. right as a translation switch recomposes), which
                // makes the scrollable throw "measured with an infinity
                // maximum height constraints".
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { onOptionSelected(option) }
                        )
                    }
                }
            }
        }
    }
}


/**
 * Compact search field for the Settings screen (Ctrl+F). Filters the
 * settings rows by label as you type; Esc / the ✕ closes it. Styled like
 * the Bible search bar — a subtle surface with a placeholder, auto-focus
 * handled by the caller's [focusRequester].
 */
@Composable
private fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = RibbonIcons.Find,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "Search settings…",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.5f)
                            )
                        )
                    }
                    inner()
                }
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .hoverable(remember { MutableInteractionSource() })
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        onClose()
                    }
                    .padding(horizontal = 4.dp)
            )
        }
    }
}


/**
 * Integer slider with − / + steppers on either side for exact value
 * control (the slider itself still drags). Used for the search thresholds
 * (1..30); the buttons disable at the bounds and the SettingsManager
 * setters clamp anyway.
 */
@Composable
private fun IntStepperSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int = 1,
    max: Int = 30
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        StepperButton(
            icon = RibbonIcons.Minus,
            enabled = value > min,
            onClick = { onValueChange(value - 1) }
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f)
        )
        StepperButton(
            icon = RibbonIcons.Plus,
            enabled = value < max,
            onClick = { onValueChange(value + 1) }
        )
    }
}


/** Initial hold before the first repeat tick of [StepperButton]. */
private const val STEPPER_INITIAL_DELAY_MS = 400L

/** Fastest repeat cadence while held (the acceleration floor). */
private const val STEPPER_MIN_INTERVAL_MS = 40L

/** Repeat interval shrinks by this factor each tick (acceleration). */
private const val STEPPER_ACCEL = 0.75

/**
 * Small square − / + button for [IntStepperSlider]: dims when disabled,
 * plays the app's hover / click sounds, and keeps a consistent hit area.
 * Press-and-hold repeats [onClick] — after a short hold it starts ticking
 * and speeds up, so nudging a value from 1 to 30 is one press, not 29.
 */
@Composable
private fun StepperButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        // Disabled buttons stay silent — no hover feedback for an action
        // the user can't take.
        if (isHovered && enabled) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    // The press-and-hold pointerInput below is keyed once (Unit), so it
    // must read the LATEST onClick / enabled through rememberUpdatedState —
    // a captured lambda would keep firing on the stale value.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)
    var pressed by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            pressed -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .size(32.dp)
            .hoverable(hover)
            // Restore the button role / disabled state that clickable used
            // to expose (the custom pointerInput below replaces it).
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (currentEnabled) {
                            // Act immediately on press, then repeat while
                            // held. detectTapGestures CANCELS this block
                            // the moment the pointer releases, so the
                            // finally below always clears the pressed tint.
                            try {
                                pressed = true
                                SoundManager.play(SoundEvent.Click)
                                currentOnClick()
                                var interval = STEPPER_INITIAL_DELAY_MS
                                while (true) {
                                    delay(interval.milliseconds)
                                    currentOnClick()
                                    interval = (interval * STEPPER_ACCEL)
                                        .toLong()
                                        .coerceAtLeast(STEPPER_MIN_INTERVAL_MS)
                                }
                            } finally {
                                pressed = false
                            }
                        }
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                }
            )
        }
    }
}
