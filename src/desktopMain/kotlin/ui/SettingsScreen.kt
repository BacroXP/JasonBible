package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds


// ---------------------------------------------------------------------------
// Settings
//
// The screen is a pure renderer: sections and rows come from
// ui.SettingsCatalog (settingsSections / filterSettingsSections), so adding a
// setting means appending one row there — no changes here.
//
// Layout, styled after the GitHub-Pages site (docs/readme):
//   - a big rotating gold gear + serif title hero inside the scroll area,
//   - the same gear collapsing into the sticky top bar once you scroll,
//     where numbered jump chips jump to each section,
//   - a numbered index rail beside the content ("01 Style", "02 Translation",
//     "03 Notes", "04 Bible") that follows the scroll position,
//   - every section a panel with its number in a gold-mono gutter.
// ---------------------------------------------------------------------------


/** Height of the app-bar slot (empty until the bar fades in). */
private val APP_BAR_HEIGHT = 54.dp

/** Scroll distance the content must travel before the bar may appear. */
private const val BAR_REVEAL_PX = 64

/** Scroll delta (px) before a movement counts as up / down. */
private const val SCROLL_DIRECTION_SLOP_PX = 2


@Composable
fun SettingsScreen(
    back: () -> Unit,
    // Pre-filled filter: when the global search's thresholds hint links
    // here, the query starts as "threshold" and the search bar is open,
    // so the list collapses to exactly the two Search sliders.
    initialSearchQuery: String = ""
) {
    val scrollState = rememberScrollState()

    // Search filter over the catalog (title / description / help /
    // keywords), opened from the magnifier in the app bar.
    var searchOpen by remember { mutableStateOf(initialSearchQuery.isNotEmpty()) }
    var searchQuery by remember { mutableStateOf(initialSearchQuery) }
    val searchFocusRequester = remember { FocusRequester() }
    // Which dropdown row (by row id) has its menu open.
    var openMenuRowId by remember { mutableStateOf<String?>(null) }

    // Scroll target of each section, recorded as it is laid out. Keyed by
    // section id so the rail and the top-bar chips can jump to it.
    val sectionOffsets = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()

    val sections = settingsSections()
    val visibleSections = filterSettingsSections(sections, searchQuery)
    val searching = searchQuery.isNotBlank()
    // Which way the content last moved. The bar appears once the hero has
    // scrolled away and the content keeps moving down; scrolling back up
    // (and reaching the top) hides it again.
    var scrollingUp by remember { mutableStateOf(false) }
    // Programmatic jumps (chips, rail) animate the scroll just like a wheel
    // would — without this guard the bar would fade out mid-jump.
    var jumping by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        var previous = scrollState.value
        snapshotFlow { scrollState.value }.collect { value ->
            val delta = value - previous
            if (!jumping) {
                if (delta < -SCROLL_DIRECTION_SLOP_PX) scrollingUp = true
                if (delta > SCROLL_DIRECTION_SLOP_PX) scrollingUp = false
            }
            previous = value
        }
    }
    // The section whose heading most recently passed the top of the
    // viewport; drives the highlighted entry in the rail.
    val activeSectionId = visibleSections
        .lastOrNull { (sectionOffsets[it.section.id] ?: Int.MAX_VALUE) <= scrollState.value + 24 }
        ?.section
        ?.id
        ?: visibleSections.firstOrNull()?.section?.id

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
    }

    fun jumpTo(sectionId: String) {
        SoundManager.play(SoundEvent.Click)
        val target = sectionOffsets[sectionId] ?: return
        scope.launch {
            jumping = true
            try {
                scrollState.animateScrollTo(target.coerceAtLeast(0))
            } finally {
                jumping = false
                scrollingUp = false
            }
        }
    }

    // Focus the search field as soon as it appears (the short delay lets
    // it attach to the composition first).
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(50.milliseconds)
            searchFocusRequester.requestFocus()
        }
    }

    // The bar stays hidden while the hero is in view and fades in once the
    // content scrolls down (or while the search field is open, so the field
    // is never orphaned). Scrolling back up hides it again. Its slot is
    // always reserved, so nothing shifts when it appears or leaves.
    val barVisible = searchOpen ||
        (scrollState.value > BAR_REVEAL_PX && !scrollingUp)

    Column(modifier = Modifier.fillMaxSize()) {
        // Faded in by hand rather than with AnimatedVisibility: inside a
        // Box the Column-scoped AnimatedVisibility overload is out of reach.
        val barAlpha by animateFloatAsState(
            targetValue = if (barVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 170),
            label = "settings-bar-alpha"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(APP_BAR_HEIGHT)
        ) {
            if (barAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = barAlpha }
                ) {
                    SettingsTopBar(
                        searchOpen = searchOpen,
                        sections = if (searching) emptyList() else visibleSections,
                        activeSectionId = activeSectionId,
                        onJump = ::jumpTo,
                        onBack = back,
                        onToggleSearch = {
                            SoundManager.play(SoundEvent.Click)
                            if (searchOpen) closeSearch() else searchOpen = true
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                SettingsSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = ::closeSearch,
                    focusRequester = searchFocusRequester
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = if (barVisible) 0.4f else 0f
            )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // The rail is a wide-window affordance; on narrow windows the
            // top-bar chips are the only navigation.
            val showRail = maxWidth >= 820.dp && !searching

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 1060.dp)
                        .fillMaxHeight()
                        .fillMaxWidth()
                ) {
                    if (showRail) {
                        SectionRail(
                            sections = visibleSections,
                            activeSectionId = activeSectionId,
                            onJump = ::jumpTo,
                            back = {
                                SoundManager.play(SoundEvent.Click)
                                back()
                            }
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(
                                    start = if (showRail) 4.dp else 20.dp,
                                    end = 20.dp,
                                    bottom = 36.dp
                                )
                        ) {
                            SettingsHero()

                            if (visibleSections.isEmpty()) {
                                Text(
                                    text = "No settings match \"${searchQuery.trim()}\".",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }

                            visibleSections.forEach { visible ->
                                SettingsSectionCard(
                                    visible = visible,
                                    openMenuRowId = openMenuRowId,
                                    onMenuOpenChange = { rowId, open ->
                                        openMenuRowId = if (open) rowId else null
                                    },
                                    onPlaced = { y ->
                                        sectionOffsets[visible.section.id] = y
                                    }
                                )
                            }

                            // Without the rail there is no section list to
                            // hang the back button under, so it closes the
                            // content instead.
                            if (!showRail) {
                                InlineBack(
                                    onClick = {
                                        SoundManager.play(SoundEvent.Click)
                                        back()
                                    }
                                )
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
}


/**
 * The app bar that appears once the content scrolls: the gear the hero left
 * behind, the title, numbered jump chips for every visible section, and the
 * search toggle. Back lives under the section list instead (rail, or the
 * closing row on narrow windows).
 */
@Composable
private fun SettingsTopBar(
    searchOpen: Boolean,
    sections: List<VisibleSection>,
    activeSectionId: String?,
    onJump: (String) -> Unit,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(APP_BAR_HEIGHT)
            .padding(horizontal = 14.dp)
    ) {
        GearBadge(size = 28.dp, iconSize = 16.dp)

        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp)
        ) {
            sections.forEach { visible ->
                SectionJumpChip(
                    number = visible.number,
                    title = visible.section.title,
                    active = visible.section.id == activeSectionId,
                    onClick = { onJump(visible.section.id) }
                )
            }
        }

        Spacer(Modifier.weight(1f))

        BackChip(onClick = onBack)

        SettingsIconButton(
            icon = RibbonIcons.Find,
            description = if (searchOpen) "Close settings search" else "Search settings",
            active = searchOpen,
            onClick = onToggleSearch
        )
    }
}


/**
 * The numbered index rail beside the content (wide windows only). The back
 * button lives at its foot, under the section list.
 */
@Composable
private fun SectionRail(
    sections: List<VisibleSection>,
    activeSectionId: String?,
    onJump: (String) -> Unit,
    back: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .width(184.dp)
            .padding(start = 18.dp, top = 30.dp, end = 10.dp)
    ) {
        Text(
            text = "SECTIONS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp)
        )
        sections.forEach { visible ->
            val selected = visible.section.id == activeSectionId
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            LaunchedEffect(hovered) {
                if (hovered) {
                    delay(60.milliseconds)
                    SoundManager.play(SoundEvent.Hover)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            selected -> activeChromeFill()
                            hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            else -> Color.Transparent
                        }
                    )
                    .hoverable(hover)
                    .clickable { onJump(visible.section.id) }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Text(
                    text = visible.number,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    ),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    }
                )
                Text(
                    text = visible.section.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(6.dp))
        RailAction(
            icon = RibbonIcons.Back,
            label = "Back to menu",
            onClick = back
        )
    }
}


