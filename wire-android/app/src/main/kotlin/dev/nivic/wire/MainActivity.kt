package dev.nivic.wire

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.nivic.wire.data.MerchantsClient
import dev.nivic.wire.data.SavingClient
import dev.nivic.wire.ui.GateScreen
import dev.nivic.wire.ui.HomeScreen
import dev.nivic.wire.ui.theme.WireTheme

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
    val ctx              = LocalContext.current
    val client           = remember { SavingClient() }
    val merchantsClient  = remember { MerchantsClient() }
    val prefs            = remember { ctx.getSharedPreferences("merchant", Context.MODE_PRIVATE) }
    var session          by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        runCatching { client.connect() }.onFailure {
            android.util.Log.e("WireConnect", "connect failed: ${it.javaClass.name}: ${it.message}", it)
        }
    }

    if (session == null) {
        GateScreen(client) { accountId -> session = accountId }
    } else {
        HomeScreen(client, session!!, merchantsClient, prefs) { session = null }
    }
}
