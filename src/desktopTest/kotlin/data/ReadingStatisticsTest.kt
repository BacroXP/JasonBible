package data

import model.Book
import model.Chapter
import testutil.TestEnv
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * Unit tests for the reading statistics layer ([ReadingStatistics]) and
 * the date-bearing read history it aggregates. Activity aggregations are
 * pure functions of a synthetic event list (fixed dates, no disk), while
 * the totals tests redirect `user.home` via [TestEnv] before touching
 * [SettingsManager] — mirroring ReadingPlanTest.
 */
class ReadingStatisticsTest {

    /** Builds `books` where book *i* has `chapterCounts[i]` chapters. */
    private fun canon(vararg chapterCounts: Int): List<Book> =
        chapterCounts.mapIndexed { index, count ->
            Book(
                book = index + 1,
                name = "Book ${index + 1}",
                chapters = (1..count).map { Chapter(it, emptyList()) }
            )
        }

    private fun entry(date: String, book: Int, chapter: Int) =
        ReadHistoryEntry(date = date, book = book, chapter = chapter)

    companion object {
        init {
            // Redirect user.home to a throwaway dir BEFORE SettingsManager
            // initialises its storage path (needed by the totals tests).
            TestEnv.homeDir
        }
    }

    // ------------------------------------------------------------------
    // Testament split
    // ------------------------------------------------------------------

    @Test
    fun oldTestamentBoundaryIsBook40() {
        assertTrue(ReadingStatistics.isOldTestament(1))   // Genesis
        assertTrue(ReadingStatistics.isOldTestament(39))  // Malachi
        assertFalse(ReadingStatistics.isOldTestament(40)) // Matthew
        assertFalse(ReadingStatistics.isOldTestament(66)) // Revelation
    }

    // ------------------------------------------------------------------
    // Totals (isolated SettingsManager)
    // ------------------------------------------------------------------

