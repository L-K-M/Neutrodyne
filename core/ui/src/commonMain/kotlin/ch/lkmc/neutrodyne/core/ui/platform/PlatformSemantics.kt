// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.ui.Modifier

/**
 * Sets `testTagsAsResourceId` where the platform has it (08 Automated checks: Android UI tests look
 * tags up as resource IDs). CMP exposes the semantics property on Android only, so this is an
 * expect/actual — the desktop actual is a no-op (deviation recorded in 08 "Navigation").
 */
public expect fun Modifier.testTagsAsResourceId(): Modifier
