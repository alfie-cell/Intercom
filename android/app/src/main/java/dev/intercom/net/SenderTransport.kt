package dev.intercom.net

import android.util.Log
import dev.intercom.protocol.Monotonic
import dev.intercom.protocol.Packet
import dev.intercom.protocol.Wire
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Sender side: unicast fan-out to every receiver from one UDP socket, TALK_START/END
 * 3× resends, and TIME_PONG replies to receivers' clock-sync pings.
 * Talk state is confined to a single executor thread.
 */
class SenderTransport(private val deviceName: String, private val groupCode: String) {
    private val socket = DatagramSocket()
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "intercom-tx") }
    private var targets: List<InetSocketAddress> = emptyList()
    private var talkId: Int? = null
    private var t0 = 0L
    private var seq = 0
    @Volatile private var closed = false

    init {
        thread(name = "intercom-tx-pong", isDaemon = true) { pongLoop() }
    }

    /** Unresolved addresses (manual entries) are resolved here, off the main thread. */
    fun setTargets(list: List<InetSocketAddress>) = exec.execute {
        targets = list.mapNotNull { a ->
            if (!a.isUnresolved) a
            else InetSocketAddress(a.hostString, a.port).takeUnless { it.isUnresolved }
                ?: null.also { Log.w(TAG, "could not resolve ${a.hostString}") }
        }.distinct()
    }

    fun startTalk() = exec.execute {
        if (talkId != null) return@execute
        val id = Random.nextInt().let { if (it == 0) 1 else it }
        talkId = id
        seq = 0
        t0 = Monotonic.nowMicros()
        Log.i(TAG, "talk $id start → ${targets.size} receiver(s)")
        broadcast(Packet.TalkStart.create(id, t0, deviceName, groupCode).encode(), Wire.START_END_RESENDS)
    }

    /** One 960-byte frame, in capture order. */
    fun sendFrame(pcm: ByteArray) = exec.execute {
        val id = talkId ?: return@execute
        broadcast(Packet.Audio(id, seq, t0 + seq * Wire.FRAME_MICROS, pcm).encode(), 1)
        seq++
    }

    fun endTalk() = exec.execute {
        val id = talkId ?: return@execute
        talkId = null
        Log.i(TAG, "talk $id end after $seq frames")
        broadcast(Packet.TalkEnd(id, maxOf(seq - 1, 0)).encode(), Wire.START_END_RESENDS)
    }

    fun close() {
        endTalk()
        exec.schedule({
            closed = true
            socket.close()
            exec.shutdown()
        }, Wire.START_END_RESENDS * Wire.RESEND_SPACING_MS + 10, TimeUnit.MILLISECONDS)
    }

    private fun broadcast(bytes: ByteArray, copies: Int) {
        sendAll(bytes)
        for (i in 1 until copies) {
            exec.schedule({ sendAll(bytes) }, i * Wire.RESEND_SPACING_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun sendAll(bytes: ByteArray) {
        for (t in targets) {
            try {
                socket.send(DatagramPacket(bytes, bytes.size, t))
            } catch (e: IOException) {
                if (!closed) Log.w(TAG, "send to $t failed: $e")
            }
        }
    }

    private fun pongLoop() {
        val buf = ByteArray(256)
        while (!closed) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                socket.receive(dp)
            } catch (e: IOException) {
                if (closed) return else continue
            }
            val ping = Packet.decode(dp.data, dp.length) as? Packet.TimePing ?: continue
            val pong = Packet.TimePong(ping.talkId, ping.seq, ping.t1, Monotonic.nowMicros()).encode()
            try {
                socket.send(DatagramPacket(pong, pong.size, dp.socketAddress))
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        const val TAG = "IntercomTx"
    }
}
