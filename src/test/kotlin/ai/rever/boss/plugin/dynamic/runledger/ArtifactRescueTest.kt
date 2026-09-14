package ai.rever.boss.plugin.dynamic.runledger

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactRescueTest {

    private val work: Path = createTempDirectory("rescue-work")
    private val runDir: Path = createTempDirectory("rescue-run")

    @AfterTest
    fun cleanUp() {
        work.toFile().deleteRecursively()
        runDir.toFile().deleteRecursively()
    }

    private fun write(relative: String, bytes: Int): Path {
        val path = work.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(bytes) { 7 })
        return path
    }

    @Test
    fun `a matched output is copied into the run directory at its relative path`() {
        write("outputs/metrics.json", 32)

        val results = ArtifactRescue.rescue(work, runDir, listOf("outputs/*.json"))

        assertEquals(1, results.size)
        assertEquals(RescueOutcome.RESCUED, results.single().outcome)
        assertEquals("artifacts/outputs/metrics.json", results.single().storedPath)
        assertTrue(Files.exists(runDir.resolve("artifacts/outputs/metrics.json")))
        assertEquals(32, Files.size(runDir.resolve("artifacts/outputs/metrics.json")).toInt())
    }

    @Test
    fun `a glob that matched nothing is reported as absent, not as a failure`() {
        val results = ArtifactRescue.rescue(work, runDir, listOf("outputs/*.json"))

        assertEquals(RescueOutcome.ABSENT, results.single().outcome)
    }

    @Test
    fun `an output larger than the remaining budget is left in place and reported`() {
        write("checkpoints/model.bin", 4096)

        val results = ArtifactRescue.rescue(
            work,
            runDir,
            listOf("checkpoints/*.bin"),
            budgetBytes = 1024,
        )

        assertEquals(RescueOutcome.OVER_BUDGET, results.single().outcome)
        assertEquals(4096L, results.single().bytes)
        assertFalse(
            Files.exists(runDir.resolve("artifacts/checkpoints/model.bin")),
            "over-budget files must not be half-copied",
        )
    }

    @Test
    fun `the budget is spent across globs, not reset for each one`() {
        write("a/one.bin", 800)
        write("b/two.bin", 800)

        val results = ArtifactRescue.rescue(
            work,
            runDir,
            listOf("a/*.bin", "b/*.bin"),
            budgetBytes = 1000,
        )

        assertEquals(1, results.count { it.outcome == RescueOutcome.RESCUED })
        assertEquals(1, results.count { it.outcome == RescueOutcome.OVER_BUDGET })
    }

    @Test
    fun `no staging file is left behind after a successful rescue`() {
        write("outputs/metrics.json", 16)

        ArtifactRescue.rescue(work, runDir, listOf("outputs/*.json"))

        val leftovers = Files.walk(runDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".part") }.count()
        }
        assertEquals(0L, leftovers, "the temporary name must not survive the move")
    }

    @Test
    fun `a symbolic link is not followed out of the project`() {
        val outside = createTempDirectory("rescue-outside")
        try {
            val secret = outside.resolve("secret.json")
            Files.write(secret, ByteArray(8))
            Files.createDirectories(work.resolve("outputs"))
            Files.createSymbolicLink(work.resolve("outputs/secret.json"), secret)

            val results = ArtifactRescue.rescue(work, runDir, listOf("outputs/*.json"))

            assertEquals(RescueOutcome.ABSENT, results.single().outcome)
            assertFalse(Files.exists(runDir.resolve("outputs/secret.json")))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the git and boss directories are never walked`() {
        write(".git/objects/blob", 16)
        write(".boss/run-ledger/runs.jsonl", 16)
        write("real.txt", 16)

        val results = ArtifactRescue.rescue(work, runDir, listOf("**"))

        val stored = results.filter { it.outcome == RescueOutcome.RESCUED }.map { it.storedPath }
        assertEquals(listOf("artifacts/real.txt"), stored)
    }

    @Test
    fun `a declared output cannot overwrite the run's own provenance files`() {
        // The failure this pins: a run that declares `*.patch` or `*.log` used to have its own
        // output copied straight onto the provenance patch and the console capture, destroying
        // the record the ledger exists to keep - and destroying it silently, reported as a
        // successful rescue.
        Files.createDirectories(runDir)
        val patch = runDir.resolve(Provenance.PATCH_FILE_NAME)
        val console = runDir.resolve(RunLauncher.CONSOLE_LOG_NAME)
        Files.writeString(patch, "the real working-tree patch")
        Files.writeString(console, "the real console capture")
        write(Provenance.PATCH_FILE_NAME, 64)
        write(RunLauncher.CONSOLE_LOG_NAME, 64)

        val results = ArtifactRescue.rescue(
            work,
            runDir,
            listOf(Provenance.PATCH_FILE_NAME, RunLauncher.CONSOLE_LOG_NAME),
        )

        assertEquals(2, results.count { it.outcome == RescueOutcome.RESCUED })
        assertEquals("the real working-tree patch", Files.readString(patch))
        assertEquals("the real console capture", Files.readString(console))
        val dir = runDir.resolve(ArtifactRescue.ARTIFACTS_DIR)
        assertEquals(64, Files.size(dir.resolve(Provenance.PATCH_FILE_NAME)).toInt())
        assertEquals(64, Files.size(dir.resolve(RunLauncher.CONSOLE_LOG_NAME)).toInt())
    }

    @Test
    fun `declaring no globs does no work at all`() {
        assertTrue(ArtifactRescue.rescue(work, runDir, emptyList()).isEmpty())
        assertFalse(Files.exists(runDir.resolve("anything")))
    }
}
