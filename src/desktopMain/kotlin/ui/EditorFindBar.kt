@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp



internal data class FindState(
    val open: Boolean = false,
    val replaceShown: Boolean = false,
    val query: String = "",
    val caseSensitive: Boolean = false,
    val replaceText: String = "",
    val matchIndex: Int = -1
)

// Pure helper that returns all non-overlapping match ranges for `query`
private fun findMatches(text: String, query: String, caseSensitive: Boolean): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val hay = if (caseSensitive) text else text.lowercase()
    val needle = if (caseSensitive) query else query.lowercase()
    if (needle.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var i = 0
    val step = needle.length.coerceAtLeast(1)
    while (i <= hay.length - needle.length) {
        if (hay.regionMatches(i, needle, 0, needle.length)) {
            out.add(i until i + needle.length)
            i += step
        } else {
            i += 1
        }
    }
    return out
}


// -----------------------------------------------------------------------
// Find/Replace overlay
//
// Slim banner that renders above the editor when `state.open` is true.
// Key plumbing:
//
//   * `findMatches(text, query, caseSensitive)` is a pure helper and
//     runs once per text/query/casing change via `remember(...)`.
//   * Auto-scrolling rides the editor's hoisted scroll plumbing:
//     `activeRange` changes (Next/Prev/typing) flow through a
//     `LaunchedEffect` that maps source -> display via the visual
//     mapping, then to a Y via the editor's TextLayoutResult.
//   * Ctrl+F / Ctrl+H / Enter / Shift+Enter / Esc are routed through
//     `handleEditorShortcut` from the editor's onPreviewKeyEvent so the
//     user has a single keyboard surface; the bar itself only hosts
//     mouse/touch click handlers.
//
// The bar is intentionally small: it lives between EditorToolbar and
// EditorSurface, takes ~56.dp tall when Just-Find mode or ~92.dp in
// Find+Replace mode. Pinned to the editor column, so it scrolls with
// neither pane nor the find bar; the editor keeps its scroll offset.
// -----------------------------------------------------------------------
@Composable
internal fun EditorFindBar(
    state: FindState,
    text: String,
    visualTransformation: VisualTransformation,
    editorScrollState: ScrollState,
    editorLayoutResult: TextLayoutResult?,
    onStateChange: (FindState) -> Unit,
    /**
     * Replace the active match (matches[matchIndex]) with `replacement`.
     * Receives a (start, endExclusive) `IntRange` plus the replacement
     * string; the parent notes-scope handler allocs the spliced text and
     * moves the caret to the just-after position.
     */
    onReplaceCurrent: (range: IntRange, replacement: String) -> Unit,
    onReplaceAll: (matches: List<IntRange>, replacement: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val matches = remember(text, state.query, state.caseSensitive) {
        findMatches(text, state.query, state.caseSensitive)
    }
    val matchCount = matches.size
    val safeIndex = when {
        matchCount == 0 -> -1
        state.matchIndex < 0 -> -1
        else -> state.matchIndex.coerceAtMost(matchCount - 1)
    }
    val activeRange = if (safeIndex in matches.indices) matches[safeIndex] else null

    // Auto-scroll to the active match when it changes (Next/Prev/typing).
    // Mirrors the same plumbing that the cross-screen pendingScrollReference
    // effect uses: source -> display via mapping, display -> line Y via
    // layout. Without this the user would see "1/12" in the count badge
    // but have no idea WHERE match #1 actually lives.
    LaunchedEffect(activeRange, editorLayoutResult, text) {
        val range = activeRange ?: return@LaunchedEffect
        val layout = editorLayoutResult ?: return@LaunchedEffect
        val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
            ?: return@LaunchedEffect
        val dispStart = mapping.originalToTransformed(range.first)
            .coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
        val lineIdx = layout.getLineForOffset(dispStart)
            .coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
        val lineY = layout.getLineTop(lineIdx)
        editorScrollState.animateScrollTo((lineY - 24f).coerceAtLeast(0f).toInt())
    }

    // Hoist the click/key handlers into `remember(...)` blocks keyed
    // ONLY on the captured state and matchCount. We deliberately omit
    // `onStateChange` from the keys: the parent NotesScreen builds it
    // as a plain lambda (`{ findState = it }`) without wrapping in
    // remember, so its identity changes on every editor keystroke when
    // Find is open. Including it as a key would defeat this whole
    // optimisation by forcing re-allocation on every keystroke.
    // It's safe to drop because `onStateChange = { findState = it }`
    // writes through a `MutableState<FindState>` delegate that is
    // itself `remember`'d in the parent — so a "stale" lambda captured
    // earlier still calls the right backing state at click time.
    val goPrev = remember(matchCount, state) {
        {
            if (matchCount > 0) {
                val base = if (state.matchIndex < 0) matchCount - 1 else state.matchIndex
                val next = (base - 1 + matchCount) % matchCount
                onStateChange(state.copy(matchIndex = next))
            }
        }
    }
    val goNext = remember(matchCount, state) {
        {
            if (matchCount > 0) {
                val next = if (state.matchIndex < 0) 0 else (state.matchIndex + 1) % matchCount
                onStateChange(state.copy(matchIndex = next))
            }
        }
    }
    val close = remember(state) {
        {
            onStateChange(state.copy(open = false, replaceShown = false, matchIndex = -1))
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BasicTextField(
                    value = state.query,
                    onValueChange = { q ->
                        onStateChange(state.copy(query = q, matchIndex = -1))
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            // Esc closes the bar even when the query
                            // field has focus (the editor Box's
                            // Modifier.onPreviewKeyEvent lives on a
                            // sibling, NOT an ancestor, so its
                            // handleEditorShortcut Esc short-circuit
                            // doesn't fire from inside this TextField).
                            // Without this, the user has to either click
                            // the × button or move focus back to the
                            // editor before pressing Esc.
                            when (event.key) {
                                Key.Escape -> {
                                    close()
                                    true
                                }
                                Key.Enter -> {
                                    if (event.isShiftPressed) goPrev() else goNext()
                                    true
                                }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) {
                            Text(
                                "Find\u2026",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            )
                        }
                        inner()
                    }
                )
                Text(
                    text = when {
                        state.query.isEmpty() -> ""
                        matchCount == 0 -> "no match"
                        state.matchIndex < 0 -> matchCount.toString()
                        else -> "${safeIndex + 1}/$matchCount"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ToolbarActionButton(icon = RibbonIcons.PrevMatch, enabled = matchCount > 0, tooltip = "Previous match", onClick = goPrev)
                ToolbarActionButton(icon = RibbonIcons.NextMatch, enabled = matchCount > 0, tooltip = "Next match", onClick = goNext)
                ToolbarActionButton(
                    label = if (state.caseSensitive) "Aa\u00B7on" else "Aa",
                    accent = state.caseSensitive,
                    tooltip = "Match case",
                    onClick = {
                        onStateChange(
                            state.copy(caseSensitive = !state.caseSensitive, matchIndex = -1)
                        )
                    }
                )
                ToolbarActionButton(icon = RibbonIcons.Close, tooltip = "Close find", onClick = close)
            }
            if (state.replaceShown) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BasicTextField(
                        value = state.replaceText,
                        onValueChange = { r -> onStateChange(state.copy(replaceText = r)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            // Esc from the replace field also dismisses
                            // the bar — see the matching comment on the
                            // query field for the rationale.
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Escape) {
                                    close()
                                    true
                                } else false
                            },
                        decorationBox = { inner ->
                            if (state.replaceText.isEmpty()) {
                                Text(
                                    "Replace with\u2026",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                )
                            }
                            inner()
                        }
                    )
                    ToolbarActionButton(
                        icon = RibbonIcons.Replace,
                        label = "Replace",
                        enabled = activeRange != null,
                        tooltip = "Replace current match",
                        onClick = {
                            activeRange?.let { onReplaceCurrent(it, state.replaceText) }
                        }
                    )
                    ToolbarActionButton(
                        icon = RibbonIcons.ReplaceAll,
                        label = "All",
                        enabled = matchCount > 0,
                        tooltip = "Replace all matches",
                        onClick = { onReplaceAll(matches, state.replaceText) }
                    )
                }
            }
        }
    }
}



