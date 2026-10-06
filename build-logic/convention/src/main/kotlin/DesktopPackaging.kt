// SPDX-License-Identifier: Unlicense
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.RelativePath
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider

/** The four desktop packaging targets of 11's platform matrix (D88, D89). */
internal enum class DesktopPackagingTarget(
    val id: String,
    val osWire: String,
    val archWire: String,
    val osName: String,
    val osArch: String,
    /** Subdirectory of the unpacked Temurin tree that is the JDK home ("" where the root is). */
    val homeSubdir: String,
) {
    WINDOWS_X64("windows-x64", "windows", "x64", "windows", "amd64", ""),
    // Temurin's macOS tarball carries Contents/Home under the top directory.
    MACOS_ARM64("macos-arm64", "macos", "arm64", "macos", "aarch64", "Contents/Home"),
    LINUX_X64("linux-x64", "linux", "x64", "linux", "amd64", ""),
    LINUX_ARM64("linux-arm64", "linux", "arm64", "linux", "aarch64", ""),
    ;

    /** The install kinds (wire names of `InstallKind`, 11 DesktopAppGraph) D89 gives this target. */
    val installKinds: List<String>
        get() =
            when (this) {
                WINDOWS_X64 -> listOf("msi", "zip")
                MACOS_ARM64 -> listOf("dmg", "mac-zip")
                LINUX_X64, LINUX_ARM64 -> listOf("deb", "rpm", "tar.gz")
            }
}

/** `neutrodyne-<size>.png`, the committed hicolor source names (desktopApp/icons/png). */
private val HICOLOR_ICON_NAME = Regex("neutrodyne-(\\d+)\\.png")

/** The target this host can package for, or null on a host outside the matrix (dev machines). */
internal fun desktopPackagingTargetOf(
    osName: String,
    osArch: String,
): DesktopPackagingTarget? =
    DesktopPackagingTarget.entries.firstOrNull {
        it.osName == osName.lowercase() && it.osArch == osArch
    }

/** `desktopApp/runtime.lock`, already narrowed to the current target's archive (11 Lockfiles). */
internal data class BundledRuntimeLock(
    val vendor: String,
    val vendorVersion: String,
    val version: String,
    val javaVersion: String,
    val archiveUrl: String,
    val sha256: String,
)

internal fun parseBundledRuntimeLock(
    lockFile: File,
    target: DesktopPackagingTarget,
): BundledRuntimeLock {
    val props = java.util.Properties()
    lockFile.inputStream().use { props.load(it) }
    fun key(name: String): String =
        props.getProperty(name)
            ?: throw GradleException("runtime.lock: missing '$name' (11 Lockfiles)")

    return BundledRuntimeLock(
        vendor = key("vendor"),
        vendorVersion = key("vendorVersion"),
        version = key("version"),
        javaVersion = key("javaVersion"),
        archiveUrl = key("${target.id}.archiveUrl"),
        sha256 = key("${target.id}.sha256"),
    )
}

/**
 * The packaging facts `desktopApp/build.gradle.kts` merges into `build-info.properties`
 * (11 DesktopAppGraph): the target's `os`, `arch` and `runtime`, and `""` on a host outside
 * the packaging matrix (every local `dev` build derives them from the running JVM).
 */
abstract class NeutrodyneDesktopPackagingExtension {
    /** Target id like `linux-x64`, or the empty string when this host packages nothing. */
    abstract val targetId: Property<String>

    /** `os` wire value (`windows`, `macos`, `linux`) of the packaging target; empty off-matrix. */
    abstract val os: Property<String>

    /** `arch` wire value (`x64`, `arm64`) of the packaging target; empty off-matrix. */
    abstract val arch: Property<String>

    /** The bundled runtime's vendor-version string (11: "Temurin-25.0.4.1+1"); empty off-matrix. */
    abstract val runtime: Property<String>

    /** The install kinds D89 gives this target, comma-separated (`msi,zip`); empty off-matrix. */
    abstract val installKinds: Property<String>
}

/** Downloads the pinned Temurin archive, checks its SHA-256 and unpacks it (11 Packaging pipeline). */
abstract class SetupBundledRuntime : DefaultTask() {
    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    /** The unpacked JDK tree root (`Contents/` inside on macOS), never the toolchain JDK. */
    @get:OutputDirectory
    abstract val home: DirectoryProperty

