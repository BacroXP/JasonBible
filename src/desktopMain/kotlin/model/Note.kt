package model

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val book: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
    val tags: List<String> = emptyList(),
    val markerColor: String? = null
)
