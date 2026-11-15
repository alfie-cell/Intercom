package dev.intercom.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.intercom.R
import dev.intercom.data.Settings
import dev.intercom.sender.SenderController

@Composable
fun SenderScreen(sender: SenderController, settings: Settings, onSettings: () -> Unit) {
    val receivers by sender.receivers.collectAsStateWithLifecycle()
    val talking by sender.talking.collectAsStateWithLifecycle()
    val error by sender.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }

    // Live only while visible; backgrounding mid-talk ends the talk.
    LifecycleStartEffect(settings) {
        sender.start(settings)
        onStopOrDispose { sender.stop() }
    }

    Scaffold(topBar = { IntercomTopBar("Intercom", onSettings) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val color by animateColorAsState(if (talking) Color(0xFFD93025) else MaterialTheme.colorScheme.primary)
            val content = if (talking) Color.White else MaterialTheme.colorScheme.onPrimary
            val scale by animateFloatAsState(if (talking) 0.94f else 1f)
            Box(
                Modifier
                    .size(260.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color)
                    .semantics { contentDescription = "Hold to speak" }
                    .pointerInput(micGranted) {
                        detectTapGestures(onPress = {
                            if (!micGranted) {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@detectTapGestures
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            sender.pressDown()
                            tryAwaitRelease()
                            sender.pressUp()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_mic), null, tint = content, modifier = Modifier.size(72.dp))
                    Text(
                        if (talking) "Talking…" else "Hold to Speak",
                        color = content,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            if (!micGranted) {
                Text("Tap the button to allow microphone access.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
            }
            val standby = receivers.count { !it.live }
            Text(
                when (receivers.size) {
                    0 -> "No receivers found"
                    1 -> "1 receiver"
                    else -> "${receivers.size} receivers"
                } + if (standby > 0) " · $standby in standby" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (receivers.isEmpty()) {
                    "Check every device is on the same Wi-Fi and the network allows device-to-device " +
                        "traffic (not a guest network, no client isolation)."
                } else {
                    receivers.joinToString(" · ") { if (it.live) it.name else "${it.name} (standby)" }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
