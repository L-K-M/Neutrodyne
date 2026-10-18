// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `file:` URI handling for the desktop file adapters (05 File inputs / Destinations, row C3a).
 * Desktop inputs arrive as `file://` URIs (11 Routing: import dialog, drop, file association,
 * hand-off); a save destination is one too.
 *
 * The only authorities accepted are the empty one (`file:///…`, `file:/…`) and `localhost` —
 * both unambiguously local. Any other host is rejected outright: on Windows `Paths.get` would
 * resolve `file://host/share/x` as a UNC path, so refusing it keeps all three desktop OSes on
 * the same local-only rule. [Paths.get] also rejects the rest — an opaque `file:x`, a query or
 * fragment, a bad escape — so malformed input never reaches a filesystem call.
 */
internal object FileUris {
    /** The local file [uri] names, or `null` when it is not a usable `file:` URI. */
    fun parse(uri: String): Path? {
        val parsed =
            try {
                URI(uri)
            } catch (_: Exception) {
                return null
            }
        if (parsed.scheme == null || !parsed.scheme.equals(SCHEME, ignoreCase = true)) return null
        val usable =
            when {
                parsed.authority.isNullOrEmpty() -> {
                    parsed
                }

                parsed.authority.equals(LOCALHOST, ignoreCase = true) -> {
                    // Re-mint without the authority; `rawPath` keeps the escapes `Paths.get`
                    // decodes (a decoded path would be re-encoded by the URI constructor).
                    try {
                        URI(SCHEME + "://" + parsed.rawPath)
                    } catch (_: Exception) {
                        return null
                    }
                }

                else -> {
                    return null
                }
            }
        return try {
            Paths.get(usable)
        } catch (_: Exception) {
            null
        }
    }

    /** The `file:` URI for an absolute local [path], percent-escaped (`Path.toUri` is RFC-correct). */
    fun uriFor(path: Path): String = path.toUri().toASCIIString()

    private const val SCHEME = "file"
    private const val LOCALHOST = "localhost"
}
