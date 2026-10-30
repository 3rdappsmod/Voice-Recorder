package org.fossify.voicerecorder.recorder

import android.content.Context
import android.util.Log
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
        private const val TAG = "WavRecorder"
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

        val dataLength = recordedSamples * BYTES_PER_SAMPLE
        if (dataLength > WavHeader.MAX_DATA_LENGTH) {
            // RIFF stores 32 bit sizes, so longer recordings cannot be described by the header.
            Log.w(TAG, "Recording is too long for a WAV header: $dataLength bytes")
        }

        val headerLength = dataLength.coerceAtMost(WavHeader.MAX_DATA_LENGTH).toInt()
        rewriteHeader(WavHeader.build(sampleRate, channels, headerLength))
    }

    override fun releaseEncoder() {
        headerWritten = false
    }
}
