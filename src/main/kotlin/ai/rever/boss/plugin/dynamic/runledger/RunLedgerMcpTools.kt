package ai.rever.boss.plugin.dynamic.runledger

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The ledger, answerable by the agent rather than only readable by a person.
 *
 * The panel already shows what produced a result. That is the wrong audience for half the question:
 * the thing most likely to ask "which version of the code made this number" is the agent that is
 * about to change the code. Without these it has to read a panel it cannot see, or parse a JSONL
 * file whose shape is this plugin's private business.
 *
 * Four tools, all read-only. Three answer questions about what happened; [REPRODUCE_TOOL] is the
 * one that does something with the answer, turning a recorded run into the commands that
 * reconstruct the tree it ran against.
 *
 * **Every handler wraps its file access in `withContext(Dispatchers.IO)`,** and that is load-bearing
 * rather than habit. The host wraps each call in `withTimeout`, which can only interrupt a handler
 * at a suspension point; a blocking read would run to completion regardless and the timeout would
 * merely stop the caller waiting. The ledger is a file, so every one of these blocks.
 *
 * Tools surface to clients as `mcp__boss__run_ledger_*`, and only while this plugin is active.
 */
internal const val PROVENANCE_TOOL = "run_ledger_provenance"
internal const val RUNS_TOOL = "run_ledger_runs"
internal const val RUN_TOOL = "run_ledger_run"
internal const val REPRODUCE_TOOL = "run_ledger_reproduce"

/** Serialises records for the model. JSON rather than prose: the caller is a program. */
private val toolJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Reads the ledger for the open project.
 *
 * Taken as a lambda rather than a stored store so that changing project is picked up: the provider
 * is registered once at load, but a tool call can happen at any point afterwards.
 */
