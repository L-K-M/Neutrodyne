// SPDX-License-Identifier: Unlicense
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junit.JUnitOptions
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Shared test-task settings for every JVM test task (09 Gradle test configuration): a fixed locale and an
 * awkward time zone so locale and offset bugs surface on every machine.
 */
internal fun Project.configureNeutrodyneTestTasks() {
    tasks.withType<Test>().configureEach {
        systemProperty("user.language", "de")
        systemProperty("user.country", "DE")
        systemProperty("user.timezone", "America/St_Johns")
        jvmArgs("-Xshare:off", "-XX:+EnableDynamicAgentLoading")
        if (pluginManager.hasPlugin("neutrodyne.desktop.library") || path == ":desktopApp") {
            jvmArgs("--enable-native-access=ALL-UNNAMED") // FFM in :playback:native and :desktop:system (01)
        }
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        maxHeapSize = if (name == MUTATION_TASK) MUTATION_HEAP else TEST_HEAP

        val updateGoldens = providers.gradleProperty("updateGoldens").isPresent
        systemProperty("neutrodyne.updateGoldens", updateGoldens)
        systemProperty("neutrodyne.moduleDir", layout.projectDirectory.asFile.absolutePath)
        systemProperty("neutrodyne.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
        systemProperty("neutrodyne.screenshotTier", providers.gradleProperty("screenshotTier").getOrElse("pr"))
        systemProperty("neutrodyne.mutationIterations", providers.gradleProperty("mutationIterations").getOrElse("20"))
        // `-PtightPerf` arrives as an empty value; the bare flag must mean "on".
        systemProperty(
            "neutrodyne.tightPerf",
            providers.gradleProperty("tightPerf").getOrElse("false").ifEmpty { "true" },
        )
        systemProperty("neutrodyne.syncSeeds", providers.gradleProperty("syncSeeds").getOrElse("1000"))
        if (updateGoldens) outputs.upToDateWhen { false }
        // E12's host test lives in :desktopApp; only there is the category class on the test classpath (09)
        if (path in CROSS_DEVICE_HOSTS && !providers.gradleProperty("crossDevice").isPresent) {
            (options as? JUnitOptions)?.excludeCategories(CROSS_DEVICE_CATEGORY)
        }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        // Robolectric's instrumented Android classes reach into JDK internals (SharedSecrets and
        // friends); without these opens every host test dies with IllegalAccessException on JDK 21.
        jvmArgs(
            "--add-opens",
            "java.base/java.lang=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.util=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.io=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.text=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.nio=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.net=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.security=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.time=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.util.zip=ALL-UNNAMED",
            "--add-opens",
            "java.base/jdk.internal.loader=ALL-UNNAMED",
            "--add-opens",
            "java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens",
            "java.base/sun.security.ssl=ALL-UNNAMED",
            "--add-opens",
            "java.base/sun.security.util=ALL-UNNAMED",
            "--add-opens",
            "java.base/sun.util.calendar=ALL-UNNAMED",
            "--add-opens",
            "java.base/sun.util.locale=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/java.awt.font=ALL-UNNAMED",
            "--add-opens",
            "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports",
            "java.base/jdk.internal.access=ALL-UNNAMED",
        )
    }
    registerMutationTest()
}

private const val MUTATION_TASK = "mutationTest"
private const val MUTATION_HEAP = "128m"
private const val TEST_HEAP = "2g"
private const val CROSS_DEVICE_CATEGORY = "ch.lkmc.neutrodyne.core.testing.CrossDevice"
private val CROSS_DEVICE_HOSTS = setOf(":desktopApp")

/** The modules that own `MutationRobustnessTest`, and the test task it lives in (09 Untrusted-input robustness). */
private val MUTATION_OWNERS =
    mapOf(
        ":feeds:jvm" to "test",
        ":youtube:api" to "desktopTest",
        ":sync:protocol" to "desktopTest",
    )
private val CAPPED_TESTS = listOf("*MutationRobustnessTest", "*HostileInputTest")

/**
 * The capped robustness tests run in their own `mutationTest` task with a small heap; the base task excludes them.
 * Until the tests exist the task simply finds nothing to run.
 */
private fun Project.registerMutationTest() {
    val owner = MUTATION_OWNERS[path] ?: return
    afterEvaluate {
        val base = tasks.named<Test>(owner)
        base.configure { CAPPED_TESTS.forEach(filter::excludeTestsMatching) }
        val mutation =
            tasks.register<Test>(MUTATION_TASK) {
                testClassesDirs = base.get().testClassesDirs
                classpath = base.get().classpath
                useJUnit()
                CAPPED_TESTS.forEach(filter::includeTestsMatching)
                filter.isFailOnNoMatchingTests = false
            }
        tasks.named("check") { dependsOn(mutation) }
    }
}
