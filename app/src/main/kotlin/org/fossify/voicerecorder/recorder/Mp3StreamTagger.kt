package org.fossify.voicerecorder.recorder

import kotlin.math.abs

/**
 * Builds a Xing/Info header with a LAME extension for a finished MP3 stream.
 *
 * LAME reserves the first frame of the stream for the Xing header and leaves its payload empty, so
 * the header can be written into that frame once the recording is complete. Players use the header
 * to report the exact duration and to seek without scanning the whole file, which makes the output
 * compatible with software that does not estimate the duration of constant bitrate streams.
 *
 * Only a constant amount of state is kept, so hours of recording do not grow the memory usage.
 * Instances are not thread safe and are meant to be used from the capture thread only.
 */
internal class Mp3StreamTagger {
    companion object {
        private const val FRAME_HEADER_SIZE = 4
        private const val INFO_FLAGS = 0x0F
        private const val TOC_ENTRIES = 100
        private const val LAME_FLAGS = 0x14
        private const val ENCODER_DELAY = 576
        private const val MAX_PADDING = 4095
        private const val QUALITY_INDICATOR = 55
        private const val MAX_BITRATE_KBPS = 255
        private const val LAME_VERSION = "LAME3.100"
        private const val CRC_POLYNOMIAL = 0xA001
        private const val CRC_MASK = 0xFFFF
        private const val CHANNEL_MODE_STEREO = 0
        private const val CHANNEL_MODE_DUAL_CHANNEL = 1
        private const val CHANNEL_MODE_JOINT_STEREO = 2
        private const val NOISE_SHAPING = 2
        private const val SEEKBAR_SCALE = 256
        private const val MAX_SEEK_POINT = 255
        private const val INFO_SIZE = 4 + 4 + 4 + 4 + TOC_ENTRIES + 4
        private const val LAME_SIZE = 9 + 1 + 1 + 4 + 2 + 2 + 1 + 1 + 3 + 1 + 1 + 2 + 4 + 2 + 2

        private val INFO_MARKER = byteArrayOf('I'.code.toByte(), 'n'.code.toByte(), 'f'.code.toByte(), 'o'.code.toByte())
        private val LAME_MARKER = LAME_VERSION.toByteArray(Charsets.US_ASCII)

        /**
         * Lowpass frequency LAME selects for constant bitrate output, as reported in the LAME tag.
         * Mirrors `optimum_bandwidth` in LAME's `lame.c`.
         */
        private val LOWPASS_BY_BITRATE = mapOf(
            8 to 2000, 16 to 3700, 24 to 3900, 32 to 5500, 40 to 7000, 48 to 7500, 56 to 10000,
            64 to 11000, 80 to 13500, 96 to 15100, 112 to 15600, 128 to 17000, 160 to 17500,
            192 to 18600, 224 to 19400, 256 to 19700, 320 to 20500
        )
        private const val MONO_LOWPASS_FACTOR = 1.5
        private const val MAX_LOWPASS = 20500
        private const val LOWPASS_ROUNDING = 100.0
        private const val MAX_LOWPASS_BYTE = 255

        private val CRC_TABLE = IntArray(256) { index ->
            var value = index
            repeat(8) {
                value = if (value and 1 != 0) {
                    (value ushr 1) xor CRC_POLYNOMIAL
                } else {
                    value ushr 1
                }
            }
            value
        }
    }

    private val headerCarry = ByteArray(FRAME_HEADER_SIZE)
    private var carrySize = 0
    private var remainingFrameBytes = 0
    private var currentFrameLength = 0
    private var lastFrameStart = 0
    private var frameCount = 0
    private var firstHeader: Mp3FrameHeader? = null
    private var firstHeaderBytes = ByteArray(0)
    private var firstFrameLength = 0
    private var firstFramePayloadIsEmpty = true
    private var musicCrc = 0
    private var isBroken = false

    /** Total number of bytes consumed from the encoded stream. */
    var consumedBytes = 0
        private set

