package app.saving.wire.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.saving.wire.data.SavingClient
import app.saving.wire.data.SavingEvent
import app.saving.wire.protocol.WireError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMsg(val text: String, val fromId: Long?)  // fromId=null → sent by me

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages

    // Wire injects incoming messages via SavingClient.onEvent → call this
    fun onIncoming(fromId: Long, text: String) {
        _messages.update { it + ChatMsg(text = text, fromId = fromId) }
    }

    fun send(toId: Long, text: String, client: SavingClient) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _messages.update { it + ChatMsg(text = trimmed, fromId = null) }
        viewModelScope.launch {
            runCatching { client.sendMsg(toId, trimmed) }
                .onFailure { e ->
                    val err = if (e is WireError) "lỗi 0x${(e.code.toInt() and 0xFF).toString(16)}"
                              else (e.message ?: "lỗi không xác định")
                    _messages.update { it + ChatMsg(text = "⚠ $err", fromId = 0L) }
                }
        }
    }

    fun clear() { _messages.value = emptyList() }
}
