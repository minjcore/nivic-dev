package app.saving.wire.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.saving.wire.protocol.AccountID
import app.saving.wire.protocol.WireCode
import app.saving.wire.protocol.WireError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GateState(
    val idText:   String  = "",
    val password: String  = "",
    val isNew:    Boolean = false,
    val error:    String? = null,
    val loading:  Boolean = false,
)

class GateViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GateState())
    val uiState: StateFlow<GateState> = _uiState.asStateFlow()

    fun updateId(v: String)       { _uiState.update { it.copy(idText = v) } }
    fun updatePassword(v: String) { _uiState.update { it.copy(password = v) } }
    fun toggleIsNew()             { _uiState.update { it.copy(isNew = !it.isNew, error = null) } }

    fun submit(loginFn: suspend (id: Long, password: String, isNew: Boolean) -> Unit) {
        val s = _uiState.value
        val id = s.idText.toLongOrNull()
        if (id == null || !AccountID.isValid(id)) {
            _uiState.update { it.copy(error = "ID phải từ 16.777.216 đến 4.294.967.295") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                loginFn(id, s.password, s.isNew)
            } catch (e: WireError) {
                _uiState.update { it.copy(error = errorMessage(e), loading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, loading = false) }
            }
        }
    }

    private fun errorMessage(e: WireError) = when (e.code) {
        WireCode.ERR_ID_TAKEN     -> "ID này đã có chủ."
        WireCode.ERR_ID_RESERVED  -> "ID này nằm trong kho VIP."
        WireCode.ERR_BAD_PASSWORD -> "Sai mật khẩu."
        WireCode.ERR_NOT_FOUND    -> "Tài khoản không tồn tại."
        else -> "Lỗi: 0x${e.code.toInt().and(0xFF).toString(16)}"
    }
}
