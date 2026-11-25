package com.jasonbible.phone

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One bundled translation, addressed by its asset path. */
data class Translation(
    val id: String,
    val label: String,
    val shortLabel: String,
    val asset: String
)

/** The translations bundled into the phone app's APK. */
val TRANSLATIONS = listOf(
    Translation("kjv", "King James Version", "KJV", "bible/kjv.json"),
    Translation("luther1912", "Luther 1912 (DE)", "LUT", "bible/luther_1912.json")
)

data class Verse(val number: Int, val text: String)

data class Chapter(val number: Int, val verses: List<Verse>)

data class Book(val number: Int, val name: String, val chapters: List<Chapter>)


/**
 * The SWORD-format module files from the desktop app's bible resources
 * folder are bundled as Android assets and parsed here into the same
 * Book → Chapter → Verse shape the desktop app uses. Parsed modules are
 * cached per translation for the app session.
 */
object BibleLibrary {

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, List<Book>>()

    @Serializable
    private data class ModuleFile(val verses: List<Record> = emptyList())

    @Serializable
    private data class Record(
        val book: Int = 0,
        val book_name: String? = null,
        val chapter: Int = 0,
        val verse: Int = 0,
        val text: String = ""
    )

    fun books(context: Context, translation: Translation): List<Book> =
        cache.getOrPut(translation.id) { parse(context, translation) }

    private fun parse(context: Context, translation: Translation): List<Book> {
        val text = context.assets.open(translation.asset)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val module = json.decodeFromString<ModuleFile>(text)
        return module.verses
            .asSequence()
            .filter { it.book > 0 && it.chapter > 0 && it.verse > 0 }
            .groupBy { it.book }
            .toSortedMap()
            .map { (number, records) ->
                val name = records.firstNotNullOfOrNull { record ->
                    record.book_name?.trim()?.takeIf { it.isNotEmpty() }
                } ?: "Book $number"
                Book(
                    number = number,
                    name = name,
                    chapters = records
                        .groupBy { it.chapter }
                        .toSortedMap()
                        .map { (chapterNumber, verses) ->
                            Chapter(
                                chapterNumber,
                                verses.sortedBy { it.verse }
                                    .map { Verse(it.verse, cleanText(it.text)) }
                            )
                        }
                )
            }
    }

    /** Strips any HTML / markup tags and decodes the few entities used. */
    private fun cleanText(text: String): String {
        val stripped = Regex("<[^>]*>").replace(text, "")
        return stripped
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}
