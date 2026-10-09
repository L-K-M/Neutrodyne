// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.text

import java.net.IDN
import java.text.Normalizer

/** Android actual: the JVM calls (android.jar carries both classes). */
public actual fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

public actual fun String.nfkc(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)

public actual fun String.idnaToAsciiOrNull(): String? =
    try {
        IDN.toASCII(this)
    } catch (_: IllegalArgumentException) {
        null
    }