/** A quiet icon + label row at the foot of the rail (the back button). */
@Composable
private fun RailAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (hovered) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                }
            )
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}


/**
 * Back button for narrow windows, where the rail (and with it the section
 * list) is hidden: it closes the content after the last section.
 */
@Composable
private fun InlineBack(onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = RibbonIcons.Back,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Back to menu",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}


/** One pill in the top bar that scrolls to its section. */
@Composable
private fun SectionJumpChip(
    number: String,
    title: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = PillShape,
        color = when {
            active -> activeChromeFill()
            hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        ),
        modifier = Modifier
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}


    /** The back-to-menu pill at the right end of the top bar. */
@Composable
private fun BackChip(onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = PillShape,
        color = when {
            hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .padding(end = 6.dp)
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(
                imageVector = RibbonIcons.Back,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Back",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}


/**
 * The big gear hero at the top of the content: a slowly turning gear inside a
 * gold ring and soft glow, the mono eyebrow, the serif title and a one-line
 * summary. Hidden by the search field while a filter is active.
 */
@Composable
private fun SettingsHero() {
    val transition = rememberInfiniteTransition(label = "settings-gear")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 36_000, easing = LinearEasing)
        ),
        label = "settings-gear-spin"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 34.dp, bottom = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(112.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(activeChromeFill())
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = RibbonIcons.Settings,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer { rotationZ = spin },
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "JASONBIBLE · PREFERENCES",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}


/** The round gear badge used in the top bar once the hero scrolls away. */
@Composable
private fun GearBadge(size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(activeChromeFill())
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                shape = CircleShape
            )
    ) {
        Icon(
            imageVector = RibbonIcons.Settings,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}


/**
 * One section panel: the gold-mono number in a left gutter, then the serif
 * title, its summary and the rows. The y position is reported so the rail and
 * the top-bar chips can scroll here.
 */
@Composable
private fun SettingsSectionCard(
    visible: VisibleSection,
    openMenuRowId: String?,
    onMenuOpenChange: (String, Boolean) -> Unit,
    onPlaced: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                onPlaced(coordinates.positionInParent().y.roundToInt())
            }
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(64.dp)
            ) {
                Text(
                    text = visible.number,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 20.dp)
            ) {
                Text(
                    text = visible.section.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = visible.section.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                visible.rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                    }
                    SettingsRowView(
                        row = row,
                        menuOpen = openMenuRowId == row.id,
                        onMenuOpenChange = { open -> onMenuOpenChange(row.id, open) }
                    )
                }
            }
        }
    }
}


