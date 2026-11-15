package dev.intercom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.intercom.R
import dev.intercom.audio.Playback
import dev.intercom.data.Config
import dev.intercom.data.Settings
import dev.intercom.net.Advertiser
import dev.intercom.net.ReceiverSocket
import dev.intercom.protocol.Wire
import dev.intercom.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class ReceiverStatus(
    val running: Boolean = false,
    val activeTalks: Int = 0,
    val lastTalk: String? = null,
    val onWifi: Boolean = true,
    val error: String? = null,
)

/**
 * The receiver: a `specialUse` foreground service holding the UDP socket, the mDNS
 * advertisement, and the locks that keep reception working with the screen off.
 * See PLAN.md §1 and PLAN-ANDROID.md "The receiver service".
 */
class ReceiverService : Service(), ReceiverSocket.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var advertiser: Advertiser
    private lateinit var playback: Playback
    private var socket: ReceiverSocket? = null
    private var stackKey: Pair<String, String>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var talkWake: PowerManager.WakeLock
    private lateinit var multicastLock: WifiManager.MulticastLock
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createChannel()
        val notification = buildNotification(Config.current)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()
        advertiser = Advertiser(this)
        playback = Playback(this).apply { onIdle = { if (talkWake.isHeld) talkWake.release() } }
        watchNetwork()
        scope.launch {
            Config.flow.collect { s ->
                playback.delayMs = s.delayMs
                playback.chirpEnabled = s.chirp
                playback.volumePercent = s.volume
                playback.playWhenMuted = s.playWhenMuted
                if (stackKey != s.deviceName to s.groupCode) rebuild(s)
                refreshNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> Config.update { it.copy(muted = !it.muted) }
            ACTION_TURN_OFF -> {
                Config.update { it.copy(receiverEnabled = false) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        teardown()
        playback.stopAll()
        releaseLocks()
        _status.value = ReceiverStatus()
        super.onDestroy()
    }

    // MARK: Network stack

    private fun rebuild(s: Settings) {
        teardown()
        stackKey = s.deviceName to s.groupCode
        try {
            val sock = ReceiverSocket(s.groupCode, this)
            sock.start()
            socket = sock
            advertiser.register(s.deviceName, sock.port, s.groupCode)
            _status.update { it.copy(running = true, error = null) }
        } catch (e: Exception) {
            Log.e(TAG, "receiver start failed", e)
            _status.update { it.copy(running = false, error = "Can't listen: ${e.message}") }
        }
    }

    private fun teardown() {
        advertiser.unregister()
        socket?.close()
        socket = null
        stackKey = null
    }

    /** Re-advertise after Wi-Fi comes back so senders rediscover us on the new link. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var networks = 0
            private var initial = true // the first onAvailable just reports the current network
            override fun onAvailable(network: Network) {
                networks++
                val changed = !initial
                initial = false
                scope.launch {
                    _status.update { it.copy(onWifi = true) }
                    if (!changed) return@launch
                    val sock = socket ?: return@launch
                    val s = Config.current
                    advertiser.register(s.deviceName, sock.port, s.groupCode)
                }
            }
            override fun onLost(network: Network) {
                networks = maxOf(0, networks - 1)
                if (networks == 0) _status.update { it.copy(onWifi = false) }
            }
        }
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        _status.update {
            it.copy(onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
        }
    }

    // MARK: ReceiverSocket.Listener (socket state thread)

    override fun onTalkStart(talkId: Int, senderName: String, t0: Long) {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        _status.update { it.copy(lastTalk = "$senderName · $time") }
        if (Config.current.muted) {
            refreshNotification()
            return
        }
        talkWake.acquire(Wire.MAX_TALK_MS + 15_000)
        playback.beginTalk(talkId, t0)
        _status.update { it.copy(activeTalks = it.activeTalks + 1) }
        refreshNotification()
    }

    override fun onAudio(talkId: Int, ts: Long, pcm: ByteArray, rxAt: Long) {
        playback.addFrame(talkId, ts, pcm, rxAt)
    }

    override fun onOffset(talkId: Int, offsetMicros: Long) {
        playback.setOffset(talkId, offsetMicros)
    }

    override fun onTalkEnd(talkId: Int) {
        if (playback.endTalk(talkId) == null) return // muted talk: never played
        _status.update { it.copy(activeTalks = maxOf(0, it.activeTalks - 1)) }
        refreshNotification()
    }

    // MARK: Locks

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        // Per talk only: a permanent CPU wake lock would cost far more battery than the
        // socket does. Incoming packets wake the CPU; this keeps it up through playout.
        talkWake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Intercom:talk").apply {
            setReferenceCounted(false)
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        // Keeps mDNS queries flowing to us so senders' browse results never expire.
        multicastLock = wifi.createMulticastLock("Intercom:mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        // HIGH_PERF keeps Wi-Fi out of power-save with the screen off on API < 34 (on 34+
        // the platform maps it to LOW_LATENCY); LOW_LATENCY covers screen-on use on 29+.
        wifiLocks += wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Intercom:wifi")
        if (Build.VERSION.SDK_INT >= 29) {
            wifiLocks += wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Intercom:wifi-ll")
        }
        wifiLocks.forEach {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseLocks() {
        if (talkWake.isHeld) talkWake.release()
        if (multicastLock.isHeld) multicastLock.release()
        wifiLocks.forEach { if (it.isHeld) it.release() }
        wifiLocks.clear()
    }

    // MARK: Notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Intercom receiver", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this device is listening for intercom messages"
                setShowBadge(false)
            }
        )
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(Config.current))
    }

    private fun buildNotification(s: Settings): Notification {
        val st = _status.value
        val text = when {
            st.activeTalks > 0 -> "Receiving…"
            st.error != null -> st.error
            else -> st.lastTalk?.let { "Last: $it" } ?: "Ready"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cell_tower)
            .setContentTitle(if (s.muted) "Intercom muted — “${s.deviceName}”" else "Intercom listening — “${s.deviceName}”")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (s.muted) "Unmute" else "Mute", serviceIntent(ACTION_TOGGLE_MUTE, 1))
            .addAction(0, "Turn off", serviceIntent(ACTION_TURN_OFF, 2))
            .build()
    }

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, ReceiverService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "IntercomService"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE_MUTE = "mute"
        private const val ACTION_TURN_OFF = "off"

        private val _status = MutableStateFlow(ReceiverStatus())
        val status: StateFlow<ReceiverStatus> get() = _status

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
