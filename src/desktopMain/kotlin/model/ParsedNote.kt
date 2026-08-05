package model

data class ParsedNote(
    val title: String,
    val fileName: String,
    val content: String,
    val blocks: List<NoteBlock>,
    val references: List<NoteReference>
)


sealed interface NoteBlock


data class HeadingBlock(
    val level: Int,
    val text: String
) : NoteBlock


data class QuoteBlock(
    val depth: Int,
    val text: String,
    val colorHex: String? = null
) : NoteBlock


data class ListBlock(
    val ordered: Boolean,
    val text: String
) : NoteBlock


data class BookBlock(
    val book: String
) : NoteBlock


data class VerseReferenceBlock(
    val book: String,
    val chapter: Int,
    val verse: Int,
    val extra: String? = null
) : NoteBlock


data class ParagraphBlock(
    val text: String
) : NoteBlock


data class NoteReference(
    val noteTitle: String,
    val book: String,
    val chapter: Int,
    val verse: Int,
    val label: String? = null
)
