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
 *
 * A pointer returned by an FFmpeg call or read out of native memory arrives as
 * a zero-length segment, so every accessor first widens the view to the field
 * it is about to touch. Widening reads nothing itself; it only lets the
 * checked access below reach the field's bytes.
 */
private fun MemorySegment.atLeast(bytes: Long): MemorySegment = if (byteSize() >= bytes) this else reinterpret(bytes)

internal fun MemorySegment.readInt(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): Int {
    val offset = layout.offset(struct, field)
    return atLeast(offset + Int.SIZE_BYTES).get(ValueLayout.JAVA_INT, offset)
}

internal fun MemorySegment.readLong(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): Long {
    val offset = layout.offset(struct, field)
    return atLeast(offset + Long.SIZE_BYTES).get(ValueLayout.JAVA_LONG, offset)
}

internal fun MemorySegment.readAddress(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): MemorySegment {
    val offset = layout.offset(struct, field)
    return atLeast(offset + ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, offset)
}

internal fun MemorySegment.writeInt(
    layout: FfmpegLayout,
    struct: String,
    field: String,
    value: Int,
) {
    val offset = layout.offset(struct, field)
    atLeast(offset + Int.SIZE_BYTES).set(ValueLayout.JAVA_INT, offset, value)
}

internal fun MemorySegment.writeAddress(
    layout: FfmpegLayout,
    struct: String,
    field: String,
    value: MemorySegment,
) {
    val offset = layout.offset(struct, field)
    atLeast(offset + ValueLayout.ADDRESS.byteSize()).set(ValueLayout.ADDRESS, offset, value)
}

/** `AVRational`: two ints at the field's offset. */
internal fun MemorySegment.readRational(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): Pair<Int, Int> {
    val base = layout.offset(struct, field)
    val bounds = atLeast(base + 2L * Int.SIZE_BYTES)
    return bounds.get(ValueLayout.JAVA_INT, base) to
        bounds.get(ValueLayout.JAVA_INT, base + Int.SIZE_BYTES)
}

/**
 * `AVChannelLayout` is embedded inside its parent struct ([field] names where),
 * so the view is widened to the end of the embedded value and then sliced at
 * its offset; reading the parent's start would read an unrelated field.
 */
private fun MemorySegment.channelLayout(
    layout: FfmpegLayout,
    struct: String,
    field: String,
): MemorySegment {
    val base = layout.offset(struct, field)
    val size = layout.struct("AVChannelLayout").size
    return atLeast(base + size).asSlice(base, size)
}

/**
 * `AVChannelLayout`'s native mask, or null when the layout is not a native one
 * (custom or unspecified), in which case the resampler is configured by channel
 * count instead.
 */
internal fun MemorySegment.readChannelMask(
    layout: FfmpegLayout,
    struct: String,
    field: String = "ch_layout",
): Long? {
    val embedded = channelLayout(layout, struct, field)
    if (embedded.readInt(layout, "AVChannelLayout", "order") !=
        layout.intConstant("AV_CHANNEL_ORDER_NATIVE")
    ) {
        return null
    }
    return embedded.readLong(layout, "AVChannelLayout", "u")
}

internal fun MemorySegment.readChannelCount(
    layout: FfmpegLayout,
    struct: String,
    field: String = "ch_layout",
): Int = channelLayout(layout, struct, field).readInt(layout, "AVChannelLayout", "nb_channels")

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
