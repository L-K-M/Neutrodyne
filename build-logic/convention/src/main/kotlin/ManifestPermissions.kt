// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.util.SortedSet
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * All declaration forms a permission can reach the merged manifest through.
 * `uses-permission-sdk-23` applies on every supported device (minSdk 26 >= 23).
 */
private val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

/** ui-tooling's PreviewActivity, ui-test-manifest's ComponentActivity, androidx.test activities. */
private val TOOLING_ACTIVITY =
    Regex(
        "androidx\\.compose\\.ui\\.(tooling|test)\\.|androidx\\.test\\.|" +
            "androidx\\.activity\\.ComponentActivity|leakcanary",
    )

/** The manifest's declared `android:name` set across every supported permission tag. */
internal fun org.w3c.dom.Document.permissionNames(): SortedSet<String> =
    PERMISSION_TAGS
        .flatMap { tag ->
            val declared = getElementsByTagName(tag)
            (0 until declared.length).map {
                declared
                    .item(it)
                    .attributes
                    .getNamedItemNS(ANDROID_NS, "name")
                    ?.nodeValue
            }
        }.filterNotNull()
        .toSortedSet()

/**
 * `verifyManifestPermissions` (01 Gradle-side policy tasks, Manifest and permissions): the merged `release`
 * manifest's `uses-permission` set must equal `app/policy/permissions.txt` exactly, `android:debuggable`/`testOnly`
 * never on, and no activity of a test or tooling artifact may be declared.
 */
abstract class VerifyManifestPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val expectedPermissions: RegularFileProperty

    init {
        group = "verification"
        description = "Release manifest permissions against app/policy/permissions.txt (01 Manifest and permissions)."
    }

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile
        val document = secureParser().parse(manifest)
        val problems = mutableListOf<String>()

        val actual = document.permissionNames()
        val expected =
            expectedPermissions
                .get()
                .asFile
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSortedSet()

        for (missing in expected - actual) problems += "missing uses-permission $missing"
        for (extra in actual - expected) {
            problems +=
                "undeclared uses-permission $extra (not in app/policy/permissions.txt)"
        }

        val applications = document.getElementsByTagName("application")
        if (applications.length > 0) {
            val app = applications.item(0)
            if (app.attributeTrue("debuggable")) problems += "application sets android:debuggable=\"true\""
            if (app.attributeTrue("testOnly")) problems += "application sets android:testOnly"
        }

        val activities = document.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val name =
                activities
                    .item(i)
                    .attributes
                    .getNamedItemNS(ANDROID_NS, "name")
                    ?.nodeValue ?: continue
            if (TOOLING_ACTIVITY.containsMatchIn(name)) {
                problems += "test/tooling activity $name must never reach the release manifest"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Release manifest policy violations:\n" + problems.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("verifyManifestPermissions: ${actual.size} permissions match app/policy/permissions.txt")
    }

    private fun secureParser() =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
            }.newDocumentBuilder()

    private fun org.w3c.dom.Node.attributeTrue(name: String): Boolean =
        attributes?.getNamedItemNS(ANDROID_NS, name)?.nodeValue == "true"
}

/** Wires `verifyManifestPermissions` to the release variant's `SingleArtifact.MERGED_MANIFEST` (CC-safe provider). */
internal fun Project.registerManifestPermissions() {
    val task =
        tasks.register<VerifyManifestPermissionsTask>("verifyManifestPermissions") {
            expectedPermissions.set(layout.projectDirectory.file("policy/permissions.txt"))
        }
    val components =
        extensions.getByType(
            com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java,
        )
    components.onVariants { variant ->
        if (variant.name != "release") return@onVariants
        task.configure {
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
        }
    }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(task) }
}
