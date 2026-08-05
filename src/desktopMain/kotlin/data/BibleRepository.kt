package data

import model.Chapter
import model.Book


object BibleRepository {

    val books: List<Book> by lazy {
        JsonLoader.loadBible()
    }


    fun getBook(name: String): Book? {

        return books.find {
            it.name == name
        }
    }


    fun getBook(number: Int): Book? {

        return books.find {
            it.book == number
        }
    }


    fun getChapter(bookName: String, chapterNumber: Int): Chapter? {

        return getBook(bookName)
            ?.chapters
            ?.find { chapter ->
                chapter.chapter == chapterNumber
            }
    }
}
