// SPDX-License-Identifier: Unlicense
import app.cash.licensee.LicenseeExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The one Licensee allow-list shared by `:app`, `:desktopApp` and `:sync:server` (01 Licensee allow-list). */
internal fun Project.configureLicensee() {
    pluginManager.apply("app.cash.licensee")
    val jna = libs.version("jna")
    val jsoup = libs.version("jsoup")
    extensions.configure<LicenseeExtension> {
        for (spdx in ALLOWED_SPDX) allow(spdx)
        allowDependency("net.java.dev.jna", "jna", jna) { because("dual-licensed; used under Apache-2.0 (D3)") }
        allowDependency(
            "net.java.dev.jna",
            "jna-platform",
            jna,
        ) { because("dual-licensed; used under Apache-2.0 (D3)") }
        // Scoped, version-pinned allows for artefacts whose POMs declare licences as URLs only (01, 2026-10-06).
        allowDependency("org.jsoup", "jsoup", jsoup) {
            because("MIT; POM declares the licence as the URL https://jsoup.org/license only")
        }
        // slf4j lands on different resolved versions per classpath (transitively capped to 2.0.19 on
        // some), so the pinned allowDependency misses; the URL itself is unambiguous MIT (01, 2026-10-06).
        allowUrl("https://opensource.org/license/mit") {
            because("MIT declared as a licence URL only (slf4j-api / slf4j-simple)")
        }
        // Transitive only (no catalog entry); 2.3.0 is the version every consumer resolves to.
        allowDependency("net.sf.kxml", "kxml2", "2.3.0") {
            // Its POM carries only the CVS-hosted licence URL + a CC public-domain URL.
            because("BSD-style licence, URL-declared only; pinned transitive version")
        }
        // No GPL, AGPL, LGPL or MPL artifact is otherwise allowed, not even scoped (D3).
    }

    // The design and the static gate name it `licenseeRelease`; AGP registers `licenseeAndroidRelease`
    // instead (there is no `licenseeRelease`), so :app gets an alias (01 deviation, 2026-10-06).
    pluginManager.withPlugin("com.android.application") {
        tasks.register("licenseeRelease") {
            group = "verification"
            description = "Alias for licenseeAndroidRelease (01's name for the task)."
            dependsOn("licenseeAndroidRelease")
        }
    }
}

private val ALLOWED_SPDX = listOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "Unlicense", "CC0-1.0")
