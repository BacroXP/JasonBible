package data

import model.Book
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters


// ---------------------------------------------------------------------------
// Reading statistics
//
// Pure, offline calculations over the read-chapter tracking in
// SettingsManager (the same `readChapters` set the ReadingPlan and Home
// card use, so every view always agrees on what counts as read):
//
//   • totals        — read chapters, fully-read books, overall percentage
//   • per book      — read/total chapters for the per-book progress list
//   • testaments    — Old / New Testament progress split at book 40
//   • activity      — chapters read per day / week / month, for the charts
//
// The activity aggregations are pure functions of a date-bearing event
// list, so they are unit-testable with fixed dates. Events come from
// SettingsManager.readHistoryEntries(), which records the date whenever a
// chapter is marked read. Chapters marked read before that history
// existed have no date and only count toward the totals.
// ---------------------------------------------------------------------------

object ReadingStatistics {

    /** Last book of the Protestant canon's Old Testament (Genesis 1 …). */
    const val OLD_TESTAMENT_BOOKS = 39

    fun isOldTestament(bookNumber: Int): Boolean = bookNumber <= OLD_TESTAMENT_BOOKS

    /** Number of books whose EVERY chapter has been read. Books with zero
     *  chapters (odd modules) never count as read. */
    fun readBookCount(books: List<Book>): Int =
        books.count { book ->
            book.chapters.isNotEmpty() &&
                book.chapters.all { SettingsManager.isChapterRead(book.book, it.chapter) }
        }

    /** (read, total) chapters of one book. */
    fun bookProgress(book: Book): Pair<Int, Int> {
        val total = book.chapters.size
        val read = book.chapters.count {
            SettingsManager.isChapterRead(book.book, it.chapter)
        }
        return read to total
    }

    /** (read, total) chapters of the Old ([oldTestament] = true) or New
     *  Testament, by the canonical book-number split at book 40. */
    fun testamentProgress(books: List<Book>, oldTestament: Boolean): Pair<Int, Int> {
        var read = 0
        var total = 0
        for (book in books) {
            if (isOldTestament(book.book) != oldTestament) continue
            total += book.chapters.size
            read += book.chapters.count {
                SettingsManager.isChapterRead(book.book, it.chapter)
            }
        }
        return read to total
    }

    /** Chapters read per calendar day, oldest first, covering the last
     *  [days] days up to and including [today] (days without activity are
     *  zero bars, so the chart is a continuous strip). */
    fun dailyActivity(
        entries: List<ReadHistoryEntry>,
        today: LocalDate,
        days: Int
    ): List<DayActivity> {
        val byDate = entries.groupBy { parseDate(it.date) }
            .mapValues { (_, list) -> list.size }
        return (days - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            DayActivity(date, byDate[date] ?: 0)
        }
    }

    /** Chapters read per ISO week (weeks start on Monday), oldest first,
     *  covering the last [weeks] weeks up to the week containing
     *  [today]. */
    fun weeklyActivity(
        entries: List<ReadHistoryEntry>,
        today: LocalDate,
        weeks: Int
    ): List<WeekActivity> {
        val thisWeekStart = mondayOf(today)
        val byWeek = entries.mapNotNull { parseDate(it.date)?.let(::mondayOf) }
            .groupBy { it }
            .mapValues { (_, list) -> list.size }
        return (weeks - 1 downTo 0).map { offset ->
            val start = thisWeekStart.minusWeeks(offset.toLong())
            WeekActivity(start, byWeek[start] ?: 0)
        }
    }

    /** Chapters read per calendar month, oldest first, covering the last
     *  [months] months up to the month containing [today]. */
    fun monthlyActivity(
        entries: List<ReadHistoryEntry>,
        today: LocalDate,
        months: Int
    ): List<MonthActivity> {
        val thisMonth = YearMonth.from(today)
        val byMonth = entries.mapNotNull { entry ->
            parseDate(entry.date)?.let(YearMonth::from)
        }.groupBy { it }
            .mapValues { (_, list) -> list.size }
        return (months - 1 downTo 0).map { offset ->
            val month = thisMonth.minusMonths(offset.toLong())
            MonthActivity(month, byMonth[month] ?: 0)
        }
    }

    private fun mondayOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw) }.getOrNull()
}


/** Chapters read on one calendar day (activity chart bar). */
data class DayActivity(val date: LocalDate, val chapters: Int)

/** Chapters read in one ISO week, [start] = the Monday of that week. */
data class WeekActivity(val start: LocalDate, val chapters: Int)

/** Chapters read in one calendar month. */
data class MonthActivity(val month: YearMonth, val chapters: Int)
