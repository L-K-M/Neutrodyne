// SPDX-License-Identifier: Unlicense
import com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Licence-file glob excludes for the fat JAR's repacked dependency jars, in every spelling upstream
 * artifacts use: META-INF names upper- and lowercase, lowercase META-INF licence directories, and
 * jar-root files. [ThirdPartyNoticesTask] keeps each jar's real copy instead.
 */
internal val FAT_JAR_LICENCE_EXCLUDES =
    arrayOf(
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "META-INF/COPYING*",
        "META-INF/license*",
        "META-INF/licence*",
        "META-INF/notice*",
        "META-INF/copying*",
        "META-INF/license/**",
        "META-INF/licenses/**",
        "META-INF/licence/**",
        "META-INF/licences/**",
        "META-INF/notice/**",
        "META-INF/copying/**",
        "LICENSE*",
        "NOTICE*",
        "COPYING*",
    )

/** The sync server `:sync:server`: Kotlin/JVM 21, `application`, one fat JAR, no Metro (01, 10 Deployment). */
class ServerApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            installPluginGuards()
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("application")
            assertKotlinPluginVersion()
            forbidDynamicVersions()

            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(SERVER_JDK)
                compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
            }
            extensions.configure<JavaApplication> { mainClass.set(MAIN_CLASS) }

            val version = providers.gradleProperty("neutrodyne.versionName")
            val runtimeClasspath = configurations.named("runtimeClasspath")
            val jar = tasks.named("jar", Jar::class.java)
            val notices = registerThirdPartyNotices()
            tasks.register<Jar>("fatJar") {
                group = "build"
                description = "Builds neutrodyne-server-{version}.jar from the runtime classpath."
                archiveFileName.set(version.map { "neutrodyne-server-$it.jar" })
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
                duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
                manifest {
                    attributes(mapOf("Main-Class" to MAIN_CLASS, "Implementation-Version" to version.get()))
                }
                from(jar.map { zipTree(it.archiveFile) })
                from(runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } }) {
                    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
                    // Same-named licence files would collide (first one wins); the notices task
                    // keeps every jar's copy under META-INF/licenses/<group>/<artifact>/.
                    exclude(*FAT_JAR_LICENCE_EXCLUDES)
                }
                from(notices)
            }

            configureLicensee()
            registerDependencyPolicy()
            configureModuleGraphAssert()
            configureNeutrodyneTestTasks()
        }

    /**
     * The fat JAR's third-party notices (01 Licensing; README Licence): AboutLibraries collects the
     * runtime classpath offline, with the component texts and NOTICE contents from the module's
     * `config/` directory, and [ThirdPartyNoticesTask] renders them into the JAR.
     */
    private fun Project.registerThirdPartyNotices() =
        run {
            pluginManager.apply("com.mikepenz.aboutlibraries.plugin")
            val definitions = layout.buildDirectory.file("generated/aboutLibraries/aboutlibraries.json")
            extensions.configure<AboutLibrariesExtension> {
                offlineMode.set(true)
                collect {
                    configPath.set(layout.projectDirectory.dir("config"))
                    fetchRemoteLicense.set(false)
                    fetchRemoteFunding.set(false)
                }
                export { outputFile.set(definitions) }
            }
            tasks.register<ThirdPartyNoticesTask>("generateThirdPartyNotices") {
                dependsOn("exportLibraryDefinitions")
                this.definitions.set(definitions)
                wireExternalArtifacts(artifactFiles, coordinatesByFileName)
                outputDirectory.set(layout.buildDirectory.dir("generated/third-party-notices"))
            }
        }

    private companion object {
        const val MAIN_CLASS = "$BASE_PACKAGE.sync.server.MainKt"
    }
}
