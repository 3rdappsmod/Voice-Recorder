package org.fossify.voicerecorder.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3FrameHeaderTest {
    @Test
    fun parsesMpeg1Mono48kHz96kbps() {
        // LAME emits this header for mono 48 kHz 96 kbps CBR: frame length 288 bytes.
        val header = Mp3FrameHeader.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x74.toByte(), 0xC4.toByte()))

        assertNotNull(header)
        header!!
        assertEquals(3, header.version)
        assertEquals(96000, header.bitrate)
        assertEquals(48000, header.sampleRate)
        assertEquals(3, header.channelMode)
        assertTrue(header.isMono)
        assertEquals(288, header.frameLength)
        assertEquals(1152, header.samplesPerFrame)
        assertEquals(17, header.sideInfoLength)
    }

    @Test
    fun parsesMpeg1Stereo44kHz128kbps() {
        val header = Mp3FrameHeader.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x04.toByte()))

        assertNotNull(header)
        header!!
        assertEquals(128000, header.bitrate)
        assertEquals(44100, header.sampleRate)
        assertEquals(0, header.channelMode)
        assertEquals(417, header.frameLength)
        assertEquals(32, header.sideInfoLength)
    }

    @Test
    fun parsesMpeg2Mono8kHz32kbps() {
        val header = Mp3FrameHeader.parse(byteArrayOf(0xFF.toByte(), 0xE3.toByte(), 0x48.toByte(), 0xC4.toByte()))

        assertNotNull(header)
        header!!
        assertEquals(0, header.version)
        assertEquals(32000, header.bitrate)
        assertEquals(8000, header.sampleRate)
        assertEquals(576, header.samplesPerFrame)
        assertEquals(9, header.sideInfoLength)
    }

    @Test
    fun rejectsInvalidHeaders() {
        assertNull(Mp3FrameHeader.parse(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        assertNull(Mp3FrameHeader.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x04.toByte(), 0xC4.toByte())))
        assertNull(Mp3FrameHeader.parse(byteArrayOf(0xFF.toByte())))
    }
}
