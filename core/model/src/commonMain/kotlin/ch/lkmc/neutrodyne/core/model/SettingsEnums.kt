// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * The values of the `networkPolicy` / `autoDownloadNetwork` override columns (02 `podcast_settings`,
 * `podcast_group_settings`): `NULL` in the column means "inherit"; these values pin a policy.
 */
enum class NetworkPolicy { UNMETERED, ANY }

/** The values of the `deleteAfter` override columns (02; `NULL` means "inherit"). */
enum class DeleteAfter { IMMEDIATELY, AFTER_24H, NEVER }
