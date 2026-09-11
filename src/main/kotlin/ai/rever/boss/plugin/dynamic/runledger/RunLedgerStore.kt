package ai.rever.boss.plugin.dynamic.runledger

import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The on-disk ledger: `<project>/.boss/run-ledger/runs.jsonl`, one JSON object per line.
 *
 * Append-only on purpose. A run is written once at launch and once at completion, and [load] folds
 * the two by id with the last write winning. That ordering is what makes the format survive a
 * crash: the host can die at any point and the worst case is a run stuck at RUNNING, never a lost
 * or half-rewritten history.
 */
class RunLedgerStore(private val root: Path) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val ledgerFile: Path get() = root.resolve(LEDGER_FILE_NAME)

    /** Directory that holds a run's rescued artifacts and working-tree patch. */
    fun runDirectory(runId: String): Path = root.resolve(runId)

    /**
     * Appends one record.
     *
     * The line is built in memory, written in a single call and forced to disk before the channel
     * closes. A partial line can therefore only be the last line in the file, which is exactly the
     * case [load] is written to tolerate.
     */
    @Throws(IOException::class)
    fun append(record: RunRecord) {
        Files.createDirectories(root)
        val line = (json.encodeToString(RunRecord.serializer(), record) + "\n")
            .toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(
            ledgerFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        ).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(line))
            channel.force(false)
        }
    }

    /**
     * Every run, newest first.
     *
     * Unreadable lines are skipped rather than thrown on. A truncated tail from a hard kill, or a
     * line someone edited by hand, costs that one run and not the panel.
     */
    fun load(): List<RunRecord> {
        if (!Files.exists(ledgerFile)) return emptyList()
        val byId = LinkedHashMap<String, RunRecord>()
        Files.newBufferedReader(ledgerFile, StandardCharsets.UTF_8).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val record = runCatching {
                    json.decodeFromString(RunRecord.serializer(), line)
                }.getOrNull() ?: return@forEach
                byId[record.id] = record
            }
        }
        return byId.values.sortedByDescending { it.startedAt }
    }

    /** Count of lines the parser could not read. Surfaced in the panel so corruption is visible. */
    fun unreadableLineCount(): Int {
        if (!Files.exists(ledgerFile)) return 0
        return Files.newBufferedReader(ledgerFile, StandardCharsets.UTF_8).use { reader ->
            reader.lineSequence().count { line ->
                line.isNotBlank() &&
                    runCatching { json.decodeFromString(RunRecord.serializer(), line) }.isFailure
            }
        }
    }

    companion object {
        const val LEDGER_FILE_NAME = "runs.jsonl"

        /** `<project>/.boss/run-ledger`. */
        fun forProject(projectPath: Path): RunLedgerStore =
            RunLedgerStore(projectPath.resolve(".boss").resolve("run-ledger"))
    }
}