    @Test
    fun readBookCountCountsOnlyFullyReadBooks() {
        val books = canon(3, 3, 2)
        SettingsManager.setChapterRead(1, 1, true)
        SettingsManager.setChapterRead(1, 2, true)
        SettingsManager.setChapterRead(1, 3, true) // book 1 fully read
        SettingsManager.setChapterRead(2, 1, true) // book 2 only partial
        try {
            assertEquals(1, ReadingStatistics.readBookCount(books))
        } finally {
            val marks = listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1)
            marks.forEach { (b, c) -> SettingsManager.setChapterRead(b, c, false) }
        }
    }

    @Test
    fun testamentProgressSeparatesOtAndNt() {
        // Real canonical book numbers: 1/39 are Old Testament, 40/66 are
        // New Testament (the split is at book 40).
        fun book(number: Int, chapters: Int) =
            Book(number, "Book $number", (1..chapters).map { Chapter(it, emptyList()) })
        val books = listOf(book(1, 3), book(39, 3), book(40, 3), book(66, 3))
        SettingsManager.setChapterRead(1, 1, true)
        SettingsManager.setChapterRead(1, 2, true)
        SettingsManager.setChapterRead(1, 3, true) // OT: 3 read of 6
        SettingsManager.setChapterRead(40, 1, true) // NT: 1 read of 6
        try {
            assertEquals(3 to 6, ReadingStatistics.testamentProgress(books, oldTestament = true))
            assertEquals(1 to 6, ReadingStatistics.testamentProgress(books, oldTestament = false))
        } finally {
            SettingsManager.setChapterRead(1, 1, false)
            SettingsManager.setChapterRead(1, 2, false)
            SettingsManager.setChapterRead(1, 3, false)
            SettingsManager.setChapterRead(40, 1, false)
        }
    }

    @Test
    fun markingAChapterReadRecordsTodaysDate() {
        SettingsManager.setChapterRead(7, 3, true)
        try {
            val entries = SettingsManager.readHistoryEntries()
            assertEquals(1, entries.size)
            assertEquals(7, entries[0].book)
            assertEquals(3, entries[0].chapter)
            assertEquals(LocalDate.now().toString(), entries[0].date)
        } finally {
            SettingsManager.setChapterRead(7, 3, false)
        }
        // Unmarking removes the history entry again.
        assertTrue(SettingsManager.readHistoryEntries().isEmpty())
    }

    // ------------------------------------------------------------------
    // Activity aggregation (pure functions, synthetic entries)
    // ------------------------------------------------------------------

    @Test
    fun dailyActivityCoversTheLastDaysInOrder() {
        val today = LocalDate.of(2026, 8, 8)
        val entries = listOf(
            entry("2026-08-08", 1, 1),
            entry("2026-08-08", 1, 2),
            entry("2026-08-06", 2, 1)
        )
        val daily = ReadingStatistics.dailyActivity(entries, today, days = 5)
        assertEquals(5, daily.size)
        // Oldest first, ending on today.
        assertEquals(LocalDate.of(2026, 8, 4), daily[0].date)
        assertEquals(today, daily[4].date)
        assertEquals(0, daily[0].chapters) // 4 Aug: nothing
        assertEquals(1, daily[2].chapters) // 6 Aug: one chapter
        assertEquals(2, daily[4].chapters) // 8 Aug: two chapters
    }

    @Test
    fun weeklyActivityGroupsByMondayWeekStart() {
        // 2026-08-08 is a Saturday; the week started Monday 3 Aug.
        val today = LocalDate.of(2026, 8, 8)
        assertEquals(LocalDate.of(2026, 8, 3), today.with(java.time.DayOfWeek.MONDAY))
        val entries = listOf(
            entry("2026-08-03", 1, 1), // Monday this week
            entry("2026-08-08", 1, 2), // Saturday this week
            entry("2026-07-28", 2, 1)  // previous week (Mon 27 Jul start)
        )
        val weekly = ReadingStatistics.weeklyActivity(entries, today, weeks = 3)
        assertEquals(3, weekly.size)
        assertEquals(LocalDate.of(2026, 7, 27), weekly[1].start)
        assertEquals(1, weekly[1].chapters) // 28 Jul belongs to the 27 Jul week
        assertEquals(LocalDate.of(2026, 8, 3), weekly[2].start)
        assertEquals(2, weekly[2].chapters)
    }

    @Test
    fun monthlyActivityGroupsByCalendarMonth() {
        val today = LocalDate.of(2026, 8, 8)
        val entries = listOf(
            entry("2026-08-01", 1, 1),
            entry("2026-08-30", 1, 2),
            entry("2026-06-15", 2, 1),
            entry("2025-12-24", 3, 1) // outside the 12-month window
        )
        val monthly = ReadingStatistics.monthlyActivity(entries, today, months = 12)
        assertEquals(12, monthly.size)
        assertEquals(YearMonth.of(2025, 9), monthly[0].month)
        assertEquals(0, monthly[0].chapters)
        assertEquals(YearMonth.of(2026, 6), monthly[9].month)
        assertEquals(1, monthly[9].chapters)
        assertEquals(YearMonth.of(2026, 8), monthly[11].month)
        assertEquals(2, monthly[11].chapters)
    }

    @Test
    fun emptyHistoryGivesZeroBars() {
        val today = LocalDate.of(2026, 8, 8)
        val daily = ReadingStatistics.dailyActivity(emptyList(), today, days = 7)
        val weekly = ReadingStatistics.weeklyActivity(emptyList(), today, weeks = 4)
        val monthly = ReadingStatistics.monthlyActivity(emptyList(), today, months = 3)
        assertTrue(daily.all { it.chapters == 0 } && daily.size == 7)
        assertTrue(weekly.all { it.chapters == 0 } && weekly.size == 4)
        assertTrue(monthly.all { it.chapters == 0 } && monthly.size == 3)
    }

    @Test
    fun malformedHistoryDatesAreIgnored() {
        val today = LocalDate.of(2026, 8, 8)
        val entries = listOf(entry("not-a-date", 1, 1), entry("2026-08-08", 2, 2))
        val daily = ReadingStatistics.dailyActivity(entries, today, days = 1)
        assertEquals(1, daily[0].chapters) // only the valid date counts
    }
}
