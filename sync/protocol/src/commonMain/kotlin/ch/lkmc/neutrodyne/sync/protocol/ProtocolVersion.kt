// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

/**
 * The Neutrodyne Sync protocol version this build speaks (10 Protocol › Versioning).
 *
 * The version changes only for incompatible changes; additive changes keep version 1 and ship
 * behind a `features` flag instead. The server advertises `[PROTOCOL_VERSION, PROTOCOL_VERSION]`
 * in its discovery document until a compatibility window makes a range necessary.
 */
public const val PROTOCOL_VERSION: Int = 1
