// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.ui.NetErrorText
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.add_error_apple_only
import ch.lkmc.neutrodyne.core.ui.resources.add_error_directory_busy
import ch.lkmc.neutrodyne.core.ui.resources.add_error_http
import ch.lkmc.neutrodyne.core.ui.resources.add_error_invalid_url
import ch.lkmc.neutrodyne.core.ui.resources.add_error_list_feed
import ch.lkmc.neutrodyne.core.ui.resources.add_error_malformed
import ch.lkmc.neutrodyne.core.ui.resources.add_error_not_a_url
import ch.lkmc.neutrodyne.core.ui.resources.add_error_spotify
import ch.lkmc.neutrodyne.core.ui.resources.add_error_youtube
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_title
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_no_media
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_not_a_feed
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_too_large

/**
 * `AddPodcastError` → `UiText` for the credentials/edit-URL snackbars (08 String mappers).
 * `:core:ui` cannot depend on `:core:domain`, so the mapping lives in the feature module — the
 * sheet's own copy is `:feature:discover`'s `AddPodcastText` (modules cannot see each other).
 */
internal fun addPodcastErrorText(error: AddPodcastError): UiText =
    when (error) {
        is AddPodcastError.NotAUrl -> UiText.Res(Res.string.add_error_not_a_url)
        AddPodcastError.InvalidUrl -> UiText.Res(Res.string.add_error_invalid_url)
        is AddPodcastError.Network -> NetErrorText.describe(error.error)
        is AddPodcastError.Http -> UiText.Res(Res.string.add_error_http, listOf(error.code))
        is AddPodcastError.AuthRequired -> UiText.Res(Res.string.add_auth_title)
        AddPodcastError.NotAFeed -> UiText.Res(Res.string.feed_err_not_a_feed)
        AddPodcastError.NoMedia -> UiText.Res(Res.string.feed_err_no_media)
        AddPodcastError.TooLarge -> UiText.Res(Res.string.feed_err_too_large)
        AddPodcastError.Malformed -> UiText.Res(Res.string.add_error_malformed)
        AddPodcastError.UnsupportedListFeed -> UiText.Res(Res.string.add_error_list_feed)
        is AddPodcastError.SubscriptionList -> UiText.Res(Res.string.add_error_list_feed)
        AddPodcastError.AppleOnlyShow -> UiText.Res(Res.string.add_error_apple_only)
        AddPodcastError.SpotifyShow -> UiText.Res(Res.string.add_error_spotify)
        AddPodcastError.DirectoryBusy -> UiText.Res(Res.string.add_error_directory_busy)
        AddPodcastError.YouTubeNotYetSupported -> UiText.Res(Res.string.add_error_youtube)
    }
