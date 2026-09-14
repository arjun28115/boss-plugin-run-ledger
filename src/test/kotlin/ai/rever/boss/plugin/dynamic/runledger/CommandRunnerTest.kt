package ai.rever.boss.plugin.dynamic.runledger

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandRunnerTest {

    private val work = createTempDirectory("command-runner")

    @AfterTest
    fun cleanUp() {
        work.toFile().deleteRecursively()
    }

    @Test
    fun `stdout is captured`() {
        val result = runCommand(listOf("/bin/sh", "-c", "echo hello"), work.toFile())

        assertTrue(result.succeeded)
        assertEquals("hello", result.trimmedOrNull())
    }

    @Test
    fun `output larger than the pipe buffer does not deadlock`() {
        // Reading stdout only after waitFor wedges any command whose output fills the pipe: the
        // child blocks on write, the parent blocks on wait, and neither moves. 256 KiB is
        // comfortably past the 64 KiB buffer on every platform this runs on.
        val result = runCommand(
            listOf("/bin/sh", "-c", "yes 0123456789abcdef | head -c 262144"),
            work.toFile(),
            timeoutSeconds = 30,
        )

        assertTrue(result.succeeded)
        assertEquals(262_144, result.stdout.length)
    }

    @Test
    fun `the timeout bounds the call, not merely the wait inside it`() {
        // The failure this pins: stdout was drained to EOF on the calling thread before waitFor
        // ran. A child that never closes stdout never reaches EOF, so the read blocked for the
        // command's whole lifetime and the timeout was measured after the wait it was meant to
        // bound. A one-second bound took as long as the command did.
        val startedAt = System.nanoTime()
        val result = runCommand(
            listOf("/bin/sh", "-c", "sleep 30"),
            work.toFile(),
            timeoutSeconds = 1,
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(-1, result.exitCode, "an expired command reports failure, not a stale status")
        assertTrue(
            elapsedMillis < TIMEOUT_CEILING_MILLIS,
            "a 1s bound returned after ${elapsedMillis}ms; the timeout is not bounding the call",
        )
    }

    private companion object {
        /** The 1s bound plus the drain grace, with room for a loaded CI machine. */
        const val TIMEOUT_CEILING_MILLIS = 10_000L
    }
}
