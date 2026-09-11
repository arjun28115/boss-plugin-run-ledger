package ai.rever.boss.plugin.dynamic.runledger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A run the user asked for, before anything has happened.
 *
 * [artifacts] are globs relative to [cwd]. [captureEnv] names the environment variables worth
 * recording. Recording the whole environment would be both noisy and a way to leak credentials
 * into a file that tends to get committed, so the plugin records only what the user names.
 */
@Serializable
data class RunSpec(
    val label: String,
    val command: String,
    val cwd: String,
    val artifacts: List<String> = emptyList(),
    @SerialName("capture_env") val captureEnv: List<String> = emptyList(),
    val note: String? = null,
) {
    fun isRunnable(): Boolean = command.isNotBlank() && label.isNotBlank()

    companion object {
        /** Rebuilds the spec that produced [record], so a past run can be launched again. */
        fun from(record: RunRecord): RunSpec = RunSpec(
            label = record.label,
            command = record.command,
            cwd = record.cwd,
            artifacts = record.artifacts.map { it.declared }.distinct(),
            captureEnv = record.env.keys.sorted(),
            note = record.note,
        )
    }
}
