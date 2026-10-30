package org.fossify.voicerecorder.recorder

import android.content.Context
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import org.fossify.voicerecorder.extensions.config
import java.io.IOException

/**
 * Records MP3 with the LAME encoder.
 *
 * The stream is tagged with a Xing/Info header (including the LAME extension) once the recording
 * finishes, so players can determine the exact length and seek properly.
 */
class Mp3Recorder(context: Context) : PcmRecorder(context) {
    companion object {
        private const val MP3_BUFFER_OVERHEAD = 7200
    }

    private var encoder: AndroidLame? = null
    private val outputBuffer = ByteArray((samplesPerRead * 1.25f).toInt() + MP3_BUFFER_OVERHEAD)
    private val tagger = Mp3StreamTagger()

    override val threadName = "Mp3Recorder"

    override fun createEncoder() {
        val bitrateKbps = (context.config.bitrate / 1000).coerceAtLeast(1)
        encoder = LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutSampleRate(sampleRate)
            .setOutBitrate(bitrateKbps)
            .setOutChannels(channels)
            .setMode(LameBuilder.Mode.MONO)
            .setQuality(5)
            .build()
    }

    override fun encode(pcm: ShortArray, sampleCount: Int) {
        val lame = encoder ?: return
        val encodedBytes = lame.encode(pcm, pcm, sampleCount, outputBuffer)
        if (encodedBytes <= 0) return

        tagger.write(outputBuffer, encodedBytes)
        output.write(outputBuffer, 0, encodedBytes)
    }

    override fun finishEncoding() {
        val lame = encoder ?: return
        val flushedBytes = lame.flush(outputBuffer)
        if (flushedBytes > 0) {
            tagger.write(outputBuffer, flushedBytes)
            output.write(outputBuffer, 0, flushedBytes)
        }

        val tag = tagger.buildTag(recordedSamples, recordedPeakAmplitude)
        if (tag != null) {
            rewriteHeader(tag)
        }
    }

    override fun releaseEncoder() {
        try {
            encoder?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        encoder = null
    }
}
