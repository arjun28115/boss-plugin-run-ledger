package ai.rever.boss.plugin.dynamic.runledger

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunLedgerStoreTest {

    private val root: Path = createTempDirectory("run-ledger-test")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = RunLedgerStore(root)

    private fun record(id: String, startedAt: String, status: RunStatus = RunStatus.RUNNING) =
        RunRecord(
            id = id,
            label = "train $id",
            command = "python train.py",
            cwd = root.toString(),
            startedAt = startedAt,
            status = status,
        )

    @Test
    fun `a completion line replaces the launch line for the same run`() {
        val store = store()
        store.append(record("r1", "2026-09-11T10:00:00Z"))
        store.append(
            record("r1", "2026-09-11T10:00:00Z", RunStatus.SUCCEEDED)
                .copy(finishedAt = "2026-09-11T10:05:00Z", exitCode = 0),
        )

        val loaded = store.load()
        assertEquals(1, loaded.size, "the two lines describe one run, not two")
        assertEquals(RunStatus.SUCCEEDED, loaded.single().status)
        assertEquals(0, loaded.single().exitCode)
    }

    @Test
    fun `a run whose completion line was never written survives as running`() {
        // This is the host-was-killed case. The launch line is all there is, and losing it would
        // erase the only record that the run ever happened.
        val store = store()
        store.append(record("r1", "2026-09-11T10:00:00Z"))

        val loaded = store.load()
        assertEquals(RunStatus.RUNNING, loaded.single().status)
        assertNull(loaded.single().finishedAt)
    }

    @Test
    fun `a truncated final line costs that run and nothing else`() {
        val store = store()
        store.append(record("r1", "2026-09-11T10:00:00Z"))
        store.append(record("r2", "2026-09-11T11:00:00Z"))
        // Simulate a kill partway through the third append.
        Files.write(
            root.resolve(RunLedgerStore.LEDGER_FILE_NAME),
            """{"id":"r3","label":"tr""".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND,
        )

        val loaded = store.load()
        assertEquals(listOf("r2", "r1"), loaded.map { it.id }, "newest first, bad tail skipped")
        assertEquals(1, store.unreadableLineCount(), "corruption is counted, not hidden")
    }

    @Test
    fun `blank lines are not counted as corruption`() {
        val store = store()
        store.append(record("r1", "2026-09-11T10:00:00Z"))
        Files.write(
            root.resolve(RunLedgerStore.LEDGER_FILE_NAME),
            "\n\n".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND,
        )

        assertEquals(1, store.load().size)
        assertEquals(0, store.unreadableLineCount())
    }

    @Test
    fun `a field this version does not know about does not break the read`() {
        // Forward compatibility: a newer plugin writing an extra key must not brick an older one.
        Files.createDirectories(root)
        Files.write(
            root.resolve(RunLedgerStore.LEDGER_FILE_NAME),
            ("""{"id":"r1","label":"a","command":"c","cwd":"/tmp",""" +
                """"started_at":"2026-09-11T10:00:00Z","future_field":42}""" + "\n")
                .toByteArray(StandardCharsets.UTF_8),
        )

        assertEquals("r1", store().load().single().id)
    }

    @Test
    fun `loading an absent ledger yields no runs rather than an error`() {
        assertTrue(store().load().isEmpty())
        assertEquals(0, store().unreadableLineCount())
    }

    @Test
    fun `duration is null while a run is in flight and measured once it finishes`() {
        assertNull(record("r1", "2026-09-11T10:00:00Z").durationMs())
        val finished = record("r1", "2026-09-11T10:00:00Z")
            .copy(finishedAt = "2026-09-11T10:00:30Z")
        assertEquals(30_000L, finished.durationMs())
    }

    @Test
    fun `an unparseable timestamp yields no duration instead of throwing`() {
        val broken = record("r1", "not-a-time").copy(finishedAt = "2026-09-11T10:00:30Z")
        assertNull(broken.durationMs())
    }
}
