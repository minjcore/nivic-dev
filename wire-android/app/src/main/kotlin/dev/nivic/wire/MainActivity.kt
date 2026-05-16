package dev.nivic.wire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import dev.nivic.wire.data.SavingClient
import dev.nivic.wire.ui.GateScreen
import dev.nivic.wire.ui.HomeScreen
import dev.nivic.wire.ui.theme.WireTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WireTheme { WireRoot() }
        }
    }
}

@Composable
private fun WireRoot() {
    val client  = remember { SavingClient() }
    var session by remember { mutableStateOf<Long?>(null) }
    val scope   = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { client.connect() }
    }

    if (session == null) {
        GateScreen(client) { accountId -> session = accountId }
    } else {
        HomeScreen(client, session!!) { session = null }
    }
}
