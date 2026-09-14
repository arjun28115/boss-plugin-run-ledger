package ai.rever.boss.plugin.dynamic.runledger

import java.io.File
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/** Exit status and captured stdout of a short helper command. */
data class CommandResult(val exitCode: Int, val stdout: String) {
    val succeeded: Boolean get() = exitCode == 0
    fun trimmedOrNull(): String? = stdout.trim().ifEmpty { null }
}

/** How long to wait for the drain thread after the child is gone. */
private const val DRAIN_GRACE_MILLIS = 2_000L

/**
 * Runs a short helper command and captures stdout.
 *
 * Every call is bounded by [timeoutSeconds] and the process is destroyed on expiry. Provenance
 * capture runs on the launch path, so a git command that blocks on an index lock or a credential
 * prompt must not be able to hang the panel.
 */
fun runCommand(
    command: List<String>,
    workingDirectory: File,
    timeoutSeconds: Long = 10,
): CommandResult {
    val process = try {
        ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(false)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (e: Exception) {
        return CommandResult(exitCode = -1, stdout = "")
    }
    // Drain stdout on its OWN thread. Reading only after waitFor deadlocks any command whose
    // output exceeds the pipe buffer, but reading to EOF on this thread was worse: readText
    // returns only when the child closes stdout, which for a hung child is never, so waitFor's
    // timeout was measured after the wait it was meant to bound. A zero-second bound still took
    // as long as the command did.
    val collected = StringWriter()
    val drain = Thread {
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                reader.copyTo(collected)
            }
        }
    }
    drain.isDaemon = true
    drain.start()

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        // Killing the child closes the pipe, which ends the drain; the bound stops a wedged
        // reader keeping this thread forever.
        drain.join(DRAIN_GRACE_MILLIS)
        return CommandResult(exitCode = -1, stdout = collected.toString())
    }
    // join() before reading: it is what makes the writer's contents visible to this thread.
    drain.join(DRAIN_GRACE_MILLIS)
    return CommandResult(process.exitValue(), collected.toString())
}
