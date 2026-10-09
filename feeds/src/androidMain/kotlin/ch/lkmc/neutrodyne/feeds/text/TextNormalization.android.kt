// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.text

import java.net.IDN
import java.text.Normalizer

/**
 * Android actual: the JVM calls (android.jar carries both classes). On Android these are
 * libcore/ICU-backed while the desktop JVM actual uses the JDK's own implementations, so
 * identical output across targets holds only as far as the shared fixed vectors pin it —
 * keep non-ASCII/punycode hosts and NFKC-sensitive strings in the commonTest vectors.
 */
public actual fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

public actual fun String.nfkc(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)

public actual fun String.idnaToAsciiOrNull(): String? =
    try {
        IDN.toASCII(this)
    } catch (_: IllegalArgumentException) {
        null
    }
