package model

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val chapter: Int,
    val verses: List<Verse>
)