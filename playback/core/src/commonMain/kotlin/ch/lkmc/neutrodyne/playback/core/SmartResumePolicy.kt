// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

/** The adapter resolves the live preference; cold-start callers retain their existing default. */
enum class SmartResumePolicy { ENABLED, DISABLED }