    /** The verified archive, kept beside [home] for `check-runtime-sources.sh --archive`. */
    @get:OutputFile
    abstract val archive: RegularFileProperty

    @get:Inject
    protected abstract val archiveOperations: ArchiveOperations

    @get:Inject
    protected abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun setup() {
        val archiveFile = archive.get().asFile
        downloadIfChanged(archiveFile)
        verifyChecksum(archiveFile)

        val homeDir = home.get().asFile
        val staging = File(homeDir.parentFile, homeDir.name + ".unpacking")
        fileSystemOperations.delete { delete(staging, homeDir) }
        staging.mkdirs()

        val tree =
            if (archiveFile.name.endsWith(".zip")) {
                archiveOperations.zipTree(archiveFile)
            } else {
                archiveOperations.tarTree(archiveOperations.gzip(archiveFile))
            }
        fileSystemOperations.copy {
            from(tree)
            into(staging)
            // Temurin archives carry one top-level directory (jdk-25.0.4.1+1/); drop it.
            eachFile {
                relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
            }
        }
        // eachFile dropped the archive's single top-level directory, so staging is the JDK root.
        val javaBin = File(staging, "bin/java")
        if (!javaBin.isFile && !File(staging, "Contents/Home/bin/java").isFile) {
            throw GradleException("the pinned Temurin archive did not unpack to a JDK tree (no bin/java)")
        }
        if (!staging.renameTo(homeDir)) {
            throw GradleException("cannot move the unpacked runtime to ${homeDir.absolutePath}")
        }
    }

    /** Downloads only when the local file is absent or its recorded checksum changed. */
    private fun downloadIfChanged(target: File) {
        if (target.isFile && sha256Of(target) == sha256.get()) return

        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".download")
        logger.lifecycle("setupBundledRuntime: downloading ${archiveUrl.get()}")
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { client ->
            val request = HttpRequest.newBuilder(URI.create(archiveUrl.get())).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
            if (response.statusCode() !in 200..299) {
                throw GradleException("downloading the pinned runtime failed: HTTP ${response.statusCode()}")
            }
        }
        if (!tmp.renameTo(target)) {
            throw GradleException("cannot move the downloaded runtime archive to ${target.absolutePath}")
        }
    }

    private fun verifyChecksum(archiveFile: File) {
        val actual = sha256Of(archiveFile)
        if (actual != sha256.get()) {
            throw GradleException(
                "runtime.lock: SHA-256 mismatch for ${archiveFile.name}: expected ${sha256.get()}, got $actual",
            )
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val BUFFER_BYTES = 1 shl 16
    }
}

/**
 * Writes the Licensee-checked runtime classpath as `sha256  name` lines (09 Build-output
 * checks): `check-desktop-image.sh` asserts every JAR of an image is one of these files.
 */
abstract class WriteDesktopRuntimeClasspath : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun write() {
        val lines =
            runtimeClasspath.files
                .filter { it.isFile }
                .sortedBy { it.name }
                .joinToString("") { file -> "${sha256Of(file)}  ${file.name}\n" }
        manifest.get().asFile.writeText(lines)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val BUFFER_BYTES = 1 shl 16
    }
}

/**
 * One `jpackage` call from the runtime.lock JDK over the prepared app image (11 Packaging
 * pipeline). The Compose plugin has no `--resource-dir` hook (recorded 2026-10-06 in 11
 * nativeDistributions configuration), so the DEB — which needs our own `control` and
 * maintainer scripts — and the RPM — which pins its soname `Requires` — are packaged here
 * instead of through Compose's `packageDeb`/`packageRpm` (names reused so the documented
 * invocations stay `:desktopApp:packageDeb` and `:desktopApp:packageRpm`).
 */
abstract class JPackageImageTask : Exec()

/** The Windows portable ZIP: the app image archived (11 Windows MSI and ZIP). */
abstract class WindowsZipTask : Zip()

/** The Linux tar.gz: the app image as `Neutrodyne/` (11 Linux DEB, RPM and tar.gz). */
abstract class LinuxTarGzTask : Tar()

