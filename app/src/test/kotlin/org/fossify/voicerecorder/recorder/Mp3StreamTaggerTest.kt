package org.fossify.voicerecorder.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Mp3StreamTaggerTest {
    private val frameLength = 288
    private val samplesPerFrame = 1152
    private val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x74.toByte(), 0xC4.toByte())

    @Test
    fun buildsTagMatchingLameLayout() {
        val tagger = Mp3StreamTagger()
        val stream = buildStream(frames = 10)
        tagger.write(stream, stream.size)

        val tag = tagger.buildTag(recordedSamples = 10L * samplesPerFrame, peakAmplitude = 12000)

        assertNotNull(tag)
        tag!!
        assertEquals(frameLength, tag.size)
        assertEquals("Info", tag.copyOfRange(21, 25).toString(Charsets.US_ASCII))
        assertEquals(0x0F, readInt(tag, 25))
        assertEquals(9, readInt(tag, 29))
        assertEquals(10 * frameLength, readInt(tag, 33))
        assertEquals(55, readInt(tag, 137))
        assertEquals("LAME3.100", tag.copyOfRange(141, 150).toString(Charsets.US_ASCII))
        assertEquals(0x01, tag[150].toInt())
        // Mono raises LAME's lowpass by 1.5 and caps it at 20500 Hz, i.e. 205 in 100 Hz units.
        assertEquals(205, tag[151].toInt() and 0xFF)
        assertEquals(10 * frameLength, readInt(tag, 169))
    }

    @Test
    fun tocIsMonotonicAndCoversWholeStream() {
        val tagger = Mp3StreamTagger()
        val stream = buildStream(frames = 64)
        tagger.write(stream, stream.size)

        val tag = tagger.buildTag(recordedSamples = 64L * samplesPerFrame, peakAmplitude = 0)!!

        val toc = tag.copyOfRange(37, 137).map { it.toInt() and 0xFF }
        assertEquals(0, toc.first())
        assertEquals(255, toc.last())
        for (i in 1 until toc.size) {
            assert(toc[i] >= toc[i - 1]) { "TOC must not decrease at $i" }
        }
    }

    @Test
    fun writesEncoderDelayAndPaddingForExactDuration() {
        val tagger = Mp3StreamTagger()
        val stream = buildStream(frames = 10)
        tagger.write(stream, stream.size)

        // Ten frames decode to 11520 samples; 9216 samples were captured. LAME's delay is 576
        // samples, and the remainder must be marked as padding so players report the real length.
        val tag = tagger.buildTag(recordedSamples = 8L * samplesPerFrame, peakAmplitude = 0)!!

        val delay = ((tag[162].toInt() and 0xFF) shl 4) or ((tag[163].toInt() and 0xFF) ushr 4)
        val padding = ((tag[163].toInt() and 0x0F) shl 8) or (tag[164].toInt() and 0xFF)
        assertEquals(576, delay)
        // The tag frame is excluded from the audio frame count: 9 data frames hold 10368 samples,
        // the capture stopped at 9216, and the 576 sample delay leaves 576 samples of padding.
        assertEquals(9 * samplesPerFrame - 8 * samplesPerFrame - 576, padding)
    }

    @Test
    fun rejectsStreamWithoutReservedFrame() {
        val tagger = Mp3StreamTagger()
        val stream = buildStream(frames = 10, emptyFirstPayload = false)
        tagger.write(stream, stream.size)

        assertNull(tagger.buildTag(recordedSamples = 10L * samplesPerFrame, peakAmplitude = 12000))
    }

    @Test
    fun rejectsTruncatedStream() {
        val tagger = Mp3StreamTagger()
        val stream = buildStream(frames = 4)
        tagger.write(stream, stream.size - 10)

        assertNull(tagger.buildTag(recordedSamples = 4L * samplesPerFrame, peakAmplitude = 12000))
    }

    @Test
    fun rejectsCorruptStream() {
        val tagger = Mp3StreamTagger()
        val stream = buildStream(frames = 4)
        stream[frameLength + 1] = 0x00
        tagger.write(stream, stream.size)

        assertNull(tagger.buildTag(recordedSamples = 4L * samplesPerFrame, peakAmplitude = 12000))
    }

    @Test
    fun rejectsSingleFrameStream() {
        val tagger = Mp3StreamTagger()
        val stream = buildStream(frames = 1)
        tagger.write(stream, stream.size)

        assertNull(tagger.buildTag(recordedSamples = samplesPerFrame.toLong(), peakAmplitude = 0))
    }

    @Test
    fun handlesChunkBoundaries() {
        val tagger = Mp3StreamTagger()
        val stream = buildStream(frames = 12)
        var position = 0
        val steps = intArrayOf(1, 3, 17, 100, 7)
        var index = 0
        while (position < stream.size) {
            val length = minOf(steps[index % steps.size], stream.size - position)
            val chunk = stream.copyOfRange(position, position + length)
            tagger.write(chunk, chunk.size)
            position += length
            index++
        }

        val tag = tagger.buildTag(recordedSamples = 12L * samplesPerFrame, peakAmplitude = 8000)
        assertNotNull(tag)
        assertEquals(11, readInt(tag!!, 29))
    }

    @Test
    fun producesSameBytesRegardlessOfChunking() {
        val stream = buildStream(frames = 20)
        val single = Mp3StreamTagger().apply { write(stream, stream.size) }
            .buildTag(20L * samplesPerFrame, 1000)

        for (chunkSize in intArrayOf(1, 2, 5, 13, 97)) {
            val chunkedTagger = Mp3StreamTagger()
            var position = 0
            while (position < stream.size) {
                val length = minOf(chunkSize, stream.size - position)
                chunkedTagger.write(stream.copyOfRange(position, position + length), length)
                position += length
            }
            val chunked = chunkedTagger.buildTag(20L * samplesPerFrame, 1000)

            assertNotNull(single)
            assertNotNull(chunked)
            assertEquals(single!!.toList(), chunked!!.toList())
        }
    }

    private fun buildStream(frames: Int, emptyFirstPayload: Boolean = true): ByteArray {
        val stream = ByteArray(frames * frameLength)
        for (frame in 0 until frames) {
            val offset = frame * frameLength
            header.copyInto(stream, offset)
            if (frame > 0 || !emptyFirstPayload) {
                for (i in 4 until frameLength) {
                    stream[offset + i] = (frame + i).toByte()
                }
            }
        }
        return stream
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
