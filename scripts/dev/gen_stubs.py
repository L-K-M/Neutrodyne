#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""One-off generator of the M0a module stubs (01 M0 scaffold checklist step 7). Skips existing files."""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPDX = "// SPDX-License-Identifier: Unlicense\n"

KMP = "kmp"; ISLAND = "island"; DESKTOP = "desktop"; ANDROID = "android"; FEATURE = "feature"; COMPOSE = "compose"

# path: (kind, extra plugins, commonMain project deps (api=True/False), platform project deps, libraries)
M = {
 ":core:model": (KMP, ["serialization"], [], [], ["kotlinx-serialization-json", "kotlinx-collections-immutable", "kotlinx-datetime"]),
 ":core:common": (KMP, ["metro"], [], [], ["kotlinx-coroutines-core", "kotlinx-datetime"]),
 ":core:domain": (KMP, ["metro"], [(":core:model", True), (":core:common", True), (":playback:api", True), (":download:api", True), (":youtube:api", True)], [], ["api:androidx-paging-common", "kotlinx-coroutines-core"]),
 ":core:navigation": (KMP, ["serialization"], [], [], ["api:navigation3-runtime", "api:cmp-runtime", "kotlinx-serialization-json"]),
 ":core:database": (KMP, ["metro", "room"], [(":core:model", False), (":core:common", False)], [], ["kotlinx-serialization-json"]),
 ":core:datastore": (KMP, ["metro"], [(":core:model", False), (":core:common", False)], [], ["androidx-datastore-preferences-core", "okio"]),
 ":core:network": (KMP, ["metro"], [(":core:model", False), (":core:common", False)], [":core:network:okhttp"], ["bom-api:ktor-bom", "api:ktor-client-core", "ktor-client-content-negotiation", "ktor-serialization-kotlinx-json", "platform:ktor-client-okhttp"]),
 ":core:network:okhttp": (ISLAND, [], [(":core:model", False), (":core:common", False)], [], ["bom-api:okhttp-bom", "api:okhttp", "okhttp-coroutines", "okio"]),
 ":core:data": (KMP, ["metro", "serialization"], [(m, False) for m in [":core:domain", ":core:model", ":core:common", ":core:database", ":core:datastore", ":core:network", ":core:artwork", ":feeds", ":youtube:api"]], [":feeds:jvm"], ["kotlinx-serialization-json", "kotlinx-datetime", "okio"]),
 ":core:artwork": (KMP, ["metro"], [(m, False) for m in [":core:model", ":core:common", ":core:database", ":core:network", ":youtube:api"]], [":core:network:okhttp"], ["bom:coil-bom", "coil-core", "okio", "platform:coil-network-okhttp"]),
 ":core:designsystem": (COMPOSE, [], [(":core:model", False)], [], ["cmp-material3-navigationSuite", "graphics-shapes", "bom:coil-bom", "coil-compose", "kotlinx-collections-immutable"]),
 ":core:ui": (COMPOSE, [], [(m, False) for m in [":core:designsystem", ":core:model", ":core:common", ":core:navigation", ":download:api"]], [], ["bom:coil-bom", "coil-compose", "kotlinx-collections-immutable", "navigation3-ui-jb", "cmp-material3-adaptive-navigation3", "lifecycle-viewmodel-compose", "lifecycle-runtime-compose", "lifecycle-viewmodel-navigation3", "metrox-viewmodel-compose"]),
 ":core:testing": (KMP, ["metro"], [(m, False) for m in [":core:domain", ":core:model", ":core:common", ":core:database", ":core:navigation", ":playback:api", ":download:api", ":youtube:api", ":sync:api", ":sync:protocol"]], [], ["api:kotlin-test", "api:kotlinx-coroutines-test", "api:turbine", "bom:coil-bom", "api:coil-test", "platform:junit4", "platform:truth"]),
 ":feeds": (KMP, ["serialization"], [], [], ["kotlinx-serialization-json", "kotlinx-datetime"]),
 ":feeds:jvm": (ISLAND, [], [(":feeds", False)], [], ["jsoup", "compileOnly:kxml2", "testImplementation:kxml2"]),
 ":playback:api": (KMP, ["metro"], [(":core:model", False), (":core:common", False)], [], ["kotlinx-coroutines-core"]),
 ":playback:core": (KMP, ["metro"], [(m, False) for m in [":playback:api", ":core:domain", ":core:model", ":core:common"]], [], ["kotlinx-coroutines-core"]),
 ":playback:impl": (ANDROID, [], [(m, False) for m in [":playback:core", ":playback:api", ":download:api", ":youtube:api", ":core:domain", ":core:model", ":core:common", ":core:database", ":core:datastore", ":core:network", ":core:artwork", ":core:network:okhttp"]], [], []),
 ":playback:engine": (DESKTOP, [], [(m, False) for m in [":playback:native", ":playback:api", ":core:network:okhttp", ":core:model", ":core:common"]], [], ["okio"]),
 ":playback:native": (DESKTOP, ["native"], [], [], []),
 ":playback:desktop": (DESKTOP, [], [(m, False) for m in [":playback:core", ":playback:api", ":playback:engine", ":desktop:system", ":download:api", ":youtube:api", ":core:domain", ":core:model", ":core:common", ":core:artwork", ":core:database", ":core:datastore"]], [], []),
 ":desktop:system": (DESKTOP, [], [(m, False) for m in [":playback:native", ":playback:api", ":core:model", ":core:common"]], [], []),
 ":download:api": (KMP, ["metro"], [(":core:model", False), (":core:common", False)], [], ["kotlinx-coroutines-core"]),
 ":download:impl": (KMP, ["metro"], [(m, False) for m in [":download:api", ":youtube:api", ":core:domain", ":core:model", ":core:common", ":core:database", ":core:datastore", ":core:network", ":core:artwork"]], [], []),
 ":youtube:api": (KMP, ["metro"], [(":core:model", False), (":core:common", False)], [], ["kotlinx-coroutines-core"]),
 ":youtube:impl": (KMP, ["metro"], [(m, False) for m in [":youtube:api", ":core:model", ":core:common", ":core:network"]], [], []),
 ":youtube:engine": (ISLAND, [], [(m, False) for m in [":youtube:api", ":core:model", ":core:common"]], [], ["kotlinx-serialization-json", "kotlinx-coroutines-core", "okio"]),
 ":youtube:ytdlp": (ANDROID, [], [(m, False) for m in [":youtube:engine", ":youtube:api", ":core:model", ":core:common", ":core:datastore", ":core:network:okhttp"]], [], []),
 ":youtube:ytdlp-desktop": (DESKTOP, [], [(m, False) for m in [":youtube:engine", ":youtube:api", ":core:model", ":core:common", ":core:datastore"]], [], []),
 ":sync:protocol": (KMP, ["serialization"], [], [], ["kotlinx-serialization-json"]),
 ":sync:api": (KMP, ["metro"], [(":core:model", False), (":core:common", False)], [], ["kotlinx-coroutines-core"]),
 ":sync:impl": (KMP, ["metro", "serialization"], [(m, False) for m in [":sync:api", ":sync:protocol", ":core:domain", ":core:model", ":core:common", ":core:database", ":core:datastore", ":core:network", ":feeds"]], [], []),
}
FEATURE_APIS = {
 "feeds": [":playback:api", ":download:api", ":youtube:api"],
 "library": [":playback:api", ":download:api", ":youtube:api"],
 "groups": [":youtube:api"],
 "podcast": [":playback:api", ":download:api", ":youtube:api"],
 "episode": [":playback:api", ":download:api", ":youtube:api"],
 "player": [":playback:api", ":download:api", ":youtube:api"],
 "queue": [":playback:api", ":download:api", ":youtube:api"],
 "downloads": [":download:api", ":playback:api", ":youtube:api"],
 "discover": [":youtube:api"],
 "importexport": [":playback:api", ":download:api", ":youtube:api"],
 "settings": [":youtube:api"],
 "sync": [":sync:api"],
}
for f, apis in FEATURE_APIS.items():
    M[":feature:" + f] = (FEATURE, [], [(a, False) for a in apis], [], ["aboutlibraries-core"] if f == "settings" else [])

