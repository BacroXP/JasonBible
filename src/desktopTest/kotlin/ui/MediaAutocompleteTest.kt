package ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import data.MediaSearchKind
import data.MediaSearchResult
import data.MediaService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


/**
 * Tests for the media-autofill logic behind the note editor:
 * [mediaSearchPrefixAt] (when an `@Phrase` triggers a search),
 * [buildMediaToken] and [applyMediaSuggestion] (the insertion).
 */
class MediaAutocompleteTest {

    private fun channel(name: String, token: String) = MediaSearchResult(
        service = MediaService.YOUTUBE,
        kind = MediaSearchKind.CHANNEL,
        title = name,
        subtitle = "Channel",
        tokenContent = token
    )

    // ------------------------------------------------------------------
    // mediaSearchPrefixAt
    // ------------------------------------------------------------------

    @Test
    fun prefixAtEndOfWord() {
        assertEquals(
            MediaPrefix(6, 12, "Josia"),
            mediaSearchPrefixAt("Watch @Josia", 12)
        )
    }

    @Test
    fun prefixAtLineStart() {
        assertEquals(
            MediaPrefix(0, 6, "Josia"),
            mediaSearchPrefixAt("@Josia", 6)
        )
    }

    @Test
    fun caretMidWordStillFindsWholePhrase() {
        // Caret after "Josi", rest of the word still counts.
        assertEquals(
            MediaPrefix(6, 12, "Josia"),
            mediaSearchPrefixAt("Watch @Josia", 10)
        )
    }

    @Test
    fun multiWordPhraseIsOneQuery() {
        assertEquals(
            MediaPrefix(0, 12, "Josia Queen"),
            mediaSearchPrefixAt("@Josia Queen", 12)
        )
    }

    @Test
    fun trailingSpaceKeepsThePhrase() {
        assertEquals(
            MediaPrefix(0, 7, "Josia"),
            mediaSearchPrefixAt("@Josia ", 7)
        )
    }

    @Test
    fun inlinePhraseStopsAtSentencePunctuation() {
        // The comma ends the phrase — no search for "Josia, ...".
        assertNull(mediaSearchPrefixAt("Watch @Josia, it is great", 22))
        assertNull(mediaSearchPrefixAt("(@Josia)", 8))
    }

    @Test
    fun forwardScanStopsAtTrailingPunctuation() {
        // Caret right after the phrase with a comma following — the comma
        // must not leak into the query or the replaced range.
        assertEquals(
            MediaPrefix(6, 12, "Josia"),
            mediaSearchPrefixAt("Watch @Josia,", 12)
        )
    }

    @Test
    fun knownServiceKeyDoesNotTrigger() {
        assertNull(mediaSearchPrefixAt("@youtube", 8))
        assertNull(mediaSearchPrefixAt("@spotify:track", 14))
        assertNull(mediaSearchPrefixAt("@file", 5))
    }

    @Test
    fun partialTokenWithColonDoesNotTrigger() {
        assertNull(mediaSearchPrefixAt("@youtube:abc", 12))
    }

    @Test
    fun tooShortPhraseDoesNotTrigger() {
        assertNull(mediaSearchPrefixAt("@J", 2))
    }

    @Test
    fun emailDoesNotTrigger() {
        assertNull(mediaSearchPrefixAt("mail me at john@mail.com", 24))
    }

    @Test
    fun noAtSignDoesNotTrigger() {
        assertNull(mediaSearchPrefixAt("just plain text", 15))
        assertNull(mediaSearchPrefixAt("", 0))
    }

    // ------------------------------------------------------------------
    // buildMediaToken + applyMediaSuggestion
    // ------------------------------------------------------------------

    @Test
    fun channelTokenUsesHandle() {
        assertEquals(
            "@youtube:@JosiaQueen ",
            buildMediaToken(channel("Josia Queen", "@JosiaQueen"))
        )
    }

    @Test
    fun videoTokenUsesVideoId() {
        val video = MediaSearchResult(
            service = MediaService.YOUTUBE,
            kind = MediaSearchKind.VIDEO,
            title = "Some Video",
            subtitle = "Channel X",
            tokenContent = "dQw4w9WgXcQ"
        )
        assertEquals("@youtube:dQw4w9WgXcQ ", buildMediaToken(video))
    }

    @Test
    fun applyReplacesPhraseAndParksCaret() {
        val current = TextFieldValue("Watch @Josia today", selection = TextRange(12))
        val prefix = MediaPrefix(6, 12, "Josia")
        val result = applyMediaSuggestion(current, prefix, channel("Josia Queen", "@JosiaQueen"))
        // The token is "@youtube:@JosiaQueen " (21 chars); the space the
        // user typed after "@Josia" is consumed, so the sentence reads
        // with exactly one separator before "today".
        assertEquals("Watch @youtube:@JosiaQueen today", result.text)
        assertEquals(6 + 21, result.selection.start)
    }

    @Test
    fun applyConsumesFollowingTypedSpace() {
        // The user typed "@Josia " (a space after the phrase) — the token
        // must not leave a double gap.
        val current = TextFieldValue("@Josia Queen", selection = TextRange(7))
        val prefix = MediaPrefix(0, 7, "Josia")
        val result = applyMediaSuggestion(current, prefix, channel("Josia Queen", "@JosiaQueen"))
        assertEquals("@youtube:@JosiaQueen Queen", result.text)
    }
}
