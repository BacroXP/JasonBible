package model

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val book: Int,
    val name: String,
    val chapters: List<Chapter>
)