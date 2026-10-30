package org.fossify.voicerecorder.recorder

import android.os.ParcelFileDescriptor

interface Recorder {
    fun setOutputFile(path: String)
    fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor)

    /**
     * Sets the path of the output file.
     *
     * Needed when the output is written through a (possibly non seekable) file descriptor, so that
     * containers with a header can still be finalized afterwards.
     */
    fun setHeaderFile(path: String) {}

    fun prepare()
    fun start()
    fun stop()
    fun pause()
    fun resume()
    fun release()
    fun getMaxAmplitude(): Int
}
