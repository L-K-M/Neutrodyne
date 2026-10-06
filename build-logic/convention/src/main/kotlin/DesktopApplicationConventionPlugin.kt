// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/**
 * The desktop shell `:desktopApp`: Kotlin/JVM on JDK 25 with the Compose application plugin. The ProGuard
 * `*Release*` tasks never run (GPL-2.0, D3). Since M0b the plugin also owns the packaging of D89:
 * `nativeDistributions` per target, the bundled runtime from `desktopApp/runtime.lock` (never the toolchain
 * JDK), the jlink module list, the per-OS JVM options, and the archive tasks Compose has no task for
 * (Windows ZIP, Linux tar.gz) plus the DEB/RPM `jpackage` runs that need our own resource files
 * (11 Packaging and the runtime exception).
 */
class DesktopApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        installPluginGuards()
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("neutrodyne.metro")
        assertKotlinPluginVersion()
        forbidDynamicVersions()
        configureDesktopJvm()

        // --- the bundled runtime of runtime.lock and the packaging target this host serves -------
        val hostTarget = desktopPackagingTargetOf(System.getProperty("os.name"), System.getProperty("os.arch"))
        val runtimeLock = hostTarget?.let { parseBundledRuntimeLock(file("runtime.lock"), it) }
        val packaging =
            extensions.create("neutrodyneDesktopPackaging", NeutrodyneDesktopPackagingExtension::class.java)
        packaging.targetId.set(hostTarget?.id ?: "")
        packaging.os.set(hostTarget?.osWire ?: "")
        packaging.arch.set(hostTarget?.archWire ?: "")
        // 11 BuildInfo: `runtime` is the bundled runtime's vendor-version string ("Temurin-25.0.4.1+1")
        packaging.runtime.set(runtimeLock?.vendorVersion ?: "")
        packaging.installKinds.set(hostTarget?.installKinds?.joinToString(",") ?: "")

        val toolchains = extensions.getByType<JavaToolchainService>()
        val toolchainJdk25 = toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(DESKTOP_JDK)) }

        // Off the packaging matrix (a developer's Intel Mac, a Windows arm64 laptop) the toolchain
        // JDK serves `run` and tests; every packaging task refuses instead (11 Platform matrix).
        val bundledRuntimeHome = hostTarget?.let { layout.buildDirectory.dir("bundled-runtime/${it.id}/jdk") }
        val setupBundledRuntime =
            hostTarget?.let { targetKind ->
                val lock = runtimeLock!!
                tasks.register<SetupBundledRuntime>("setupBundledRuntime") {
                    description = "Downloads the runtime.lock Temurin 25 archive, checks its SHA-256, unpacks it (11)."
                    archiveUrl.set(lock.archiveUrl)
                    sha256.set(lock.sha256)
                    archive.set(layout.buildDirectory.file("bundled-runtime/${targetKind.id}/archive"))
                    home.set(bundledRuntimeHome!!)
                }
            }

        val compose = extensions.getByType<ComposeExtension>()
        val desktop = (compose as org.gradle.api.plugins.ExtensionAware).extensions.getByType<DesktopExtension>()
        // run and the packaging tasks use the JDK 25 toolchain, not the JDK running Gradle (21);
        // on a packaging host they use the pinned Temurin runtime instead (11 jlink modules)
        desktop.application {
            mainClass = "$BASE_PACKAGE.desktop.MainKt"
            // Off the packaging matrix a missing JDK 25 must not fail configuration (the Android release
            // container has only JDK 21; review 2026-10-06): the default then stays and only `run` fails
            bundledRuntimeHome?.get()?.asFile?.resolve(hostTarget!!.homeSubdir)?.absolutePath
                ?.let { javaHome = it }
                ?: runCatching { toolchainJdk25.get().metadata.installationPath.asFile.absolutePath }
                    .getOrNull()
                    ?.let { javaHome = it }
            jvmArgs += desktopJvmOptions(hostTarget)
            // Compose resolves ProGuard (GPL-2.0, D3) through a detached configuration inside the release
            // task actions, so it never lands on a named configuration to scan. Disabling the release
            // build type's ProGuard is the enforceable gate; verifyDependencyPolicy asserts that no
            // ProGuard task stays enabled (2026-10-06).
            buildTypes.release.proguard.isEnabled.set(false)
        }
        desktop.application { configureNativeDistributions(this, target, hostTarget) }

        setupBundledRuntime?.let { setup ->
            tasks.matching {
                it.name in RUNTIME_IMAGE_CONSUMERS
            }.configureEach { dependsOn(setup) }
        }

        if (hostTarget != null && runtimeLock != null) {
            // createDistributable exists only after Compose's own afterEvaluate has run, so the
            // task wiring that reads it registers after evaluation too.
            afterEvaluate { registerDesktopPackagingTasks(hostTarget, runtimeLock) }
        } else {
            // Off-matrix: producing an image would use the toolchain JDK (forbidden by 11:
            // the bundled runtime comes only from runtime.lock) or the wrong target's arch.
            tasks.matching {
                it.name in PACKAGING_ONLY_TASKS ||
                    (it.name in RUNTIME_IMAGE_CONSUMERS && it.name != "run") ||
                    (
                        it.name.startsWith("package") &&
                            it.name !in listOf("packageUberJarForCurrentOS", "packageReleaseUberJarForCurrentOS")
                    )
            }.configureEach {
                doFirst {
                    throw org.gradle.api.GradleException(
                        "this host is outside 11's packaging matrix; build the desktop packages on their own runner",
                    )
                }
            }
        }

        // Compose desktop's *Release* tasks run ProGuard (GPL-2.0); they are never part of any build (D3)
        tasks.matching { it.name.contains("Release") }.configureEach { enabled = false }

        configureLicensee()
        registerDependencyPolicy()
        configureModuleGraphAssert()
        configureNeutrodyneTestTasks()
    }
}

