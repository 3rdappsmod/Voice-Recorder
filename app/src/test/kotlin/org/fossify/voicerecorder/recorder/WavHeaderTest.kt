package org.fossify.voicerecorder.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {
    @Test
    fun buildsCanonicalHeader() {
        val header = WavHeader.build(sampleRate = 48000, channels = 1, dataLength = 96000)

        assertEquals(WavHeader.HEADER_SIZE, header.size)
        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", header.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", header.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(36 + 96000, readInt(header, 4))
        assertEquals(1, readShort(header, 20))
        assertEquals(1, readShort(header, 22))
        assertEquals(48000, readInt(header, 24))
        assertEquals(96000, readInt(header, 28))
        assertEquals(2, readShort(header, 32))
        assertEquals(16, readShort(header, 34))
        assertEquals(96000, readInt(header, 40))
    }

    @Test
    fun emptyRecordingHasZeroSizes() {
        val header = WavHeader.build(sampleRate = 8000, channels = 1, dataLength = 0)

        assertEquals(36, readInt(header, 4))
        assertEquals(0, readInt(header, 40))
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
