package ai.rever.boss.plugin.dynamic.runledger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunLedgerViewModelTest {

    private val project = createTempDirectory("vm-project")
    private val scope = CoroutineScope(Dispatchers.IO)

    @AfterTest
    fun cleanUp() {
        project.toFile().deleteRecursively()
    }

    private fun viewModel(path: String? = project.toString()) = RunLedgerViewModel(path, scope)

    @Test
    fun `with no project open the panel offers nothing to write to`() {
        val vm = viewModel(path = null)

        assertFalse(vm.hasProject)
        assertNull(vm.ledgerLocation())
        assertFalse(vm.launchDraft(), "a launch without a project must not invent a location")
    }

    @Test
    fun `the ledger lives under the project, not the host working directory`() {
        assertEquals(
            project.resolve(".boss").resolve("run-ledger").toString(),
            viewModel().ledgerLocation(),
        )
    }

    @Test
    fun `outputs accept commas or newlines, because lists get pasted`() {
        val draft = RunDraft(
            command = "python train.py",
            artifacts = "outputs/*.json,\n figures/*.png ,",
        )

        assertEquals(
            listOf("outputs/*.json", "figures/*.png"),
            draft.toSpec("/tmp").artifacts,
            "blank entries from a trailing comma are dropped",
        )
    }

    @Test
    fun `a run with no label is named after its command rather than left blank`() {
        val spec = RunDraft(command = "python train.py --lr 3e-4").toSpec("/tmp")

        assertEquals("python train.py --lr 3e-4", spec.label)
        assertTrue(spec.isRunnable())
    }

    @Test
    fun `a draft with only whitespace cannot be launched`() {
        assertFalse(RunDraft(command = "   ").canLaunch)
        assertFalse(RunDraft(command = "", label = "named").canLaunch)
    }

    @Test
    fun `selecting the open run closes it again`() {
        val vm = viewModel()
        vm.select("r1")
        assertEquals("r1", vm.state.value.selectedId)
        vm.select("r1")
        assertNull(vm.state.value.selectedId, "a second click collapses the detail")
    }

    @Test
    fun `reusing a run fills the draft instead of launching straight away`() {
        val vm = viewModel()
        val record = RunRecord(
            id = "r1",
            label = "baseline",
            command = "python train.py",
            cwd = project.toString(),
            startedAt = "2026-09-11T10:00:00Z",
            artifacts = listOf(RescuedArtifact("outputs/*.json", RescueOutcome.RESCUED)),
            env = mapOf("SEED" to "7"),
        )

        vm.loadIntoDraft(record)

        assertEquals("python train.py", vm.draft.value.command)
        assertEquals("outputs/*.json", vm.draft.value.artifacts)
        assertEquals("SEED", vm.draft.value.captureEnv)
        assertTrue(vm.state.value.runs.isEmpty(), "nothing was launched by loading a draft")
    }

    @Test
    fun `a run still in flight shows as running rather than a duration of zero`() {
        val running = RunRecord(
            id = "r1",
            label = "l",
            command = "c",
            cwd = "/tmp",
            startedAt = "2026-09-11T10:00:00Z",
            status = RunStatus.RUNNING,
        )

        assertEquals("running", durationLabel(running))
    }

    @Test
    fun `durations read in the unit that suits their length`() {
        fun finished(seconds: Long) = RunRecord(
            id = "r",
            label = "l",
            command = "c",
            cwd = "/tmp",
            startedAt = "2026-09-11T10:00:00Z",
            finishedAt = java.time.Instant.parse("2026-09-11T10:00:00Z")
                .plusSeconds(seconds).toString(),
            status = RunStatus.SUCCEEDED,
        )

        assertEquals("45s", durationLabel(finished(45)))
        assertEquals("2m 5s", durationLabel(finished(125)))
        assertEquals("1h 1m", durationLabel(finished(3660)))
    }
}