internal fun Project.registerDesktopPackagingTasks(target: DesktopPackagingTarget, lock: BundledRuntimeLock) {
    val versionName = providers.gradleProperty("neutrodyne.versionName").get()
    val setupRuntime = tasks.named("setupBundledRuntime", SetupBundledRuntime::class.java)
    val jpackage =
        setupRuntime.map { task ->
            File(File(task.home.get().asFile, target.homeSubdir), "bin/jpackage")
        }

    // The app image is createDistributable's destinationDir/<name>[.app]; taking it from the
    // task keeps this in step with the plugin's layout instead of hardcoding compose/binaries.
    val appImageLeaf = if (target == DesktopPackagingTarget.MACOS_ARM64) "$DESKTOP_PACKAGE_NAME.app" else DESKTOP_PACKAGE_NAME
    val appImage =
        tasks
            .named("createDistributable", org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask::class.java)
            .flatMap { it.destinationDir }
            .map { it.dir(appImageLeaf) }

    // The desktop entry and hicolor icons the DEB/RPM scriptlets install travel inside the
    // image through appResourcesRootDir (11 Links and files from the OS). The PNG set comes
    // from the committed brand assets (desktopApp/icons/png), mapped into the hicolor layout.
    // On the other targets nothing is merged; the task still runs so the resources dir exists.
    tasks.register<org.gradle.api.tasks.Sync>("syncDesktopIntegrationResources") {
        // Compose merges appResourcesRootDir/{common,<os>,<os>-<arch>} into the image's
        // resources dir, so the Linux payload nests under its <os> dir.
        into(layout.buildDirectory.dir("desktop-resources"))
        if (target.osWire == "linux") {
            from("packaging/desktop-integration") { into("linux/desktop-integration") }
            // neutrodyne-48.png -> linux/desktop-integration/hicolor/48x48/apps/neutrodyne.png
            file("icons/png").listFiles().orEmpty().forEach { icon ->
                val size =
                    HICOLOR_ICON_NAME.matchEntire(icon.name)?.groupValues?.get(1)
                        ?: throw GradleException("unexpected icon name ${icon.name} (hicolor mapping)")
                from(icon) {
                    into("linux/desktop-integration/hicolor/${size}x${size}/apps")
                    rename { "neutrodyne.png" }
                }
            }
        }
    }

    tasks.named("createDistributable") {
        dependsOn("syncDesktopIntegrationResources", "writeDesktopRuntimeClasspath")
    }
    // Compose merges appResourcesRootDir/{common,<os>,<os>-<arch>} into the image through its own
    // Sync; ours must run first or Gradle flags the shared output dir as an implicit dependency.
    tasks.matching { it.name == "prepareAppResources" }.configureEach {
        dependsOn("syncDesktopIntegrationResources")
    }

    // Values the task actions and argument providers need, captured as plain types up front so
    // the configuration cache can serialize the tasks (no Project/layout/providers inside).
    val installKindProperty = providers.gradleProperty("neutrodyne.installKind")
    val licenseFile = rootProject.file("LICENSE")
    val debResourceDir = file("packaging/deb")
    val rpmResourceDir = file("packaging/rpm")
    val debDest = layout.buildDirectory.dir("desktop-packaging/deb").get().asFile
    val rpmDest = layout.buildDirectory.dir("desktop-packaging/rpm").get().asFile
    val jpackagePath = jpackage.get().absolutePath

    /** Packaging tasks refuse when the image does not carry their install kind (11 Resources layout). */
    fun org.gradle.api.Task.requireInstallKind(kind: String) = doFirst {
        val actual = installKindProperty.orNull
        if (actual != kind) {
            throw GradleException(
                "${this@requireInstallKind.name} needs -Pneutrodyne.installKind=$kind " +
                    "(found ${actual ?: "dev"}), so the image carries its install kind (11 Resources layout)",
            )
        }
    }

    // --- DEB: jpackage with our own control file and maintainer scripts -----------------------
    tasks.register<JPackageImageTask>("packageDeb") {
        group = "distribution"
        description = "jpackages the app image into the DEB with our control and maintainer scripts (11)."
        dependsOn("createDistributable")
        requireInstallKind("deb")
        executable(jpackagePath)
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "--type", "deb",
                    "--name", DESKTOP_PACKAGE_NAME,
                    "--app-image", appImage.get().asFile.absolutePath,
                    "--app-version", versionName,
                    "--description", DESKTOP_DESCRIPTION,
                    "--vendor", DESKTOP_VENDOR,
                    "--license-file", licenseFile.absolutePath,
                    "--install-dir", LINUX_INSTALL_PARENT,
                    "--linux-package-name", LINUX_PACKAGE_NAME,
                    "--linux-app-release", "1",
                    "--linux-app-category", "AudioVideo",
                    "--linux-deb-maintainer", DESKTOP_DEB_MAINTAINER,
                    "--resource-dir", debResourceDir.absolutePath,
                    "--dest", debDest.absolutePath,
                )
            },
        )
        // jpackage runs dpkg-deb, whose default zstd is unreadable inside our glibc floor (11).
        environment("DPKG_DEB_COMPRESSOR_TYPE", "xz")
        doFirst { debDest.mkdirs() }
    }

    // --- RPM: jpackage with our own spec template (soname Requires + scriptlets, 11) ---------
    tasks.register<JPackageImageTask>("packageRpm") {
        group = "distribution"
        description = "jpackages the app image into the RPM with our spec (11)."
        dependsOn("createDistributable")
        requireInstallKind("rpm")
        executable(jpackagePath)
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "--type", "rpm",
                    "--name", DESKTOP_PACKAGE_NAME,
                    "--app-image", appImage.get().asFile.absolutePath,
                    "--app-version", versionName,
                    "--description", DESKTOP_DESCRIPTION,
                    "--vendor", DESKTOP_VENDOR,
                    "--license-file", licenseFile.absolutePath,
                    "--install-dir", LINUX_INSTALL_PARENT,
                    "--linux-package-name", LINUX_PACKAGE_NAME,
                    "--linux-app-release", "1",
                    "--linux-app-category", "AudioVideo",
                    "--linux-rpm-license-type", "Unlicense",
                    "--resource-dir", rpmResourceDir.absolutePath,
                    "--dest", rpmDest.absolutePath,
                )
            },
        )
        doFirst { rpmDest.mkdirs() }
    }

    // --- Windows ZIP: the image, with its Neutrodyne/ top directory ----------------------------
    tasks.register<WindowsZipTask>("packageZip") {
        group = "distribution"
        description = "Archives the app image as the portable Windows ZIP (11 Windows MSI and ZIP)."
        dependsOn("createDistributable")
        requireInstallKind("zip")
        archiveFileName.set("neutrodyne-$versionName-${target.id}.zip")
        destinationDirectory.set(layout.buildDirectory.dir("desktop-packaging/zip"))
        from(appImage.map { it.asFile.parentFile }) { include("${appImageLeaf}/**") }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    // --- Linux tar.gz: the image as Neutrodyne/, executable bits carried from the image -------
    tasks.register<LinuxTarGzTask>("packageTarGz") {
        group = "distribution"
        description = "Archives the app image as the portable Linux tar.gz (11 Linux DEB, RPM and tar.gz)."
        dependsOn("createDistributable")
        requireInstallKind("tar.gz")
        archiveFileName.set("neutrodyne-$versionName-${target.id}.tar.gz")
        destinationDirectory.set(layout.buildDirectory.dir("desktop-packaging/tar-gz"))
        compression = org.gradle.api.tasks.bundling.Compression.GZIP
        from(appImage.map { it.asFile.parentFile }) { include("${appImageLeaf}/**") }
        // Gradle 9's replacement for dirMode/fileMode: the tar keeps the modes the image files
        // already have (jpackage writes the launcher and jspawnhelper with 0755).
        useFileSystemPermissions()
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.register<WriteDesktopRuntimeClasspath>("writeDesktopRuntimeClasspath") {
        description = "Writes the runtime-classpath manifest check-desktop-image.sh checks image JARs against."
        // Compose resolves project dependencies to their jar artifacts for the image,
        // so the manifest must ask for jar artifacts too (otherwise project deps show
        // up as classes directories) and include this project's own jar.
        runtimeClasspath.from(
            configurations.named("runtimeClasspath").map { conf ->
                conf.incoming
                    .artifactView {
                        attributes {
                            attribute(
                                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                ArtifactTypeDefinition.JAR_TYPE,
                            )
                        }
                    }
                    .files
            },
        )
        runtimeClasspath.from(tasks.named("jar"))
        manifest.set(layout.buildDirectory.file("desktop-packaging/runtime-classpath.txt"))
    }
}
