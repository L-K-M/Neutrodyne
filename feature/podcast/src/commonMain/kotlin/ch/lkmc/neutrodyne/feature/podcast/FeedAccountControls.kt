// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

/**
 * M1b gate for the feed-account controls (08 Podcast settings, dated deviation 2026-10-08):
 * `PodcastRepository.editFeedUrl`/`setCredentials`/`merge` are M1b semantics and the M1a
 * implementations still throw `UnsupportedOperationException`, so every control that would call
 * them — Podcast settings' "Edit feed address" and "Username and password" rows and the detail
 * banner's "Enter password" action — stays hidden while this is `false`. M1b flips it.
 */
internal const val FEED_ACCOUNT_CONTROLS_ENABLED: Boolean = false
