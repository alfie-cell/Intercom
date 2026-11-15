package dev.intercom.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings as SystemSettings
import dev.intercom.protocol.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress

enum class Role { SENDER, RECEIVER }

data class Settings(
    val setupDone: Boolean = false,
    val role: Role = Role.RECEIVER,
    val deviceName: String = "Android",
    val groupCode: String = "",
    val delayMs: Int = Wire.DEFAULT_DELAY_MS,
    val chirp: Boolean = true,
    val muted: Boolean = false,
    /** Receiver only: media volume (0–100 %) applied while a message plays, then restored. */
    val volume: Int = 80,
    /** Receiver only: if media volume is at zero, still play messages at [volume]. */
    val playWhenMuted: Boolean = true,
    /** Receiver only: false after "Turn off", so boot doesn't bring the service back. */
    val receiverEnabled: Boolean = true,
    /** Sender only: comma-separated host:port escape hatch for networks where mDNS fails. */
    val manualReceivers: String = "",
) {
    fun manualAddresses(): List<InetSocketAddress> = manualReceivers.split(',', ' ', '\n')
        .mapNotNull { entry ->
            val s = entry.trim()
            val colon = s.lastIndexOf(':')
            if (colon <= 0) return@mapNotNull null
            val port = s.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            InetSocketAddress.createUnresolved(s.substring(0, colon).removeSurrounding("[", "]"), port)
        }
}

/**
 * SharedPreferences-backed settings exposed as a StateFlow. Synchronous reads
 * matter here: BootReceiver must decide instantly whether to start the service.
 */
object Config {
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> get() = state
    val current: Settings get() = state.value

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("intercom", Context.MODE_PRIVATE)
        val d = Settings()
        state.value = Settings(
            setupDone = prefs.getBoolean("setupDone", d.setupDone),
            role = runCatching { Role.valueOf(prefs.getString("role", null)!!) }.getOrDefault(d.role),
            deviceName = prefs.getString("deviceName", null) ?: defaultDeviceName(context),
            groupCode = prefs.getString("groupCode", d.groupCode)!!,
            delayMs = prefs.getInt("delayMs", d.delayMs),
            chirp = prefs.getBoolean("chirp", d.chirp),
            muted = prefs.getBoolean("muted", d.muted),
            volume = prefs.getInt("volume", d.volume),
            playWhenMuted = prefs.getBoolean("playWhenMuted", d.playWhenMuted),
            receiverEnabled = prefs.getBoolean("receiverEnabled", d.receiverEnabled),
            manualReceivers = prefs.getString("manualReceivers", d.manualReceivers)!!,
        )
    }

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val s = transform(state.value)
        prefs.edit()
            .putBoolean("setupDone", s.setupDone)
            .putString("role", s.role.name)
            .putString("deviceName", s.deviceName)
            .putString("groupCode", s.groupCode)
            .putInt("delayMs", s.delayMs)
            .putBoolean("chirp", s.chirp)
            .putBoolean("muted", s.muted)
            .putInt("volume", s.volume)
            .putBoolean("playWhenMuted", s.playWhenMuted)
            .putBoolean("receiverEnabled", s.receiverEnabled)
            .putString("manualReceivers", s.manualReceivers)
            .apply()
        state.value = s
    }

    private fun defaultDeviceName(context: Context): String =
        SystemSettings.Global.getString(context.contentResolver, SystemSettings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
