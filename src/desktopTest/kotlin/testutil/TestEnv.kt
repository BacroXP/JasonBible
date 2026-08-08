package testutil

import java.nio.file.Files


/**
 * Test-environment setup shared by the JVM test suite.
 *
 * [SettingsManager] persists to `~/.bibleapp/private.json`, so any test
 * that touches it (e.g. `ReadingPlan.progress` or the copy-with-suffix
 * path) would read and write the developer's REAL settings file unless
 * `user.home` is redirected first. This object does exactly that: the
 * first reference to [homeDir] (any test class may simply touch the
 * object in a companion init) swaps `user.home` for a throwaway
 * directory BEFORE the SettingsManager singleton initialises its
 * storage path. The redirect runs once per test JVM and is idempotent.
 */
object TestEnv {

    /** Throwaway home directory used for the whole test JVM. */
    val homeDir: String = run {
        val dir = Files.createTempDirectory("bibleapp-test-home")
        System.setProperty("user.home", dir.toString())
        dir.toString()
    }
}
