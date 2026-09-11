package ai.rever.boss.plugin.dynamic.runledger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths

/** What the panel needs to draw itself. */
data class RunLedgerUiState(
    val runs: List<RunRecord> = emptyList(),
    val selectedId: String? = null,
    val runningIds: Set<String> = emptySet(),
    val unreadableLines: Int = 0,
    val error: String? = null,
) {
    val selected: RunRecord? get() = runs.firstOrNull { it.id == selectedId }
}

/** The draft the user is filling in before launching. */
data class RunDraft(
    val label: String = "",
    val command: String = "",
    val artifacts: String = "",
    val captureEnv: String = "",
) {
    fun toSpec(cwd: String): RunSpec = RunSpec(
        label = label.trim().ifEmpty { command.trim().take(40) },
        command = command.trim(),
        cwd = cwd,
        artifacts = splitList(artifacts),
        captureEnv = splitList(captureEnv),
    )

    val canLaunch: Boolean get() = command.isNotBlank()

    private companion object {
        /** Accepts commas or newlines so a pasted list works without reformatting. */
        fun splitList(text: String): List<String> =
            text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Panel state.
 *
 * The whole view model is a no-op without a project path. A ledger belongs to a project, and
 * inventing a location for it when no project is open would scatter run history into whatever
 * directory the host happened to start in.
 */
class RunLedgerViewModel(
    private val projectPath: String?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RunLedgerUiState())
    val state: StateFlow<RunLedgerUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow(RunDraft())
    val draft: StateFlow<RunDraft> = _draft.asStateFlow()

    private val projectRoot: Path? = projectPath?.let { runCatching { Paths.get(it) }.getOrNull() }
    private val store: RunLedgerStore? = projectRoot?.let { RunLedgerStore.forProject(it) }
    private val launcher: RunLauncher? =
        store?.let { RunLauncher(it, scope, onLedgerChanged = ::refresh) }

    val hasProject: Boolean get() = store != null

    /** Directory the ledger writes to, for the panel to show and the user to open. */
    fun ledgerLocation(): String? = projectRoot?.resolve(".boss")?.resolve("run-ledger")?.toString()

    fun runDirectory(runId: String): String? = store?.runDirectory(runId)?.toString()

    fun refresh() {
        val store = store ?: return
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { store.load() }
            val unreadable = runCatching { store.unreadableLineCount() }.getOrDefault(0)
            _state.value = _state.value.copy(
                runs = loaded.getOrDefault(emptyList()),
                runningIds = launcher?.runningIds().orEmpty(),
                unreadableLines = unreadable,
                error = loaded.exceptionOrNull()?.message,
            )
        }
    }

    fun select(runId: String?) {
        _state.value = _state.value.copy(
            selectedId = if (_state.value.selectedId == runId) null else runId,
        )
    }

    fun updateDraft(transform: (RunDraft) -> RunDraft) {
        _draft.value = transform(_draft.value)
    }

    /** Launches the draft. Returns false when there is nothing runnable, so the UI can say so. */
    fun launchDraft(): Boolean {
        val cwd = projectPath ?: return false
        val launcher = launcher ?: return false
        val spec = _draft.value.toSpec(cwd)
        val launched = launcher.launch(spec) ?: return false
        _draft.value = RunDraft()
        _state.value = _state.value.copy(selectedId = launched.first)
        refresh()
        return true
    }

    /**
     * Loads a past run back into the draft rather than launching it straight away.
     *
     * Re-running is nearly always re-running with one thing changed, and a button that fires
     * immediately makes that the awkward path. It also keeps an accidental click from starting a
     * long job.
     */
    fun loadIntoDraft(record: RunRecord) {
        val spec = RunSpec.from(record)
        _draft.value = RunDraft(
            label = spec.label,
            command = spec.command,
            artifacts = spec.artifacts.joinToString(", "),
            captureEnv = spec.captureEnv.joinToString(", "),
        )
    }

    fun stop(runId: String) {
        launcher?.stop(runId)
        refresh()
    }
}
