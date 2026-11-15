package dev.intercom

import dev.intercom.audio.Decimator2x
import dev.intercom.audio.Interpolator2x
import dev.intercom.audio.Timeline
import dev.intercom.protocol.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioDspTest {
    private fun tone(freq: Double, n: Int, amp: Double = 10_000.0) =
        ShortArray(n) { (amp * sin(2 * PI * freq * it / 48_000)).toInt().toShort() }

    private fun rms(s: ShortArray, skip: Int) =
        sqrt(s.drop(skip).sumOf { it.toDouble() * it } / (s.size - skip))

    private fun decimate(input: ShortArray, chunk: Int = 960): ShortArray {
        val d = Decimator2x()
        val out = ShortArray(input.size / 2 + 1)
        var o = 0
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            o += d.process(input.copyOfRange(i, i + n), n, out, o)
            i += n
        }
        return out.copyOf(o)
    }

    @Test
    fun halvesSampleCount() {
        assertEquals(480, decimate(ShortArray(960)).size)
        assertEquals(24_000, decimate(ShortArray(48_000), chunk = 333).size) // odd chunking keeps phase
    }

    @Test
    fun passesDcAndSpeechBand() {
        val dc = decimate(ShortArray(4_800) { 1_000 })
        assertTrue(dc.drop(40).all { it in 998..1_002 })
        val inRms = rms(tone(1_000.0, 48_000), 0)
        val outRms = rms(decimate(tone(1_000.0, 48_000)), 100)
        assertEquals(1.0, outRms / inRms, 0.01)
    }

    @Test
    fun rejectsAliasingBand() {
        // 18 kHz would fold to 6 kHz at 24 kHz output; it must be attenuated hard.
        val inRms = rms(tone(18_000.0, 48_000), 0)
        val outRms = rms(decimate(tone(18_000.0, 48_000)), 100)
        val db = 20 * log10(outRms / inRms)
        assertTrue("attenuation only $db dB", db < -50)
    }

    /** Amplitude of [freq] in [s] at [rate] (Goertzel), skipping the filter warm-up. */
    private fun amplitude(s: ShortArray, rate: Double, freq: Double, skip: Int = 200): Double {
        val n = s.size - skip
        val w = 2 * PI * freq / rate
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            re += s[skip + i] * kotlin.math.cos(w * i)
            im += s[skip + i] * sin(w * i)
        }
        return 2 * sqrt(re * re + im * im) / n
    }

    @Test
    fun interpolatorDoublesRateAndRejectsImage() {
        val input = ShortArray(24_000) { (10_000 * sin(2 * PI * 1_000 * it / 24_000)).toInt().toShort() }
        val out = ShortArray(48_000)
        assertEquals(48_000, Interpolator2x().process(input, input.size, out))
        assertEquals(10_000.0, amplitude(out, 48_000.0, 1_000.0), 100.0)        // speech band at unity gain
        val imageDb = 20 * log10(amplitude(out, 48_000.0, 23_000.0) / 10_000)   // zero-stuffing mirror
        assertTrue("image only $imageDb dB down", imageDb < -50)
        val dc = ShortArray(960)
        Interpolator2x().process(ShortArray(480) { 1_000 }, 480, dc)
        assertTrue(dc.drop(40).all { it in 998..1_002 })
    }

    private fun frame(value: Short) = ByteArray(Wire.FRAME_BYTES).also {
        for (k in 0 until Wire.FRAME_SAMPLES) {
            it[2 * k] = (value.toInt() and 0xFF).toByte()
            it[2 * k + 1] = (value.toInt() shr 8).toByte()
        }
    }

    @Test
    fun timelinePlacesFramesBySenderTimestamp() {
        val t0 = 5_000_000L
        val tl = Timeline(t0)
        tl.write(t0, frame(100))
        tl.write(t0 + 2 * Wire.FRAME_MICROS, frame(-300)) // frame 1 lost
        assertEquals(3L * Wire.FRAME_SAMPLES, tl.highest)

        val out = ShortArray(3 * Wire.FRAME_SAMPLES + 10)
        tl.read(-5, out, out.size)
        assertTrue(out.copyOfRange(0, 5).all { it == 0.toShort() })             // before t0
        assertTrue(out.copyOfRange(5, 485).all { it == 100.toShort() })         // frame 0
        assertTrue(out.copyOfRange(485, 965).all { it == 0.toShort() })         // lost frame → silence
        assertTrue(out.copyOfRange(965, 1_445).all { it == (-300).toShort() })  // frame 2
        assertTrue(out.copyOfRange(1_445, out.size).all { it == 0.toShort() })  // not yet arrived
    }

    @Test
    fun timelineLateFrameFillsItsOwnSlotAndOutOfOrderIsFine() {
        val t0 = 0L
        val tl = Timeline(t0)
        tl.write(Wire.FRAME_MICROS, frame(7))
        tl.write(0, frame(3)) // arrives late but before playout
        val out = ShortArray(2 * Wire.FRAME_SAMPLES)
        tl.read(0, out, out.size)
        assertEquals(3.toShort(), out[0])
        assertEquals(7.toShort(), out[Wire.FRAME_SAMPLES])
    }

    @Test
    fun timelineIgnoresAbsurdTimestamps() {
        val tl = Timeline(1_000)
        tl.write(0, frame(1))                       // before t0
        tl.write(1_000 + 3_600_000_000L, frame(1))  // an hour in
        assertEquals(0L, tl.highest)
    }
}
