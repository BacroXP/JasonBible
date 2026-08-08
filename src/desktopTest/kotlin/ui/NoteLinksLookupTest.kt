package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * Tests for `[[Title]]` note-to-note link detection in the editor's tap
 * lookup ([buildReferenceLookup] / [findReferenceInLookup]): tokens are
 * scanned with source-absolute offsets including the brackets, clicks
 * inside a link resolve to [ReferenceHit.Note], and Bible references
 * take priority over a link that wraps around them.
 */
class NoteLinksLookupTest {

    @Test
    fun noteLinkTokensAreHitTested() {
        val text = "# Title\n\nSee [[Prayer Notes]] and more.\n"
        val lookup = buildReferenceLookup(text)
        val link = lookup.noteLinks.single()
        assertEquals("Prayer Notes", link.title)
        // The token includes the [[ ]] delimiters, so the transformation
        // can hide them and render the title as a chip.
        assertEquals("[[Prayer Notes]]", text.substring(link.sourceStart, link.sourceEnd))

        // Click inside the title → a Note hit with the title.
        val mid = (link.sourceStart + link.sourceEnd) / 2
        val hit = findReferenceInLookup(lookup, mid)
        assertTrue(hit is ReferenceHit.Note)
        assertEquals("Prayer Notes", (hit as ReferenceHit.Note).title)

        // Plain text outside the link resolves to nothing.
        assertNull(findReferenceInLookup(lookup, link.sourceEnd + 4))
    }

    @Test
    fun noteLinksInColoredQuoteTrailingAreHitTested() {
        // The transformation renders note links inside a colored quote's
        // trailing text as chips, so the lookup must resolve them there
        // too (Bible / media tokens stay excluded on those lines).
        val text = "\"Verse\"[#FFD54F] see [[Devotional]]\n"
        val lookup = buildReferenceLookup(text)
        val link = lookup.noteLinks.single()
        assertEquals("Devotional", link.title)
        val hit = findReferenceInLookup(lookup, link.sourceStart + 3)
        assertTrue(hit is ReferenceHit.Note)
    }

    @Test
    fun noteLinksDontShadowBibleReferences() {
        val text = "See \$Lukas\$3\$16 and [[John]].\n"
        val lookup = buildReferenceLookup(text)

        // Clicking the Bible token resolves to the Bible reference, not
        // to a note link.
        val bibleToken = lookup.tokens.single()
        val bibleHit = findReferenceInLookup(lookup, bibleToken.sourceStart + 2)
        assertTrue(bibleHit is ReferenceHit.Bible)

        // Clicking the note link resolves to the note.
        val noteLink = lookup.noteLinks.single()
        assertEquals("John", noteLink.title)
        val noteHit = findReferenceInLookup(lookup, noteLink.sourceStart + 2)
        assertTrue(noteHit is ReferenceHit.Note)
    }
}
