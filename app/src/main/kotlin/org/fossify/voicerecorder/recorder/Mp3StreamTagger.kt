package org.fossify.voicerecorder.recorder

/**
 * Builds a Xing/Info header with a LAME extension for a finished MP3 stream.
 *
 * LAME reserves the first frame of the stream for the Xing header and leaves its payload empty, so
 * the header can be written into that frame once the recording is complete. Players use the header
 * to report the exact duration and to seek without scanning the whole file, which makes the output
 * compatible with software that does not estimate the duration of constant bitrate streams.
 *
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
        private const val INFO_SIZE = 4 + 4 + 4 + 4 + TOC_ENTRIES + 4
        private const val LAME_SIZE = 9 + 1 + 1 + 4 + 2 + 2 + 1 + 1 + 3 + 1 + 1 + 2 + 4 + 2 + 2

        private val INFO_MARKER = byteArrayOf('I'.code.toByte(), 'n'.code.toByte(), 'f'.code.toByte(), 'o'.code.toByte())
        private val LAME_MARKER = LAME_VERSION.toByteArray(Charsets.US_ASCII)

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

    private val frameOffsets = ArrayList<Int>()
    private val headerCarry = ByteArray(FRAME_HEADER_SIZE)
    private var carrySize = 0
    private var remainingFrameBytes = 0
    private var currentFrameLength = 0
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

                frameOffsets.add(consumedBytes - FRAME_HEADER_SIZE)
                currentFrameLength = header.frameLength
                remainingFrameBytes = header.frameLength - FRAME_HEADER_SIZE
                carrySize = 0

                if (firstHeader == null) {
                    firstHeader = header
                    firstHeaderBytes = headerCarry.copyOf()
                    firstFrameLength = header.frameLength
                }
            } else {
                val taken = minOf(remainingFrameBytes, length - position)
                val frameOffset = currentFrameLength - remainingFrameBytes
                if (frameOffsets.size == 1) {
                    checkFirstFramePayload(buffer, position, taken, frameOffset)
                }

                val audioStart = maxOf(consumedBytes, firstFrameLength)
                val skip = audioStart - consumedBytes
                if (skip < taken) {
                    musicCrc = crc16(musicCrc, buffer, position + skip, taken - skip)
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
        if (frameOffsets.size < 2 || !firstFramePayloadIsEmpty) return null

        val totalBytes = consumedBytes
        if (totalBytes != frameOffsets.last() + currentFrameLength) return null

        val tagStart = FRAME_HEADER_SIZE + header.sideInfoLength
        val tagLength = tagStart + INFO_SIZE + LAME_SIZE
        if (tagLength > firstFrameLength) return null

        val tag = ByteArray(firstFrameLength)
        firstHeaderBytes.copyInto(tag)

        var position = tagStart
        position = putBytes(tag, position, INFO_MARKER)
        position = putInt(tag, position, INFO_FLAGS)
        position = putInt(tag, position, frameOffsets.size - 1)
        position = putInt(tag, position, totalBytes)
        position = putToc(tag, position, totalBytes)
        position = putInt(tag, position, QUALITY_INDICATOR)

        val bitrateKbps = header.bitrate / 1000
        val audioSamples = header.samplesPerFrame.toLong() * (frameOffsets.size - 1)
        val padding = (audioSamples - recordedSamples - ENCODER_DELAY).coerceIn(0L, MAX_PADDING.toLong())
        val peak = peakAmplitude.coerceIn(0, Short.MAX_VALUE.toInt()) shl 8

        position = putBytes(tag, position, LAME_MARKER)
        tag[position++] = 0x01
        tag[position++] = 0x00
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

    private fun putToc(target: ByteArray, offset: Int, totalBytes: Int): Int {
        val dataFrames = frameOffsets.size - 1
        for (i in 0 until TOC_ENTRIES) {
            val frameIndex = if (i == 0) {
                0
            } else {
                1 + ((dataFrames - 1).toLong() * i / (TOC_ENTRIES - 1)).toInt()
            }
            val byteOffset = frameOffsets[frameIndex].toLong()
            target[offset + i] = ((byteOffset * 256) / totalBytes).coerceIn(0, 255).toByte()
        }
        return offset + TOC_ENTRIES
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
