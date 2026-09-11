package ai.rever.boss.plugin.dynamic.runledger

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvenanceTest {

    private val work: Path = createTempDirectory("prov-work")
    private val runDir: Path = createTempDirectory("prov-run")

    @AfterTest
    fun cleanUp() {
        work.toFile().deleteRecursively()
        runDir.toFile().deleteRecursively()
    }

    private fun git(vararg args: String) =
        runCommand(listOf("git", *args), work.toFile()).also { require(it.succeeded) { args.joinToString(" ") } }

    private fun initRepo() {
        git("init", "--quiet", "--initial-branch=main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        git("config", "commit.gpgsign", "false")
        Files.write(work.resolve("train.py"), "print(1)\n".toByteArray(StandardCharsets.UTF_8))
        git("add", ".")
        git("commit", "--quiet", "-m", "initial")
    }

    @Test
    fun `a directory that is not a repository yields no snapshot`() {
        assertNull(Provenance.capture(work.toFile(), runDir))
    }

    @Test
    fun `a clean tree records the commit and writes no patch`() {
        initRepo()

        val snapshot = assertNotNull(Provenance.capture(work.toFile(), runDir))

        assertEquals("main", snapshot.branch)
        assertEquals(40, snapshot.sha.length)
        assertTrue(!snapshot.dirty)
        assertEquals(0, snapshot.dirtyFileCount)
        assertNull(snapshot.patchFile)
        assertTrue(!Files.exists(runDir.resolve(Provenance.PATCH_FILE_NAME)))
    }

    @Test
    fun `a modified tracked file is captured in the patch, so the run is reproducible`() {
        initRepo()
        Files.write(work.resolve("train.py"), "print(2)\n".toByteArray(StandardCharsets.UTF_8))

        val snapshot = assertNotNull(Provenance.capture(work.toFile(), runDir))

        assertTrue(snapshot.dirty)
        assertEquals(1, snapshot.dirtyFileCount)
        assertEquals(Provenance.PATCH_FILE_NAME, snapshot.patchFile)
        val patch = Files.readString(runDir.resolve(Provenance.PATCH_FILE_NAME))
        assertTrue(patch.contains("print(2)"), "the patch must carry the change that was run")
        assertTrue(patch.contains(snapshot.sha), "the header names the commit to apply it to")
    }

    @Test
    fun `untracked files are named in the patch header because the diff cannot carry them`() {
        initRepo()
        Files.write(work.resolve("config.yaml"), "lr: 0.01\n".toByteArray(StandardCharsets.UTF_8))

        val snapshot = assertNotNull(Provenance.capture(work.toFile(), runDir))

        assertTrue(snapshot.dirty)
        val patch = Files.readString(runDir.resolve(Provenance.PATCH_FILE_NAME))
        assertTrue(patch.contains("NOT carried by this patch"))
        assertTrue(patch.contains("config.yaml"))
    }

    @Test
    fun `a detached head records the commit and no branch name`() {
        initRepo()
        val sha = git("rev-parse", "HEAD").trimmedOrNull()!!
        git("checkout", "--quiet", sha)

        val snapshot = assertNotNull(Provenance.capture(work.toFile(), runDir))

        assertEquals(sha, snapshot.sha)
        assertNull(snapshot.branch, "HEAD is not a branch name worth recording")
    }
}
