package data

import model.Book
import model.Chapter
import testutil.TestEnv
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Unit tests for the deterministic 365-day [ReadingPlan]. Fixtures are
 * synthetic canonical book/chapter lists (chapter verse bodies are empty
 * — the plan only walks the canon's shape), so nothing touches disk
 * except the `progress` tests, which redirect `user.home` via [TestEnv]
 * before touching [SettingsManager].
 */
class ReadingPlanTest {

    /** Builds `books` where book *i* has `chapterCounts[i]` chapters. */
    private fun canon(vararg chapterCounts: Int): List<Book> =
        chapterCounts.mapIndexed { index, count ->
            Book(
                book = index + 1,
                name = "Book ${index + 1}",
                chapters = (1..count).map { Chapter(it, emptyList()) }
            )
        }

    /** Builds a canon of [count] books, each with [chaptersPerBook] chapters. */
    private fun manyBooks(count: Int, chaptersPerBook: Int): List<Book> =
        canon(*IntArray(count) { chaptersPerBook })


    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    fun chaptersForDayIsDeterministic() {
        val books = canon(50, 40, 27, 36, 34, 24)
        // Same day, same books → identical assignment, on repeated calls
        // (the app's "restart" scenario).
        assertEquals(
            ReadingPlan.chaptersForDay(books, 0),
            ReadingPlan.chaptersForDay(books, 0)
        )
        assertEquals(
            ReadingPlan.chaptersForDay(books, 173),
            ReadingPlan.chaptersForDay(books, 173)
        )
        // An identically-shaped canon (as re-loaded from a module) yields
        // the same assignment.
        assertEquals(
            ReadingPlan.chaptersForDay(books, 0),
            ReadingPlan.chaptersForDay(canon(50, 40, 27, 36, 34, 24), 0)
        )
    }


    // ------------------------------------------------------------------
    // Canon coverage
    // ------------------------------------------------------------------

    @Test
    fun planCoversTheWholeCanonExactlyOnce() {
        // 730 chapters → exactly two per day across the 365-day cycle,
        // with nothing dropped and nothing doubled.
        val books = canon(150, 140, 130, 120, 100, 90)
        assertEquals(730, ReadingPlan.totalChapters(books))
        val seen = HashSet<Pair<Int, Int>>()
        for (day in 0 until ReadingPlan.PLAN_DAYS) {
            val dayChapters = ReadingPlan.chaptersForDay(books, day)
            assertEquals(2, dayChapters.size, "day $day should get exactly 2 chapters")
            assertTrue(dayChapters.all(seen::add), "duplicate chapter on day $day")
        }
        assertEquals(730, seen.size)
    }

    @Test
    fun everyDayIsFilledForFullSizedCanons() {
        // A full-size canon (≥ 365 chapters) never leaves a day empty —
        // the uniform walk guarantees at least one chapter per day.
        val books = manyBooks(40, 10) // 400 chapters
        assertTrue(ReadingPlan.totalChapters(books) >= ReadingPlan.PLAN_DAYS)
        for (day in 0 until ReadingPlan.PLAN_DAYS) {
            assertTrue(
                ReadingPlan.chaptersForDay(books, day).isNotEmpty(),
                "day $day is unexpectedly empty"
            )
        }
    }

    @Test
    fun smallCanonsCanHaveEmptyDays() {
        // A canon smaller than the plan runs dry EARLY — the uniform walk
        // front-loads the first days, so day 0 has no chapters while the
        // final chapters still land on the last days.
        val books = canon(50) // 50 chapters < 365
        assertTrue(ReadingPlan.chaptersForDay(books, 0).isEmpty())
        assertTrue(ReadingPlan.chaptersForDay(books, 364).isNotEmpty())
    }

    @Test
    fun emptyCanonGivesEmptyPlan() {
        assertTrue(ReadingPlan.chaptersForDay(emptyList(), 0).isEmpty())
        assertEquals(0, ReadingPlan.totalChapters(emptyList()))
    }


    // ------------------------------------------------------------------
    // Plan-day mapping (date → cycle day)
    // ------------------------------------------------------------------

    @Test
    fun planDayAnchorsAndWraps() {
        assertEquals(0, ReadingPlan.planDay(LocalDate.of(2026, 1, 1)))
        assertEquals(1, ReadingPlan.planDay(LocalDate.of(2026, 1, 2)))
        // Days before the epoch wrap backwards through the cycle.
        assertEquals(364, ReadingPlan.planDay(LocalDate.of(2025, 12, 31)))
        // The plan repeats yearly.
        assertEquals(0, ReadingPlan.planDay(LocalDate.of(2027, 1, 1)))
        assertEquals(364, ReadingPlan.planDay(LocalDate.of(2026, 12, 31)))
    }


    // ------------------------------------------------------------------
    // Progress (needs an isolated SettingsManager)
    // ------------------------------------------------------------------

    companion object {
        init {
            // Redirect user.home to a throwaway dir BEFORE SettingsManager
            // initialises its storage path.
            TestEnv.homeDir
        }
    }

    @Test
    fun progressTracksReadChapters() {
        SettingsManager.setChapterRead(1, 1, true)
        SettingsManager.setChapterRead(1, 2, true)
        try {
            val books = canon(5) // 5 chapters total
            assertEquals(0.4f, ReadingPlan.progress(books), 0.001f)
        } finally {
            // Undo the marks so the shared temp-home settings stay empty
            // and the test is idempotent (safe under retries).
            SettingsManager.setChapterRead(1, 1, false)
            SettingsManager.setChapterRead(1, 2, false)
        }
    }

    @Test
    fun progressIsZeroForEmptyModules() {
        assertEquals(0f, ReadingPlan.progress(emptyList()), 0.001f)
    }

    @Test
    fun planDayWrapsFarBeforeEpoch() {
        // A date ~26 years before the epoch yields a large negative day
        // count; the double-modulo still wraps it into 0..364.
        val day = ReadingPlan.planDay(LocalDate.of(2000, 1, 1))
        assertTrue(day in 0 until ReadingPlan.PLAN_DAYS)
    }
}
