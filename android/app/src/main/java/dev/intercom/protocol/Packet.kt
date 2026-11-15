package dev.intercom.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire packets (PROTOCOL.md). u32 fields are carried as Int bit patterns and
 * u64 µs timestamps as Long; all encoding is little-endian.
 */
sealed class Packet {
    abstract val talkId: Int

    class TalkStart(
        override val talkId: Int,
        val ts: Long,
        val format: Byte,
        val nameBytes: ByteArray,
        val hmac: ByteArray,
    ) : Packet() {
        val name: String get() = String(nameBytes, Charsets.UTF_8)

        fun verify(groupCode: String): Boolean =
            MessageDigest.isEqual(hmac, GroupAuth.talkHmac(groupCode, talkId, ts, format, nameBytes))

        companion object {
            fun create(talkId: Int, ts: Long, name: String, groupCode: String): TalkStart {
                val nameBytes = utf8Truncated(name, 255)
                return TalkStart(talkId, ts, Wire.FORMAT_PCM24K, nameBytes,
                    GroupAuth.talkHmac(groupCode, talkId, ts, Wire.FORMAT_PCM24K, nameBytes))
            }
        }
    }

    class Audio(override val talkId: Int, val seq: Int, val ts: Long, val pcm: ByteArray) : Packet()
    data class TalkEnd(override val talkId: Int, val lastSeq: Int) : Packet()
    data class TimePing(override val talkId: Int, val seq: Int, val t1: Long) : Packet()
    data class TimePong(override val talkId: Int, val seq: Int, val t1: Long, val t2: Long) : Packet()

    fun encode(): ByteArray {
        val (type, seq, bodySize) = when (this) {
            is TalkStart -> Triple(1, 0, 8 + 1 + 1 + nameBytes.size + 16)
            is Audio -> Triple(2, seq, 8 + pcm.size)
            is TalkEnd -> Triple(3, 0, 4)
            is TimePing -> Triple(4, seq, 8)
            is TimePong -> Triple(5, seq, 16)
        }
        val buf = ByteBuffer.allocate(Wire.HEADER_BYTES + bodySize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(Wire.MAGIC0).put(Wire.MAGIC1).put(Wire.VERSION).put(type.toByte())
        buf.putInt(talkId).putInt(seq)
        when (this) {
            is TalkStart -> buf.putLong(ts).put(format).put(nameBytes.size.toByte()).put(nameBytes).put(hmac, 0, 16)
            is Audio -> buf.putLong(ts).put(pcm)
            is TalkEnd -> buf.putInt(lastSeq)
            is TimePing -> buf.putLong(t1)
            is TimePong -> buf.putLong(t1).putLong(t2)
        }
        return buf.array()
    }

    companion object {
        /** Returns null for anything malformed, foreign, or from another protocol version. */
        fun decode(data: ByteArray, length: Int = data.size): Packet? {
            if (length < Wire.HEADER_BYTES || data[0] != Wire.MAGIC0 || data[1] != Wire.MAGIC1 ||
                data[2] != Wire.VERSION
            ) return null
            val b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            val type = data[3].toInt()
            val talkId = b.getInt(4)
            val seq = b.getInt(8)
            val body = Wire.HEADER_BYTES
            return when (type) {
                1 -> {
                    if (length < body + 10) return null
                    val nameLen = data[body + 9].toInt() and 0xFF
                    if (length != body + 10 + nameLen + 16) return null
                    TalkStart(
                        talkId, b.getLong(body), data[body + 8],
                        data.copyOfRange(body + 10, body + 10 + nameLen),
                        data.copyOfRange(length - 16, length),
                    )
                }
                2 -> if (length <= body + 8) null
                     else Audio(talkId, seq, b.getLong(body), data.copyOfRange(body + 8, length))
                3 -> if (length < body + 4) null else TalkEnd(talkId, b.getInt(body))
                4 -> if (length < body + 8) null else TimePing(talkId, seq, b.getLong(body))
                5 -> if (length < body + 16) null else TimePong(talkId, seq, b.getLong(body), b.getLong(body + 8))
                else -> null
            }
        }

        /** UTF-8 bytes, cut to at most [max] bytes without splitting a code point. */
        fun utf8Truncated(s: String, max: Int): ByteArray {
            val bytes = s.toByteArray(Charsets.UTF_8)
            if (bytes.size <= max) return bytes
            var cut = max
            while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
            return bytes.copyOf(cut)
        }
    }
}

/** PROTOCOL.md "Group authentication". */
object GroupAuth {
    fun talkHmac(groupCode: String, talkId: Int, ts: Long, format: Byte, nameBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sha256(groupCode.toByteArray(Charsets.UTF_8)), "HmacSHA256"))
        val msg = ByteBuffer.allocate(4 + 8 + 1 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(talkId).putLong(ts).put(format).put(nameBytes).array()
        return mac.doFinal(msg).copyOf(16)
    }

    /** TXT-record tag: first 8 bytes of SHA-256("intercom-grp:" + code), lowercase hex. */
    fun grpTag(groupCode: String): String =
        sha256("intercom-grp:$groupCode".toByteArray(Charsets.UTF_8))
            .copyOf(8).joinToString("") { "%02x".format(it) }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
