package ai.rever.boss.plugin.dynamic.runledger

import ai.rever.boss.plugin.api.McpToolArgs
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The ledger as an agent sees it.
 *
 * These drive the tool handlers the way the host does, through [McpToolArgs] and against a real
 * ledger on disk, rather than testing the store a second time. What is pinned is the part an agent
 * depends on: that a question about a file finds the run that wrote it, that a missing answer is a
 * plain answer rather than an error, and that a genuine mistake is an error the model can act on.
 */
class RunLedgerMcpToolsTest {

    private val project: Path = createTempDirectory("run-ledger-mcp-test")
    private val store = RunLedgerStore.forProject(project)
    private val provider = RunLedgerMcpToolProvider("test.plugin") { project.toString() }

    @AfterTest
    fun cleanUp() {
        project.toFile().deleteRecursively()
    }

    private fun tool(name: String) =
        provider.tools().firstOrNull { it.name == name } ?: error("no tool named $name")

    private fun call(name: String, vararg args: Pair<String, Any?>) = runBlocking {
        tool(name).handler.call(McpToolArgs(args.toMap()))
    }

    private fun record(
        id: String,
        started: String,
        status: RunStatus = RunStatus.SUCCEEDED,
        artifacts: List<RescuedArtifact> = emptyList(),
        git: GitSnapshot? = null,
    ) = RunRecord(
        id = id,
        label = "run $id",
        command = "python train.py --seed 7",
        cwd = project.toString(),
        startedAt = started,
        finishedAt = started,
        exitCode = 0,
        status = status,
        git = git,
        artifacts = artifacts,
    )

    // -----------------------------------------------------------------
    // The tool surface itself.
    // -----------------------------------------------------------------

    @Test
    fun `all four tools are offered and are read-only`() {
        val names = provider.tools().map { it.name }
        assertEquals(
            listOf(PROVENANCE_TOOL, RUNS_TOOL, RUN_TOOL, REPRODUCE_TOOL).sorted(),
            names.sorted(),
        )
        // The ledger is a record. A tool that claimed to mutate it would invite an agent to try.
        assertTrue(provider.tools().all { it.readOnly }, "every tool here only reads")
        assertTrue(names.all { it.startsWith("run_ledger_") }, "names are namespaced to this plugin")
    }

    @Test
    fun `every tool declares a schema an agent can fill in`() {
        provider.tools().forEach { tool ->
            assertTrue(tool.description.length > 40, "${tool.name} needs a description worth reading")
            assertContains(tool.inputSchema, "\"type\":\"object\"", message = tool.name)
        }
    }

    // -----------------------------------------------------------------
    // Provenance: the question the plugin exists to answer.
    // -----------------------------------------------------------------

    @Test
    fun `provenance finds the run that wrote a file`() {
        store.append(
            record(
                "r1", "2026-09-11T10:00:00Z",
                artifacts = listOf(
                    RescuedArtifact("outputs/*.json", RescueOutcome.RESCUED, storedPath = "outputs/metrics.json"),
                ),
            ),
        )

        val result = call(PROVENANCE_TOOL, "path" to "outputs/metrics.json")

        assertFalse(result.isError)
        assertContains(result.text, "r1")
        assertContains(result.text, "python train.py --seed 7")
    }

    @Test
    fun `provenance matches an absolute path against a recorded relative one`() {
        // The agent has a path from the filesystem; the ledger may hold it as declared.
        store.append(
            record(
                "r1", "2026-09-11T10:00:00Z",
                artifacts = listOf(
                    RescuedArtifact("out/*.csv", RescueOutcome.RESCUED, storedPath = "out/results.csv"),
                ),
            ),
        )

        val result = call(PROVENANCE_TOOL, "path" to "/home/u/project/out/results.csv")

        assertFalse(result.isError)
        assertContains(result.text, "r1")
    }

    @Test
    fun `provenance does not match a file whose name merely ends the same way`() {
        store.append(
            record(
                "r1", "2026-09-11T10:00:00Z",
                artifacts = listOf(
                    RescuedArtifact("out/*.csv", RescueOutcome.RESCUED, storedPath = "out/results.csv"),
                ),
            ),
        )

        val result = call(PROVENANCE_TOOL, "path" to "out/old-results.csv")

        assertContains(result.text, "No recorded run claims that file")
    }

    @Test
    fun `the most recent writer of a path is reported first`() {
        // The same output path is overwritten by a later run, and the later one is what is on disk.
        val artifact = listOf(RescuedArtifact("m.json", RescueOutcome.RESCUED, storedPath = "m.json"))
        store.append(record("older", "2026-09-11T10:00:00Z", artifacts = artifact))
        store.append(record("newer", "2026-09-12T10:00:00Z", artifacts = artifact))

        val result = call(PROVENANCE_TOOL, "path" to "m.json")

        assertTrue(
            result.text.indexOf("newer") < result.text.indexOf("older"),
            "the run whose output is actually on disk must come first",
        )
    }

    @Test
    fun `an unclaimed file is an answer, not an error`() {
        // An agent asking about a file nobody recorded has learned something. Flagging it as an
        // error would invite a retry of a question that will never succeed.
        val result = call(PROVENANCE_TOOL, "path" to "outputs/never-written.json")

        assertFalse(result.isError)
        assertContains(result.text, "No recorded run claims that file")
    }

