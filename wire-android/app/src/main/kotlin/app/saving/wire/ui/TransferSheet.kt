package app.saving.wire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.saving.wire.data.SavingClient
import app.saving.wire.protocol.WireCode
import app.saving.wire.protocol.WireError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(client: SavingClient, initialToId: String = "", onDone: () -> Unit, onDismiss: () -> Unit) {
    var toId    by remember { mutableStateOf(initialToId) }
    var amount  by remember { mutableStateOf("") }
    var error   by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope   = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111111)
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Chuyển tiền", color = Color.White, style = MaterialTheme.typography.titleMedium)

            WireTextField(toId,   { toId   = it }, "ID người nhận", keyboardType = KeyboardType.Number)
            WireTextField(amount, { amount = it }, "Số tiền (VND)",  keyboardType = KeyboardType.Number)

            error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

            WirePrimaryButton(title = "GỬI NGAY", loading = loading, enabled = toId.isNotEmpty() && amount.isNotEmpty()) {
                scope.launch {
                    val to  = toId.toLongOrNull()
                    val amt = amount.toLongOrNull()
                    if (to == null || amt == null || amt <= 0) { error = "Nhập ID và số tiền hợp lệ"; return@launch }
                    loading = true; error = null
                    try { client.transfer(to, amt); onDone(); onDismiss() }
                    catch (e: WireError) { error = if (e.code == WireCode.ERR_LOW_BALANCE) "Không đủ số dư." else "Lỗi: ${e.code}" }
                    catch (e: Exception) { error = e.message }
                    finally { loading = false }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
