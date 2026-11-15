package dev.intercom.audio

import dev.intercom.protocol.Wire

/**
 * One talk's received audio, indexed on the sender's timeline: sample index i
 * is sender time t0 + i / 24 kHz. Lost frames read back as silence; frames
 * that arrive after their slot was read are simply never heard. Thread-safe.
 */
class Timeline(private val t0: Long, private val capacity: Int = Wire.SAMPLE_RATE * 4) {
    private val ring = ShortArray(capacity)

    /** One past the highest sample index written so far. */
    @Volatile var highest = 0L
        private set

    @Synchronized
    fun write(ts: Long, pcm: ByteArray) {
        if (ts < t0) return
        val start = Wire.microsToSamples(ts - t0)
        if (start > MAX_INDEX) return
        val n = pcm.size / 2
        if (start > highest) {
            // Zero the gap so stale data from a previous lap of the ring never plays.
            for (i in highest until minOf(start, highest + capacity)) ring[(i % capacity).toInt()] = 0
        }
        for (k in 0 until n) {
            val s = (pcm[2 * k].toInt() and 0xFF) or (pcm[2 * k + 1].toInt() shl 8)
            ring[((start + k) % capacity).toInt()] = s.toShort()
        }
        highest = maxOf(highest, start + n)
    }

    /** Fills out[0 until count] with samples [from, from + count); unknown samples are 0. */
    @Synchronized
    fun read(from: Long, out: ShortArray, count: Int) {
        val oldest = highest - capacity
        for (k in 0 until count) {
            val i = from + k
            out[k] = if (i >= 0 && i < highest && i >= oldest) ring[(i % capacity).toInt()] else 0
        }
    }

    companion object {
        /** Talks are capped at 60 s; allow a second of slack for timestamps. */
        val MAX_INDEX = Wire.microsToSamples((Wire.MAX_TALK_MS + 1_000) * 1_000)
    }
}
