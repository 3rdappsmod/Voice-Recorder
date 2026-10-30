package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import org.fossify.commons.extensions.showErrorToast
import org.fossify.voicerecorder.extensions.config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/**
 * Base class for recorders that capture raw PCM through [AudioRecord] and encode it themselves.
 *
 * Capturing happens on a dedicated thread, so [start] never blocks the caller. [stop] is idempotent
 * and waits for the capture thread to terminate, which guarantees no more bytes are written once it
 * returns. Subclasses write through [output] and can patch a header with [rewriteHeader] when the
 * recording is finished.
 */
abstract class PcmRecorder(protected val context: Context) : Recorder {
    companion object {
        private const val PAUSE_POLL_INTERVAL_MS = 50L
        private const val THREAD_JOIN_TIMEOUT_MS = 5000L
        private const val OUTPUT_BUFFER_SIZE = 64 * 1024
        private const val MIN_PCM_SAMPLES = 1024
    }

    protected val sampleRate = context.config.samplingRate
    protected val channels = 1
    protected val bytesPerSample = 2

    protected val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(MIN_PCM_SAMPLES * 2)
    protected val samplesPerRead = max(minBufferSize / 2, MIN_PCM_SAMPLES)

    private val isPaused = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private val amplitude = AtomicInteger(0)
    private val peakAmplitude = AtomicInteger(0)
    private val capturedSamples = AtomicLong(0)

    private var outputPath: String? = null
    private var headerPath: String? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var bufferedStream: BufferedOutputStream? = null
    private var captureThread: Thread? = null

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        context.config.microphoneMode,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferSize * 2
    )

    /** Name of the background capture thread, used for debugging. */
    protected abstract val threadName: String

    /** Stream encoder implementations should write to. */
    protected val output: OutputStream
        get() = checkNotNull(bufferedStream) { "The recorder has not been started" }

    /** Number of PCM samples captured so far. */
    protected val recordedSamples: Long
        get() = capturedSamples.get()

    /** Highest absolute sample value captured so far. */
    protected val recordedPeakAmplitude: Int
        get() = peakAmplitude.get()

    /** Sets up the encoder before the first PCM samples are passed to [encode]. */
    protected abstract fun createEncoder()

    /** Encodes and writes [sampleCount] samples from [pcm]. */
    protected abstract fun encode(pcm: ShortArray, sampleCount: Int)

    /** Called after the capture thread finished, before the streams are closed. */
    protected open fun finishEncoding() {}

    /** Called once the recorder is released, even if the capture failed. */
    protected open fun releaseEncoder() {}

    override fun setOutputFile(path: String) {
        outputPath = path
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        fileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }

    override fun setHeaderFile(path: String) {
        headerPath = path
    }

    override fun prepare() {}

    override fun start() {
        val fileStream = openFileStream()
        val buffered = BufferedOutputStream(fileStream, OUTPUT_BUFFER_SIZE)
        bufferedStream = buffered

        try {
            createEncoder()
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Unable to start audio recording"
            }
        } catch (e: Exception) {
            closeStreams()
            releaseEncoder()
            throw e
        }

        val pcmBuffer = ShortArray(samplesPerRead)
        captureThread = Thread({ captureLoop(pcmBuffer) }, threadName).apply { start() }
    }

    private fun captureLoop(pcmBuffer: ShortArray) {
        try {
            while (!isStopped.get()) {
                if (isPaused.get()) {
                    Thread.sleep(PAUSE_POLL_INTERVAL_MS)
                    continue
                }

                val sampleCount = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (sampleCount < 0) {
                    break
                } else if (sampleCount == 0) {
                    continue
                }

                capturedSamples.addAndGet(sampleCount.toLong())
                updateAmplitude(pcmBuffer, sampleCount)
                encode(pcmBuffer, sampleCount)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!isStopped.get()) {
                context.showErrorToast(e)
            }
        }
    }

    override fun stop() {
        isPaused.set(true)
        isStopped.set(true)
        try {
            audioRecord.stop()
        } catch (ignored: IllegalStateException) {
            // The recorder was already stopped or has never been started.
        }
        stopCaptureThread()
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        if (!isReleased.compareAndSet(false, true)) {
            return
        }

        stop()

        try {
            finishEncoding()
            flushOutput()
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            try {
                releaseEncoder()
            } catch (ignored: Exception) {
            }

            closeStreams()
            try {
                fileDescriptor?.close()
            } catch (ignored: IOException) {
            }
            fileDescriptor = null
            try {
                audioRecord.release()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun getMaxAmplitude() = amplitude.get()

    /** Pushes buffered audio to the underlying file. */
    protected fun flushOutput() {
        bufferedStream?.flush()
    }

    /**
     * Overwrites the beginning of the output with [bytes].
     *
     * The file position is restored afterwards, so this can be called before the last bytes are
     * written. Returns false when the underlying file cannot be seeked, for example when it lives on
     * a stream that only supports appending.
     */
    protected fun rewriteHeader(bytes: ByteArray): Boolean {
        flushOutput()

        // Prefer the descriptor: files opened through SAF cannot always be reopened by path.
        val descriptor = fileDescriptor
        if (descriptor != null && rewriteDescriptorHeader(descriptor, bytes)) {
            return true
        }

        val path = headerPath ?: outputPath
        return path != null && rewriteFileHeader(path, bytes)
    }

    private fun rewriteFileHeader(path: String, bytes: ByteArray): Boolean {
        return try {
            RandomAccessFile(File(path), "rw").use { file ->
                file.seek(0)
                file.write(bytes)
            }
            true
        } catch (e: IOException) {
            android.util.Log.e("PcmRecorder", "Failed to rewrite header through $path", e)
            false
        }
    }

    private fun rewriteDescriptorHeader(descriptor: ParcelFileDescriptor, bytes: ByteArray): Boolean {
        val fd = descriptor.fileDescriptor
        return try {
            val endPosition = Os.lseek(fd, 0, OsConstants.SEEK_END)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            Os.write(fd, bytes, 0, bytes.size)
            Os.lseek(fd, endPosition, OsConstants.SEEK_SET)
            true
        } catch (e: Exception) {
            android.util.Log.e("PcmRecorder", "Failed to rewrite header through the descriptor", e)
            false
        }
    }

    private fun stopCaptureThread() {
        val thread = captureThread ?: return
        try {
            thread.join(THREAD_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (!thread.isAlive) {
            captureThread = null
        }
    }

    private fun openFileStream(): FileOutputStream {
        val descriptor = fileDescriptor
        return if (descriptor != null) {
            FileOutputStream(descriptor.fileDescriptor)
        } else {
            val path = checkNotNull(outputPath) { "No output file has been specified" }
            FileOutputStream(File(path))
        }
    }

    private fun closeStreams() {
        try {
            bufferedStream?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        bufferedStream = null
    }

    private fun updateAmplitude(data: ShortArray, sampleCount: Int) {
        var chunkPeak = 0
        for (i in 0 until sampleCount) {
            val sampleAmplitude = abs(data[i].toInt())
            if (sampleAmplitude > chunkPeak) {
                chunkPeak = sampleAmplitude
            }
        }
        amplitude.set(chunkPeak)

        if (chunkPeak > peakAmplitude.get()) {
            peakAmplitude.set(chunkPeak)
        }
    }
}
