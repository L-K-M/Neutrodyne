// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.ui.NetErrorText
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_title
import ch.lkmc.neutrodyne.core.ui.resources.add_error_apple_only
import ch.lkmc.neutrodyne.core.ui.resources.add_error_directory_busy
import ch.lkmc.neutrodyne.core.ui.resources.add_error_http
import ch.lkmc.neutrodyne.core.ui.resources.add_error_invalid_url
import ch.lkmc.neutrodyne.core.ui.resources.add_error_list_feed
import ch.lkmc.neutrodyne.core.ui.resources.add_error_malformed
import ch.lkmc.neutrodyne.core.ui.resources.add_error_not_a_url
import ch.lkmc.neutrodyne.core.ui.resources.add_error_spotify
import ch.lkmc.neutrodyne.core.ui.resources.add_error_youtube
import ch.lkmc.neutrodyne.core.ui.resources.add_import_later
import ch.lkmc.neutrodyne.core.ui.resources.add_subscription_list
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_no_media
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_not_a_feed
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_storage
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_too_large
import ch.lkmc.neutrodyne.core.ui.resources.net_other

/**
 * 08 Add podcast sheet — `AddPodcastError`/`SubscribeError` → `UiText`. `NotAUrl` carries a query
 * the sheet routes to "Search for …", so its line is a gentle nudge, not an error colour.
 */
internal object AddPodcastText {
    fun describe(error: AddPodcastError): UiText =
        when (error) {
            is AddPodcastError.NotAUrl -> {
                UiText.Res(Res.string.add_error_not_a_url)
            }

            AddPodcastError.InvalidUrl -> {
                UiText.Res(Res.string.add_error_invalid_url)
            }

            is AddPodcastError.Network -> {
                NetErrorText.describe(error.error)
            }

            is AddPodcastError.Http -> {
                UiText.Res(Res.string.add_error_http, listOf(error.code))
            }

            is AddPodcastError.AuthRequired -> {
                UiText.Res(Res.string.add_auth_title)
            }

            AddPodcastError.NotAFeed -> {
                UiText.Res(Res.string.feed_err_not_a_feed)
            }

            AddPodcastError.NoMedia -> {
                UiText.Res(Res.string.feed_err_no_media)
            }

            AddPodcastError.TooLarge -> {
                UiText.Res(Res.string.feed_err_too_large)
            }

            AddPodcastError.Malformed -> {
                UiText.Res(Res.string.add_error_malformed)
            }

            AddPodcastError.UnsupportedListFeed -> {
                UiText.Res(Res.string.add_error_list_feed)
            }

            is AddPodcastError.SubscriptionList -> {
                UiText.Joined(
                    listOf(
                        UiText.Res(Res.string.add_subscription_list),
                        UiText.Res(Res.string.add_import_later),
                    ),
                    separator = " ",
                    suffix = "",
                )
            }

            AddPodcastError.AppleOnlyShow -> {
                UiText.Res(Res.string.add_error_apple_only)
            }

            AddPodcastError.SpotifyShow -> {
                UiText.Res(Res.string.add_error_spotify)
            }

            AddPodcastError.DirectoryBusy -> {
                UiText.Res(Res.string.add_error_directory_busy)
            }

            AddPodcastError.YouTubeNotYetSupported -> {
                UiText.Res(Res.string.add_error_youtube)
            }
        }
}

/** `SubscribeError` → `UiText`; [alreadyText]/[mismatchText] come from the sheet's call site. */
internal object SubscribeErrorText {
    fun describe(error: SubscribeError): UiText =
        when (error) {
            is SubscribeError.Fetch -> AddPodcastText.describe(error.error)

            SubscribeError.NoMedia -> UiText.Res(Res.string.feed_err_no_media)

            SubscribeError.Storage -> UiText.Res(Res.string.feed_err_storage)

            // The sheet handles AlreadySubscribed inline (it has the podcastId to open).
            is SubscribeError.AlreadySubscribed -> UiText.Res(Res.string.net_other)
        }
}
