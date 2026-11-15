package dev.intercom.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Blackman-windowed half-band FIR low-pass (cutoff = quarter of the high rate), unity DC gain. */
private fun halfBand(taps: Int): FloatArray = FloatArray(taps).also { h ->
    val mid = (taps - 1) / 2
    for (n in 0 until taps) {
        val m = n - mid
        val sinc = if (m == 0) 0.5 else sin(PI * m / 2) / (PI * m)
        val w = 0.42 - 0.5 * cos(2 * PI * n / (taps - 1)) + 0.08 * cos(4 * PI * n / (taps - 1))
        h[n] = (sinc * w).toFloat()
    }
    val sum = h.sum()
    for (n in 0 until taps) h[n] /= sum
}

/** Streaming FIR over a circular delay line. */
private class Fir(private val h: FloatArray) {
    private val delay = FloatArray(h.size)
    private var pos = 0

    fun push(x: Float) {
        delay[pos] = x
        pos = if (pos + 1 == h.size) 0 else pos + 1
    }

    fun output(): Short {
        var acc = 0f
        var idx = pos
        for (k in h.indices) {
            acc += h[k] * delay[idx]
            idx = if (idx + 1 == h.size) 0 else idx + 1
        }
        return acc.roundToInt().coerceIn(-32768, 32767).toShort()
    }
}

/** 48 kHz → 24 kHz: half-band low-pass, keep every second sample. State carries across calls. */
class Decimator2x(taps: Int = 31) {
    private val fir = Fir(halfBand(taps))
    private var odd = false

    /** Consumes [count] samples of [input]; writes ⌊(count + parity) / 2⌋ samples to [out]; returns that count. */
    fun process(input: ShortArray, count: Int, out: ShortArray, outOffset: Int = 0): Int {
        var o = outOffset
        for (i in 0 until count) {
            fir.push(input[i].toFloat())
            odd = !odd
            if (!odd) out[o++] = fir.output()
        }
        return o - outOffset
    }
}

/**
 * 24 kHz → 48 kHz: zero-stuff, then the same half-band low-pass at gain 2 to remove
 * the image. Playing at the native 48 kHz is what lets Android route the track to its
 * low-latency (FastMixer) output; a 24 kHz track takes the normal path (~100+ ms).
 */
class Interpolator2x(taps: Int = 31) {
    private val fir = Fir(halfBand(taps).also { h -> for (i in h.indices) h[i] *= 2f })

    /** Writes 2 × [count] samples to [out]; returns that count. */
    fun process(input: ShortArray, count: Int, out: ShortArray): Int {
        for (i in 0 until count) {
            fir.push(input[i].toFloat())
            out[2 * i] = fir.output()
            fir.push(0f)
            out[2 * i + 1] = fir.output()
        }
        return 2 * count
    }
}
