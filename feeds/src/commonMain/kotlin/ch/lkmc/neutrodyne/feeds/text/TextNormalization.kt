// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.text

// The Unicode and IDNA helpers `:feeds` needs (03 Package layout; 03 open question 13). commonMain
// cannot see the JVM classes directly, so these are expects whose two actuals are the same JVM
// calls: Android, the desktop and the sync server therefore normalise identically.

/** Unicode NFC normalisation (05's `GroupNames` compares NFC forms). */
public expect fun String.nfc(): String

/** Unicode NFKC normalisation ([TitleMatch][ch.lkmc.neutrodyne.feeds.identity.TitleMatch]). */
public expect fun String.nfkc(): String

/**
 * IDNA `toASCII` for URL hosts ([UrlNormalizer][ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer]);
 * null when the host cannot be converted (IDN's `toASCII` throws).
 */
public expect fun String.idnaToAsciiOrNull(): String?
