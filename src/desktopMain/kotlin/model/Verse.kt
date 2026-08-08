package model

import kotlinx.serialization.Serializable

@Serializable
data class Verse(
    val verse: Int,
    val text: String
)