/** The frozen packaging identity (11 Frozen identifiers; the MSI upgradeUuid was generated once in M0b). */
internal const val DESKTOP_PACKAGE_NAME = "Neutrodyne"
internal const val DESKTOP_DESCRIPTION = "Podcast player organised around groups"
internal const val DESKTOP_VENDOR = "Neutrodyne contributors"
internal const val DESKTOP_DEB_MAINTAINER = "neutrodyne@users.noreply.github.com"
internal const val LINUX_PACKAGE_NAME = "neutrodyne"
/** jpackage appends the package name: `--install-dir /opt` lands the app at `/opt/neutrodyne`. */
internal const val LINUX_INSTALL_PARENT = "/opt"

/** Generated once in M0b with a UUIDv4 generator and frozen (11 Frozen identifiers, D61). */
internal const val NEUTRODYNE_UPGRADE_UUID = "ff259c6f-6bd7-4437-a1cc-8d7493892fc0"

/**
 * The jlink module list of 11 jlink modules: the measured research set minus the empty
 * `jdk.crypto.ec`, plus the four modules the features need. One list serves every target.
 */
internal val JLINK_MODULES =
    listOf(
        "java.base",
        "java.datatransfer",
        "java.desktop",
        "java.logging",
        "java.management",
        "java.naming",
        "java.prefs",
        "java.security.sasl",
        "java.sql",
        "java.transaction.xa",
        "java.xml",
        "jdk.accessibility",
        "jdk.charsets",
        "jdk.localedata",
        "jdk.net",
        "jdk.security.auth",
        "jdk.unsupported",
    )

/** Tasks that execute or probe the javaHome JDK and therefore need the pinned runtime unpacked first. */
internal val RUNTIME_IMAGE_CONSUMERS =
    setOf(
        "checkRuntime",
        "createRuntimeImage",
        "createDistributable",
        "run",
        "runDistributable",
        "packageMsi",
        "packageDmg",
    )

/** Our own packaging tasks; blocked on hosts outside the matrix. */
internal val PACKAGING_ONLY_TASKS =
    setOf("packageDeb", "packageRpm", "packageZip", "packageTarGz")

/** Per-OS launcher options (11 JVM options). The AOT cache is not shipped before MD5 (PO-42 fallback). */
internal fun desktopJvmOptions(target: DesktopPackagingTarget?): List<String> =
    buildList {
        add("--enable-native-access=ALL-UNNAMED")
        add("-XX:+ExitOnOutOfMemoryError")
        add("-Xmx768m")
        when (target?.osWire) {
            "windows" -> {
                // The jpackage launcher expands environment variables in its configuration file
                // (jpackage 25), so the crash log lands in the state directory (11 JVM options).
                add("-XX:ErrorFile=\$LOCALAPPDATA/Neutrodyne/Logs/hs_err_pid%p.log")
                add("-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge")
            }
            "macos" -> {
                add("-XX:ErrorFile=\$HOME/Library/Logs/Neutrodyne/hs_err_pid%p.log")
                add("-Dapple.awt.application.appearance=system")
            }
            else -> {
                // Dev hosts outside the matrix get the Linux options: common case and harmless for `run`.
                add("-XX:ErrorFile=\$HOME/.local/state/neutrodyne/hs_err_pid%p.log")
            }
        }
    }

