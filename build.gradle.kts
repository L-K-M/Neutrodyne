// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.quality)
    alias(libs.plugins.detekt) apply false
}

// Room 3.0.3's connection pool retries forever when a caller's timeout cancels a waiting
// acquisition (TimeoutCancellationException misclassification). Until upstream ships the
// fix, every request for androidx.room3:room3-runtime — direct or transitive via
// room3-paging/room3-testing — resolves to the source-built patched artifact in
// third_party/room3-maven (see third_party/room3/README.md). Same 3.0.3 API surface.
// The vendored rebuild exists only at 3.0.3, and upstream still carries the defect: a
// room3 bump without a republished rebuild must fail loudly rather than bind new room3
// siblings to a stale patched runtime — or self-disable the rule and silently drop the
// verified patch.
val patchedRoom3Version = "3.0.3"
check(libs.versions.room3.get() == patchedRoom3Version) {
    "libs.versions.toml pins room3 = ${libs.versions.room3.get()} but " +
        "third_party/room3-maven only contains the patched $patchedRoom3Version runtime. " +
        "Keep room3 at $patchedRoom3Version, rebuild and republish the patch via " +
        "third_party/room3, or remove this substitution (and third_party/room3*) once " +
        "upstream ships the acquisition-timeout fix."
}
allprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("androidx.room3:room3-runtime"))
                .using(module("androidx.room3:room3-runtime-rebuild:3.0.3"))
                .because("Room 3.0.3 acquisition cancellation fix; see third_party/room3/README.md")
        }
    }
}
