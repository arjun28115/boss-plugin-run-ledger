package ai.rever.boss.plugin.dynamic.runledger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Starts a run, records it, and records it again when it ends.
 *
 * The run is a child process rather than a command typed into a terminal tab, and that is a
 * deliberate trade. A terminal shows the output more comfortably, but it cannot tell the plugin
 * when the command finished or what it exited with, and without those two facts there is no moment
 * at which to rescue artifacts and nothing truthful to write in the status column. Console output
 * is not lost either way: it is teed to `console.log` inside the run directory.
 *
 * The launch line is written to the ledger **before** the process starts. If the host dies one
 * instruction later the run is still on the record, as RUNNING, which is the honest answer.
 */
class RunLauncher(
    private val store: RunLedgerStore,
    private val scope: CoroutineScope,
    private val onLedgerChanged: () -> Unit = {},
) {

    private val inFlight = ConcurrentHashMap<String, Process>()
    private val sequence = AtomicLong(0)

    /** Ids in flight right now. Used by the panel to offer a stop button. */
    fun runningIds(): Set<String> = inFlight.keys().toList().toSet()

    fun launch(spec: RunSpec): Pair<String, Job>? {
        if (!spec.isRunnable()) return null
        val workingDirectory = File(spec.cwd)
        if (!workingDirectory.isDirectory) return null

        val runId = newRunId(spec.label)
        val runDirectory = store.runDirectory(runId)

        val job = scope.launch(Dispatchers.IO) {
            val started = nowUtc()
            val git = runCatching { Provenance.capture(workingDirectory, runDirectory) }.getOrNull()
            val env = spec.captureEnv
                .mapNotNull { key -> System.getenv(key)?.let { key to it } }
                .toMap()

            val launched = RunRecord(
                id = runId,
                label = spec.label,
                command = spec.command,
                cwd = spec.cwd,
                startedAt = started,
                status = RunStatus.RUNNING,
                git = git,
                env = env,
                note = spec.note,
            )
            appendQuietly(launched)

            val exitCode = execute(runId, spec.command, workingDirectory, runDirectory)
            val artifacts = runCatching {
                ArtifactRescue.rescue(workingDirectory.toPath(), runDirectory, spec.artifacts)
            }.getOrDefault(emptyList())

            appendQuietly(
                launched.copy(
                    finishedAt = nowUtc(),
                    exitCode = exitCode,
                    status = when (exitCode) {
                        0 -> RunStatus.SUCCEEDED
                        null -> RunStatus.UNKNOWN
                        else -> RunStatus.FAILED
                    },
                    artifacts = artifacts,
                ),
            )
        }
        return runId to job
    }

    /** Asks an in-flight run to stop. The completion line is written by the launch coroutine. */
    fun stop(runId: String) {
        inFlight[runId]?.destroy()
    }

    private suspend fun execute(
        runId: String,
        command: String,
        workingDirectory: File,
        runDirectory: Path,
    ): Int? = withContext(Dispatchers.IO) {
        val consoleLog = runDirectory.resolve(CONSOLE_LOG_NAME).toFile()
        runCatching { consoleLog.parentFile?.mkdirs() }
        try {
            val process = ProcessBuilder(shellInvocation(command))
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(consoleLog))
                .start()
            inFlight[runId] = process
            // A run may have no stdin; closing it stops an interactive command from hanging on a
            // prompt nobody can answer.
            runCatching { process.outputStream.close() }
            try {
                // runInterruptible, not a bare waitFor: cancelling the plugin scope cannot
                // interrupt a blocking wait, so the coroutine went away and the child carried on
                // writing into console.log after the plugin that launched it had been unloaded.
                // This turns cancellation into an interrupt, and the child is killed on the way
                // out - a run belongs to the plugin that started it.
                runInterruptible { process.waitFor() }
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            }
        } catch (e: CancellationException) {
            // Before the generic catch below, which would otherwise swallow the rethrow above and
            // report a cancelled run as one that failed to start. CancellationException is an
            // Exception, so ordering is the whole of what keeps them apart.
            throw e
        } catch (e: Exception) {
            runCatching { consoleLog.appendText("\n[run-ledger] could not start: ${e.message}\n") }
            null
        } finally {
            inFlight.remove(runId)
        }
    }

    private fun appendQuietly(record: RunRecord) {
        runCatching { store.append(record) }
        onLedgerChanged()
    }

    /**
     * Ids sort lexicographically in launch order and stay readable in a directory listing:
     * `20260911T101500Z-0003-train-baseline`. The counter disambiguates two runs started inside
     * the same second, which a plain timestamp cannot.
     */
    private fun newRunId(label: String): String {
        val stamp = nowUtc().replace("-", "").replace(":", "")
        val counter = sequence.incrementAndGet().toString().padStart(4, '0')
        return "$stamp-$counter-${slug(label)}"
    }

    private fun slug(label: String): String = label
        .lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
        .take(40)
        .ifEmpty { "run" }

    private fun shellInvocation(command: String): List<String> =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("/bin/sh", "-lc", command)
        }

    companion object {
        const val CONSOLE_LOG_NAME = "console.log"
    }
}
