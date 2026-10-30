package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.fossify.commons.extensions.showErrorToast
import org.fossify.voicerecorder.extensions.config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/**
 * Base class for recorders that capture raw PCM through [AudioRecord] and encode it themselves.
 *
 * Capturing runs on a dedicated thread, so [start] never blocks the caller. All writes to the
 * output happen on that thread: when [release] is called the stream is only closed once the thread
 * has finished, either on the calling thread or, if the device is slow to stop capturing, from the
 * capture thread itself. This guarantees that no bytes are written after the file is finalized.
 *
 * Subclasses write through [output] and can patch a header with [rewriteHeader] from
 * [finishEncoding], which is invoked exactly once, right before the streams are closed.
 */
abstract class PcmRecorder(protected val context: Context) : Recorder {
    companion object {
        private const val TAG = "PcmRecorder"
        private const val PAUSE_POLL_INTERVAL_MS = 50L
        private const val THREAD_JOIN_TIMEOUT_MS = 5000L
        private const val OUTPUT_BUFFER_SIZE = 64 * 1024
        private const val MIN_PCM_SAMPLES = 1024
    }

    protected val sampleRate = context.config.samplingRate
    protected val channels = 1

    protected val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(MIN_PCM_SAMPLES * 2)
    protected val samplesPerRead = max(minBufferSize / 2, MIN_PCM_SAMPLES)

    private val isPaused = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private val isFinalized = AtomicBoolean(false)
    private val finalizedLatch = CountDownLatch(1)
    private val amplitude = AtomicInteger(0)
    private val peakAmplitude = AtomicInteger(0)
    private val capturedSamples = AtomicLong(0)

    @Volatile
    private var bufferedStream: BufferedOutputStream? = null
    private var outputPath: String? = null
    private var headerPath: String? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
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

    /** Finalizes the stream, for example by writing a container header. Runs once. */
    protected open fun finishEncoding() {}

    /** Releases encoder resources. Runs once, even if capturing failed. */
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
            finalizeRecording()
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
        } finally {
            // When release() gave up waiting for this thread, finalizing here keeps all writes
            // on the same thread and still closes the file.
            if (isReleased.get()) {
                finalizeRecording()
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

        if (captureThread == null) {
            finalizeRecording()
        }

        // Wait for the file to be closed before the caller scans or reads it.
        try {
            finalizedLatch.await(THREAD_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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

    protected fun finalizeRecording() {
        if (!isFinalized.compareAndSet(false, true)) {
            return
        }

        try {
            finishEncoding()
            bufferedStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to finalize the recording", e)
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            try {
                releaseEncoder()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release the encoder", e)
            }

            closeStreams()
            try {
                fileDescriptor?.close()
            } catch (ignored: IOException) {
            }
            fileDescriptor = null
            try {
                audioRecord.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release the audio recorder", e)
            } finally {
                finalizedLatch.countDown()
            }
        }
    }

    private fun rewriteFileHeader(path: String, bytes: ByteArray): Boolean {
        return try {
            RandomAccessFile(File(path), "rw").use { file ->
                file.seek(0)
                file.write(bytes)
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to rewrite the header through $path", e)
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
            Log.e(TAG, "Failed to rewrite the header through the descriptor", e)
            false
        }
    }

    private fun stopCaptureThread() {
        val thread = captureThread ?: return
        var finished = false
        try {
            thread.join(THREAD_JOIN_TIMEOUT_MS)
            finished = !thread.isAlive
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (finished) {
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
            Log.e(TAG, "Failed to close the output stream", e)
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
