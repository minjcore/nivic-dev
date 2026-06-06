package app.saving.wire.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.saving.wire.data.MerchantsClient
import app.saving.wire.data.SavingClient
import app.saving.wire.data.SavingEvent
import app.saving.wire.deeplink.SavingDeeplink
import app.saving.wire.util.vndFormatted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Session {
    data object Gate : Session
    data class Home(val accountId: Long) : Session
}

data class HomeState(
    val balance: Long = 0,
    val toast: String? = null,
)

class WireViewModel(app: Application) : AndroidViewModel(app) {

    val client          = SavingClient()
    val merchantsClient = MerchantsClient()
    val prefs: SharedPreferences = app.getSharedPreferences("merchant", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow<Session>(Session.Gate)
    val session: StateFlow<Session> = _session.asStateFlow()

    private val _homeState   = MutableStateFlow(HomeState())
    val homeState: StateFlow<HomeState> = _homeState.asStateFlow()

    private val _intentPaid = MutableSharedFlow<SavingEvent.IntentPaid>()
    val intentPaid: SharedFlow<SavingEvent.IntentPaid> = _intentPaid.asSharedFlow()

    private val _launchDeeplink = MutableStateFlow<SavingDeeplink?>(null)
    val launchDeeplink: StateFlow<SavingDeeplink?> = _launchDeeplink.asStateFlow()

    fun setLaunchDeeplink(link: SavingDeeplink?) {
        _launchDeeplink.value = link
    }

    fun consumeLaunchDeeplink(): SavingDeeplink? {
        val link = _launchDeeplink.value
        _launchDeeplink.value = null
        return link
    }

    // Battery mode: hold the Wire socket only while the app is in the foreground.
    // When the last activity stops we drop the socket (no radio/wakelock kept
    // in the background); when one starts again we redial. SavingClient's
    // resilient layer re-logs-in transparently, so dropping the socket is cheap.
    private var startedActivities = 0
    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (startedActivities++ == 0) onEnterForeground()
        }
        override fun onActivityStopped(activity: Activity) {
            if (--startedActivities == 0) onEnterBackground()
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    private fun onEnterForeground() {
        // Pre-warm so the balance/home is live the moment the user looks; if not
        // logged in yet, just open the socket. Any failure is recovered lazily.
        viewModelScope.launch {
            runCatching {
                if (!client.isConnected.value) client.connect()
                if (_session.value is Session.Home) refreshBalance()
            }.onFailure { Log.e("WireVM", "foreground connect failed: ${it.message}") }
        }
    }

    private fun onEnterBackground() {
        // Drop the socket but keep cached creds — resilient() re-logs-in on next use.
        client.disconnect()
    }

    init {
        getApplication<Application>().registerActivityLifecycleCallbacks(lifecycleCallbacks)
        viewModelScope.launch {
            runCatching { client.connect() }.onFailure {
                Log.e("WireVM", "connect failed: ${it.message}")
            }
        }
        client.onEvent = { event ->
            when (event) {
                is SavingEvent.TransferIn -> _homeState.update {
                    it.copy(
                        balance = event.transfer.balance,
                        toast   = "+${event.transfer.amount.vndFormatted()} từ #${event.transfer.fromId}"
                    )
                }
                is SavingEvent.IntentPaid -> viewModelScope.launch { _intentPaid.emit(event) }
                else -> {}
            }
        }
    }

    suspend fun login(id: Long, password: String, isNew: Boolean) {
        if (!client.isConnected.value) client.connect()
        if (isNew) client.createAccount(id, password)
        client.login(id, password)
        _session.value = Session.Home(id)
        refreshBalance()
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { client.logout() }
            _session.value = Session.Gate
            _homeState.value = HomeState()
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            val b = runCatching { client.balance() }.getOrNull() ?: return@launch
            _homeState.update { it.copy(balance = b) }
        }
    }

    fun clearToast() { _homeState.update { it.copy(toast = null) } }

    override fun onCleared() {
        getApplication<Application>().unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        client.disconnect()
    }
}
