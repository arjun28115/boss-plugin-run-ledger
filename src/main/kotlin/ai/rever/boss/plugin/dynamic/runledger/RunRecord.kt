package ai.rever.boss.plugin.dynamic.runledger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the working tree looked like when a run started.
 *
 * [sha] alone is not enough to reproduce a run. A researcher almost never launches from a clean
 * tree, so a ledger that records only the commit hash records a tree that never existed. When
 * [dirty] is true the launcher also writes `working-tree.patch` into the run directory, and
 * [patchFile] names it; `git checkout <sha> && git apply working-tree.patch` then reconstructs the
 * exact source the run saw.
 */
@Serializable
data class GitSnapshot(
    val sha: String,
    val branch: String? = null,
    val dirty: Boolean = false,
    @SerialName("dirty_file_count") val dirtyFileCount: Int = 0,
    @SerialName("patch_file") val patchFile: String? = null,
)

/** Why an artifact did or did not make it into the run directory. */
@Serializable
enum class RescueOutcome {
    @SerialName("rescued") RESCUED,

    /** The glob matched nothing. Usually the run failed before it wrote anything. */
    @SerialName("absent") ABSENT,

    /** Matched, but rescuing it would have blown the per-run byte budget. Left where it was. */
    @SerialName("over_budget") OVER_BUDGET,

    /** Matched, but the copy itself failed. [RescuedArtifact.detail] carries the reason. */
    @SerialName("failed") FAILED,
}

/** One declared output glob and what became of it. */
@Serializable
data class RescuedArtifact(
    val declared: String,
    val outcome: RescueOutcome,
    @SerialName("stored_path") val storedPath: String? = null,
    val bytes: Long = 0,
    val detail: String? = null,
)

/** Terminal state of a run. UNKNOWN covers a host that was killed while the run was in flight. */
@Serializable
enum class RunStatus {
    @SerialName("running") RUNNING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("unknown") UNKNOWN,
}

/**
 * One launched command and everything needed to say what produced its output.
 *
 * Records are appended to the ledger twice: once at launch with [status] RUNNING, and once at
 * completion. [RunLedgerStore] folds the two by [id], last write winning, so a run that never
 * completes survives as RUNNING rather than vanishing.
 */
@Serializable
data class RunRecord(
    val id: String,
    val label: String,
    val command: String,
    val cwd: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val status: RunStatus = RunStatus.RUNNING,
    val git: GitSnapshot? = null,
    val env: Map<String, String> = emptyMap(),
    val artifacts: List<RescuedArtifact> = emptyList(),
    val note: String? = null,
) {
    /** Wall-clock duration in milliseconds, or null while the run is still in flight. */
    fun durationMs(): Long? {
        val start = parseInstantMillis(startedAt) ?: return null
        val end = finishedAt?.let(::parseInstantMillis) ?: return null
        return (end - start).coerceAtLeast(0)
    }
}
