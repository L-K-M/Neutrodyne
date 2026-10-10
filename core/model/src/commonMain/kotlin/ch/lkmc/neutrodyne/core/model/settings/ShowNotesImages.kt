// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/**
 * When show-notes images are fetched (03 Images and links, PO-21): [TAP_TO_LOAD] is the privacy
 * default — image hosts learn nothing until a tap; on the desktop [WIFI_ONLY] behaves like
 * [ALWAYS] because every desktop network counts as unmetered.
 */
enum class ShowNotesImages { ALWAYS, WIFI_ONLY, TAP_TO_LOAD }
