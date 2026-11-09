// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `trimPythonStandalone`'s Windows shell selection. On the hosted runner (run 42576549410,
 * job 129025459186) the task's bare `bash` reached `C:\Windows\System32\bash.exe`, WSL's
 * launcher, which exits 1 without a distribution, and a `D:\` script path would never have
 * reached tar anyway (`host:file` remote syntax). The environment and filesystem are injected,
 * so these cases simulate the runner without executing anything on Windows.
 */
class WindowsBashTest {
    /** A windows-2025 runner's environment: System32 first on PATH, Git under Program Files. */
    private val hostedEnv =
        mapOf(
            "SystemRoot" to "C:\\Windows",
            "ProgramFiles" to "C:\\Program Files",
            "ProgramFiles(x86)" to "C:\\Program Files (x86)",
            "LOCALAPPDATA" to "C:\\Users\\runneradmin\\AppData\\Local",
            // The runner's variable is `Path`, not `PATH`: lookup must be case-insensitive.
            "Path" to
                "C:\\Windows\\System32;C:\\Program Files\\Git\\cmd;C:\\Windows;" +
                "C:\\Program Files\\Git\\usr\\bin;D:\\a\\_temp\\msys64\\usr\\bin",
        )

    /** Its filesystem: the WSL stub sits first on PATH; real bash lives in Git and MSYS2. */
    private val hostedFs =
        setOf(
            "C:/Windows/System32/bash.exe",
            "C:/Program Files/Git/cmd/git.exe",
            "C:/Program Files/Git/bin/bash.exe",
            "C:/Program Files/Git/usr/bin/bash.exe",
            "C:/msys64/usr/bin/bash.exe",
        )

    private val hostedIsFile = { path: String -> path in hostedFs }

    @Test
    fun `a hosted runner trims under its own Git Bash, never the WSL stub`() {
        val argv =
            trimShellCommand(
                "Windows Server 2025",
                hostedEnv,
                hostedIsFile,
                "D:\\a\\Neutrodyne\\Neutrodyne\\scripts\\engine\\trim-python.sh",
                "D:\\a\\Neutrodyne\\Neutrodyne\\youtube\\ytdlp-desktop\\build dir\\archive",
                "D:\\a\\Neutrodyne\\Neutrodyne\\youtube\\ytdlp-desktop\\build dir\\trimmed",
            )
        assertEquals("C:/Program Files/Git/bin/bash.exe", argv[0])
        // Windows paths go in as MSYS paths, one argv entry each, spaces included, so the
        // script's tar -tzf/-xzf never see `host:file` syntax or a split token.
        assertEquals(
            listOf(
                "/d/a/Neutrodyne/Neutrodyne/scripts/engine/trim-python.sh",
                "/d/a/Neutrodyne/Neutrodyne/youtube/ytdlp-desktop/build dir/archive",
                "/d/a/Neutrodyne/Neutrodyne/youtube/ytdlp-desktop/build dir/trimmed",
            ),
            argv.drop(1),
        )
    }

    @Test
    fun `the WSL stub is never a candidate even when it is the only bash on PATH`() {
        val env =
            mapOf(
                "SystemRoot" to "C:\\Windows",
                "PATH" to "C:\\Windows\\System32",
            )
        // git.exe beside the stub cannot rescue it: System32 stays excluded either way.
        val fs = setOf("C:/Windows/System32/bash.exe", "C:/Windows/System32/git.exe")
        val candidates = windowsBashCandidates(env, BashPreference.GIT_BASH) { it in fs }
        assertTrue(candidates.none { WSL_BASH.matches(it) }, "no System32-family bash: $candidates")
        assertNull(selectWindowsBash(env, BashPreference.GIT_BASH) { it in fs })
        val failure =
            assertFailsWith<GradleException> {
                trimShellCommand("Windows 11", env, { it in fs }, "C:\\s.sh", "C:\\a", "C:\\o")
            }
        assertTrue(
            failure.message.orEmpty().contains("System32"),
            "the refusal names what was tried and why WSL cannot serve: ${failure.message}",
        )
    }

    @Test
    fun `a git on PATH in a spaced custom root resolves its own sibling bash`() {
        val env = mapOf("PATH" to "C:\\Windows\\System32;D:\\Dev Tools\\Git Portable\\cmd")
        val fs =
            setOf(
                "C:/Windows/System32/bash.exe",
                "D:/Dev Tools/Git Portable/cmd/git.exe",
                "D:/Dev Tools/Git Portable/bin/bash.exe",
            )
        assertEquals(
            "D:/Dev Tools/Git Portable/bin/bash.exe",
            selectWindowsBash(env, BashPreference.GIT_BASH) { it in fs },
        )
    }

    @Test
    fun `msys paths convert drive letters and keep POSIX and UNC forms`() {
        assertEquals("/d/a/b/archive", msysPath("D:\\a\\b\\archive"))
        assertEquals("/c/Program Files/Git", msysPath("C:\\Program Files\\Git"))
        assertEquals("/c/", msysPath("C:\\"))
        assertEquals("//server/share/dir", msysPath("\\\\server\\share\\dir"))
        assertEquals("/already/posix", msysPath("/already/posix"))
    }

    @Test
    fun `ffmpeg's shell still prefers MSYS2 over Git Bash`() {
        assertEquals(
            "C:/msys64/usr/bin/bash.exe",
            selectWindowsBash(hostedEnv, BashPreference.MSYS2, hostedIsFile),
        )
    }

    @Test
    fun `unix keeps the bare bash command untouched`() {
        assertEquals(
            listOf("bash", "/repo/s.sh", "/repo/a", "/repo/out"),
            trimShellCommand("Linux", emptyMap(), { false }, "/repo/s.sh", "/repo/a", "/repo/out"),
        )
        assertEquals(
            listOf("bash", "/repo/s.sh", "/repo/a", "/repo/out"),
            trimShellCommand("Mac OS X", emptyMap(), { false }, "/repo/s.sh", "/repo/a", "/repo/out"),
        )
    }

    private companion object {
        val WSL_BASH = Regex("(?i)/(system32|syswow64|sysnative)/bash\\.exe$")
    }
}
