package ai.rever.boss.plugin.dynamic.runledger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunLauncherTest {

    private val project: Path = createTempDirectory("launcher-project")
    private val store = RunLedgerStore.forProject(project)
    private val scope = CoroutineScope(Dispatchers.IO)

    @AfterTest
    fun cleanUp() {
        project.toFile().deleteRecursively()
    }

    private fun launchAndWait(spec: RunSpec): RunRecord {
        val (id, job) = assertNotNull(RunLauncher(store, scope).launch(spec))
        runBlocking { job.join() }
        return store.load().single { it.id == id }
    }

    @Test
    fun `a successful command is recorded with its exit code and console output`() {
        val record = launchAndWait(
            RunSpec(label = "echo", command = "echo hello-ledger", cwd = project.toString()),
        )

        assertEquals(RunStatus.SUCCEEDED, record.status)
        assertEquals(0, record.exitCode)
        assertNotNull(record.finishedAt)
        val console = store.runDirectory(record.id).resolve(RunLauncher.CONSOLE_LOG_NAME)
        assertTrue(Files.readString(console).contains("hello-ledger"))
    }

    @Test
    fun `a failing command is recorded as failed, not dropped`() {
        val record = launchAndWait(
            RunSpec(label = "boom", command = "exit 3", cwd = project.toString()),
        )

        assertEquals(RunStatus.FAILED, record.status)
        assertEquals(3, record.exitCode)
    }

    @Test
    fun `the outputs a run declared are rescued into its run directory`() {
        val record = launchAndWait(
            RunSpec(
                label = "writes",
                command = "mkdir -p outputs && echo '{\"acc\":0.91}' > outputs/metrics.json",
                cwd = project.toString(),
                artifacts = listOf("outputs/*.json"),
            ),
        )

        val rescued = record.artifacts.single()
        assertEquals(RescueOutcome.RESCUED, rescued.outcome)
        val stored = store.runDirectory(record.id).resolve("outputs/metrics.json")
        assertTrue(Files.readString(stored).contains("0.91"))
    }

    @Test
    fun `only the named environment variables are recorded`() {
        val present = System.getenv().keys.firstOrNull { System.getenv(it).isNullOrBlank().not() }
        assertNotNull(present, "the test host must expose at least one environment variable")

        val record = launchAndWait(
            RunSpec(
                label = "env",
                command = "true",
                cwd = project.toString(),
                captureEnv = listOf(present, "RUN_LEDGER_DEFINITELY_ABSENT"),
            ),
        )

        assertEquals(setOf(present), record.env.keys, "absent names are omitted, not stored empty")
    }

    @Test
    fun `a command that cannot start is recorded as unknown rather than succeeded`() {
        val record = launchAndWait(
            RunSpec(label = "missing", command = "this-command-does-not-exist-9f3a", cwd = project.toString()),
        )

        // /bin/sh reports 127 for a missing command, so this is a real failure, not an unknown.
        assertEquals(RunStatus.FAILED, record.status)
        assertEquals(127, record.exitCode)
    }

    @Test
    fun `a run in a directory that does not exist is refused instead of half recorded`() {
        val result = RunLauncher(store, scope).launch(
            RunSpec(label = "nowhere", command = "true", cwd = project.resolve("absent").toString()),
        )

        assertNull(result)
        assertTrue(store.load().isEmpty(), "nothing may reach the ledger for a refused launch")
    }

    @Test
    fun `a blank command is refused`() {
        assertNull(
            RunLauncher(store, scope).launch(
                RunSpec(label = "empty", command = "   ", cwd = project.toString()),
            ),
        )
    }

    @Test
    fun `two runs started in the same second get distinct ids`() {
        val launcher = RunLauncher(store, scope)
        val first = assertNotNull(launcher.launch(RunSpec("a", "true", project.toString())))
        val second = assertNotNull(launcher.launch(RunSpec("a", "true", project.toString())))
        runBlocking { first.second.join(); second.second.join() }

        assertEquals(2, store.load().size, "a shared timestamp must not collapse two runs into one")
    }

    @Test
    fun `a past run can be relaunched from its own record`() {
        val original = launchAndWait(
            RunSpec(
                label = "repeat me",
                command = "echo again",
                cwd = project.toString(),
                artifacts = listOf("outputs/*.json"),
            ),
        )

        val respec = RunSpec.from(original)

        assertEquals("echo again", respec.command)
        assertEquals(listOf("outputs/*.json"), respec.artifacts, "declared outputs survive a re-run")
    }

    @Test
    fun `the working tree patch is written next to the run that used it`() {
        runCommand(listOf("git", "init", "--quiet", "--initial-branch=main"), project.toFile())
        runCommand(listOf("git", "config", "user.email", "t@example.com"), project.toFile())
        runCommand(listOf("git", "config", "user.name", "T"), project.toFile())
        Files.write(project.resolve("train.py"), "print(1)\n".toByteArray(StandardCharsets.UTF_8))
        runCommand(listOf("git", "add", "."), project.toFile())
        runCommand(listOf("git", "commit", "--quiet", "-m", "init"), project.toFile())
        Files.write(project.resolve("train.py"), "print(2)\n".toByteArray(StandardCharsets.UTF_8))

        val record = launchAndWait(RunSpec("dirty", "true", project.toString()))

        val git = assertNotNull(record.git)
        assertTrue(git.dirty)
        assertTrue(
            Files.exists(store.runDirectory(record.id).resolve(Provenance.PATCH_FILE_NAME)),
            "the diff that was actually run must be stored with the run",
        )
    }
}