    /** Consumes the next [length] bytes of the encoded stream. */
    fun write(buffer: ByteArray, length: Int) {
        if (isBroken) {
            consumedBytes += length
            return
        }

        var position = 0
        while (position < length) {
            if (remainingFrameBytes == 0) {
                val taken = minOf(FRAME_HEADER_SIZE - carrySize, length - position)
                System.arraycopy(buffer, position, headerCarry, carrySize, taken)
                carrySize += taken
                position += taken
                consumedBytes += taken

                if (carrySize < FRAME_HEADER_SIZE) {
                    return
                }

                val header = Mp3FrameHeader.parse(headerCarry)
                if (header == null) {
                    isBroken = true
                    return
                }

                lastFrameStart = consumedBytes - FRAME_HEADER_SIZE
                frameCount++
                currentFrameLength = header.frameLength
                remainingFrameBytes = header.frameLength - FRAME_HEADER_SIZE
                carrySize = 0

                if (firstHeader == null) {
                    firstHeader = header
                    firstHeaderBytes = headerCarry.copyOf()
                    firstFrameLength = header.frameLength
                } else {
                    // The LAME music CRC covers everything after the reserved header frame,
                    // including the headers of the following frames.
                    musicCrc = crc16(musicCrc, headerCarry, 0, FRAME_HEADER_SIZE)
                }
            } else {
                val taken = minOf(remainingFrameBytes, length - position)
                val frameOffset = currentFrameLength - remainingFrameBytes
                if (frameCount == 1) {
                    checkFirstFramePayload(buffer, position, taken, frameOffset)
                } else {
                    musicCrc = crc16(musicCrc, buffer, position, taken)
                }

                remainingFrameBytes -= taken
                position += taken
                consumedBytes += taken
            }
        }
    }

    /**
     * Builds the header for the completed stream.
     *
     * Returns null when the stream is damaged, does not contain a reserved header frame or is too
     * short for the header to fit, in which case the recording is still a valid MP3 file.
     */
    fun buildTag(recordedSamples: Long, peakAmplitude: Int): ByteArray? {
        val header = firstHeader ?: return null
        if (isBroken || remainingFrameBytes != 0 || carrySize != 0) return null
        if (frameCount < 2 || !firstFramePayloadIsEmpty) return null

        val totalBytes = consumedBytes
        if (totalBytes != lastFrameStart + currentFrameLength) return null

        val tagStart = FRAME_HEADER_SIZE + header.sideInfoLength
        if (tagStart + INFO_SIZE + LAME_SIZE > firstFrameLength) return null

        val tag = ByteArray(firstFrameLength)
        firstHeaderBytes.copyInto(tag)

        var position = tagStart
        position = putBytes(tag, position, INFO_MARKER)
        position = putInt(tag, position, INFO_FLAGS)
        position = putInt(tag, position, frameCount - 1)
        position = putInt(tag, position, totalBytes)
        position = putToc(tag, position)
        position = putInt(tag, position, QUALITY_INDICATOR)

        val bitrateKbps = header.bitrate / 1000
        val audioSamples = header.samplesPerFrame.toLong() * (frameCount - 1)
        val padding = (audioSamples - recordedSamples - ENCODER_DELAY).coerceIn(0L, MAX_PADDING.toLong())
        val peak = peakAmplitude.coerceIn(0, Short.MAX_VALUE.toInt()) shl 8

        position = putBytes(tag, position, LAME_MARKER)
        tag[position++] = 0x01
        tag[position++] = lowpassByte(header, bitrateKbps).toByte()
        position = putInt(tag, position, peak)
        position = putShort(tag, position, 0)
        position = putShort(tag, position, 0)
        tag[position++] = LAME_FLAGS.toByte()
        tag[position++] = bitrateKbps.coerceIn(0, MAX_BITRATE_KBPS).toByte()
        tag[position++] = ((ENCODER_DELAY shr 4) and 0xFF).toByte()
        tag[position++] = (((ENCODER_DELAY and 0x0F) shl 4) or ((padding.toInt() shr 8) and 0x0F)).toByte()
        tag[position++] = (padding.toInt() and 0xFF).toByte()
        tag[position++] = miscByte(header).toByte()
        tag[position++] = 0x00
        position = putShort(tag, position, bitrateKbps)
        position = putInt(tag, position, totalBytes)
        position = putShort(tag, position, musicCrc)
        putShort(tag, position, crc16(0, tag, 0, position))

        return tag
    }

