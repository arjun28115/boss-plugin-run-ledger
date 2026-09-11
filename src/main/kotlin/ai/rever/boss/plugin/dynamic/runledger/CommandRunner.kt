package ai.rever.boss.plugin.dynamic.runledger

import java.io.File
import java.util.concurrent.TimeUnit

/** Exit status and captured stdout of a short helper command. */
data class CommandResult(val exitCode: Int, val stdout: String) {
    val succeeded: Boolean get() = exitCode == 0
    fun trimmedOrNull(): String? = stdout.trim().ifEmpty { null }
}

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
    // Drain stdout on this thread while the process runs. Reading only after waitFor would
    // deadlock any command whose output exceeds the pipe buffer.
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return CommandResult(exitCode = -1, stdout = output)
    }
    return CommandResult(process.exitValue(), output)
}
