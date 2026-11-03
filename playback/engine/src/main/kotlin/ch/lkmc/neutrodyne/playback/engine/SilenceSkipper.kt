// SPDX-License-Identifier: Apache-2.0

/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by Neutrodyne, 2026: Kotlin PCM16 port for the MD0 spike.
package ch.lkmc.neutrodyne.playback.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class SilenceSkipper(
    private val minimumSilenceDurationUs: Long = DEFAULT_MINIMUM_SILENCE_DURATION_US,
    private val silenceRetentionRatio: Float = DEFAULT_SILENCE_RETENTION_RATIO,
    private val maxSilenceToKeepDurationUs: Long = DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
    private val minVolumeToKeepPercentageWhenMuting: Int = DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
    private val silenceThresholdLevel: Short = DEFAULT_SILENCE_THRESHOLD_LEVEL,
) {
    private var bytesPerFrame = 0
    private var sampleRate = 0
    private var state = STATE_NOISY
    var skippedFrames: Long = 0
        private set
    private var outputSilenceFramesSinceNoise = 0
    private var maybeSilenceBuffer = ByteArray(0)
    private var contiguousOutputBuffer = ByteArray(0)
    private var maybeSilenceBufferStartIndex = 0
    private var maybeSilenceBufferContentsSize = 0
    private var ended = false
    private var inputEnded = false
    private data class Output(val pcm: ByteBuffer, val sourceFrame: Long)

    private val outputQueue = ArrayDeque<Output>()
    private var sourceFramesConsumed = 0L

    /** True once a stream format has been configured via [configure]. */
    fun configure(
        sampleRateHz: Int,
        channelCount: Int,
    ) {
        sampleRate = sampleRateHz
        bytesPerFrame = channelCount * 2
        val size =
            alignToFrame((durationUsToFrames(minimumSilenceDurationUs) * bytesPerFrame) / 2) * 2
        if (maybeSilenceBuffer.size != size) {
            maybeSilenceBuffer = ByteArray(size)
            contiguousOutputBuffer = ByteArray(size)
        }
        state = STATE_NOISY
        skippedFrames = 0
        sourceFramesConsumed = 0
        outputSilenceFramesSinceNoise = 0
        maybeSilenceBufferStartIndex = 0
        maybeSilenceBufferContentsSize = 0
    }

    /** Feeds [input] (native-order PCM 16) into the state machine. */
    fun queueInput(input: ByteBuffer) {
        while (input.hasRemaining()) {
            when (state) {
                STATE_NOISY -> processNoisy(input)
                STATE_SHORTENING_SILENCE -> shortenSilenceUntilNoise(input)
                else -> error("unexpected state $state")
            }
        }
    }

    /** Signals end of input; pending silence is flushed on the next [getOutput]. */
    fun queueEndOfStream() {
        inputEnded = true
        if (state == STATE_SHORTENING_SILENCE) {
            outputShortenedSilenceBuffer(shouldTransitionToNoisyState = true)
            outputSilenceFramesSinceNoise = 0
        }
        ended = true
    }

    fun isEnded(): Boolean = ended && outputQueue.isEmpty()

    /** Drain retained PCM and return its actual first frame in the source timeline. */
    fun getOutput(buffer: ByteBuffer): Long? {
        val block = outputQueue.firstOrNull() ?: return null
        val out = block.pcm
        val sourceFrame = block.sourceFrame + out.position() / bytesPerFrame
        val count = minOf(buffer.remaining(), out.remaining())
        val limit = out.limit()
        out.limit(out.position() + count)
        buffer.put(out)
        out.limit(limit)
        if (!out.hasRemaining()) outputQueue.removeFirst()
        return sourceFrame
    }

    fun hasOutput(): Boolean = outputQueue.isNotEmpty()

    private fun output(data: ByteBuffer) {
        val out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder())
        out.put(data).flip()
        outputQueue.addLast(Output(out, sourceFramesConsumed))
        sourceFramesConsumed += out.remaining() / bytesPerFrame
    }

    private fun outputRange(
        data: ByteArray,
        size: Int,
        rampType: Int,
        sourceFrame: Long,
    ) {
        require(size % bytesPerFrame == 0)
        modifyVolume(data, size, rampType)
        val out = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder())
        out.put(data, 0, size).flip()
        outputQueue.addLast(Output(out, sourceFrame))
    }

    private fun processNoisy(input: ByteBuffer) {
        val limit = input.limit()
        input.limit(minOf(limit, input.position() + maybeSilenceBuffer.size))
        val noiseLimit = findNoiseLimit(input)
        if (noiseLimit == input.position()) {
            state = STATE_SHORTENING_SILENCE
        } else {
            input.limit(minOf(noiseLimit, input.capacity()))
            output(input)
        }
        input.limit(limit)
    }

    private fun shortenSilenceUntilNoise(input: ByteBuffer) {
        check(maybeSilenceBufferStartIndex < maybeSilenceBuffer.size)
        val limit = input.limit()
        val noisePosition = findNoisePosition(input)
        val silenceInputSize = noisePosition - input.position()
        val indexToWriteTo: Int
        val contiguousBufferRemaining: Int
        if (maybeSilenceBufferStartIndex + maybeSilenceBufferContentsSize < maybeSilenceBuffer.size) {
            contiguousBufferRemaining =
                maybeSilenceBuffer.size - (maybeSilenceBufferContentsSize + maybeSilenceBufferStartIndex)
            indexToWriteTo = maybeSilenceBufferStartIndex + maybeSilenceBufferContentsSize
        } else {
            val amountInUpperPartOfBuffer = maybeSilenceBuffer.size - maybeSilenceBufferStartIndex
            indexToWriteTo = maybeSilenceBufferContentsSize - amountInUpperPartOfBuffer
            contiguousBufferRemaining = maybeSilenceBufferStartIndex - indexToWriteTo
        }
        val noiseFound = noisePosition < limit
        val bytesOfInput = minOf(silenceInputSize, contiguousBufferRemaining)
        input.limit(input.position() + bytesOfInput)
        input.get(maybeSilenceBuffer, indexToWriteTo, bytesOfInput)
        maybeSilenceBufferContentsSize += bytesOfInput
        check(maybeSilenceBufferContentsSize <= maybeSilenceBuffer.size)
        val shouldTransitionToNoisyState = noiseFound && silenceInputSize < contiguousBufferRemaining
        outputShortenedSilenceBuffer(shouldTransitionToNoisyState)
        if (shouldTransitionToNoisyState) {
            state = STATE_NOISY
            outputSilenceFramesSinceNoise = 0
        }
        input.limit(limit)
    }

    private fun outputShortenedSilenceBuffer(shouldTransitionToNoisyState: Boolean) {
        val sizeBeforeOutput = maybeSilenceBufferContentsSize
        var bytesToOutput: Int
        val bytesConsumed: Int
        val rampType: Int
        if (maybeSilenceBufferContentsSize == maybeSilenceBuffer.size || shouldTransitionToNoisyState) {
            if (outputSilenceFramesSinceNoise == 0) {
                if (shouldTransitionToNoisyState) {
                    bytesToOutput = maybeSilenceBufferContentsSize
                    rampType = DO_NOT_CHANGE_VOLUME
                    bytesConsumed = bytesToOutput
                } else {
                    check(maybeSilenceBufferContentsSize >= maybeSilenceBuffer.size / 2)
                    bytesToOutput = maybeSilenceBuffer.size / 2
                    rampType = FADE_OUT
                    bytesConsumed = bytesToOutput
                }
            } else if (shouldTransitionToNoisyState) {
                val bytesRemainingAfterOutputtingHalfMin =
                    maybeSilenceBufferContentsSize - maybeSilenceBuffer.size / 2
                bytesConsumed = bytesRemainingAfterOutputtingHalfMin + maybeSilenceBuffer.size / 2
                val shortenedSilenceLength = calculateShortenedSilenceLength(bytesRemainingAfterOutputtingHalfMin)
                bytesToOutput = maybeSilenceBuffer.size / 2 + shortenedSilenceLength
                rampType = FADE_IN
            } else {
                bytesConsumed = maybeSilenceBufferContentsSize - maybeSilenceBuffer.size / 2
                bytesToOutput = calculateShortenedSilenceLength(bytesConsumed)
                rampType = MUTE
            }
            // Fade-in retains the source tail; muted/fade-out blocks keep its
            // front. A cumulative skip total would attach future skips too early.
            val sourceFrame = sourceFramesConsumed +
                if (rampType == FADE_IN) (bytesConsumed - bytesToOutput) / bytesPerFrame else 0
            outputSilence(bytesToOutput, rampType, sourceFrame)
            sourceFramesConsumed += bytesConsumed / bytesPerFrame
            check(bytesConsumed % bytesPerFrame == 0)
            check(sizeBeforeOutput >= bytesToOutput)
            maybeSilenceBufferContentsSize -= bytesConsumed
            maybeSilenceBufferStartIndex =
                (maybeSilenceBufferStartIndex + bytesConsumed) % maybeSilenceBuffer.size
            outputSilenceFramesSinceNoise += bytesToOutput / bytesPerFrame
            skippedFrames += (bytesConsumed - bytesToOutput) / bytesPerFrame
        }
    }

    private fun calculateShortenedSilenceLength(silenceToShortenBytes: Int): Int {
        val bytesNeededToReachMax =
            (durationUsToFrames(maxSilenceToKeepDurationUs) - outputSilenceFramesSinceNoise) * bytesPerFrame -
                maybeSilenceBuffer.size / 2
        check(bytesNeededToReachMax >= 0)
        return alignToFrame(
            minOf(silenceToShortenBytes * silenceRetentionRatio + .5f, bytesNeededToReachMax.toFloat()).toInt(),
        )
    }

    private fun alignToFrame(value: Int): Int = (value / bytesPerFrame) * bytesPerFrame

    private fun outputSilence(
        sizeToOutput: Int,
        rampType: Int,
        sourceFrame: Long,
    ) {
        if (sizeToOutput == 0) return
        require(maybeSilenceBufferContentsSize >= sizeToOutput)
        if (rampType == FADE_IN) {
            if (maybeSilenceBufferStartIndex + maybeSilenceBufferContentsSize <= maybeSilenceBuffer.size) {
                System.arraycopy(
                    maybeSilenceBuffer,
                    maybeSilenceBufferStartIndex + maybeSilenceBufferContentsSize - sizeToOutput,
                    contiguousOutputBuffer,
                    0,
                    sizeToOutput,
                )
            } else {
                val sizeInUpperPartOfArray = maybeSilenceBuffer.size - maybeSilenceBufferStartIndex
                val sizeInLowerPartOfArray = maybeSilenceBufferContentsSize - sizeInUpperPartOfArray
                if (sizeInLowerPartOfArray >= sizeToOutput) {
                    System.arraycopy(
                        maybeSilenceBuffer,
                        sizeInLowerPartOfArray - sizeToOutput,
                        contiguousOutputBuffer,
                        0,
                        sizeToOutput,
                    )
                } else {
                    val sizeToOutputInUpperPart = sizeToOutput - sizeInLowerPartOfArray
                    System.arraycopy(
                        maybeSilenceBuffer,
                        maybeSilenceBuffer.size - sizeToOutputInUpperPart,
                        contiguousOutputBuffer,
                        0,
                        sizeToOutputInUpperPart,
                    )
                    System.arraycopy(
                        maybeSilenceBuffer,
                        0,
                        contiguousOutputBuffer,
                        sizeToOutputInUpperPart,
                        sizeInLowerPartOfArray,
                    )
                }
            }
        } else {
            if (maybeSilenceBufferStartIndex + sizeToOutput <= maybeSilenceBuffer.size) {
                System.arraycopy(
                    maybeSilenceBuffer,
                    maybeSilenceBufferStartIndex,
                    contiguousOutputBuffer,
                    0,
                    sizeToOutput,
                )
            } else {
                val sizeToCopyInUpperPartOfArray = maybeSilenceBuffer.size - maybeSilenceBufferStartIndex
                System.arraycopy(
                    maybeSilenceBuffer,
                    maybeSilenceBufferStartIndex,
                    contiguousOutputBuffer,
                    0,
                    sizeToCopyInUpperPartOfArray,
                )
                val amountToCopyFromLowerPartOfArray = sizeToOutput - sizeToCopyInUpperPartOfArray
                System.arraycopy(
                    maybeSilenceBuffer,
                    0,
                    contiguousOutputBuffer,
                    sizeToCopyInUpperPartOfArray,
                    amountToCopyFromLowerPartOfArray,
                )
            }
        }
        outputRange(contiguousOutputBuffer, sizeToOutput, rampType, sourceFrame)
    }

    private fun modifyVolume(
        sampleBuffer: ByteArray,
        size: Int,
        volumeChangeType: Int,
    ) {
        if (volumeChangeType == DO_NOT_CHANGE_VOLUME) return
        val lastFrameIdx = (size / bytesPerFrame) - 1
        var idx = 0
        while (idx < size) {
            var sample = twoByteSampleToInt(sampleBuffer[idx + 1], sampleBuffer[idx])
            val percentage =
                when (volumeChangeType) {
                    FADE_OUT -> calculateFadeOutPercentage(idx / bytesPerFrame, lastFrameIdx)
                    FADE_IN -> calculateFadeInPercentage(idx / bytesPerFrame, lastFrameIdx)
                    else -> minVolumeToKeepPercentageWhenMuting
                }
            sample = (sample * percentage) / 100
            sampleIntToTwoBigEndianBytes(sampleBuffer, idx, sample)
            idx += 2
        }
    }

    private fun calculateFadeOutPercentage(
        value: Int,
        max: Int,
    ): Int {
        if (max == 0) return minVolumeToKeepPercentageWhenMuting
        return (
            (minVolumeToKeepPercentageWhenMuting - 100) *
                (AVOID_TRUNCATION_FACTOR * value / max)
        ) / AVOID_TRUNCATION_FACTOR + 100
    }

    private fun calculateFadeInPercentage(
        value: Int,
        max: Int,
    ): Int {
        if (max == 0) return 100
        return minVolumeToKeepPercentageWhenMuting +
            (100 - minVolumeToKeepPercentageWhenMuting) *
            (AVOID_TRUNCATION_FACTOR * value / max) / AVOID_TRUNCATION_FACTOR
    }

    private fun durationUsToFrames(durationUs: Long): Int = (durationUs * sampleRate / MICROS_PER_SECOND).toInt()

    private fun findNoisePosition(buffer: ByteBuffer): Int {
        var i = buffer.position() + 1
        while (i < buffer.limit()) {
            if (isNoise(buffer.get(i), buffer.get(i - 1))) {
                return bytesPerFrame * (i / bytesPerFrame)
            }
            i += 2
        }
        return buffer.limit()
    }

    private fun findNoiseLimit(buffer: ByteBuffer): Int {
        var i = buffer.limit() - 1
        while (i >= buffer.position()) {
            if (isNoise(buffer.get(i), buffer.get(i - 1))) {
                return bytesPerFrame * (i / bytesPerFrame) + bytesPerFrame
            }
            i -= 2
        }
        return buffer.position()
    }

    private fun isNoise(
        mostSignificantByte: Byte,
        leastSignificantByte: Byte,
    ): Boolean =
        Math.abs(twoByteSampleToInt(mostSignificantByte, leastSignificantByte)) >
            silenceThresholdLevel.toInt()

    companion object {
        // Defaults match Android's PlayerFactory construction, not Media3's.
        const val DEFAULT_MINIMUM_SILENCE_DURATION_US = 250_000L
        const val DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US = 400_000L
        const val DEFAULT_SILENCE_RETENTION_RATIO = 0.2f
        const val DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE = 10
        const val DEFAULT_SILENCE_THRESHOLD_LEVEL: Short = 1024
        private const val STATE_NOISY = 0
        private const val STATE_SHORTENING_SILENCE = 1
        private const val FADE_OUT = 0
        private const val MUTE = 1
        private const val FADE_IN = 2
        private const val DO_NOT_CHANGE_VOLUME = 3
        private const val AVOID_TRUNCATION_FACTOR = 1000
        private const val MICROS_PER_SECOND = 1_000_000L

        private fun twoByteSampleToInt(
            mostSignificantByte: Byte,
            leastSignificantByte: Byte,
        ): Int = (leastSignificantByte.toInt() and 0xFF) or (mostSignificantByte.toInt() shl 8)

        private fun sampleIntToTwoBigEndianBytes(
            byteArray: ByteArray,
            startIndex: Int,
            sample: Int,
        ) {
            when {
                sample >= Short.MAX_VALUE -> {
                    byteArray[startIndex] = 0xFF.toByte()
                    byteArray[startIndex + 1] = 0x7F.toByte()
                }

                sample <= Short.MIN_VALUE -> {
                    byteArray[startIndex] = 0x00.toByte()
                    byteArray[startIndex + 1] = 0x80.toByte()
                }

                else -> {
                    byteArray[startIndex] = (sample and 0xFF).toByte()
                    byteArray[startIndex + 1] = (sample shr 8).toByte()
                }
            }
        }
    }
}
