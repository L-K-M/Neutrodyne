// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * File selection shared by the root source-scan tasks (01 Gradle-side policy tasks). `third_party/` is S7's
 * republished Chaquopy metadata, the `youtube/ytdlp/engine/` and `playback/native/…` paths are the approved
 * vendored trees (miniaudio, FFmpeg source, C++/WinRT headers, yt-dlp) that carry their own licences.
 */
private fun Project.scannedTree(include: Array<String>): FileTree =
    fileTree(layout.projectDirectory) {
        include(*include)
        exclude(
            "**/build/**",
            ".git/**",
            ".gradle/**",
            "third_party/**",
            ".kotlin/**",
            "**/.cxx/**",
            // approved vendored trees (they keep their upstream licence headers; 01 Copied code and contributions)
            "youtube/ytdlp/engine/**",
            "playback/native/ffmpeg/**",
            "playback/native/src/native/miniaudio/**",
            "playback/native/src/native/winrt/**",
            "media-sources/**",
        )
    }

/** `checkSpdxHeaders` (01): our SPDX header convention plus the copied-code credit rule. */
abstract class CheckSpdxHeadersTask : DefaultTask() {
    @get:Internal
    abstract val rootDir: DirectoryProperty

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scannedFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thirdPartyNotices: org.gradle.api.file.RegularFileProperty

    init {
        group = "verification"
        description = "SPDX headers: only Unlicense on our sources; copied files keep upstream headers (01)."
    }

