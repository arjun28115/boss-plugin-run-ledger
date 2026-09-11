package ai.rever.boss.plugin.dynamic.runledger

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Captures the source state a run is about to execute against.
 *
 * The design point: a commit hash describes a tree the run probably did not use. Local work is the
 * normal state of a research checkout, so when the tree is dirty this also writes the diff to
 * `working-tree.patch` in the run directory. Commit plus patch reconstructs the tree exactly;
 * commit alone quietly misattributes every result produced from uncommitted work.
 *
 * Known limit, recorded rather than hidden: `git diff HEAD` covers tracked files only. Untracked
 * paths are listed in the patch preamble so a reader knows what the patch does not carry.
 */
object Provenance {

    const val PATCH_FILE_NAME = "working-tree.patch"

    fun capture(workingDirectory: File, runDirectory: Path): GitSnapshot? {
        val insideRepo = runCommand(
            listOf("git", "rev-parse", "--is-inside-work-tree"),
            workingDirectory,
        )
        if (!insideRepo.succeeded || insideRepo.trimmedOrNull() != "true") return null

        val sha = runCommand(listOf("git", "rev-parse", "HEAD"), workingDirectory)
            .takeIf { it.succeeded }?.trimmedOrNull() ?: return null

        val branch = runCommand(
            listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
            workingDirectory,
        ).takeIf { it.succeeded }?.trimmedOrNull()?.takeIf { it != "HEAD" }

        val porcelain = runCommand(listOf("git", "status", "--porcelain"), workingDirectory)
        val changedLines = porcelain.stdout.lines().filter { it.isNotBlank() }
        if (changedLines.isEmpty()) {
            return GitSnapshot(sha = sha, branch = branch, dirty = false, dirtyFileCount = 0)
        }

        val untracked = changedLines.filter { it.startsWith("??") }.map { it.substring(3).trim() }
        val patchFile = writePatch(workingDirectory, runDirectory, sha, untracked)

        return GitSnapshot(
            sha = sha,
            branch = branch,
            dirty = true,
            dirtyFileCount = changedLines.size,
            patchFile = patchFile,
        )
    }

    /**
     * Writes the tracked-file diff, prefixed with a header naming the commit to apply it to and
     * every untracked path the diff does not contain. Returns the file name, or null if the diff
     * could not be produced, in which case the snapshot still records that the tree was dirty.
     */
    private fun writePatch(
        workingDirectory: File,
        runDirectory: Path,
        sha: String,
        untracked: List<String>,
    ): String? {
        val diff = runCommand(listOf("git", "diff", "HEAD"), workingDirectory, timeoutSeconds = 30)
        if (!diff.succeeded) return null

        val header = buildString {
            appendLine("# BOSS run-ledger working-tree patch")
            appendLine("# Apply with: git checkout $sha && git apply $PATCH_FILE_NAME")
            if (untracked.isEmpty()) {
                appendLine("# No untracked files at launch.")
            } else {
                appendLine("# Untracked at launch and NOT carried by this patch:")
                untracked.forEach { appendLine("#   $it") }
            }
            appendLine("#")
        }

        return try {
            Files.createDirectories(runDirectory)
            Files.write(
                runDirectory.resolve(PATCH_FILE_NAME),
                (header + diff.stdout).toByteArray(StandardCharsets.UTF_8),
            )
            PATCH_FILE_NAME
        } catch (e: Exception) {
            null
        }
    }
}
