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
allprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("androidx.room3:room3-runtime"))
                .using(module("androidx.room3:room3-runtime-rebuild:3.0.3"))
                .because("Room 3.0.3 acquisition cancellation fix; see third_party/room3/README.md")
        }
    }
}
