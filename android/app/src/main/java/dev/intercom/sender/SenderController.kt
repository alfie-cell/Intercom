package dev.intercom.sender

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.intercom.audio.Capture
import dev.intercom.data.Settings
import dev.intercom.net.Browser
import dev.intercom.net.DiscoveredReceiver
import dev.intercom.net.SenderTransport
import dev.intercom.protocol.GroupAuth
import dev.intercom.protocol.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sender role: runs only while the sender screen is visible (no service needed —
 * you're looking at it while talking). Main-thread API.
 */
class SenderController(private val context: Context) {
    private val _receivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val receivers: StateFlow<List<DiscoveredReceiver>> get() = _receivers
    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> get() = _talking
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    private val main = Handler(Looper.getMainLooper())
    private var settings: Settings? = null
    private var browser: Browser? = null
    private var transport: SenderTransport? = null
    private var capture: Capture? = null
    private var discovered: List<DiscoveredReceiver> = emptyList()
    private val known = KnownReceivers(context)
    private val autoStop = Runnable { pressUp() }

    // Low-latency Wi-Fi while the sender is on screen: exactly the case it's designed for.
    private val wifiLock: WifiManager.WifiLock? = if (Build.VERSION.SDK_INT >= 29) {
        context.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Intercom:sender")
            .apply { setReferenceCounted(false) }
    } else null

    fun start(s: Settings) {
        stop()
        settings = s
        transport = SenderTransport(s.deviceName, s.groupCode)
        browser = Browser(context) { list ->
            discovered = list
            known.remember(list)
            publishTargets()
        }.also { it.start() }
        publishTargets()
        wifiLock?.acquire()
    }

    fun stop() {
        pressUp()
        browser?.stop()
        browser = null
        transport?.close()
        transport = null
        discovered = emptyList()
        _receivers.value = emptyList()
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun publishTargets() {
        val s = settings ?: return
        val tag = GroupAuth.grpTag(s.groupCode)
        fun inGroup(r: DiscoveredReceiver) = r.grpTag == null || r.grpTag == tag
        val live = discovered.filter(::inGroup)
        val liveNames = live.map { it.name }.toSet()
        val liveAddresses = live.map { it.address }.toSet()
        val standby = known.recent().filter { inGroup(it) && it.name !in liveNames && it.address !in liveAddresses }
        val visible = live + standby +
            s.manualAddresses().map { DiscoveredReceiver("${it.hostString}:${it.port}", null, it) }
        _receivers.value = visible
        transport?.setTargets(visible.map { it.address })
    }

    /** Caller must already hold RECORD_AUDIO. */
    fun pressDown() {
        val t = transport ?: return
        if (_talking.value) return
        val c = Capture { frame -> t.sendFrame(frame) }
        try {
            c.start()
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            _error.value = e.message ?: "Microphone unavailable"
            return
        }
        _error.value = null
        capture = c
        t.startTalk()
        _talking.value = true
        main.postDelayed(autoStop, Wire.MAX_TALK_MS)
    }

    fun pressUp() {
        if (!_talking.value) return
        main.removeCallbacks(autoStop)
        capture?.stop()
        capture = null
        transport?.endTalk()
        _talking.value = false
    }

    private companion object {
        const val TAG = "IntercomSender"
    }
}
