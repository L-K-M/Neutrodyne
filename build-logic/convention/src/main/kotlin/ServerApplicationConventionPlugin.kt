// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

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
                }
            }

            configureLicensee()
            registerDependencyPolicy()
            configureModuleGraphAssert()
            configureNeutrodyneTestTasks()
        }

    private companion object {
        const val MAIN_CLASS = "$BASE_PACKAGE.sync.server.MainKt"
    }
}
