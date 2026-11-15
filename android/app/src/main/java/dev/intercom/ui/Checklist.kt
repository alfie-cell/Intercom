package dev.intercom.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.intercom.R
import dev.intercom.data.Role

/** ok: true = done, false = needs fixing, null = advisory. */
data class CheckItem(
    val title: String,
    val detail: String,
    val ok: Boolean?,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** dontkillmyapp.com guides for brands known to kill background services regardless of settings. */
private val OEM_GUIDES = mapOf(
    "samsung" to "samsung", "xiaomi" to "xiaomi", "redmi" to "xiaomi", "poco" to "xiaomi",
    "huawei" to "huawei", "honor" to "huawei", "oneplus" to "oneplus", "oppo" to "oppo",
    "realme" to "realme", "vivo" to "vivo", "meizu" to "meizu", "asus" to "asus", "nokia" to "nokia",
)

/** Recomputed whenever the app resumes, since every fix happens in a system screen. */
@Composable
fun rememberChecks(role: Role): List<CheckItem> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var askedMic by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val pkg = context.packageName
    fun open(intent: Intent) = runCatching { context.startActivity(intent) }
    fun appSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    return remember(tick, role, askedMic, askedNotifications) { buildList {
        if (role == Role.SENDER) {
            add(CheckItem(
                "Microphone",
                "Used only while you hold the talk button.",
                hasPermission(context, Manifest.permission.RECORD_AUDIO),
                "Allow",
            ) {
                if (!askedMic) micLauncher.launch(Manifest.permission.RECORD_AUDIO) else appSettings()
                askedMic = true
            })
            return@buildList
        }

        if (Build.VERSION.SDK_INT >= 33) {
            add(CheckItem(
                "Notification",
                "Shows that the intercom is listening, with Mute and Turn off buttons.",
                hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
                "Allow",
            ) {
                if (!askedNotifications) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                askedNotifications = true
            })
        }

        val power = context.getSystemService(PowerManager::class.java)
        add(CheckItem(
            "Unrestricted battery use",
            "Lets Android deliver messages while the device sleeps.",
            power.isIgnoringBatteryOptimizations(pkg),
            "Allow",
        ) {
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
            open(intent)
        })

        OEM_GUIDES[Build.MANUFACTURER.lowercase()]?.let { slug ->
            add(CheckItem(
                "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} background limits",
                "This brand can stop background apps even when allowed. Follow the guide to exempt Intercom.",
                null,
                "Guide",
            ) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$slug"))) })
        }

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        add(CheckItem(
            if (plugged) "Plugged in" else "On battery",
            if (plugged) "Ideal for a receiver." else "Works, but a receiver is most reliable on a charger.",
            if (plugged) true else null,
        ))
    } }
}

@Composable
fun Checklist(items: List<CheckItem>) {
    items.forEach { item ->
        ListItem(
            headlineContent = { Text(item.title) },
            supportingContent = { Text(item.detail) },
            leadingContent = {
                val (bg, glyph) = when (item.ok) {
                    true -> MaterialTheme.colorScheme.primary to "✓"
                    false -> MaterialTheme.colorScheme.error to "!"
                    null -> MaterialTheme.colorScheme.outline to "i"
                }
                Box(Modifier.size(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    Text(glyph, color = if (item.ok == true) MaterialTheme.colorScheme.onPrimary else Color.White,
                        style = MaterialTheme.typography.labelLarge)
                }
            },
            trailingContent = {
                if (item.ok != true && item.onAction != null && item.actionLabel != null) {
                    TextButton(onClick = item.onAction) { Text(item.actionLabel) }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntercomTopBar(title: String, onSettings: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        actions = {
            IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), "Settings") }
        },
    )
}
