// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.text

import java.net.IDN
import java.text.Normalizer

/** Desktop actual: the same JVM calls the Android actual uses, so keys match across devices. */
public actual fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

public actual fun String.nfkc(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)

public actual fun String.idnaToAsciiOrNull(): String? =
    try {
        IDN.toASCII(this)
    } catch (_: IllegalArgumentException) {
        null
    }
