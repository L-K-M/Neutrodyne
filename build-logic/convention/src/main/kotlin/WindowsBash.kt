// SPDX-License-Identifier: Unlicense

/*
 * A real bash for Gradle tasks on Windows.
 *
 * A bare `bash` is ambiguous there: the PATH search reaches `C:\Windows\System32\bash.exe`,
 * the WSL launcher, before any Git Bash, so `trimPythonStandalone` on the hosted runner died
 * with "Windows Subsystem for Linux has no installed distributions" before the script ran
 * (run 42576549410, job 129025459186). Even a configured WSL could not run it: inside WSL the
 * task's `D:\` paths do not resolve. The System32-family stubs are therefore never candidates;
 * the lookup names only a bash beside a `git` on PATH or under a known Git/MSYS2 install root.
 */

/** The bash family a Windows task prefers: MSYS2 for FFmpeg's MSVC build, Git Bash elsewhere. */
internal enum class BashPreference { MSYS2, GIT_BASH }

/** The one `os.name` question every Windows branch in this package asks. */
internal fun isWindowsHost(osName: String): Boolean = osName.startsWith("Windows")

/**
 * The bash candidates a Windows host offers, most preferred first, as forward-slash paths the
 * Windows filesystem accepts unchanged. PATH-derived Git entries come only from directories
 * that hold `git.exe` (a Git install's `cmd`, `bin` or `mingw64\bin`), so PATH dirs such as
 * `C:\Windows\System32` can never smuggle their own `bash.exe` in. [isFile] is injected so the
 * selection can be exercised off Windows.
 */
internal fun windowsBashCandidates(
    env: Map<String, String>,
    preference: BashPreference,
    isFile: (String) -> Boolean,
): List<String> {
    val msys2 = msys2Candidates(env)
    val git = gitBashCandidates(env, isFile)
    val ordered = if (preference == BashPreference.MSYS2) msys2 + git else git + msys2
    return ordered
        .filterNot { WSL_STUB.containsMatchIn(it) }
        .distinctBy { it.lowercase() }
}

/** The first candidate that exists on [isFile], or null when the host offers none. */
internal fun selectWindowsBash(
    env: Map<String, String>,
    preference: BashPreference,
    isFile: (String) -> Boolean,
): String? = windowsBashCandidates(env, preference, isFile).firstOrNull(isFile)

/**
 * The MSYS form a bash script needs for a Windows path: `D:\a\b` -> `/d/a/b`, a UNC
 * `\\server\share` -> `//server/share`. Without it the script's arguments arrive literally and
 * GNU tar reads `D:\a\b` as `host:file` remote syntax. POSIX paths pass through unchanged.
 */
internal fun msysPath(path: String): String {
    val forward = path.replace('\\', '/')
    return when {
        DRIVE_PATH.matches(forward) -> "/${forward[0].lowercase()}${forward.substring(2)}"
        DRIVE_ONLY.matches(forward) -> "/${forward[0].lowercase()}/"
        else -> forward
    }
}

/** WSL's launchers: real WSL bash cannot run the task's scripts, so these never resolve. */
private val WSL_STUB = Regex("/(?:system32|syswow64|sysnative)/bash\\.exe$", RegexOption.IGNORE_CASE)

private val DRIVE_PATH = Regex("^[A-Za-z]:/.*")
private val DRIVE_ONLY = Regex("^[A-Za-z]:$")

/** Windows environment names are case-insensitive (`Path`, not only `PATH`). */
private fun envValue(
    env: Map<String, String>,
    name: String,
): String? =
    env.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }

private fun String.winSlashes(): String = replace('\\', '/')

/** MSYS2's bash: FFmpeg's documented MSVC shell, and a real bash for everything else. */
private fun msys2Candidates(env: Map<String, String>): List<String> =
    listOfNotNull(
        envValue(env, "MSYS2_ROOT")?.let { "${it.winSlashes()}/usr/bin/bash.exe" },
        "C:/msys64/usr/bin/bash.exe",
        "C:/Program Files/MSYS2/usr/bin/bash.exe",
    )

/**
 * Git Bash: beside every `git.exe` on PATH first (that install's own shell), then the
 * environment-rooted and well-known install dirs. `bin/bash.exe` is the documented entry
 * point GitHub Actions' `shell: bash` uses; `usr/bin/bash.exe` is the interpreter behind it.
 */
private fun gitBashCandidates(
    env: Map<String, String>,
    isFile: (String) -> Boolean,
): List<String> {
    val candidates = mutableListOf<String>()
    for (dir in envValue(env, "PATH")?.split(';').orEmpty()) {
        val d =
            dir
                .trim()
                .removeSurrounding("\"")
                .winSlashes()
                .trimEnd('/')
        if (d.isEmpty() || !isFile("$d/git.exe")) continue
        candidates += "$d/bash.exe"
        val root =
            when {
                d.endsWith("/mingw64/bin", ignoreCase = true) -> d.dropLast("/mingw64/bin".length)
                d.endsWith("/cmd", ignoreCase = true) -> d.dropLast("/cmd".length)
                d.endsWith("/bin", ignoreCase = true) -> d.dropLast("/bin".length)
                else -> null
            }
        if (root != null) {
            candidates += "$root/bin/bash.exe"
            candidates += "$root/usr/bin/bash.exe"
        }
    }
    for (key in listOf("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
        envValue(env, key)?.let { candidates += "${it.winSlashes()}/Git/bin/bash.exe" }
    }
    envValue(env, "LOCALAPPDATA")?.let { candidates += "${it.winSlashes()}/Programs/Git/bin/bash.exe" }
    envValue(env, "USERPROFILE")?.let { candidates += "${it.winSlashes()}/scoop/apps/git/current/bin/bash.exe" }
    candidates += "C:/Program Files/Git/bin/bash.exe"
    candidates += "C:/Program Files (x86)/Git/bin/bash.exe"
    return candidates
}