/** Draws one catalog row according to its control type. */
@Composable
private fun SettingsRowView(
    row: SettingRowSpec,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        when (val control = row.control) {
            is SettingControl.Toggle -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RowLabel(row, modifier = Modifier.weight(1f))
                    Switch(
                        checked = control.checked,
                        onCheckedChange = control.onChange,
                        colors = SwitchDefaults.colors(
                            // On: a deep-blue plate with the gold point on top.
                            // Off: the knob keeps the gear gold while the
                            // plate underneath stays plainly neutral.
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = activeChromeFill(),
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.primary,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }

            is SettingControl.Slider -> {
                RowLabel(row, valueLabel = control.valueLabel)
                Slider(
                    value = control.value,
                    onValueChange = control.onChange,
                    valueRange = control.range,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is SettingControl.IntSlider -> {
                RowLabel(row, valueLabel = control.valueLabel)
                Slider(
                    value = control.value.toFloat(),
                    onValueChange = { control.onChange(it.toInt()) },
                    valueRange = control.range.first.toFloat()..control.range.last.toFloat(),
                    steps = (control.range.last - control.range.first - 1).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth()
                )
                if (row.help.isNotEmpty()) {
                    Text(
                        text = row.help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is SettingControl.Dropdown -> {
                RowLabel(row)
                DropdownField(
                    value = control.value,
                    options = control.options,
                    expanded = menuOpen,
                    onExpandedChange = onMenuOpenChange,
                    onOptionSelected = control.onSelect
                )
            }

            is SettingControl.Action -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RowLabel(row, modifier = Modifier.weight(1f))
                    Button(onClick = control.onClick) {
                        control.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(control.label)
                    }
                }
            }

            is SettingControl.Info -> {
                RowLabel(row)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = control.value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (control.actionLabel != null && control.onAction != null) {
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = control.onAction) {
                            Text(control.actionLabel)
                        }
                    }
                }
            }

            is SettingControl.Custom -> {
                RowLabel(row)
                control.content()
            }
        }
    }
}


/**
 * Title + description (+ optional current value on the right). Every control
 * type shares this block so labels never drift between rows.
 */
@Composable
private fun RowLabel(
    row: SettingRowSpec,
    modifier: Modifier = Modifier,
    valueLabel: String? = null
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (row.description.isNotEmpty()) {
                Text(
                    text = row.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (valueLabel != null) {
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}


/** A tappable field that opens the row's options in a scrollable menu. */
@Composable
private fun DropdownField(
    value: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOptionSelected: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(true) }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = RibbonIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            // Scrollable so long option lists (60+ languages, ~90
            // translations) never overflow the window height. NOTE:
            // `heightIn` must come BEFORE `verticalScroll` — with the scroll
            // modifier outermost, a DropdownMenu popup measures its content
            // with infinite max height during dismissal (e.g. right as a
            // translation switch recomposes), which makes the scrollable
            // throw "measured with an infinity maximum height constraints".
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


/** Round icon button with hover tint + sound (back / search). */
@Composable
private fun SettingsIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    active -> activeChromeFill()
                    hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else -> Color.Transparent
                }
            )
            .hoverable(hover)
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(21.dp),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}


/**
 * Color style picker: four selectable chips (Normal / Saturated / Gray /
 * Custom), each showing its palette's primary hue, plus — when Custom is
 * selected — a row of accent seed swatches. Selecting a style re-themes the
 * whole window instantly (the scheme is derived in ui.AppTheme from the
 * persisted key / seed).
 */
@Composable
fun ColorStylePicker() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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


/**
 * Notes → Save location: the folder in use, a folder picker that MOVES the
 * existing notes there, and a one-line result message.
 */
@Composable
fun NotesFolderSetting() {
    val folder = NotesRepository.notesFolder()
    var status by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = folder.toString(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    val picked = chooseFolder(folder, "Choose the notes folder")
                    if (picked != null && picked != folder) {
                        val result = NotesRepository.migrateTo(picked)
                        status = when {
                            !result.ok -> "Could not move the notes: ${result.error}"
                            result.skipped > 0 -> "Notes moved to ${result.folder} — " +
                                "${result.skipped} file(s) already existed there and stayed put"
                            else -> "Notes moved to ${result.folder}"
                        }
                    }
                }
            ) {
                Text("Change…")
            }
            Text(
                text = "Open folder",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        openFolder(folder)
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        status?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
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
            activeChromeFill()
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


/**
 * Compact search field for the Settings screen (Ctrl+F). Filters the catalog
 * as you type; Esc / the ✕ closes it. Auto-focus is handled by the caller's
 * [focusRequester].
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


/** Open a folder in the OS file manager; failures are swallowed. */
fun openFolder(path: Path) {
    runCatching {
        Files.createDirectories(path)
        val desktop = Desktop.getDesktop()
        if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
            desktop.open(path.toFile())
        } else {
            ProcessBuilder("xdg-open", path.toString()).start()
        }
    }
}