def pkg(path): return "ch.lkmc.neutrodyne" + path.replace(":", ".").replace("-", "")
def acc(alias): return "libs." + alias.replace("-", ".")
def d(path): return os.path.join(ROOT, *path.strip(":").split(":"))

def write(p, s):
    if os.path.exists(p): return
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(s)

def lib_lines(libs, kmp):
    common, platform, top = [], [], []
    for l in libs:
        scope, _, alias = l.rpartition(":")
        if scope == "bom": (common if kmp else top).append(f"implementation(project.dependencies.platform({acc(alias)}))" if kmp else f"implementation(platform({acc(alias)}))")
        elif scope == "bom-api": (common if kmp else top).append(f"api(project.dependencies.platform({acc(alias)}))" if kmp else f"api(platform({acc(alias)}))")
        elif scope == "api": (common if kmp else top).append(f"api({acc(alias)})")
        elif scope == "platform": platform.append(f"implementation({acc(alias)})")
        elif scope: (top).append(f"{scope}({acc(alias)})")
        else: (common if kmp else top).append(f"implementation({acc(alias)})")
    return common, platform, top

def gen(path, spec):
    kind, extra, deps, pdeps, libs = spec
    root = d(path); p = pkg(path); pdir = p.replace(".", "/")
    plugins = {KMP: "neutrodyne.kmp.library", COMPOSE: "neutrodyne.kmp.compose", FEATURE: "neutrodyne.kmp.feature",
               ISLAND: "neutrodyne.jvm.island", DESKTOP: "neutrodyne.desktop.library", ANDROID: "neutrodyne.android.library"}[kind]
    pl = [f"    alias(libs.plugins.{plugins.replace('-', '.')})"]
    for e in extra:
        pl.append({"metro": "    alias(libs.plugins.neutrodyne.metro)", "room": "    alias(libs.plugins.neutrodyne.room)",
                   "serialization": '    id("org.jetbrains.kotlin.plugin.serialization")',
                   "native": "    alias(libs.plugins.neutrodyne.desktop.native)"}[e])
    kmp = kind in (KMP, COMPOSE, FEATURE)
    common, platform, top = lib_lines(libs, kmp)
    if kmp:
        cm = [f"            {'api' if a else 'implementation'}(project(\"{m}\"))" for m, a in deps] + ["            " + c for c in common]
        body = "kotlin {\n    sourceSets {\n"
        if cm: body += "        commonMain.dependencies {\n" + "\n".join(cm) + "\n        }\n"
        plat = [f"            implementation(project(\"{m}\"))" for m in pdeps] + ["            " + c for c in platform]
        if plat:
            for ss in ("androidMain", "desktopMain"):
                extra_bom = []
                if any("coil" in x for x in platform): extra_bom.append("            implementation(project.dependencies.platform(libs.coil.bom))")
                if any("ktor" in x for x in platform): extra_bom.append("            implementation(project.dependencies.platform(libs.ktor.bom))")
                body += f"        {ss}.dependencies {{\n" + "\n".join(extra_bom + plat) + "\n        }\n"
        body += "    }\n}\n"
        if not cm and not plat: body = ""
        src = os.path.join(root, "src/commonMain/kotlin", pdir); test = os.path.join(root, "src/commonTest/kotlin", pdir)
    else:
        lines = [f"    {'api' if a else 'implementation'}(project(\"{m}\"))" for m, a in deps] + ["    " + t for t in top]
        body = ("dependencies {\n" + "\n".join(lines) + "\n}\n") if lines else ""
        if kind == ANDROID:
            src = os.path.join(root, "src/main/kotlin", pdir); test = os.path.join(root, "src/test/kotlin", pdir)
        else:
            src = os.path.join(root, "src/main/kotlin", pdir); test = os.path.join(root, "src/test/kotlin", pdir)
    write(os.path.join(root, "build.gradle.kts"), SPDX + "plugins {\n" + "\n".join(pl) + "\n}\n" + ("\n" + body if body else ""))
    write(os.path.join(src, "ModuleInfo.kt"), SPDX + f"package {p}\n\n/** Module stub (M0a); content arrives with its milestone (01 Module layout). */\ninternal object ModuleInfo {{\n    const val PATH: String = \"{path}\"\n}}\n")
    if kind == ANDROID or kind == ISLAND or kind == DESKTOP:
        timport = "import org.junit.Assert.assertEquals\nimport org.junit.Test\n"
        tbody = "    @Test\n    fun pathMatchesModule() {\n        assertEquals(\"" + path + "\", ModuleInfo.PATH)\n    }\n"
    else:
        timport = "import kotlin.test.Test\nimport kotlin.test.assertEquals\n"
        tbody = "    @Test\n    fun pathMatchesModule() {\n        assertEquals(\"" + path + "\", ModuleInfo.PATH)\n    }\n"
    write(os.path.join(test, "ModuleInfoTest.kt"), SPDX + f"package {p}\n\n{timport}\nclass ModuleInfoTest {{\n{tbody}}}\n")

for path, spec in M.items():
    gen(path, spec)
print("generated", len(M))
