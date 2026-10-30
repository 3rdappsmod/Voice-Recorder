package org.fossify.voicerecorder.recorder

/**
 * Header of a single MPEG audio (Layer III) frame.
 *
 * Only the fields required to walk the stream and to build a Xing header are exposed.
 */
internal data class Mp3FrameHeader(
    val version: Int,
    val bitrate: Int,
    val sampleRate: Int,
    val channelMode: Int,
    val frameLength: Int,
    val samplesPerFrame: Int
) {
    val isMpeg1: Boolean
        get() = version == VERSION_MPEG1

    val isMono: Boolean
        get() = channelMode == CHANNEL_MODE_MONO

    /** Length of the side information, which is where the Xing tag starts. */
    val sideInfoLength: Int
        get() = if (isMpeg1) {
            if (isMono) SIDE_INFO_MPEG1_MONO else SIDE_INFO_MPEG1_STEREO
        } else {
            if (isMono) SIDE_INFO_MPEG2_MONO else SIDE_INFO_MPEG2_STEREO
        }

    companion object {
        private const val HEADER_SIZE = 4
        private const val SYNC_MASK = 0xFFE0
        private const val SYNC_PATTERN = 0xFFE0
        private const val VERSION_MPEG1 = 3
        private const val VERSION_MPEG2 = 2
        private const val VERSION_MPEG2_5 = 0
        private const val LAYER_III = 1
        private const val MAX_BITRATE_INDEX = 14
        private const val MAX_SAMPLE_RATE_INDEX = 2
        private const val CHANNEL_MODE_MONO = 3
        private const val MPEG1_SAMPLES_PER_FRAME = 1152
        private const val MPEG2_SAMPLES_PER_FRAME = 576
        private const val MPEG1_FRAME_FACTOR = 144
        private const val MPEG2_FRAME_FACTOR = 72
        private const val SIDE_INFO_MPEG1_MONO = 17
        private const val SIDE_INFO_MPEG1_STEREO = 32
        private const val SIDE_INFO_MPEG2_MONO = 9
        private const val SIDE_INFO_MPEG2_STEREO = 17

        private val BITRATES_MPEG1 = intArrayOf(
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320
        )
        private val BITRATES_MPEG2 = intArrayOf(
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160
        )
        private val SAMPLE_RATES_MPEG1 = intArrayOf(44100, 48000, 32000)
        private val SAMPLE_RATES_MPEG2 = intArrayOf(22050, 24000, 16000)
        private val SAMPLE_RATES_MPEG2_5 = intArrayOf(11025, 12000, 8000)

        /** Parses a frame header, returning null when the bytes are not a valid Layer III header. */
        fun parse(bytes: ByteArray): Mp3FrameHeader? {
            if (bytes.size < HEADER_SIZE) return null

            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            val third = bytes[2].toInt() and 0xFF
            val fourth = bytes[3].toInt() and 0xFF

            if (((first shl 8) or second) and SYNC_MASK != SYNC_PATTERN) return null

            val version = (second shr 3) and 0x03
            val layer = (second shr 1) and 0x03
            if (layer != LAYER_III) return null

            val bitrateIndex = (third shr 4) and 0x0F
            val sampleRateIndex = (third shr 2) and 0x03
            if (bitrateIndex !in 1..MAX_BITRATE_INDEX) return null
            if (sampleRateIndex > MAX_SAMPLE_RATE_INDEX) return null

            val isMpeg1 = version == VERSION_MPEG1
            val sampleRates = when (version) {
                VERSION_MPEG1 -> SAMPLE_RATES_MPEG1
                VERSION_MPEG2 -> SAMPLE_RATES_MPEG2
                VERSION_MPEG2_5 -> SAMPLE_RATES_MPEG2_5
                else -> return null
            }

            val bitrateTable = if (isMpeg1) BITRATES_MPEG1 else BITRATES_MPEG2
            val bitrate = bitrateTable[bitrateIndex] * 1000
            val sampleRate = sampleRates[sampleRateIndex]
            val padding = (third shr 1) and 0x01
            val frameFactor = if (isMpeg1) MPEG1_FRAME_FACTOR else MPEG2_FRAME_FACTOR
            val frameLength = frameFactor * bitrate / sampleRate + padding
            if (frameLength <= HEADER_SIZE) return null

            return Mp3FrameHeader(
                version = version,
                bitrate = bitrate,
                sampleRate = sampleRate,
                channelMode = (fourth shr 6) and 0x03,
                frameLength = frameLength,
                samplesPerFrame = if (isMpeg1) MPEG1_SAMPLES_PER_FRAME else MPEG2_SAMPLES_PER_FRAME
            )
        }
    }
}
