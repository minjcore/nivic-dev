package app.saving.wire.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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

    init {
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

    override fun onCleared() { client.disconnect() }
}