/** `nativeDistributions` per 11 nativeDistributions configuration, for every format of D89. */
private fun configureNativeDistributions(
    app: org.jetbrains.compose.desktop.application.dsl.JvmApplication,
    project: Project,
    target: DesktopPackagingTarget?,
) = with(app) {
    nativeDistributions {
        packageName = DESKTOP_PACKAGE_NAME
        packageVersion = project.providers.gradleProperty("neutrodyne.versionName").get()
        description = DESKTOP_DESCRIPTION
        vendor = DESKTOP_VENDOR
        licenseFile.set(project.rootProject.file("LICENSE"))
        // MSI and DMG go through Compose's packageMsi/packageDmg (WiX download, Info.plist, WixUI);
        // DEB and RPM are our own jpackage runs with resource overrides (11 nativeDistributions
        // configuration records why, 2026-10-06). ZIP, mac-zip and tar.gz are archive tasks.
        targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
        modules(*JLINK_MODULES.toTypedArray())
        // common/, <os>/ and <os>-<arch>/ merge into the image (11 Resources layout); M0b ships
        // only the DEB's desktop-integration payload; engine/, native/ and licenses/ follow.
        appResourcesRootDir.set(project.layout.buildDirectory.dir("desktop-resources"))

        windows {
            perUserInstall = true
            upgradeUuid = NEUTRODYNE_UPGRADE_UUID
            menu = true
            menuGroup = DESKTOP_PACKAGE_NAME
            shortcut = false
            dirChooser = false
            // Mandatory: jpackage's per-user default would be %LOCALAPPDATA%\Neutrodyne\ — the data
            // directory its uninstaller deletes (11 Windows MSI and ZIP).
            installationPath = "Programs\\$DESKTOP_PACKAGE_NAME"
            iconFile.set(project.file("icons/neutrodyne.ico"))
            fileAssociation("text/x-opml", "opml", "OPML subscription list", project.file("icons/neutrodyne.ico"))
        }
        macOS {
            bundleID = BASE_PACKAGE
            dockName = DESKTOP_PACKAGE_NAME
            minimumSystemVersion = "13.0"
            appCategory = "public.app-category.music"
            iconFile.set(project.file("icons/neutrodyne.icns"))
            fileAssociation("text/x-opml", "opml", "OPML subscription list", project.file("icons/neutrodyne.icns"))
            infoPlist { extraKeysRawXml = MAC_URL_TYPES + MAC_LOCAL_NETWORK_USAGE }
            // jpackage refuses a macOS version whose first number is 0 (11 macOS DMG, ad-hoc signing
            // and the 0.x ZIP): before 1.0.0 the image carries the 1.0.0 placeholder and
            // scripts/desktop/mac-zip.sh writes the real 0.Y.Z into Info.plist (PO-39).
            if (packageVersion?.startsWith("0.") == true) packageVersion = MAC_PLACEHOLDER_VERSION
            // no signing block: jpackage signs ad hoc (11 macOS DMG, ad-hoc signing and the 0.x ZIP)
        }
        linux {
            packageName = LINUX_PACKAGE_NAME
            appCategory = "AudioVideo"
            menuGroup = "AudioVideo"
            debMaintainer = DESKTOP_DEB_MAINTAINER
            rpmLicenseType = "Unlicense"
            installationPath = LINUX_INSTALL_PARENT
            // no iconFile, shortcut or fileAssociation: our own desktop entry (11 Links and files
            // from the OS), installed by the DEB maintainer scripts
        }
    }
}

/** `CFBundleURLTypes` for our schemes (11 nativeDistributions configuration). */
internal val MAC_URL_TYPES =
    """
    <key>CFBundleURLTypes</key>
    <array>
      <dict>
        <key>CFBundleURLName</key>
        <string>ch.lkmc.neutrodyne</string>
        <key>CFBundleURLSchemes</key>
        <array>
          <string>neutrodyne</string>
          <string>feed</string>
          <string>podcast</string>
          <string>pcast</string>
          <string>itpc</string>
        </array>
      </dict>
    </array>
    """.trimIndent()

/** `NSLocalNetworkUsageDescription` for a sync server on the local network (TN3179). */
internal val MAC_LOCAL_NETWORK_USAGE =
    """
    <key>NSLocalNetworkUsageDescription</key>
    <string>Neutrodyne connects to your own sync server on your local network.</string>
    """.trimIndent()

internal const val MAC_PLACEHOLDER_VERSION = "1.0.0"
