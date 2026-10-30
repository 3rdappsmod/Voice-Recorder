package org.fossify.voicerecorder.recorder

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records uncompressed PCM audio into a RIFF/WAVE file.
 *
 * A placeholder header is written when recording starts and the final sizes are patched in when
 * the recording finishes, which keeps the file playable even if the app is killed mid-recording.
 */
class WavRecorder(context: Context) : PcmRecorder(context) {
    companion object {
        private const val BYTES_PER_SAMPLE = WavHeader.BITS_PER_SAMPLE / 8
    }

    private var headerWritten = false

    override val threadName = "WavRecorder"

    override fun createEncoder() {
        output.write(WavHeader.build(sampleRate, channels, dataLength = 0))
        headerWritten = true
    }

    override fun encode(pcm: ShortArray, sampleCount: Int) {
        val buffer = ByteBuffer.allocate(sampleCount * BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            buffer.putShort(pcm[i])
        }
        output.write(buffer.array())
    }

    override fun finishEncoding() {
        if (!headerWritten) return

        val dataLength = (recordedSamples * BYTES_PER_SAMPLE)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        rewriteHeader(WavHeader.build(sampleRate, channels, dataLength))
    }

    override fun releaseEncoder() {
        headerWritten = false
    }
}
