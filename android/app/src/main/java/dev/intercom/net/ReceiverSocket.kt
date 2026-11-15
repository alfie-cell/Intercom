package dev.intercom.net

import android.util.Log
import dev.intercom.protocol.ClockSync
import dev.intercom.protocol.Monotonic
import dev.intercom.protocol.Packet
import dev.intercom.protocol.Wire
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Receiver side: one UDP socket; verifies TALK_START, runs the per-talk clock-sync
 * ping burst, demuxes audio, and expires dead talks. All talk state lives on a
 * single executor thread; listener callbacks fire there too.
 */
class ReceiverSocket(private val groupCode: String, private val listener: Listener) {
    interface Listener {
        fun onTalkStart(talkId: Int, senderName: String, t0: Long)
        /** [rxAt]: local monotonic µs when the packet arrived. */
        fun onAudio(talkId: Int, ts: Long, pcm: ByteArray, rxAt: Long)
        fun onOffset(talkId: Int, offsetMicros: Long)
        fun onTalkEnd(talkId: Int)
    }

    private class Talk(val from: SocketAddress, now: Long) {
        val sync = ClockSync()
        var pingSeq = 0
        var lastAudioAt = now
        var ended = false
        var frames = 0
        /** (rx time − sender ts) of the first frame; later frames' excess over it is jitter. */
        var baseLag = 0L
        var maxJitter = 0L
    }

    private val socket: DatagramSocket = bind()
    val port: Int get() = socket.localPort

    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "intercom-rx-state") }
    private val talks = HashMap<Int, Talk>()
    @Volatile private var closed = false

    fun start() {
        thread(name = "intercom-rx", isDaemon = true) { receiveLoop() }
        exec.scheduleWithFixedDelay(::sweep, 500, 500, TimeUnit.MILLISECONDS)
    }

    fun close() {
        closed = true
        socket.close()
        exec.shutdownNow()
    }

    private fun bind(): DatagramSocket {
        for (port in listOf(Wire.PREFERRED_PORT, 0)) {
            try {
                return DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: IOException) {
                Log.w(TAG, "bind $port failed: $e")
            }
        }
        throw IOException("Could not open a UDP socket")
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (!closed) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                socket.receive(dp)
            } catch (e: IOException) {
                if (closed) return else continue
            }
            val rxAt = Monotonic.nowMicros()
            val pkt = Packet.decode(dp.data, dp.length) ?: continue
            val from = dp.socketAddress
            exec.execute { handle(pkt, from, rxAt) }
        }
    }

    private fun handle(pkt: Packet, from: SocketAddress, rxAt: Long) {
        when (pkt) {
            is Packet.TalkStart -> {
                if (talks.containsKey(pkt.talkId)) return // resends are idempotent
                if (pkt.format != Wire.FORMAT_PCM24K || !pkt.verify(groupCode)) {
                    Log.i(TAG, "rejected talk ${pkt.talkId} from $from (group code or format mismatch)")
                    return
                }
                val talk = Talk(from, rxAt)
                talks[pkt.talkId] = talk
                Log.i(TAG, "talk ${pkt.talkId} start from '${pkt.name}' $from")
                listener.onTalkStart(pkt.talkId, pkt.name, pkt.ts)
                schedulePings(pkt.talkId, talk)
            }
            is Packet.Audio -> {
                val talk = talks[pkt.talkId] ?: return
                if (talk.ended || talk.from != from || pkt.pcm.size != Wire.FRAME_BYTES) return
                talk.lastAudioAt = rxAt
                val lag = rxAt - pkt.ts
                if (talk.frames++ == 0) talk.baseLag = lag
                talk.maxJitter = maxOf(talk.maxJitter, lag - talk.baseLag)
                listener.onAudio(pkt.talkId, pkt.ts, pkt.pcm, rxAt)
            }
            is Packet.TalkEnd -> endTalk(pkt.talkId)
            is Packet.TimePong -> {
                val talk = talks[pkt.talkId] ?: return
                if (talk.ended) return
                if (talk.sync.addSample(pkt.t1, pkt.t2, rxAt)) {
                    val offset = talk.sync.offsetMicros!!
                    Log.d(TAG, "talk ${pkt.talkId} offset=$offset rtt=${talk.sync.bestRttMicros}")
                    listener.onOffset(pkt.talkId, offset)
                }
            }
            is Packet.TimePing -> Unit // receivers don't answer pings
        }
    }

    private fun schedulePings(talkId: Int, talk: Talk) {
        for (i in 0 until Wire.PING_COUNT) {
            exec.schedule({
                if (!talk.ended) {
                    val ping = Packet.TimePing(talkId, talk.pingSeq++, Monotonic.nowMicros()).encode()
                    send(ping, talk.from)
                }
            }, i * Wire.PING_SPACING_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun send(bytes: ByteArray, to: SocketAddress) {
        try {
            socket.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: IOException) {
            Log.w(TAG, "send to $to failed: $e")
        }
    }

    private fun endTalk(talkId: Int) {
        val talk = talks[talkId] ?: return
        if (talk.ended) return
        talk.ended = true
        val transit = talk.sync.offsetMicros?.let { "${(it + talk.baseLag) / 1_000} ms" } ?: "n/a"
        Log.i(TAG, "talk $talkId end: ${talk.frames} frames, max arrival jitter ${talk.maxJitter / 1_000} ms, " +
            "ping rtt ${talk.sync.bestRttMicros / 1_000} ms, implied first-frame transit $transit")
        listener.onTalkEnd(talkId)
        // Keep the entry briefly so straggler packets and resends stay idempotent.
        exec.schedule({ talks.remove(talkId) }, 2, TimeUnit.SECONDS)
    }

    private fun sweep() {
        val now = Monotonic.nowMicros()
        talks.filter { (_, t) -> !t.ended && now - t.lastAudioAt > Wire.TALK_TIMEOUT_MICROS }
            .keys.forEach(::endTalk)
    }

    private companion object {
        const val TAG = "IntercomRx"
    }
}
