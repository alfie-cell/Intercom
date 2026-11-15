package dev.intercom.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.intercom.protocol.Monotonic
import dev.intercom.protocol.Wire
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Receiver playback: one [TalkPlayer] (AudioTrack + thread) per active talk, so
 * simultaneous senders mix in the platform mixer. While anything plays it holds
 * transient-duck audio focus and sets the media volume to the intercom's own level,
 * restoring the user's level afterwards. Thread-safe.
 */
class Playback(context: Context) {
    @Volatile var delayMs = Wire.DEFAULT_DELAY_MS
    @Volatile var chirpEnabled = true
    /** Media volume (0–100 %) to play messages at. */
    @Volatile var volumePercent = 80
    /** If the media volume is at zero, play anyway (at [volumePercent]). */
    @Volatile var playWhenMuted = true
    /** Fires (on a player thread) when the last active talk finishes playing. */
    var onIdle: (() -> Unit)? = null

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val players = ConcurrentHashMap<Int, TalkPlayer>()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(ATTRS)
        .build()
    /** User's media volume before we changed it, and the level we set; null when untouched. */
    private var savedVolume: Pair<Int, Int>? = null

    fun beginTalk(talkId: Int, t0: Long) {
        val p = TalkPlayer(t0, delayMs * 1_000L, chirpEnabled) { finished(talkId) }
        synchronized(this) {
            if (players.isEmpty()) {
                audioManager.requestAudioFocus(focus)
                applyIntercomVolume()
            }
            players[talkId] = p
        }
        p.start()
    }

    fun setOffset(talkId: Int, offsetMicros: Long) = players[talkId]?.updateOffset(offsetMicros)
    fun addFrame(talkId: Int, ts: Long, pcm: ByteArray, rxAt: Long) = players[talkId]?.addFrame(ts, pcm, rxAt)
    fun endTalk(talkId: Int) = players[talkId]?.markEnded()

    fun stopAll() {
        players.values.forEach { it.cancel() }
    }

    private fun finished(talkId: Int) = synchronized(this) {
        players.remove(talkId)
        if (players.isEmpty()) {
            restoreUserVolume()
            audioManager.abandonAudioFocusRequest(focus)
            onIdle?.invoke()
        }
    }

    private fun applyIntercomVolume() {
        if (audioManager.isVolumeFixed) return
        val stream = AudioManager.STREAM_MUSIC
        val current = audioManager.getStreamVolume(stream)
        if (current == 0 && !playWhenMuted) return
        val max = audioManager.getStreamMaxVolume(stream)
        val target = (max * volumePercent + 50) / 100
        if (target == current) return
        try {
            audioManager.setStreamVolume(stream, target, 0)
            savedVolume = current to target
        } catch (e: SecurityException) {
            Log.w(TAG, "can't set media volume (Do Not Disturb?): $e")
        }
    }

    private fun restoreUserVolume() {
        val (user, ours) = savedVolume ?: return
        savedVolume = null
        val stream = AudioManager.STREAM_MUSIC
        // If the user moved the volume during the message, their choice wins.
        if (audioManager.getStreamVolume(stream) != ours) return
        runCatching { audioManager.setStreamVolume(stream, user, 0) }
    }