internal class RunLedgerMcpToolProvider(
    override val providerId: String,
    private val projectPath: () -> String?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = PROVENANCE_TOOL,
            description =
                "What produced a file. Given the path of an output file, return the run that " +
                    "created it: its command, working directory, exit status, the commit it ran " +
                    "against, and whether that commit had uncommitted changes on top. Use this " +
                    "before trusting or changing a result whose origin is not obvious.",
            inputSchema = """
                {"type":"object",
                 "properties":{"path":{"type":"string",
                   "description":"Path of the output file, absolute or as recorded."}},
                 "required":["path"]}
            """.trimIndent(),
            handler = McpToolHandler { args -> provenance(args) },
        ),
        McpToolDefinition(
            name = RUNS_TOOL,
            description =
                "List recorded runs, most recent first. Optionally filter by status " +
                    "(running, succeeded, failed, unknown). Use this to find a run when you do " +
                    "not have an output file to start from.",
            inputSchema = """
                {"type":"object",
                 "properties":{
                   "status":{"type":"string","enum":["running","succeeded","failed","unknown"]},
                   "limit":{"type":"integer","description":"Maximum runs to return. Default 20."}},
                 "required":[]}
            """.trimIndent(),
            handler = McpToolHandler { args -> runs(args) },
        ),
        McpToolDefinition(
            name = RUN_TOOL,
            description =
                "Everything recorded about one run, by id: command, timings, exit code, git " +
                    "state, environment captured, and every declared output with what became of " +
                    "it (rescued, absent, over budget, or failed).",
            inputSchema = """
                {"type":"object",
                 "properties":{"id":{"type":"string","description":"Run id."}},
                 "required":["id"]}
            """.trimIndent(),
            handler = McpToolHandler { args -> run(args) },
        ),
        McpToolDefinition(
            name = REPRODUCE_TOOL,
            description =
                "How to reconstruct the source tree a run saw, by id. Returns the git commands " +
                    "to check out its commit and apply the uncommitted diff it captured, plus " +
                    "the command it ran and the directory it ran in. Read the caveats it " +
                    "returns: the captured patch covers tracked files only.",
            inputSchema = """
                {"type":"object",
                 "properties":{"id":{"type":"string","description":"Run id."}},
                 "required":["id"]}
            """.trimIndent(),
            handler = McpToolHandler { args -> reproduce(args) },
        ),
    )

    // -----------------------------------------------------------------
    // Handlers.
    // -----------------------------------------------------------------

    private suspend fun provenance(args: McpToolArgs): McpToolResult {
        val wanted = args.string("path")?.trim().orEmpty()
        if (wanted.isEmpty()) return failure("`path` is required.")
        val records = loadOrFail() ?: return noProject()

        val matches = records.filter { record -> record.artifacts.any { matchesPath(it.storedPath, wanted) } }
        if (matches.isEmpty()) {
            return McpToolResult(
                "No recorded run claims that file. It may predate the ledger, have been written " +
                    "by a command run outside Run Ledger, or not be a declared output of any run.",
            )
        }
        // Most recent first: the same path can be rewritten by a later run, and the later one is
        // what the file on disk now holds.
        return McpToolResult(toolJson.encodeToString(matches.sortedByDescending { it.startedAt }))
    }

    private suspend fun runs(args: McpToolArgs): McpToolResult {
        val records = loadOrFail() ?: return noProject()
        val status = args.string("status")?.trim()?.lowercase()
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val filtered = if (status.isNullOrEmpty()) {
            records
        } else {
            val wanted = RunStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) }
                ?: return failure("Unknown status '$status'. Expected one of: running, succeeded, failed, unknown.")
            records.filter { it.status == wanted }
        }
        return McpToolResult(
            toolJson.encodeToString(filtered.sortedByDescending { it.startedAt }.take(limit)),
        )
    }

    private suspend fun run(args: McpToolArgs): McpToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return failure("`id` is required.")
        val records = loadOrFail() ?: return noProject()
        val record = records.firstOrNull { it.id == id }
            ?: return failure("No run with id '$id'. Use $RUNS_TOOL to list the ids that exist.")
        return McpToolResult(toolJson.encodeToString(record))
    }

    private suspend fun reproduce(args: McpToolArgs): McpToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return failure("`id` is required.")
        val records = loadOrFail() ?: return noProject()
        val record = records.firstOrNull { it.id == id }
            ?: return failure("No run with id '$id'. Use $RUNS_TOOL to list the ids that exist.")

        val git = record.git
            ?: return McpToolResult(
                "Run '$id' recorded no git state, so its source cannot be reconstructed from the " +
                    "ledger. It ran `${record.command}` in ${record.cwd}.",
            )

        val steps = buildString {
            appendLine("Run:      ${record.id}  (${record.label})")
            appendLine("Command:  ${record.command}")
            appendLine("Ran in:   ${record.cwd}")
            appendLine("Status:   ${record.status.name.lowercase()}${record.exitCode?.let { " (exit $it)" }.orEmpty()}")
            appendLine()
            appendLine("Reconstruct the tree it ran against:")
            appendLine("  git checkout ${git.sha}")
            if (git.dirty && git.patchFile != null) {
                appendLine("  git apply ${git.patchFile}")
                appendLine()
                appendLine(
                    "The patch is in this run's directory. It was captured because the tree was " +
                        "dirty at launch (${git.dirtyFileCount} changed file(s)), so the commit " +
                        "alone describes a tree that never existed.",
                )
            } else if (git.dirty) {
                appendLine()
                appendLine(
                    "The tree was dirty at launch but no patch was captured, so this " +
                        "reconstruction is incomplete: the commit is not what the run saw.",
                )
            } else {
                appendLine()
                appendLine("The tree was clean at launch, so the commit is the whole story.")
            }
            appendLine()
            appendLine(
                "Caveat that applies whenever a patch is used: it carries tracked files only. " +
                    "Untracked files the run read are named in the patch preamble but their " +
                    "contents are not stored, so a run that depended on one cannot be fully " +
                    "reconstructed from the ledger alone.",
            )
        }
        return McpToolResult(steps)
    }

    // -----------------------------------------------------------------
    // Shared.
    // -----------------------------------------------------------------

    /**
     * Loads the ledger off the calling thread, or null when no project is open.
     *
     * `withContext(Dispatchers.IO)` is what makes the host's per-call timeout able to cancel this;
     * see the file KDoc.
     */
    private suspend fun loadOrFail(): List<RunRecord>? = withContext(Dispatchers.IO) {
        val root = projectRoot() ?: return@withContext null
        runCatching { RunLedgerStore.forProject(root).load() }.getOrElse { emptyList() }
    }

    private fun projectRoot(): Path? =
        projectPath()?.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it) }.getOrNull() }

    private fun noProject() = failure(
        "No project is open, so there is no ledger to read. Run Ledger records runs per project.",
    )

    private fun failure(message: String) = McpToolResult(message, isError = true)

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 200

        /**
         * Whether a recorded artifact path refers to the file the caller asked about.
         *
         * Compared by suffix in both directions rather than by equality: the caller has an
         * absolute path from the filesystem, while the ledger may hold the path as declared
         * relative to the run directory. Requiring a separator before the suffix stops
         * `results.csv` matching `old-results.csv`.
         */
        fun matchesPath(stored: String?, wanted: String): Boolean {
            if (stored.isNullOrBlank()) return false
            val a = stored.replace('\\', '/')
            val b = wanted.replace('\\', '/')
            if (a == b) return true
            return a.endsWith("/$b") || b.endsWith("/$a")
        }
    }
}
