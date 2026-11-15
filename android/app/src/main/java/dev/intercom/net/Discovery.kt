package dev.intercom.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import dev.intercom.protocol.GroupAuth
import dev.intercom.protocol.Wire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** [live]: currently answering mDNS. False for remembered receivers that have gone quiet. */
data class DiscoveredReceiver(
    val name: String,
    val grpTag: String?,
    val address: InetSocketAddress,
    val live: Boolean = true,
)

private const val TAG = "IntercomNsd"

/** Receiver side: advertises `_intercom._udp` with the TXT keys from PROTOCOL.md. */
class Advertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(deviceName: String, port: Int, groupCode: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = Wire.SERVICE_TYPE
            this.port = port
            setAttribute("name", deviceName)
            setAttribute("ver", Wire.VERSION.toString())
            setAttribute("grp", GroupAuth.grpTag(groupCode))
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                Log.i(TAG, "advertising '${info.serviceName}' on port $port").let { }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) =
                Log.w(TAG, "advertise failed: $error").let { }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }
}

/**
 * Sender side: browses for receivers and resolves each to an address. API 34+ uses
 * live ServiceInfoCallbacks (track IP changes); older releases resolve serially,
 * because concurrent resolveService calls fail with FAILURE_ALREADY_ACTIVE.
 * [onUpdate] fires on the main thread.
 */
class Browser(context: Context, private val onUpdate: (List<DiscoveredReceiver>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val found = ConcurrentHashMap<String, DiscoveredReceiver>()
    private val callbacks = ConcurrentHashMap<String, Any>() // serviceName → ServiceInfoCallback
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var active = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Log.i(TAG, "browsing").let { }
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "browse failed: $errorCode; retrying")
            main.postDelayed({ if (active) restartDiscovery() }, 2_000)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            if (!active) return
            if (Build.VERSION.SDK_INT >= 34) watch(info) else enqueueResolve(info)
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            if (Build.VERSION.SDK_INT >= 34) unwatch(info.serviceName)
            if (found.remove(info.serviceName) != null) publish()
        }
    }

    fun start() {
        if (active) return
        active = true
        nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
        if (Build.VERSION.SDK_INT >= 34) callbacks.keys.toList().forEach(::unwatch)
        synchronized(resolveQueue) { resolveQueue.clear() }
        found.clear()
        publish()
    }

    private fun restartDiscovery() {
        runCatching { nsd.stopServiceDiscovery(discovery) }
        runCatching { nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    private fun publish() {
        val list = found.values.sortedBy { it.name.lowercase() }
        main.post { onUpdate(list) }
    }

    private fun record(info: NsdServiceInfo, host: InetAddress?) {
        if (!active || host == null || info.port <= 0) return
        val grp = info.attributes["grp"]?.let { String(it, Charsets.UTF_8) }
        found[info.serviceName] = DiscoveredReceiver(info.serviceName, grp, InetSocketAddress(host, info.port))
        publish()
    }

    // API 34+: live callbacks per service.

    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo) {
        if (callbacks.containsKey(info.serviceName)) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                callbacks.remove(info.serviceName)
            }
            override fun onServiceUpdated(updated: NsdServiceInfo) {
                val addrs = updated.hostAddresses
                record(updated, addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull())
            }
            override fun onServiceLost() {
                if (found.remove(info.serviceName) != null) publish()
            }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        callbacks[info.serviceName] = cb
        runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }
            .onFailure { callbacks.remove(info.serviceName) }
    }

    @RequiresApi(34)
    private fun unwatch(serviceName: String) {
        val cb = callbacks.remove(serviceName) as? NsdManager.ServiceInfoCallback ?: return
        runCatching { nsd.unregisterServiceInfoCallback(cb) }
    }

    // API < 34: serialized one-shot resolves.

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                record(info, info.host)
                resolveNext()
            }
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && active) {
                    main.postDelayed({ enqueueResolve(info) }, 300)
                }
                resolveNext()
            }
        })
    }
}
