package dev.intercom.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.intercom.IntercomApp
import dev.intercom.data.Config
import dev.intercom.data.Role
import dev.intercom.sender.SenderController
import dev.intercom.service.ReceiverService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sender = (application as IntercomApp).sender
        setContent {
            IntercomTheme {
                AppRoot(sender)
            }
        }
    }
}

@Composable
private fun AppRoot(sender: SenderController) {
    val settings by Config.flow.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // The receiver service follows the saved settings.
    LaunchedEffect(settings.setupDone, settings.role, settings.receiverEnabled) {
        if (settings.setupDone && settings.role == Role.RECEIVER && settings.receiverEnabled) {
            ReceiverService.start(context)
        } else {
            ReceiverService.stop(context)
        }
    }

    when {
        !settings.setupDone || editing -> SetupScreen(
            initial = settings,
            firstRun = !settings.setupDone,
            onDone = { updated ->
                Config.update { updated.copy(setupDone = true) }
                editing = false
            },
            onBack = { editing = false },
        )
        settings.role == Role.SENDER -> SenderScreen(sender, settings, onSettings = { editing = true })
        else -> ReceiverScreen(settings, onSettings = { editing = true })
    }
}