    @Test
    fun `a missing path argument is an error`() {
        val result = call(PROVENANCE_TOOL)
        assertTrue(result.isError)
    }

    // -----------------------------------------------------------------
    // Listing and lookup.
    // -----------------------------------------------------------------

    @Test
    fun `runs are listed newest first and can be filtered by status`() {
        store.append(record("ok", "2026-09-11T10:00:00Z", status = RunStatus.SUCCEEDED))
        store.append(record("bad", "2026-09-12T10:00:00Z", status = RunStatus.FAILED))

        val all = call(RUNS_TOOL)
        assertTrue(all.text.indexOf("bad") < all.text.indexOf("ok"), "newest first")

        val failed = call(RUNS_TOOL, "status" to "failed")
        assertContains(failed.text, "bad")
        assertFalse(failed.text.contains("\"id\": \"ok\""))
    }

    @Test
    fun `an unknown status is refused rather than silently returning everything`() {
        store.append(record("r1", "2026-09-11T10:00:00Z"))

        val result = call(RUNS_TOOL, "status" to "explosive")

        assertTrue(result.isError)
        assertContains(result.text, "Unknown status")
    }

    @Test
    fun `the limit is honoured and cannot be used to ask for nothing`() {
        repeat(5) { store.append(record("r$it", "2026-09-1${it}T10:00:00Z")) }

        assertEquals(1, call(RUNS_TOOL, "limit" to 1).text.split("\"id\"").size - 1)
        // Clamped rather than rejected: a model that sends 0 or -1 gets the smallest useful answer.
        assertEquals(1, call(RUNS_TOOL, "limit" to 0).text.split("\"id\"").size - 1)
    }

    @Test
    fun `an unknown run id says which tool lists the real ones`() {
        val result = call(RUN_TOOL, "id" to "nope")

        assertTrue(result.isError)
        assertContains(result.text, RUNS_TOOL)
    }

    // -----------------------------------------------------------------
    // Reproduce: the tool that does something with the answer.
    // -----------------------------------------------------------------

    @Test
    fun `reproducing a dirty run gives both the checkout and the patch`() {
        store.append(
            record(
                "r1", "2026-09-11T10:00:00Z",
                git = GitSnapshot(
                    sha = "abc123",
                    branch = "main",
                    dirty = true,
                    dirtyFileCount = 3,
                    patchFile = "working-tree.patch",
                ),
            ),
        )

        val result = call(REPRODUCE_TOOL, "id" to "r1")

        assertFalse(result.isError)
        assertContains(result.text, "git checkout abc123")
        assertContains(result.text, "git apply working-tree.patch")
        // The limitation has to travel with the instructions, not live in a doc nobody opens.
        assertContains(result.text, "tracked files only")
    }

    @Test
    fun `reproducing a clean run says the commit is the whole story`() {
        store.append(
            record("r1", "2026-09-11T10:00:00Z", git = GitSnapshot(sha = "abc123", dirty = false)),
        )

        val result = call(REPRODUCE_TOOL, "id" to "r1")

        assertContains(result.text, "git checkout abc123")
        assertFalse(result.text.contains("git apply"), "there is no patch to apply")
        assertContains(result.text, "clean at launch")
    }

    @Test
    fun `a dirty run with no captured patch admits the reconstruction is incomplete`() {
        // Silence here would be the worst outcome: the agent would check out a commit that is not
        // what ran and believe it had reproduced the result.
        store.append(
            record(
                "r1", "2026-09-11T10:00:00Z",
                git = GitSnapshot(sha = "abc123", dirty = true, dirtyFileCount = 2, patchFile = null),
            ),
        )

        val result = call(REPRODUCE_TOOL, "id" to "r1")

        assertContains(result.text, "incomplete")
    }

    @Test
    fun `a run with no git state says so instead of inventing commands`() {
        store.append(record("r1", "2026-09-11T10:00:00Z", git = null))

        val result = call(REPRODUCE_TOOL, "id" to "r1")

        assertFalse(result.isError)
        assertContains(result.text, "recorded no git state")
        assertFalse(result.text.contains("git checkout"))
    }

    // -----------------------------------------------------------------
    // No project open.
    // -----------------------------------------------------------------

    @Test
    fun `with no project open every tool says so rather than failing obscurely`() {
        val orphan = RunLedgerMcpToolProvider("test.plugin") { null }

        listOf(
            PROVENANCE_TOOL to arrayOf<Pair<String, Any?>>("path" to "x.json"),
            RUNS_TOOL to emptyArray(),
            RUN_TOOL to arrayOf<Pair<String, Any?>>("id" to "r1"),
            REPRODUCE_TOOL to arrayOf<Pair<String, Any?>>("id" to "r1"),
        ).forEach { (name, args) ->
            val result = runBlocking {
                orphan.tools().first { it.name == name }.handler.call(McpToolArgs(args.toMap()))
            }
            assertTrue(result.isError, name)
            assertContains(result.text, "No project is open", message = name)
        }
    }
}