    private companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /**
     * Plays one talk so that the sample stamped sender-time `ts` leaves the speaker at
     * receiver-local `ts − offset + D` (PROTOCOL.md). Writes are paced by the DAC; every
     * 500 ms (and whenever the offset or arrival lag changes) the track's presentation
     * timestamp is checked and the read position nudged (skip ahead or pad silence) if it
     * drifted more than 5 ms. If this device's output latency plus the measured arrival
     * lag doesn't fit inside D, D is raised for this device only (see effectiveDelayMicros).
     */
    private class TalkPlayer(
        private val t0: Long,
        private val delayMicros: Long,
        private val chirp: Boolean,
        private val onFinished: () -> Unit,
    ) : Thread("intercom-play") {
        val timeline = Timeline(t0)
        @Volatile private var offsetMicros = Monotonic.nowMicros().let { t0 - it } // fallback: play on arrival
        @Volatile private var offsetLocked = false
        @Volatile private var offsetDirty = true
        @Volatile private var ended = false
        @Volatile private var cancelled = false
        /** Worst (arrival − sender timestamp) seen, receiver clock minus sender clock, µs. */
        @Volatile private var maxArrivalLag = Long.MIN_VALUE
        @Volatile private var lagDirty = false

        fun addFrame(ts: Long, pcm: ByteArray, rxAt: Long) {
            timeline.write(ts, pcm)
            // An idle receiver's Wi-Fi power-save holds the first packets of a talk for up to
            // a beacon interval. Those frames are nearly always pre-speech silence, so they
            // may be dropped rather than delay (and desync) the whole talk.
            if (ts - t0 < STARTUP_GRACE_MICROS) return
            val lag = rxAt - ts
            if (lag > maxArrivalLag) {
                maxArrivalLag = lag
                lagDirty = true
            }
        }

        fun updateOffset(offset: Long) {
            if (offsetLocked) return
            offsetMicros = offset
            offsetDirty = true
        }

        fun markEnded() {
            ended = true
        }

        fun cancel() {
            cancelled = true
        }

        /**
         * The shared delay D, raised for this device alone when its output latency (e.g. a
         * Bluetooth speaker) leaves too little time for audio to arrive: playing slightly
         * out of sync beats playing nothing. Only ever grows within a talk. Player thread only.
         */
        private var effectiveDelayMicros = delayMicros

        /** Talk-timeline sample index that should be audible at receiver-local time [localMicros]. */
        private fun indexAt(localMicros: Long) =
            Wire.microsToSamples(localMicros + offsetMicros - effectiveDelayMicros - t0)

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val track = try {
                buildTrack()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack failed: $e")
                onFinished()
                return
            }
            val chunk = ShortArray(Wire.FRAME_SAMPLES)            // 24 kHz talk timeline
            val out = ShortArray(Wire.FRAME_SAMPLES * 2)          // 48 kHz to the track
            val silence = ShortArray(Wire.FRAME_SAMPLES * 2)
            val upsampler = Interpolator2x()
            val stamp = AudioTimestamp()
            var written = 0L // in OUT_RATE frames
            var lastAlign = 0L
            var audible = 0L
            var corrections = 0
            var next = indexAt(Monotonic.nowMicros() + track.bufferSizeInFrames * 1_000_000L / OUT_RATE)
            try {
                track.play()
                while (!cancelled) {
                    if (ended && next >= timeline.highest) break
                    if (next > Timeline.MAX_INDEX || next > timeline.highest + STALL_LIMIT) break

                    val now = Monotonic.nowMicros()
                    val presentAt: Long? = if (offsetDirty || lagDirty || now - lastAlign > 500_000) {
                        if (track.getTimestamp(stamp)) {
                            lagDirty = false
                            val monoToElapsed = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
                            val at = (stamp.nanoTime + monoToElapsed +
                                (written - stamp.framePosition) * 1_000_000_000L / OUT_RATE) / 1_000
                            // A sample written now is heard (at − now) later, and must already have
                            // arrived: D has to cover output latency plus the worst arrival lag
                            // (sender capture buffering + transit + Wi-Fi power-save delays).
                            val arrival = if (maxArrivalLag == Long.MIN_VALUE) 0L
                                          else maxOf(0L, maxArrivalLag + offsetMicros)
                            val needed = (at - now) + arrival + SAFETY_MICROS
                            if (needed > effectiveDelayMicros) effectiveDelayMicros = needed
                            at
                        } else if (offsetDirty) {
                            // No hardware timestamp yet: estimate from what's still queued in the track.
                            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            now + (written - head) * 1_000_000L / OUT_RATE
                        } else null
                    } else null
                    if (presentAt != null) {
                        offsetDirty = false
                        lastAlign = now
                        val drift = indexAt(presentAt) - next
                        if (drift > ALIGN_TOLERANCE || drift < -ALIGN_TOLERANCE) {
                            corrections++
                            Log.d(TAG, "align: drift=${drift * 1_000 / Wire.SAMPLE_RATE}ms " +
                                "latency=${(presentAt - now) / 1_000}ms delay=${effectiveDelayMicros / 1_000}ms")
                        }
                        if (drift > ALIGN_TOLERANCE) {
                            next += drift // behind schedule: skip ahead
                        } else if (drift < -ALIGN_TOLERANCE) {
                            var pad = -drift * 2 // ahead of schedule: insert silence (48 kHz frames)
                            while (pad > 0) {
                                val n = min(pad, silence.size.toLong()).toInt()
                                track.write(silence, 0, n)
                                written += n
                                pad -= n
                            }
                        }
                    }

                    timeline.read(next, chunk, chunk.size)
                    if (next >= 0) audible += (minOf(next + chunk.size, timeline.highest) - next).coerceAtLeast(0)
                    if (chirp && next < 0) mixChirp(next, chunk)
                    if (next >= 0) offsetLocked = true // no offset jumps once speech is audible
                    val n = track.write(out, 0, upsampler.process(chunk, chunk.size, out))
                    if (n < 0) {
                        Log.w(TAG, "AudioTrack.write error $n")
                        break
                    }
                    written += n
                    next += n / 2
                }
            } finally {
                runCatching { track.stop() }
                track.release()
                Log.i(TAG, "played ${audible * 1_000 / Wire.SAMPLE_RATE} ms of ${timeline.highest * 1_000 / Wire.SAMPLE_RATE} ms " +
                    "received, $corrections timing corrections, delay ${effectiveDelayMicros / 1_000} ms" +
                    if (effectiveDelayMicros > delayMicros) " (raised for this device's output latency)" else "")
                onFinished()
            }
        }

