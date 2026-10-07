// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import java.text.Normalizer

actual object Nfc {
    actual fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
}
