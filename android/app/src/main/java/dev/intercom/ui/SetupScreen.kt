package dev.intercom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.intercom.R
import dev.intercom.data.Role
import dev.intercom.data.Settings
import kotlin.math.roundToInt

/** First-run setup and the settings screen: edits are local until Start/Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(initial: Settings, firstRun: Boolean, onDone: (Settings) -> Unit, onBack: () -> Unit) {
    var role by rememberSaveable { mutableStateOf(initial.role) }
    var name by rememberSaveable { mutableStateOf(initial.deviceName) }
    var code by rememberSaveable { mutableStateOf(initial.groupCode) }
    var delay by rememberSaveable { mutableFloatStateOf(initial.delayMs.toFloat()) }
    var chirp by rememberSaveable { mutableStateOf(initial.chirp) }
    var volume by rememberSaveable { mutableFloatStateOf(initial.volume.toFloat()) }
    var playWhenMuted by rememberSaveable { mutableStateOf(initial.playWhenMuted) }
    var manual by rememberSaveable { mutableStateOf(initial.manualReceivers) }

    if (!firstRun) BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (firstRun) "Set up Intercom" else "Settings") },
                navigationIcon = {
                    if (!firstRun) IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    onDone(
                        initial.copy(
                            role = role,
                            deviceName = name.trim().ifEmpty { initial.deviceName },
                            groupCode = code,
                            delayMs = delay.roundToInt(),
                            chirp = chirp,
                            volume = volume.roundToInt(),
                            playWhenMuted = playWhenMuted,
                            manualReceivers = manual.trim(),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
            ) { Text(if (firstRun) "Start" else "Save") }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("This device")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Role.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = role == r,
                        onClick = { role = r },
                        shape = SegmentedButtonDefaults.itemShape(i, Role.entries.size),
                    ) { Text(if (r == Role.SENDER) "Sender" else "Receiver") }
                }
            }
            Hint(
                if (role == Role.SENDER) "Senders have the talk button. They never play intercom messages."
                else "Receivers play what senders say, in sync across rooms. They never send."
            )
            OutlinedTextField(
                name, { name = it },
                label = { Text("Device name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                code, { code = it },
                label = { Text("Group code") },
                supportingText = { Text("Use the same code on all your devices.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (role == Role.RECEIVER) {
                SectionTitle("Audio")
                Text("Sync delay: ${delay.roundToInt()} ms")
                Slider(value = delay, onValueChange = { delay = it }, valueRange = 50f..500f, steps = 44)
                Hint("Higher survives busier Wi-Fi. Use the same value on every receiver so rooms stay in sync.")
                SwitchRow("Chirp before each message", chirp) { chirp = it }
                Text("Intercom volume: ${volume.roundToInt()}%")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, steps = 19)
                Hint("Messages play at this level whatever the device's media volume is set to, then it's put back.")
                SwitchRow("Play even when media is muted", playWhenMuted) { playWhenMuted = it }
            } else {
                SectionTitle("Manual receivers")
                OutlinedTextField(
                    manual, { manual = it },
                    label = { Text("host:port, host:port") },
                    supportingText = { Text("Only needed if receivers aren't found automatically, e.g. on a different subnet.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionTitle(if (role == Role.RECEIVER) "Reliability" else "Permissions")
            Checklist(rememberChecks(role))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
