// SPDX-License-Identifier: Unlicense
import com.jraska.module.graph.assertion.GraphRulesExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * The module-graph rules of [01 Dependency rules] as module-graph-assert 2.9.1 DSL (`allowed`: every discovered
 * project edge must match a rule; `restricted`: no edge may match). Applied to `:app`, `:desktopApp`,
 * `:sync:server` and `:core:testing`; the plugin wires `assertModuleGraph` into `check`.
 *
 * Verified 2026-10-06 (recorded in 01): 2.9.1's extension is `moduleGraphAssert` with `maxHeight`, `restricted`,
 * `allowed`, `configurations`, `assertOnAnyBuild`; the graph walks the named configurations' *declared*
 * `ProjectDependency`s of every project and asserts the subtree rooted at the module the plugin is applied to.
 * It sees KMP source-set edges through their configuration names (`commonMainImplementation` etc.), which is why
 * they are listed here; it cannot tell the two platforms' sets apart beyond the name, so the
 * islands-only-from-platform-code part of rule 14 stays with `verifyDependencyPolicy`'s declared-edge check
 * and `checkBannedApis` rule 2's text scan (2026-10-06).
 */
internal fun Project.configureModuleGraphAssert() {
    pluginManager.apply("com.jraska.module.graph.assertion")
    extensions.configure<GraphRulesExtension> {
        maxHeight = 6
        // Every main source set's four dependency scopes (Api, Implementation, CompileOnly,
        // RuntimeOnly) plus the plain JVM/Android buckets, so compile- or runtime-scoped
        // project edges cannot bypass the graph (KMP DSL reference, DependencyKinds).
        configurations +=
            setOf(
                "api",
                "implementation",
                "compileOnly",
                "runtimeOnly",
                "commonMainApi",
                "commonMainImplementation",
                "commonMainCompileOnly",
                "commonMainRuntimeOnly",
                "androidMainApi",
                "androidMainImplementation",
                "androidMainCompileOnly",
                "androidMainRuntimeOnly",
                "desktopMainApi",
                "desktopMainImplementation",
                "desktopMainCompileOnly",
                "desktopMainRuntimeOnly",
            )
        allowed =
            arrayOf(
                ":app -> .*",
                ":desktopApp -> .*",
                ":sync:server -> :(sync:protocol|feeds)",
                ":feature:[a-z]+ -> :core:(domain|model|common|designsystem|ui|navigation)",
                ":feature:[a-z]+ -> :(playback|download|youtube|sync):api",
                ":core:domain -> :core:(model|common)",
                ":core:domain -> :(playback|download|youtube):api",
                ":core:common -> :core:model", // 08: Monogram returns MonogramSpec (moved 2026-10-05)
                ":core:data -> :core:(domain|model|common|database|datastore|network|artwork)",
                ":core:data -> :feeds(:jvm)?",
                ":core:data -> :youtube:api",
                ":core:artwork -> :core:(model|common|database|network|network:okhttp)",
                ":core:artwork -> :youtube:api",
                ":playback:core -> :playback:api",
                ":playback:core -> :core:(domain|model|common)",
                ":playback:impl -> :playback:(core|api)",
                ":playback:impl -> :(download|youtube):api",
                ":playback:impl -> :core:(domain|model|common|database|datastore|network|network:okhttp|artwork)",
                ":download:impl -> :(download|youtube):api",
                ":download:impl -> :core:(domain|model|common|database|datastore|network|artwork)",
                ":youtube:impl -> :youtube:api",
                ":youtube:impl -> :core:(model|common|network)",
                ":sync:impl -> :sync:(api|protocol)",
                ":sync:impl -> :core:(domain|model|common|database|datastore|network)",
                ":sync:impl -> :feeds",
                ":youtube:ytdlp -> :youtube:(engine|api)",
                ":youtube:ytdlp -> :core:(model|common|datastore|network:okhttp)",
                ":youtube:ytdlp-desktop -> :youtube:(engine|api)",
                ":youtube:ytdlp-desktop -> :core:(model|common|datastore)",
                ":youtube:engine -> :youtube:api",
                ":youtube:engine -> :core:(model|common)",
                ":feeds:jvm -> :feeds",
                ":core:network:okhttp -> :core:(model|common)",
                ":playback:engine -> :playback:(native|api)",
                ":playback:engine -> :core:(model|common|network:okhttp)",
                ":playback:desktop -> :playback:(core|api|engine)",
                ":playback:desktop -> :desktop:system",
                ":playback:desktop -> :(download|youtube):api",
                ":playback:desktop -> :core:(domain|model|common|artwork|database|datastore)",
                ":desktop:system -> :playback:(native|api)",
                ":desktop:system -> :core:(model|common)",
                ":core:designsystem -> :core:model",
                ":core:ui -> :core:(designsystem|model|common|navigation)",
                ":core:ui -> :download:api",
                ":core:testing -> :core:(domain|model|common|database|navigation)",
                ":core:testing -> :(playback|download|youtube|sync):api",
                ":core:testing -> :sync:protocol",
                ":(playback|download|youtube|sync):api -> :core:(model|common)",
                ":core:(database|datastore) -> :core:(model|common)",
                ":core:network -> :core:(model|common|network:okhttp)",
            )
        restricted =
            arrayOf(
                ":feature:.* -X> :feature:.*",
                ":(?!app).* -X> :(youtube:ytdlp|playback:impl)",
                ":(?!desktopApp).* -X> :(youtube:ytdlp-desktop|playback:desktop)",
                ":(?!desktopApp|playback:desktop).* -X> :(playback:engine|desktop:system)",
                ":(?!desktopApp|playback:engine|desktop:system).* -X> :playback:native",
                ":.* -X> :(app|desktopApp|sync:server)",
                ":.* -X> :core:testing", // main configurations only; test edges are not asserted (rule 13)
                ":(playback|download|youtube|sync):(impl|ytdlp|ytdlp-desktop|desktop|engine) -X> :core:data",
                ":sync:server -X> :(core|feature|playback|download|youtube|desktop):.*",
                ":(feeds|sync:protocol) -X> :.*",
            )
    }
}
