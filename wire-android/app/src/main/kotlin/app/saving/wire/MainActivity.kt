package app.saving.wire

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saving.wire.deeplink.SavingDeeplinkParser
import app.saving.wire.ui.GateScreen
import app.saving.wire.ui.HomeScreen
import app.saving.wire.ui.theme.WireTheme
import app.saving.wire.viewmodel.Session
import app.saving.wire.viewmodel.WireViewModel

class MainActivity : ComponentActivity() {

    private fun wireViewModel(): WireViewModel =
        ViewModelProvider(this)[WireViewModel::class.java]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SavingDeeplinkParser.fromIntent(intent)?.let { wireViewModel().setLaunchDeeplink(it) }
        enableEdgeToEdge()
        setContent { WireTheme { WireRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SavingDeeplinkParser.fromIntent(intent)?.let { wireViewModel().setLaunchDeeplink(it) }
    }
}

@Composable
private fun WireRoot() {
    val vm: WireViewModel = viewModel()
    val session by vm.session.collectAsStateWithLifecycle()

    when (val s = session) {
        is Session.Gate -> GateScreen(vm)
        is Session.Home -> HomeScreen(vm, s.accountId)
    }
}
