package dev.intercom.sender

import android.content.Context
import dev.intercom.net.DiscoveredReceiver
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Receivers this sender has discovered before, persisted. Many receivers (Samsung
 * tablets with the screen off, for one) stop answering mDNS while still receiving
 * unicast audio perfectly, so a receiver that drops out of discovery keeps getting
 * talks at its last known address until it's unseen for [MAX_AGE_MS].
 */
class KnownReceivers(context: Context) {
    private val prefs = context.getSharedPreferences("known_receivers", Context.MODE_PRIVATE)

    fun remember(live: List<DiscoveredReceiver>, now: Long = System.currentTimeMillis()) {
        if (live.isEmpty()) return
        val all = load().associateBy { it.name }.toMutableMap()
        for (r in live) {
            val ip = r.address.address?.hostAddress ?: continue
            all[r.name] = Entry(r.name, r.grpTag, ip, r.address.port, now)
        }
        prefs.edit().putStringSet(KEY, all.values.map(Entry::encode).toSet()).apply()
    }

    /** Remembered receivers seen within [MAX_AGE_MS], as standby (live = false) entries. */
    fun recent(now: Long = System.currentTimeMillis()): List<DiscoveredReceiver> =
        load().filter { now - it.lastSeen < MAX_AGE_MS }.map {
            DiscoveredReceiver(it.name, it.grpTag, InetSocketAddress(InetAddress.getByName(it.ip), it.port), live = false)
        }

    private fun load(): List<Entry> = prefs.getStringSet(KEY, emptySet())!!.mapNotNull(Entry::decode)

    internal data class Entry(val name: String, val grpTag: String?, val ip: String, val port: Int, val lastSeen: Long) {
        fun encode() = listOf(name, grpTag.orEmpty(), ip, port, lastSeen).joinToString(SEP)

        companion object {
            fun decode(s: String): Entry? {
                val f = s.split(SEP)
                if (f.size != 5) return null
                val port = f[3].toIntOrNull() ?: return null
                val seen = f[4].toLongOrNull() ?: return null
                // Only IP literals are stored, so building the address never touches DNS.
                if (!f[2].contains(':') && !IPV4.matches(f[2])) return null
                return Entry(f[0], f[1].ifEmpty { null }, f[2], port, seen)
            }
        }
    }

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
        private const val KEY = "entries"
        private const val SEP = "\u001F" // unit separator: can't appear in a typed device name
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
