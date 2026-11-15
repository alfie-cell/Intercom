package dev.intercom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.intercom.R
import dev.intercom.data.Config
import dev.intercom.data.Role
import dev.intercom.data.Settings
import dev.intercom.service.ReceiverService

@Composable
fun ReceiverScreen(settings: Settings, onSettings: () -> Unit) {
    val status by ReceiverService.status.collectAsStateWithLifecycle()
    val active = status.activeTalks > 0

    Scaffold(topBar = { IntercomTopBar("Intercom", onSettings) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            val (bg, fg) = when {
                active -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
                !settings.receiverEnabled || settings.muted ->
                    MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
            }
            Box(Modifier.size(200.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_speaker), null, tint = fg, modifier = Modifier.size(80.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                when {
                    !settings.receiverEnabled -> "Intercom is off"
                    active -> "Receiving…"
                    settings.muted -> "Muted"
                    else -> "Listening as “${settings.deviceName}”"
                },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            status.lastTalk?.let {
                Spacer(Modifier.height(4.dp))
                Text("Last message: $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            SwitchRow("Intercom on", settings.receiverEnabled) { on -> Config.update { it.copy(receiverEnabled = on) } }
            SwitchRow("Mute", settings.muted) { on -> Config.update { it.copy(muted = on) } }

            if (settings.receiverEnabled) {
                Spacer(Modifier.height(16.dp))
                status.error?.let { Warning(it) }
                if (!status.onWifi) Warning("Not connected to Wi-Fi — messages can't arrive.")
                Checklist(rememberChecks(Role.RECEIVER).filter { it.ok == false })
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Warning(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
