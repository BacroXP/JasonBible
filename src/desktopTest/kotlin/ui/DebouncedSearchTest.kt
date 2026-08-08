package ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import model.Book
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * Virtual-time tests for the search bar's debounce ([debouncedSearch]) —
 * the 200 ms wait the Bible pane inserts between keystrokes and the verse
 * scan. Using kotlinx-coroutines-test's scheduler means the tests assert
 * the debounce contract WITHOUT sleeping: a blank query short-circuits
 * immediately (no virtual time consumed), the scan runs only after the
 * window elapses, and a cancelled wait (exactly what LaunchedEffect
 * re-keying does on every keystroke) never reaches the scan.
 */
class DebouncedSearchTest {

    private val book = Book(1, "Genesis", emptyList())

    private fun match() = BibleSearchMatch(
        book = book,
        chapter = 1,
        verse = 1,
        text = "In the beginning God created the heaven and the earth."
    )


    @Test
    fun blankQueryShortCircuitsWithoutWaitingOrScanning() = runTest {
        var scanCalls = 0
        val result = debouncedSearch("   ", debounceMillis = 60_000) {
            scanCalls++
            emptyList()
        }
        assertTrue(result.isEmpty())
        assertEquals(0, scanCalls, "a blank query must never reach the scan")
        // No virtual time was consumed — the blank check ran before any
        // delay, so the (huge) window was never started.
        assertEquals(0, currentTime)
    }

    @Test
    fun scanRunsOnlyAfterTheDebounceWindowElapses() = runTest {
        var ran = false
        val job = launch {
            debouncedSearch("day", debounceMillis = 200) {
                ran = true
                emptyList()
            }
        }
        // Just before the window elapses the scan must not have run.
        advanceTimeBy(199)
        runCurrent()
        assertFalse(ran, "scan must not run before the debounce window elapses")
        // Crossing the 200 ms mark fires the scan.
        advanceTimeBy(1)
        runCurrent()
        assertTrue(ran, "scan must run once the debounce window has elapsed")
        job.join()
    }

    @Test
    fun cancelledDebounceNeverRunsTheScan() = runTest {
        // Models the LaunchedEffect re-keying: the user typed again, so the
        // pending wait is cancelled and the intermediate query must never
        // trigger a scan (this is what collapses a burst of typing into one
        // scan of the final query).
        var scanCalls = 0
        val job = launch {
            debouncedSearch("he", debounceMillis = 200) {
                scanCalls++
                emptyList()
            }
        }
        advanceTimeBy(50)
        job.cancel() // keystroke arrives mid-window
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, scanCalls, "a cancelled debounce must not run the scan")
    }

    @Test
    fun queryIsTrimmedBeforeScanning() = runTest {
        var seenQuery: String? = null
        val result = debouncedSearch("  day  ", debounceMillis = 0) {
            seenQuery = it
            emptyList()
        }
        assertEquals("day", seenQuery)
        assertTrue(result.isEmpty())
    }

    @Test
    fun scanResultIsPassedThrough() = runTest {
        val expected = match()
        val result = debouncedSearch("day", debounceMillis = 0) {
            listOf(expected)
        }
        assertEquals(listOf(expected), result)
    }
}
