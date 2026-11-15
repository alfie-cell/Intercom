package dev.intercom.protocol

import android.os.SystemClock

/**
 * NTP-style offset estimator over TIME_PING/PONG samples (PROTOCOL.md "Clock sync & playout").
 * Keeps the offset from the lowest-RTT sample; pure math, no I/O.
 */
class ClockSync {
    /** Responder clock minus requester clock, µs. Null until a sample lands. */
    var offsetMicros: Long? = null
        private set
    var bestRttMicros: Long = Long.MAX_VALUE
        private set

    /** t1: requester send, t2: responder reply, t3: requester receive. True if the estimate improved. */
    fun addSample(t1: Long, t2: Long, t3: Long): Boolean {
        if (t3 < t1) return false
        val rtt = t3 - t1
        if (rtt >= bestRttMicros) return false
        bestRttMicros = rtt
        offsetMicros = t2 - (t1 / 2 + t3 / 2 + (t1 and t3 and 1L))
        return true
    }
}

/** Monotonic µs clock that keeps ticking through deep sleep (talks arrive mid-Doze). */
object Monotonic {
    fun nowMicros(): Long = SystemClock.elapsedRealtimeNanos() / 1_000
}
