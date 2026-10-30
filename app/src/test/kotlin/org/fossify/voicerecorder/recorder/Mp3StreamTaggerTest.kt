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
        assertEquals(0, tag[21 + 8 + 4 + 4 + 100].toInt())
        assertEquals("LAME", tag.copyOfRange(141, 145).toString(Charsets.US_ASCII))
        assertEquals(10 * frameLength, readInt(tag, 141 + 9 + 1 + 1 + 4 + 2 + 2 + 1 + 1 + 3 + 1 + 1 + 2))
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
