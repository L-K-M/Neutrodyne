// SPDX-License-Identifier: Apache-2.0
/*
 * Ported from androidx.media3.common.audio.Sonic (Media3 1.11.1, Apache-2.0),
 * itself a Java port of Bill Cox's Sonic library. Copyright 2026 The Android
 * Open Source Project.
 *
 * Faithful translation of the 16-bit PCM path (SonicShortImpl): speed/pitch
 * change via pitch-period skipping and insertion with AMDF period finding,
 * plus output resampling via adjustRate when the output rate differs.
 */
package ch.lkmc.neutrodyne.playback.engine

import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal class Sonic(
    private val inputSampleRateHz: Int,
    private val channelCount: Int,
    private val speed: Float,
    private val pitch: Float,
    outputSampleRateHz: Int,
) {
    private val rate = inputSampleRateHz.toFloat() / outputSampleRateHz
    private val minPeriod = inputSampleRateHz / MAXIMUM_PITCH
    private val maxPeriod = inputSampleRateHz / MINIMUM_PITCH
    private val maxRequiredFrameCount = 2 * maxPeriod

    private var inputBuffer = ShortArray(maxRequiredFrameCount * channelCount)
    private var outputBuffer = ShortArray(maxRequiredFrameCount * channelCount)
    private var pitchBuffer = ShortArray(maxRequiredFrameCount * channelCount)
    private val downSampleBuffer = ShortArray(maxRequiredFrameCount)

    private var inputFrameCount = 0
    private var outputFrameCount = 0
    private var pitchFrameCount = 0
    private var oldRatePosition = 0
    private var newRatePosition = 0
    private var remainingInputToCopyFrameCount = 0
    private var prevPeriod = 0
    private var minDiff = 0
    private var maxDiff = 0
    private var prevMinDiff = 0
    private var accumulatedSpeedAdjustmentError = 0.0

    val pendingInputBytes: Int get() = inputFrameCount * channelCount * 2
    val outputSize: Int get() = outputFrameCount * channelCount * 2

    fun queueInput(buffer: ByteBuffer) {
        val bytesToWrite = buffer.remaining()
        val framesToWrite = bytesToWrite / (channelCount * 2)
        ensureInput(framesToWrite)
        val dst = inputBuffer
        var pos = inputFrameCount * channelCount
        repeat(framesToWrite * channelCount) {
            dst[pos++] = buffer.getShort()
        }
        inputFrameCount += framesToWrite
        processStreamInput()
    }

    fun getOutput(buffer: ByteBuffer) {
        check(outputFrameCount >= 0)
        val framesToRead = minOf(buffer.remaining() / (channelCount * 2), outputFrameCount)
        repeat(framesToRead * channelCount) { buffer.putShort(outputBuffer[it]) }
        outputFrameCount -= framesToRead
        System.arraycopy(
            outputBuffer,
            framesToRead * channelCount,
            outputBuffer,
            0,
            outputFrameCount * channelCount,
        )
    }

    fun queueEndOfStream() {
        val remainingFrameCount = inputFrameCount
        val s = (speed / pitch).toDouble()
        val r = (rate * pitch).toDouble()
        val adjustedRemainingFrames = remainingFrameCount - remainingInputToCopyFrameCount
        val expectedOutputFrames =
            outputFrameCount +
                (((adjustedRemainingFrames / s + remainingInputToCopyFrameCount +
                    accumulatedSpeedAdjustmentError + pitchFrameCount) / r) + 0.5).toInt()
        accumulatedSpeedAdjustmentError = 0.0
        ensureInput(remainingFrameCount + 2 * maxRequiredFrameCount)
        for (i in 0 until 2 * maxRequiredFrameCount * channelCount) {
            inputBuffer[remainingFrameCount * channelCount + i] = 0
        }
        inputFrameCount += 2 * maxRequiredFrameCount
        processStreamInput()
        if (outputFrameCount > expectedOutputFrames) {
            outputFrameCount = maxOf(expectedOutputFrames, 0)
        }
        inputFrameCount = 0
        remainingInputToCopyFrameCount = 0
        pitchFrameCount = 0
    }

    fun flush() {
        inputFrameCount = 0
        outputFrameCount = 0
        pitchFrameCount = 0
        oldRatePosition = 0
        newRatePosition = 0
        remainingInputToCopyFrameCount = 0
        prevPeriod = 0
        accumulatedSpeedAdjustmentError = 0.0
        prevMinDiff = 0
        minDiff = 0
        maxDiff = 0
    }

    private fun ensureInput(additional: Int) {
        inputBuffer = ensureSpace(inputBuffer, inputFrameCount, additional)
    }

    private fun ensureOutput(additional: Int) {
        outputBuffer = ensureSpace(outputBuffer, outputFrameCount, additional)
    }

    private fun ensurePitch(additional: Int) {
        pitchBuffer = ensureSpace(pitchBuffer, pitchFrameCount, additional)
    }

    private fun ensureSpace(
        buffer: ShortArray,
        frameCount: Int,
        additional: Int,
    ): ShortArray {
        val capacity = buffer.size / channelCount
        if (frameCount + additional <= capacity) return buffer
        return buffer.copyOf((3 * capacity / 2 + additional) * channelCount)
    }

    private fun copyToOutput(
        positionFrames: Int,
        frameCount: Int,
    ) {
        ensureOutput(frameCount)
        System.arraycopy(
            inputBuffer,
            positionFrames * channelCount,
            outputBuffer,
            outputFrameCount * channelCount,
            frameCount * channelCount,
        )
        outputFrameCount += frameCount
    }

    private fun copyInputToOutput(positionFrames: Int): Int {
        val frameCount = minOf(maxRequiredFrameCount, remainingInputToCopyFrameCount)
        copyToOutput(positionFrames, frameCount)
        remainingInputToCopyFrameCount -= frameCount
        return frameCount
    }

    private fun downSampleInput(
        positionFrames: Int,
        skip: Int,
    ) {
        val frameCount = maxRequiredFrameCount / skip
        val samplesPerValue = channelCount * skip
        var pos = positionFrames * channelCount
        for (i in 0 until frameCount) {
            var value = 0
            for (j in 0 until samplesPerValue) {
                value += inputBuffer[pos + i * samplesPerValue + j]
            }
            downSampleBuffer[i] = (value / samplesPerValue).toShort()
        }
    }

    private fun findPitchPeriodInRange(
        samples: ShortArray,
        positionFrames: Int,
        minPeriod: Int,
        maxPeriod: Int,
    ): Int {
        var bestPeriod = 0
        var worstPeriod = 255
        var minDiffL = 1L
        var maxDiffL = 0L
        val pos = positionFrames * channelCount
        for (period in minPeriod..maxPeriod) {
            var diff = 0L
            for (i in 0 until period) {
                val sVal = samples[pos + i]
                val pVal = samples[pos + period + i]
                diff += kotlin.math.abs(sVal - pVal)
            }
            if (diff * bestPeriod < minDiffL * period) {
                minDiffL = diff
                bestPeriod = period
            }
            if (diff * worstPeriod > maxDiffL * period) {
                maxDiffL = diff
                worstPeriod = period
            }
        }
        minDiff = (minDiffL / bestPeriod).toInt()
        maxDiff = (maxDiffL / worstPeriod).toInt()
        return bestPeriod
    }

    private fun previousPeriodBetter(): Boolean {
        if (minDiff == 0 || prevPeriod == 0) return false
        if (maxDiff > minDiff * 3) return false
        if (minDiff * 2 <= prevMinDiff * 3) return false
        return true
    }

    private fun findPitchPeriod(positionFrames: Int): Int {
        var period: Int
        val retPeriod: Int
        val skip = if (inputSampleRateHz > AMDF_FREQUENCY) inputSampleRateHz / AMDF_FREQUENCY else 1
        if (channelCount == 1 && skip == 1) {
            period = findPitchPeriodInRange(inputBuffer, positionFrames, minPeriod, maxPeriod)
        } else {
            downSampleInput(positionFrames, skip)
            period =
                findPitchPeriodInRange(downSampleBuffer, 0, minPeriod / skip, maxPeriod / skip)
            if (skip != 1) {
                period *= skip
                var minP = period - skip * 4
                var maxP = period + skip * 4
                if (minP < minPeriod) minP = minPeriod
                if (maxP > maxPeriod) maxP = maxPeriod
                if (channelCount == 1) {
                    period = findPitchPeriodInRange(inputBuffer, positionFrames, minP, maxP)
                } else {
                    downSampleInput(positionFrames, 1)
                    period = findPitchPeriodInRange(downSampleBuffer, 0, minP, maxP)
                }
            }
        }
        retPeriod = if (previousPeriodBetter()) prevPeriod else period
        prevMinDiff = minDiff
        prevPeriod = period
        return retPeriod
    }

    private fun interpolate(
        `in`: ShortArray,
        inPos: Int,
        oldSampleRate: Long,
        newSampleRate: Long,
    ): Short {
        val left = `in`[inPos].toLong()
        val right = `in`[inPos + channelCount].toLong()
        val position = newRatePosition.toLong() * oldSampleRate
        val leftPosition = oldRatePosition.toLong() * newSampleRate
        val rightPosition = (oldRatePosition + 1).toLong() * newSampleRate
        val ratio = rightPosition - position
        val width = rightPosition - leftPosition
        return ((ratio * left + (width - ratio) * right) / width).toShort()
    }

    private fun overlapAdd(
        frameCount: Int,
        outPosition: Int,
        rampDownPosition: Int,
        rampUpPosition: Int,
    ) {
        for (i in 0 until channelCount) {
            var o = outPosition * channelCount + i
            var u = rampUpPosition * channelCount + i
            var d = rampDownPosition * channelCount + i
            for (t in 0 until frameCount) {
                outputBuffer[o] =
                    ((inputBuffer[d] * (frameCount - t) + inputBuffer[u] * t) / frameCount).toShort()
                o += channelCount
                d += channelCount
                u += channelCount
            }
        }
    }

    private fun adjustRate(
        rate: Float,
        originalOutputFrameCount: Int,
    ) {
        if (outputFrameCount == originalOutputFrameCount) return
        var newSampleRate = (inputSampleRateHz / rate).toLong()
        var oldSampleRate = inputSampleRateHz.toLong()
        while (newSampleRate != 0L && oldSampleRate != 0L &&
            newSampleRate % 2 == 0L && oldSampleRate % 2 == 0L
        ) {
            newSampleRate /= 2
            oldSampleRate /= 2
        }
        moveNewSamplesToPitchBuffer(originalOutputFrameCount)
        for (position in 0 until pitchFrameCount - 1) {
            while ((oldRatePosition + 1) * newSampleRate > newRatePosition * oldSampleRate) {
                ensureOutput(1)
                for (i in 0 until channelCount) {
                    outputBuffer[outputFrameCount * channelCount + i] =
                        interpolate(
                            pitchBuffer,
                            position * channelCount + i,
                            oldSampleRate,
                            newSampleRate,
                        )
                }
                newRatePosition++
                outputFrameCount++
            }
            oldRatePosition++
            if (oldRatePosition.toLong() == oldSampleRate) {
                oldRatePosition = 0
                check(newRatePosition.toLong() == newSampleRate)
                newRatePosition = 0
            }
        }
        removePitchFrames(pitchFrameCount - 1)
    }

    private fun moveNewSamplesToPitchBuffer(originalOutputFrameCount: Int) {
        val frameCount = outputFrameCount - originalOutputFrameCount
        ensurePitch(frameCount)
        System.arraycopy(
            outputBuffer,
            originalOutputFrameCount * channelCount,
            pitchBuffer,
            pitchFrameCount * channelCount,
            frameCount * channelCount,
        )
        outputFrameCount = originalOutputFrameCount
        pitchFrameCount += frameCount
    }

    private fun removePitchFrames(frameCount: Int) {
        if (frameCount == 0) return
        System.arraycopy(
            pitchBuffer,
            frameCount * channelCount,
            pitchBuffer,
            0,
            (pitchFrameCount - frameCount) * channelCount,
        )
        pitchFrameCount -= frameCount
    }

    private fun skipPitchPeriod(
        position: Int,
        speed: Double,
        period: Int,
    ): Int {
        val newFrameCount: Int
        if (speed >= 2.0f) {
            val expected = period / (speed - 1.0) + accumulatedSpeedAdjustmentError
            newFrameCount = expected.roundToInt()
            accumulatedSpeedAdjustmentError = expected - newFrameCount
        } else {
            newFrameCount = period
            val expected =
                period * (2.0f - speed) / (speed - 1.0f) + accumulatedSpeedAdjustmentError
            remainingInputToCopyFrameCount = expected.roundToInt()
            accumulatedSpeedAdjustmentError = expected - remainingInputToCopyFrameCount
        }
        ensureOutput(newFrameCount)
        overlapAdd(newFrameCount, outputFrameCount, position, position + period)
        outputFrameCount += newFrameCount
        return newFrameCount
    }

    private fun insertPitchPeriod(
        position: Int,
        speed: Double,
        period: Int,
    ): Int {
        val newFrameCount: Int
        if (speed < 0.5f) {
            val expected = period * speed / (1.0f - speed) + accumulatedSpeedAdjustmentError
            newFrameCount = expected.roundToInt()
            accumulatedSpeedAdjustmentError = expected - newFrameCount
        } else {
            newFrameCount = period
            val expected =
                period * (2.0f * speed - 1.0f) / (1.0f - speed) + accumulatedSpeedAdjustmentError
            remainingInputToCopyFrameCount = expected.roundToInt()
            accumulatedSpeedAdjustmentError = expected - remainingInputToCopyFrameCount
        }
        ensureOutput(period + newFrameCount)
        System.arraycopy(
            inputBuffer,
            position * channelCount,
            outputBuffer,
            outputFrameCount * channelCount,
            period * channelCount,
        )
        overlapAdd(newFrameCount, outputFrameCount + period, position + period, position)
        outputFrameCount += period + newFrameCount
        return newFrameCount
    }

    private fun changeSpeed(speed: Double) {
        if (inputFrameCount < maxRequiredFrameCount) return
        val frameCount = inputFrameCount
        var positionFrames = 0
        do {
            if (remainingInputToCopyFrameCount > 0) {
                positionFrames += copyInputToOutput(positionFrames)
            } else {
                val period = findPitchPeriod(positionFrames)
                positionFrames +=
                    if (speed > 1.0) {
                        period + skipPitchPeriod(positionFrames, speed, period)
                    } else {
                        insertPitchPeriod(positionFrames, speed, period)
                    }
            }
        } while (positionFrames + maxRequiredFrameCount <= frameCount)
        removeProcessedInputFrames(positionFrames)
    }

    private fun removeProcessedInputFrames(positionFrames: Int) {
        val remainingFrames = inputFrameCount - positionFrames
        System.arraycopy(
            inputBuffer,
            positionFrames * channelCount,
            inputBuffer,
            0,
            remainingFrames * channelCount,
        )
        inputFrameCount = remainingFrames
    }

    private fun processStreamInput() {
        val originalOutputFrameCount = outputFrameCount
        val s = (speed / pitch).toDouble()
        val r = rate * pitch
        if (s > MINIMUM_SPEEDUP_RATE || s < MINIMUM_SLOWDOWN_RATE) {
            changeSpeed(s)
        } else {
            copyToOutput(0, inputFrameCount)
            inputFrameCount = 0
        }
        if (r != 1.0f) {
            adjustRate(r, originalOutputFrameCount)
        }
    }

    private companion object {
        const val MINIMUM_PITCH = 65
        const val MAXIMUM_PITCH = 400
        const val AMDF_FREQUENCY = 4000
        const val MINIMUM_SPEEDUP_RATE = 1.00001
        const val MINIMUM_SLOWDOWN_RATE = 0.99999
    }
}