    private fun checkFirstFramePayload(
        buffer: ByteArray,
        position: Int,
        length: Int,
        frameOffset: Int
    ) {
        val header = firstHeader ?: return
        val payloadStart = FRAME_HEADER_SIZE + header.sideInfoLength
        val skip = maxOf(frameOffset, payloadStart) - frameOffset
        for (i in skip until length) {
            if (buffer[position + i] != 0.toByte()) {
                firstFramePayloadIsEmpty = false
                return
            }
        }
    }

    private fun putToc(target: ByteArray, offset: Int): Int {
        // Mirrors LAME's seek table: entry i points at the frame reached after i percent of the
        // recording. LAME accumulates the bitrate of every frame, which for constant bitrate audio
        // is proportional to the frame index, and scales the result to a byte.
        val dataFrames = frameCount - 1
        target[offset] = 0
        for (i in 1 until TOC_ENTRIES) {
            val frameIndex = (dataFrames.toLong() * i / TOC_ENTRIES).toInt()
                .coerceIn(0, dataFrames - 1)
            val seekPoint = SEEKBAR_SCALE.toLong() * (frameIndex + 1) / dataFrames
            target[offset + i] = seekPoint.coerceIn(0L, MAX_SEEK_POINT.toLong()).toByte()
        }
        return offset + TOC_ENTRIES
    }

    /**
     * Lowpass frequency LAME reports in its tag, in units of 100 Hz.
     */
    private fun lowpassByte(header: Mp3FrameHeader, bitrateKbps: Int): Int {
        val closestBitrate = LOWPASS_BY_BITRATE.keys.minByOrNull { abs(it - bitrateKbps) } ?: return 0
        var lowpass = LOWPASS_BY_BITRATE.getValue(closestBitrate).toDouble()
        if (header.isMono) {
            lowpass *= MONO_LOWPASS_FACTOR
        }
        lowpass = lowpass.coerceAtMost(MAX_LOWPASS.toDouble())
        lowpass = lowpass.coerceAtMost(header.sampleRate / 2.0)
        return ((lowpass / LOWPASS_ROUNDING) + 0.5).toInt().coerceIn(0, MAX_LOWPASS_BYTE)
    }

    private fun miscByte(header: Mp3FrameHeader): Int {
        val stereoMode = when (header.channelMode) {
            CHANNEL_MODE_STEREO -> 1
            CHANNEL_MODE_DUAL_CHANNEL -> 2
            CHANNEL_MODE_JOINT_STEREO -> 3
            else -> 0
        }
        val sourceFrequency = when {
            header.sampleRate <= 32000 -> 0
            header.sampleRate == 48000 -> 2
            header.sampleRate > 48000 -> 3
            else -> 1
        }
        val nonOptimal = if (header.sampleRate <= 32000) 1 else 0
        return NOISE_SHAPING or (stereoMode shl 2) or (nonOptimal shl 5) or (sourceFrequency shl 6)
    }

    private fun putBytes(target: ByteArray, offset: Int, value: ByteArray): Int {
        value.copyInto(target, offset)
        return offset + value.size
    }

    private fun putShort(target: ByteArray, offset: Int, value: Int): Int {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
        return offset + 2
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int): Int {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
        return offset + 4
    }

    private fun crc16(initial: Int, data: ByteArray, offset: Int, length: Int): Int {
        var crc = initial
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor CRC_TABLE[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc and CRC_MASK
    }
}
