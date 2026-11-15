package dev.intercom

import dev.intercom.protocol.ClockSync
import dev.intercom.protocol.GroupAuth
import dev.intercom.protocol.Packet
import dev.intercom.protocol.Wire
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProtocolTest {
    private val pcm = ByteArray(Wire.FRAME_BYTES) { ((it * 7) and 0xFF).toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun roundtripAllTypes() {
        val packets = listOf(
            Packet.TalkStart.create(0xDEADBEEF.toInt(), 123_456_789, "Kitchen Phone", "pw"),
            Packet.Audio(0xDEADBEEF.toInt(), 41, 123_456_789 + 41 * 20_000L, pcm),
            Packet.TalkEnd(0xDEADBEEF.toInt(), 41),
            Packet.TimePing(0xDEADBEEF.toInt(), 2, 777),
            Packet.TimePong(0xDEADBEEF.toInt(), 2, 777, 999),
        )
        for (p in packets) {
            val decoded = Packet.decode(p.encode())
            assertNotNull(decoded)
            assertArrayEquals(p.encode(), decoded!!.encode())
            assertEquals(p::class, decoded::class)
        }
    }

    @Test
    fun headerLayout() {
        val d = Packet.TalkEnd(0x01020304, 0x0A0B0C0D).encode()
        assertEquals("49430103" + "04030201" + "00000000" + "0d0c0b0a", hex(d))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Packet.decode(ByteArray(0)))
        assertNull(Packet.decode(ByteArray(64)))
        val bad = Packet.TalkEnd(1, 1).encode()
        bad[2] = 99
        assertNull(Packet.decode(bad))
        assertNull(Packet.decode(Packet.TalkEnd(1, 1).encode(), 13)) // truncated
        val start = Packet.TalkStart.create(1, 1, "x", "").encode()
        assertNull(Packet.decode(start, start.size - 1))
    }

    @Test
    fun decodeHonoursLengthOfReusedBuffer() {
        val buf = ByteArray(2048) { 0x55 }
        val p = Packet.TimePing(5, 1, 42).encode()
        p.copyInto(buf)
        assertEquals(Packet.TimePing(5, 1, 42), Packet.decode(buf, p.size))
    }

    @Test
    fun hmacVerification() {
        val p = Packet.TalkStart.create(7, 100, "Study", "kitchen")
        assertTrue(p.verify("kitchen"))
        assertFalse(p.verify("KITCHEN"))
        assertFalse(p.verify(""))
        assertTrue(Packet.TalkStart.create(7, 100, "Study", "").verify(""))
    }

    @Test
    fun nameTruncationKeepsValidUtf8() {
        val ascii = Packet.TalkStart.create(1, 1, "x".repeat(300), "")
        assertEquals(255, ascii.nameBytes.size)
        // 2-byte chars: 255 bytes would split a char, so it must stop at 254.
        val accented = Packet.TalkStart.create(1, 1, "é".repeat(200), "")
        assertEquals(254, accented.nameBytes.size)
        assertEquals("é".repeat(127), accented.name)
        val decoded = Packet.decode(accented.encode()) as Packet.TalkStart
        assertTrue(decoded.verify(""))
    }

    @Test
    fun clockSyncSymmetricPath() {
        val cs = ClockSync()
        assertTrue(cs.addSample(1_000_000, 4_002_000, 1_002_000))
        assertEquals(3_001_000L, cs.offsetMicros)
        assertEquals(2_000L, cs.bestRttMicros)
    }

    @Test
    fun clockSyncKeepsLowestRtt() {
        val cs = ClockSync()
        cs.addSample(0, 10_000, 8_000)
        assertFalse(cs.addSample(100_000, 110_000, 109_000))
        assertEquals(6_000L, cs.offsetMicros)
        assertTrue(cs.addSample(200_000, 203_500, 201_000))
        assertEquals(3_000L, cs.offsetMicros)
        assertFalse(ClockSync().addSample(100, 50, 90))
    }

    /** Conformance against the vectors authored by the Swift implementation. */
    @Test
    fun matchesSharedTestVectors() {
        val path = System.getProperty("intercom.vectors") ?: error("intercom.vectors not set")
        val root = JSONObject(File(path).readText())
        val code = root.getString("groupCode")
        assertEquals(root.getString("grpTag"), GroupAuth.grpTag(code))

        val expected = mutableMapOf<String, String>()
        val arr = root.getJSONArray("packets")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            expected[o.getString("name")] = o.getString("hex")
        }
        val id = 0x01020304
        val ours = mapOf(
            "talk_start" to Packet.TalkStart.create(id, 1_000_000, "Office Mac", code),
            "audio" to Packet.Audio(id, 7, 1_140_000, pcm),
            "talk_end" to Packet.TalkEnd(id, 42),
            "time_ping" to Packet.TimePing(id, 0, 2_000_000),
            "time_pong" to Packet.TimePong(id, 0, 2_000_000, 5_000_500),
        )
        assertEquals(expected.keys, ours.keys)
        for ((name, packet) in ours) {
            assertEquals(name, expected[name], hex(packet.encode()))
            val bytes = expected[name]!!.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertArrayEquals(name, bytes, Packet.decode(bytes)!!.encode())
        }
        val start = Packet.decode(ours["talk_start"]!!.encode()) as Packet.TalkStart
        assertTrue(start.verify(code))
        assertFalse(start.verify("wrong"))

        val clock = root.getJSONObject("clock")
        val cs = ClockSync()
        cs.addSample(clock.getLong("t1"), clock.getLong("t2"), clock.getLong("t3"))
        assertEquals(clock.getLong("expectedOffsetMicros"), cs.offsetMicros)
        assertEquals(clock.getLong("expectedRttMicros"), cs.bestRttMicros)
    }
}
