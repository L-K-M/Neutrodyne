// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.privacy_add_input_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_add_input_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_app_updates_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_app_updates_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_apple_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_apple_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_artwork_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_artwork_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_chapters_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_chapters_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_feeds_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_feeds_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_fyyd_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_fyyd_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_issue_tracker_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_issue_tracker_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_links_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_links_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_media_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_media_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_notes_images_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_notes_images_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_podcastindex_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_podcastindex_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_server_updates_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_server_updates_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_sync_server_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_sync_server_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_youtube_engine_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_youtube_engine_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_youtube_streams_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_youtube_streams_title
import ch.lkmc.neutrodyne.core.ui.resources.privacy_youtube_subscriptions_body
import ch.lkmc.neutrodyne.core.ui.resources.privacy_youtube_subscriptions_title
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource

/** Stable IDs shared by the rendered Settings inventory and PRIVACY.md. */
public data class PrivacyDestination(
    val id: String,
    val title: StringResource,
    val body: StringResource,
    val provider: ProviderId? = null,
)

public object PrivacyInventory {
    public val destinations =
        persistentListOf(
            PrivacyDestination(
                "feeds",
                Res.string.privacy_feeds_title,
                Res.string.privacy_feeds_body,
            ),
            PrivacyDestination(
                "add-input",
                Res.string.privacy_add_input_title,
                Res.string.privacy_add_input_body,
            ),
            PrivacyDestination(
                "media",
                Res.string.privacy_media_title,
                Res.string.privacy_media_body,
            ),
            PrivacyDestination(
                "artwork",
                Res.string.privacy_artwork_title,
                Res.string.privacy_artwork_body,
            ),
            PrivacyDestination(
                "chapters",
                Res.string.privacy_chapters_title,
                Res.string.privacy_chapters_body,
            ),
            PrivacyDestination(
                "notes-images",
                Res.string.privacy_notes_images_title,
                Res.string.privacy_notes_images_body,
            ),
            PrivacyDestination(
                "apple",
                Res.string.privacy_apple_title,
                Res.string.privacy_apple_body,
                ProviderId.APPLE,
            ),
            PrivacyDestination(
                "fyyd",
                Res.string.privacy_fyyd_title,
                Res.string.privacy_fyyd_body,
                ProviderId.FYYD,
            ),
            PrivacyDestination(
                "podcastindex",
                Res.string.privacy_podcastindex_title,
                Res.string.privacy_podcastindex_body,
                ProviderId.PODCAST_INDEX,
            ),
            PrivacyDestination(
                "youtube-subscriptions",
                Res.string.privacy_youtube_subscriptions_title,
                Res.string.privacy_youtube_subscriptions_body,
            ),
            PrivacyDestination(
                "youtube-streams",
                Res.string.privacy_youtube_streams_title,
                Res.string.privacy_youtube_streams_body,
            ),
            PrivacyDestination(
                "youtube-engine",
                Res.string.privacy_youtube_engine_title,
                Res.string.privacy_youtube_engine_body,
            ),
            PrivacyDestination(
                "app-updates",
                Res.string.privacy_app_updates_title,
                Res.string.privacy_app_updates_body,
            ),
            PrivacyDestination(
                "sync-server",
                Res.string.privacy_sync_server_title,
                Res.string.privacy_sync_server_body,
            ),
            PrivacyDestination(
                "links",
                Res.string.privacy_links_title,
                Res.string.privacy_links_body,
            ),
            PrivacyDestination(
                "issue-tracker",
                Res.string.privacy_issue_tracker_title,
                Res.string.privacy_issue_tracker_body,
            ),
            PrivacyDestination(
                "server-updates",
                Res.string.privacy_server_updates_title,
                Res.string.privacy_server_updates_body,
            ),
        )
}
