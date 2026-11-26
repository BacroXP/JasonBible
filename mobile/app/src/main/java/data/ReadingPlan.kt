package data

import model.Book
import java.time.LocalDate
import java.time.temporal.ChronoUnit


/**
 * Deterministic 365-day Bible reading plan.
 *
 * The plan walks the active translation's canonical book/chapter list in
 * order and slices it into ~365 equal portions, so each day gets 3-4
 * chapters and no day is ever empty. [planDay] maps any date onto the
 * fixed 365-day cycle (day 0 = 1 Jan 2026), so the plan is stable across
 * restarts and repeats yearly. Progress reuses the existing read-chapter
 * tracking in [SettingsManager] (`readChapters`), so the Home card and
 * any future plan views always agree on what counts as read.
 */
object ReadingPlan {

    /** Length of the plan cycle in days. */
    const val PLAN_DAYS = 365

    // The calendar date that anchors plan day 0. Any fixed date works —
    // the plan is deterministic and repeats yearly from here.
    private val EPOCH = LocalDate.of(2026, 1, 1)

    /**
     * The plan day (0..364) for [date]. Days before the epoch wrap
     * backwards through the cycle; the plan repeats yearly.
     */
    fun planDay(date: LocalDate): Int {
        // TOTAL days since the epoch (ChronoUnit.DAYS.between), NOT the
        // `days` field of a Period — Period.between(2026-01-01, 2026-12-31)
        // is P11M30D whose `.days` is 30, which would make the plan
        // non-sequential (days 31..364 unreachable, dates colliding).
        val days = ChronoUnit.DAYS.between(EPOCH, date)
        return (((days % PLAN_DAYS) + PLAN_DAYS) % PLAN_DAYS).toInt()
    }

    /**
     * The (book number, chapter number) assignments for [day] (0-based),
     * derived from [books] by a uniform walk over the canon. Returns an
     * empty list when the module has no chapters.
     */
    fun chaptersForDay(books: List<Book>, day: Int): List<Pair<Int, Int>> {
        val canon = ArrayList<Pair<Int, Int>>(books.sumOf { it.chapters.size })
        for (book in books) {
            for (chapter in book.chapters) {
                canon.add(book.book to chapter.chapter)
            }
        }
        if (canon.isEmpty()) return emptyList()
        val start = day.toLong() * canon.size / PLAN_DAYS
        val end = (day + 1L) * canon.size / PLAN_DAYS
        val from = start.toInt()
        val to = end.toInt()
        return if (from < to) canon.subList(from, to) else emptyList()
    }

    /** Total chapter count of the active module (0 when empty). */
    fun totalChapters(books: List<Book>): Int = books.sumOf { it.chapters.size }

    /**
     * Overall progress through the plan: chapters marked read divided by
     * the module's total chapter count (0..1, or 0 when nothing is loaded).
     */
    fun progress(books: List<Book>): Float {
        val total = totalChapters(books)
        if (total == 0) return 0f
        return SettingsManager.readChapterCount().toFloat() / total
    }
}
