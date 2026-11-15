package dev.intercom.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import dev.intercom.protocol.Wire
import kotlin.concurrent.thread

/**
 * Mic → 48 kHz mono (VOICE_COMMUNICATION: platform echo/noise processing) →
 * 2:1 decimation → exact 960-byte, 20 ms, 24 kHz s16le frames, in order.
 */
class Capture(private val onFrame: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Caller must hold RECORD_AUDIO. Throws if the mic can't be opened. */
    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "48 kHz capture not supported" }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, IN_RATE * 2 / 10),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("Microphone unavailable")
        }
        rec.startRecording()
        running = true
        worker = thread(name = "intercom-capture") { loop(rec) }
    }

    fun stop() {
        running = false
        worker?.join(250)
        worker = null
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val decimator = Decimator2x()
        val input = ShortArray(Wire.FRAME_SAMPLES * 2)
        val pending = ShortArray(Wire.FRAME_SAMPLES * 2)
        var fill = 0
        try {
            while (running) {
                val n = rec.read(input, 0, input.size)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error $n")
                    break
                }
                fill += decimator.process(input, n, pending, fill)
                while (fill >= Wire.FRAME_SAMPLES) {
                    onFrame(toBytes(pending, Wire.FRAME_SAMPLES))
                    pending.copyInto(pending, 0, Wire.FRAME_SAMPLES, fill)
                    fill -= Wire.FRAME_SAMPLES
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun toBytes(s: ShortArray, n: Int) = ByteArray(n * 2).also { b ->
        for (k in 0 until n) {
            b[2 * k] = s[k].toInt().toByte()
            b[2 * k + 1] = (s[k].toInt() shr 8).toByte()
        }
    }

    private companion object {
        const val TAG = "IntercomCapture"
        const val IN_RATE = 48_000
    }
}
