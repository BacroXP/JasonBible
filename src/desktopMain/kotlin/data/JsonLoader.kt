package data

import kotlinx.serialization.json.Json
import model.Book


object JsonLoader {

    private val json = Json {
        ignoreUnknownKeys = true
    }


    fun loadBible(): List<Book> {

        val classLoader = object {}.javaClass.classLoader

        val stream =
            classLoader.getResourceAsStream("bible/bible.json")
                ?: classLoader.getResourceAsStream("bible/luther1912.json")
                ?: error(
                    "Bible file missing. Expected src/desktopMain/resources/bible/bible.json"
                )


        return json.decodeFromString(
            stream.bufferedReader().readText()
        )
    }
}
