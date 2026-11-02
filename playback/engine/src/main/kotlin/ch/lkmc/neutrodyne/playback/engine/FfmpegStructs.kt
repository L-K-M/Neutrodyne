// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Typed readers for the public struct fields the bindings touch (11 FFM
 * bindings). Every offset comes from [FfmpegLayout], which `ffoffsets.c`
 * generated from the very headers of the build being loaded, so a same-major
 * FFmpeg rebuild whose layout shifted still reads the right bytes.
 *
 * These are the only struct fields the design does not reach through an FFmpeg
 * function or an AVOption: FFmpeg has had no accessors for them since 4.0.
 */
internal fun MemorySegment.readInt(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): Int = get(ValueLayout.JAVA_INT, layout.offset(struct, field))

internal fun MemorySegment.readLong(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): Long = get(ValueLayout.JAVA_LONG, layout.offset(struct, field))

internal fun MemorySegment.readAddress(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): MemorySegment = get(ValueLayout.ADDRESS, layout.offset(struct, field))

internal fun MemorySegment.writeInt(
    layout: FfmpegLayout,
    struct: String,
    field: String,
    value: Int,
) {
    set(ValueLayout.JAVA_INT, layout.offset(struct, field), value)
}

internal fun MemorySegment.writeAddress(
    layout: FfmpegLayout,
    struct: String,
    field: String,
    value: MemorySegment,
) {
    set(ValueLayout.ADDRESS, layout.offset(struct, field), value)
}

/** `AVRational`: two ints at the field's offset. */
internal fun MemorySegment.readRational(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): Pair<Int, Int> {
    val base = layout.offset(struct, field)
    return get(ValueLayout.JAVA_INT, base) to get(ValueLayout.JAVA_INT, base + Int.SIZE_BYTES)
}

/**
 * `AVChannelLayout`'s native mask, or null when the layout is not a native one
 * (custom or unspecified), in which case the resampler is configured by channel
 * count instead.
 */
internal fun MemorySegment.readChannelMask(layout: FfmpegLayout): Long? {
    val order = readInt(layout, "AVChannelLayout", "order")
    if (order != layout.intConstant("AV_CHANNEL_ORDER_NATIVE")) return null
    return readLong(layout, "AVChannelLayout", "u")
}

internal fun MemorySegment.readChannelCount(layout: FfmpegLayout): Int =
    readInt(layout, "AVChannelLayout", "nb_channels")

/**
 * The frame's `uint8_t **extended_data`: passed straight to `swr_convert`, which
 * is what lets the bindings avoid dereferencing a plane pointer themselves.
 */
internal fun MemorySegment.readExtendedData(layout: FfmpegLayout): MemorySegment =
    readAddress(layout, "AVFrame", "extended_data")

/** True when a pointer read out of native memory is null. */
internal fun MemorySegment.isNull(): Boolean = address() == 0L

/** True when a pointer read out of native memory is usable. */
internal fun MemorySegment.isPresent(): Boolean = !isNull()

/** The pointer a slot holds, or null when FFmpeg has released it. */
internal fun MemorySegment.dereference(): MemorySegment? = get(ValueLayout.ADDRESS, 0L).takeIf { it.address() != 0L }

/**
 * Element [index] of a native array of pointers, such as `AVFormatContext.streams`
 * or `chapters`. The array's own address carries no length, so the extent is
 * widened to the element being read.
 */
internal fun MemorySegment.elementAt(index: Int): MemorySegment =
    reinterpret((index + 1).toLong() * Long.SIZE_BYTES).get(ValueLayout.ADDRESS, index.toLong() * Long.SIZE_BYTES)
