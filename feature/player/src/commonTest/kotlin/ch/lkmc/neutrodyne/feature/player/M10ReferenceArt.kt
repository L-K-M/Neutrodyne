// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import coil3.Image

internal const val REFERENCE_ART_SIDE = 512

/** Owned synthetic gradients, decoded through each target's real bitmap implementation. */
internal expect fun referenceImage(art: ReferenceArt): Image

internal fun referencePixels(art: ReferenceArt): IntArray =
    IntArray(REFERENCE_ART_SIDE * REFERENCE_ART_SIDE) { index ->
        val x = index % REFERENCE_ART_SIDE
        val y = index / REFERENCE_ART_SIDE
        val diagonal = (x + y) * 255 / (2 * (REFERENCE_ART_SIDE - 1))
        val channels =
            when (art) {
                ReferenceArt.COLOURFUL -> {
                    Triple(
                        16 + x * 64 / REFERENCE_ART_SIDE,
                        40 + y * 100 / REFERENCE_ART_SIDE,
                        180 + diagonal * 75 / 255,
                    )
                }

                ReferenceArt.MONOCHROME -> {
                    (32 + diagonal * 192 / 255).let { Triple(it, it, it) }
                }

                ReferenceArt.LIGHT -> {
                    Triple(
                        250 + x * 5 / REFERENCE_ART_SIDE,
                        250 + y * 5 / REFERENCE_ART_SIDE,
                        190 + diagonal * 28 / 255,
                    )
                }
            }
        -0x1000000 or (channels.first shl 16) or (channels.second shl 8) or channels.third
    }
