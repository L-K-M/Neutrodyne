// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import dev.zacsweers.metro.BindingContainer

/**
 * The one place that binds `:youtube:api` interfaces on Android (01 YouTube bindings). Empty until its first
 * consumer: M2 adds `YouTubeCapabilitiesSource`; from M9a the file moves into `src/youtubeEngine/` and
 * `src/noYouTubeEngine/`.
 */
@BindingContainer
interface YouTubeBindingsModule