    @TaskAction
    fun check() {
        val root = rootDir.get().asFile
        val problems = mutableListOf<String>()

        for (file in scannedFiles.files.sortedBy { it.relativeTo(root).path }) {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val identifiers =
                SPDX_LINE
                    .findAll(file.readText())
                    .map {
                        it.groupValues[1]
                            .trim()
                            .trimEnd('-', '>', '/', '*')
                            .trim()
                    }
            for (identifier in identifiers) {
                if (RESTRICTED_SPDX.containsMatchIn(identifier)) {
                    problems +=
                        "$relative declares restricted licence '$identifier' (GPL/LGPL/AGPL/MPL never ships, D3)"
                }
            }
        }

        // Files recorded as copied or ported in THIRD_PARTY_NOTICES.md must keep their original
        // SPDX line or credit header (01 Copied code and contributions).
        for (relative in copiedFilePaths(thirdPartyNotices.get().asFile)) {
            val target = File(root, relative)
            if (!target.isFile) {
                problems += "THIRD_PARTY_NOTICES.md lists copied file '$relative' but it does not exist"
                continue
            }
            val text = target.readText()
            if (!SPDX_LINE.containsMatchIn(text) && !CREDIT_HEADER.containsMatchIn(text)) {
                problems +=
                    "$relative is listed as copied/ported in THIRD_PARTY_NOTICES.md " +
                    "but has no SPDX line or credit header"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("SPDX header violations:\n" + problems.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("checkSpdxHeaders: clean")
    }

    private fun copiedFilePaths(notices: File): List<String> {
        if (!notices.isFile) return emptyList()
        val section =
            notices
                .readText()
                .substringAfter("## Copied or ported code", "")
                .substringBefore("\n## ")
        return BACKTICK_PATH.findAll(section).map { it.groupValues[1] }.toList()
    }

    private companion object {
        val SPDX_LINE = Regex("""SPDX-License-Identifier\s*:\s*(.+)""")
        val CREDIT_HEADER = Regex("""(?i)ported from|copied from|based on .*(code|file)""")
        val BACKTICK_PATH = Regex("""`([a-zA-Z0-9_./-]+\.[a-zA-Z0-9]+)`""")
    }
}

/**
 * `checkBannedApis` (01 "checkBannedApis rules"): the ten source-set-aware rules over every module's Kotlin,
 * Java, C/C++/Objective-C sources, Android manifests and module build scripts (never `build-logic/`).
 */
abstract class CheckBannedApisTask : DefaultTask() {
    @get:Internal
    abstract val rootDir: DirectoryProperty

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scannedFiles: ConfigurableFileCollection

    init {
        group = "verification"
        description = "Banned-API text scan per source set (01 checkBannedApis rules 1–10)."
    }

    @TaskAction
    fun check() {
        val root = rootDir.get().asFile
        val problems = mutableListOf<String>()

        for (file in scannedFiles.files.sortedBy { it.relativeTo(root).path }) {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val text = file.readText()
            val isBuildScript = file.name == "build.gradle.kts"
            val isManifest = file.name == "AndroidManifest.xml"

            problems += checkGlobalBans(relative, text, isManifest)
            problems += checkCommonMain(relative, text)
            problems += checkOptIns(relative, text)
            problems += checkProcessAndNative(relative, text)
            problems += checkNetworkOwnership(relative, text)
            problems += checkUiText(relative, text)
            if (isBuildScript) problems += checkBuildScript(relative, text)
            problems += checkDebugDirs(relative)
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Banned-API violations:\n" + problems.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("checkBannedApis: clean")
    }

    /** Rule 7: the always-banned tokens. Manifests add rule for install permissions + `android:debuggable`. */
    private fun checkGlobalBans(
        relative: String,
        text: String,
        isManifest: Boolean,
    ): List<String> {
        val problems = mutableListOf<String>()
        for ((token, reason) in GLOBAL_TOKENS) {
            text.indexOf(token).takeIf { it >= 0 }?.let {
                problems += "$relative uses banned token '$token' ($reason)"
            }
        }
        if (isManifest) {
            for ((token, reason) in MANIFEST_TOKENS) {
                if (token.containsMatchIn(text)) problems += "$relative sets $token ($reason)"
            }
        }
        return problems
    }

    /** Rule 1: `commonMain` stays free of `java.*`, `javax.*`, `android.*` and `System.currentTimeMillis`. */
    private fun checkCommonMain(
        relative: String,
        text: String,
    ): List<String> {
        if (!relative.contains("/src/commonMain/") && !relative.startsWith("src/commonMain/")) return emptyList()
        val problems = mutableListOf<String>()
        for ((regex, reason) in COMMON_MAIN_TOKENS) {
            regex.find(text)?.let { match ->
                problems += "$relative commonMain uses '${match.value}' ($reason)"
            }
        }
        return problems
    }

    /** Rule 3: opt-in annotation tokens confined to their owning modules. */
    private fun checkOptIns(
        relative: String,
        text: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (!relative.startsWith("core/designsystem/") &&
            (M3_OPTIN.containsMatchIn(text))
        ) {
            problems += "$relative uses ExperimentalMaterial3*Api outside :core:designsystem"
        }
        if (!relative.startsWith("playback/impl/") && UNSTABLE_API.containsMatchIn(text)) {
            problems += "$relative uses Media3 UnstableApi outside :playback:impl"
        }
        return problems
    }

    /** Rules 4–6: Chaquopy/process boundaries, process spawning, AWT and FFM. */
    private fun checkProcessAndNative(
        relative: String,
        text: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (!relative.startsWith("youtube/ytdlp/")) {
            if (CHAQUO.containsMatchIn(text)) {
                problems +=
                    "$relative references com.chaquo.python outside :youtube:ytdlp"
            }
            if (relative.endsWith("AndroidManifest.xml") && ANDROID_PROCESS.containsMatchIn(text)) {
                problems += "$relative sets android:process outside :youtube:ytdlp (:ytx owns the only extra process)"
            }
        }
        if (!relative.startsWith("youtube/ytdlp-desktop/")) {
            if (PROCESS_BUILDER.containsMatchIn(text)) {
                problems += "$relative starts a process outside :youtube:ytdlp-desktop (rule 17)"
            }
        }
        if (!AWT_DESKTOP_DIRS.any { relative.startsWith(it) } && AWT_DESKTOP.containsMatchIn(text)) {
            problems += "$relative uses java.awt.Desktop outside :desktopApp/:desktop:system/:core:ui desktopMain"
        }
        if (!FFM_DIRS.any { relative.startsWith(it) } && FFM_API.containsMatchIn(text)) {
            problems += "$relative uses java.lang.foreign outside :playback:native/:desktop:system/:playback:engine"
        }
        return problems
    }

    /** Rule 8: OkHttp and Ktor client construction belongs to the network island / sync server. */
    private fun checkNetworkOwnership(
        relative: String,
        text: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (!relative.startsWith("core/network/okhttp/")) {
            for (regex in OKHTTP_TOKENS) {
                regex.find(text)?.let {
                    problems +=
                        "$relative constructs OkHttp outside :core:network:okhttp ('${it.value}')"
                }
            }
        }
        if (!relative.startsWith("core/network/") && !relative.startsWith("sync/server/") &&
            KTOR_CLIENT.containsMatchIn(text)
        ) {
            problems += "$relative constructs a Ktor HttpClient outside :core:network (the sync server is exempt)"
        }
        return problems
    }

    /** Rule 10: state collection and hard-coded UI text. */
    private fun checkUiText(
        relative: String,
        text: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (relative.startsWith("feature/") && COLLECT_AS_STATE.containsMatchIn(text)) {
            problems += "$relative uses collectAsState(); feature screens use collectAsStateWithLifecycle (rule 10)"
        }
        val commonUi = relative.contains("/src/commonMain/")
        if (commonUi && (relative.startsWith("feature/") || relative.startsWith("core/ui/")) &&
            HARD_TEXT.containsMatchIn(text)
        ) {
            problems +=
                "$relative has a hard-coded Text(\"…\") in common UI code " +
                "(rule 10; text comes from Compose resources)"
        }
        return problems
    }

    /** Rules 2 and 9: build-script dependency rules. */
    private fun checkBuildScript(
        relative: String,
        text: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (relative != "app/build.gradle.kts" && DEBUG_CONFIG.containsMatchIn(text)) {
            problems += "$relative uses debugImplementation/debugApi (allowed only in :app, rule 9)"
        }
        for (island in commonMainIslandEdges(text)) {
            problems +=
                "$relative adds JVM island :$island to commonMain " +
                "(rule 2/14: islands only from androidMain/desktopMain/platform modules)"
        }
        val isKmpModule = text.contains("neutrodyne.kmp")
        if (isKmpModule) {
            for (module in PLATFORM_ONLY_MODULES) {
                if (text.contains("project(\":$module\")")) {
                    problems += "$relative adds platform-only :$module to a KMP module (rule 15)"
                }
            }
        }
        return problems
    }

    /** Rule 9: only `:app` may carry a `src/debug/` source-set directory. */
    private fun checkDebugDirs(relative: String): List<String> {
        if (relative.contains("/src/debug/") && !relative.startsWith("app/")) {
            return listOf("$relative is a non-app src/debug/ source (rule 9)")
        }
        return emptyList()
    }

    private companion object {
        val GLOBAL_TOKENS =
            listOf(
                "System.load(" to "native code loads through the runtime block only (rule 17)",
                "System.loadLibrary(" to "native code loads through the runtime block only (rule 17)",
                "DexClassLoader" to "no dynamic code loading (N10)",
                "InMemoryDexClassLoader" to "no dynamic code loading (N10)",
                "PackageInstaller" to "the app installs nothing (D78)",
                "api.github.com" to "the update check goes through neutrodyne.repoUrl (09)",
                "GlobalScope" to "structured concurrency only",
                "override fun onBackPressed" to "navigation3 predictive back (01 Navigation)",
                "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "explicitly not requested (01 Manifest)",
                "REQUEST_INSTALL_PACKAGES" to "the app installs nothing (D78)",
                "UPDATE_PACKAGES_WITHOUT_USER_ACTION" to "the app installs nothing (D78)",
            )

        val MANIFEST_TOKENS =
            listOf(
                Regex("android\\s*:\\s*debuggable\\s*=\\s*\"true\"") to "release must not be debuggable",
                Regex("android\\s*:\\s*testOnly") to "release must not be testOnly",
            )

        val COMMON_MAIN_TOKENS =
            listOf(
                Regex("(?<![\\w$])java\\.") to "java.* never in commonMain (D81)",
                Regex("(?<![\\w$])javax\\.") to "javax.* never in commonMain (D81)",
                Regex("(?<![\\w$])android\\.(?![xX])") to "android.* never in commonMain (androidx is fine)",
                Regex("System\\.currentTimeMillis") to "kotlin.time, never System.currentTimeMillis",
            )

        val M3_OPTIN = Regex("ExperimentalMaterial3Api|ExperimentalMaterial3ExpressiveApi")
        val UNSTABLE_API = Regex("(?<![\\w$])UnstableApi")
        val CHAQUO = Regex("com\\.chaquo\\.python")
        val ANDROID_PROCESS = Regex("android\\s*:\\s*process")
        val PROCESS_BUILDER = Regex("ProcessBuilder\\s*\\(|Runtime\\.getRuntime\\(\\)\\s*\\.exec")
        val AWT_DESKTOP = Regex("java\\.awt\\.Desktop")
        val AWT_DESKTOP_DIRS = listOf("desktopApp/", "desktop/system/", "core/ui/src/desktopMain/")
        val FFM_API = Regex("java\\.lang\\.foreign")
        val FFM_DIRS = listOf("playback/native/", "desktop/system/", "playback/engine/")
        val OKHTTP_TOKENS =
            listOf(
                Regex("okhttp3\\.Cache\\s*\\("),
                Regex("(?<![\\w])OkHttpClient\\s*\\("),
                Regex("(?<![\\w])OkHttpClient\\.Builder\\s*\\("),
                Regex("\\.cache\\(Cache\\("),
            )
        val KTOR_CLIENT = Regex("(?<![\\w])HttpClient\\s*\\(")
        val COLLECT_AS_STATE = Regex("(?<![\\w])collectAsState\\s*\\(")
        val HARD_TEXT = Regex("(?<![\\w$])[A-Za-z]*Text\\s*\\(\\s*\"")
        val DEBUG_CONFIG = Regex("debugImplementation|debugApi")
    }
}

// Each alternative ends at the `{` opening the block that contains the dependencies, so the match's
// last index is always the block's brace. `commonMain { }` and the delegated `by getting`/`getByName`
// forms are accepted KMP DSL (the Gradle-side check in VerifyDependencyPolicyTask is authoritative;
// this scan is the second line of defence for the forms it can see).
private val COMMON_MAIN_DEPS =
    Regex(
        "(?<![\\w$])commonMain\\s*(?:\\.\\s*dependencies\\s*)?\\{|" +
            "(?<![\\w$])commonMain\\s+by\\s+getting\\s*\\{|" +
            "(?<![\\w$])(?:getByName|named)\\(\\s*\"commonMain\"\\s*\\)" +
            "\\s*(?:\\.\\s*(?:dependencies|apply|also)\\s*)?\\{",
    )

/**
 * The body of each `commonMain` dependencies block of a build script, covering
 * `commonMain.dependencies { }`, `commonMain { dependencies { } }`, `val commonMain by getting
 * { dependencies { } }` and `getByName`/`named("commonMain")` followed by a block, a `.dependencies`
 * block or an `.apply`/`.also` block.
 */
private fun commonMainDependencyBlocks(text: String): List<String> =
    COMMON_MAIN_DEPS.findAll(text).map { balancedBlock(text, it.range.last) }.toList()

private fun balancedBlock(
    text: String,
    openBrace: Int,
): String {
    var depth = 0
    for (i in openBrace until text.length) {
        when (text[i]) {
            '{' -> {
                depth++
            }

            '}' -> {
                depth--
                if (depth == 0) return text.substring(openBrace, i + 1)
            }
        }
    }
    return text.substring(openBrace)
}

/**
 * Rule 2's text-scan half — `verifyDependencyPolicy` checks the declared `commonMain*` configurations
 * directly and is authoritative; this returns the islands a `commonMain` block references for the
 * DSL forms the scan can isolate.
 */
internal fun commonMainIslandEdges(text: String): List<String> =
    commonMainDependencyBlocks(text)
        .flatMap { block -> JVM_ISLANDS.filter { block.contains("project(\":$it\")") } }
        .distinct()

/** Modules whose bytecode assumes a JVM or Android classpath and must never be a `commonMain` edge (rule 14). */
internal val JVM_ISLANDS = listOf("feeds:jvm", "core:network:okhttp", "youtube:engine")

/** Modules that only exist on one platform; a KMP module may never depend on them (rule 15). */
internal val PLATFORM_ONLY_MODULES =
    listOf(
        "playback:impl",
        "playback:desktop",
        "playback:engine",
        "playback:native",
        "desktop:system",
        "youtube:ytdlp",
        "youtube:ytdlp-desktop",
    )

/** Registers the two root scan tasks; called by `neutrodyne.quality` on the root project only. */
internal fun Project.registerSourceScanTasks() {
    val spdxTree =
        scannedTree(
            arrayOf(
                "**/*.kt",
                "**/*.kts",
                "**/*.java",
                "**/*.c",
                "**/*.cpp",
                "**/*.h",
                "**/*.m",
                "**/*.mm",
                "**/*.py",
                "**/*.sh",
                "**/*.xml",
                "**/*.yml",
                "**/*.yaml",
                "**/*.properties",
                "**/*.toml",
                "**/*.lock",
            ),
        )
    // build-logic is the tooling source; checkBannedApis' "module build scripts" excludes it per design.
    val bannedApiWithoutBuildLogic =
        fileTree(layout.projectDirectory) {
            include(
                "**/*.kt",
                "**/*.java",
                "**/*.c",
                "**/*.cpp",
                "**/*.h",
                "**/*.m",
                "**/*.mm",
                "**/*.aidl",
                "**/AndroidManifest.xml",
                "**/build.gradle.kts",
            )
            exclude(
                "build-logic/**",
                "**/build/**",
                ".git/**",
                ".gradle/**",
                "third_party/**",
                ".kotlin/**",
                "**/.cxx/**",
                "media-sources/**",
            )
        }

    val spdx =
        tasks.register<CheckSpdxHeadersTask>("checkSpdxHeaders") {
            rootDir.set(layout.projectDirectory)
            scannedFiles.from(spdxTree)
            thirdPartyNotices.set(layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
        }
    val banned =
        tasks.register<CheckBannedApisTask>("checkBannedApis") {
            rootDir.set(layout.projectDirectory)
            scannedFiles.from(bannedApiWithoutBuildLogic)
        }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(spdx, banned) }
}
