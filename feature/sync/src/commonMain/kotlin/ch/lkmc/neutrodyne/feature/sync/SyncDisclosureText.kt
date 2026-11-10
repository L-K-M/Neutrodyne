// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_disclosure_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_encrypted
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_not_encrypted
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_private_feeds
import ch.lkmc.neutrodyne.sync.api.SyncDisclosure
import org.jetbrains.compose.resources.stringResource

/**
 * The disclosure text (08 Linking a device, R1.9, N3; 10 Privacy and disclosure), shared by the
 * pre-link card and the linked "What the server stores" dialog: [title] names the host in the
 * caller's tense ("will store" before the link, "stores" while linked), then the same body —
 * the private-feed count, readable data without end-to-end encryption, feed passwords only with
 * Share feed passwords, audio/downloads/artwork never sent — and the transport line.
 */
@Composable
internal fun DisclosureText(
    title: String,
    disclosure: SyncDisclosure,
    modifier: Modifier = Modifier,
) {
    val privateFeeds =
        if (disclosure.privateFeedLinks > 0) {
            stringResource(Res.string.sync_setup_private_feeds, disclosure.privateFeedLinks)
        } else {
            ""
        }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(Res.string.sync_setup_disclosure_body, privateFeeds),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                if (disclosure.encrypted) {
                    Res.string.sync_setup_encrypted
                } else {
                    Res.string.sync_setup_not_encrypted
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