        private fun buildTrack(): AudioTrack {
            val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(ATTRS)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OUT_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, OUT_RATE * 2 * 30 / 1_000)) // 30 ms: one 20 ms write + slack
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }

        private fun mixChirp(from: Long, chunk: ShortArray) {
            for (k in chunk.indices) {
                val c = (from + k - CHIRP_START).toInt()
                if (c in CHIRP.indices) chunk[k] = CHIRP[c]
            }
        }

        companion object {
            const val ALIGN_TOLERANCE = 120L // 5 ms, in 24 kHz timeline samples
            /** Native rate of virtually all current devices; required for the low-latency output path. */
            const val OUT_RATE = 48_000
            const val STARTUP_GRACE_MICROS = 300_000L
            /** Headroom over the measured latency + arrival lag, for jitter not yet observed. */
            const val SAFETY_MICROS = 20_000L
            val STALL_LIMIT = Wire.microsToSamples(2_000_000) // talk silently vanished

            /** Classic intercom lead-in: two 45 ms tones ending 10 ms before speech. */
            val CHIRP: ShortArray = run {
                val tone = Wire.SAMPLE_RATE * 45 / 1_000
                val fade = Wire.SAMPLE_RATE * 8 / 1_000
                ShortArray(tone * 2) { i ->
                    val freq = if (i < tone) 880.0 else 1174.66
                    val j = i % tone
                    val env = min(1.0, min(j, tone - j).toDouble() / fade)
                    (sin(2 * PI * freq * j / Wire.SAMPLE_RATE) * 0.25 * env * 32_767).toInt().toShort()
                }
            }
            val CHIRP_START = -(CHIRP.size + Wire.SAMPLE_RATE / 100).toLong()
        }
    }
}

private const val TAG = "IntercomPlay"
