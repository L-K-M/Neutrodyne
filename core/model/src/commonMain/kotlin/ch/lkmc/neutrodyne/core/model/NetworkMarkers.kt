// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * Internal request marker for fetches the user never submitted (03 note 2026-11-02): add
 * previews, autodiscovery candidates and probes, directory-hit lookups and their artwork.
 * Callers set it as a header; the network island promotes it to an OkHttp tag and strips it
 * before the wire, so no peer ever sees it — the same pattern the directory-origin marker
 * uses (01 Interceptors). Marked calls may only reach public peers: the network-layer guard
 * refuses any loopback, private, link-local, ULA, unspecified or IPv4-mapped-local
 * connection, at every redirect hop.
 */
public const val PRE_CONFIRMATION_HEADER: String = "X-Neutrodyne-Pre-Confirmation"
