package org.fossify.voicerecorder.recorder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the 44 byte RIFF/WAVE header for uncompressed 16 bit PCM audio.
 *
 * Kept free of Android dependencies so that it can be verified with plain unit tests.
 */
internal object WavHeader {
    const val BITS_PER_SAMPLE = 16
    const val HEADER_SIZE = 44

    /**
     * Largest value the 32 bit RIFF size fields can hold. Files longer than this can still be
     * written, but the header cannot describe them accurately.
     */
    const val MAX_DATA_LENGTH = 0xFFFFFFFFL - 36L

    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private const val AUDIO_FORMAT_PCM = 1
    private const val RIFF_FORMAT_SIZE = 36
    private const val WAVE_FORMAT_PCM_SIZE = 16

    /** Builds a header describing [dataLength] bytes of audio data. */
    fun build(sampleRate: Int, channels: Int, dataLength: Int): ByteArray {
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(RIFF_FORMAT_SIZE + dataLength)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(WAVE_FORMAT_PCM_SIZE)
        header.putShort(AUDIO_FORMAT_PCM.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * BYTES_PER_SAMPLE).toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLength)
        return header.array()
    }
}
