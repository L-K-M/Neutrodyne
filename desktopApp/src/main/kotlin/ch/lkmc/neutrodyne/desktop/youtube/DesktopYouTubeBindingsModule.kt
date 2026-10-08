// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import dev.zacsweers.metro.BindingContainer

/**
 * The one place that binds `:youtube:api` interfaces on the desktop (01 YouTube bindings). Empty
 * until its first consumer: M2 adds `YouTubeCapabilitiesSource`; from MD3 the file moves into
 * `src/youtubeEngine/` and `src/noYouTubeEngine/`. Public because the graph includes it by name
 * (S8: a container a graph includes must be public).
 */
@BindingContainer
interface DesktopYouTubeBindingsModule
