package ai.rever.boss.plugin.dynamic.runledger

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Copies a finished run's declared outputs into its run directory.
 *
 * This exists because the outputs that matter are usually written somewhere disposable. A run
 * writes checkpoints and figures under a scratch or temp directory, the machine reboots or the
 * directory is cleaned, and the numbers in a notebook no longer have anything behind them. Rescue
 * runs once at completion and moves those files somewhere the ledger owns.
 *
 * Three rules make it safe to run unattended:
 *  - a per-run byte budget, so one forgotten checkpoint directory cannot fill the disk. Over-budget
 *    matches are reported, not silently dropped;
 *  - copies land on a temporary name and are then moved into place, so a rescue interrupted
 *    halfway never leaves a truncated file that reads as a real artifact;
 *  - symbolic links are recorded but not followed, so a link pointing outside the project cannot
 *    drag unrelated files into the ledger.
 */
object ArtifactRescue {

    const val DEFAULT_BUDGET_BYTES: Long = 512L * 1024 * 1024
    private const val MAX_WALK_DEPTH = 12

    fun rescue(
        workingDirectory: Path,
        runDirectory: Path,
        declaredGlobs: List<String>,
        budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    ): List<RescuedArtifact> {
        if (declaredGlobs.isEmpty()) return emptyList()
        var remaining = budgetBytes
        val results = mutableListOf<RescuedArtifact>()

        for (glob in declaredGlobs) {
            val matches = try {
                findMatches(workingDirectory, glob)
            } catch (e: Exception) {
                results += RescuedArtifact(glob, RescueOutcome.FAILED, detail = e.messageOrType())
                continue
            }
            if (matches.isEmpty()) {
                results += RescuedArtifact(glob, RescueOutcome.ABSENT)
                continue
            }
            for (match in matches) {
                val size = runCatching { Files.size(match) }.getOrDefault(0L)
                if (size > remaining) {
                    results += RescuedArtifact(
                        declared = glob,
                        outcome = RescueOutcome.OVER_BUDGET,
                        bytes = size,
                        detail = "left at ${workingDirectory.relativize(match)}",
                    )
                    continue
                }
                val relative = workingDirectory.relativize(match).toString()
                results += copyOne(match, runDirectory.resolve(relative), glob, relative, size)
                    .also { if (it.outcome == RescueOutcome.RESCUED) remaining -= size }
            }
        }
        return results
    }

    private fun copyOne(
        source: Path,
        destination: Path,
        glob: String,
        relative: String,
        size: Long,
    ): RescuedArtifact = try {
        Files.createDirectories(destination.parent)
        val staging = destination.resolveSibling(destination.fileName.toString() + ".part")
        Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING)
        // Publish under the real name only once the bytes are all there. A reader therefore never
        // sees a partially written artifact, whatever happens mid-copy.
        Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING)
        RescuedArtifact(glob, RescueOutcome.RESCUED, storedPath = relative, bytes = size)
    } catch (e: Exception) {
        RescuedArtifact(glob, RescueOutcome.FAILED, detail = e.messageOrType())
    }

    /** Regular files under [root] matching [glob], relative-path matched, never following links. */
    private fun findMatches(root: Path, glob: String): List<Path> {
        val matcher = root.fileSystem.getPathMatcher("glob:$glob")
        val found = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            emptySet(),
            MAX_WALK_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                    if (dir != root && dir.fileName?.toString() in SKIPPED_DIRECTORIES) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val isLink = Files.isSymbolicLink(file)
                    if (!isLink && attrs.isRegularFile && matcher.matches(root.relativize(file))) {
                        found.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException) =
                    FileVisitResult.CONTINUE
            },
        )
        return found.sorted()
    }

    private val SKIPPED_DIRECTORIES = setOf(".git", ".boss", "node_modules", ".gradle")

    private fun Exception.messageOrType(): String = message ?: this::class.java.simpleName

    /** True when [path] exists and is a symbolic link, which rescue records but will not follow. */
    fun isUnfollowedLink(path: Path): Boolean =
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)
}
