package app.saving.wire.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.saving.wire.data.SavingClient
import app.saving.wire.protocol.WireCode
import app.saving.wire.protocol.WireError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianSheet(client: SavingClient, onDismiss: () -> Unit) {
    var guardianId by remember { mutableStateOf("") }
    var error      by remember { mutableStateOf<String?>(null) }
    var success    by remember { mutableStateOf<String?>(null) }
    var loading    by remember { mutableStateOf(false) }
    val scope      = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF111111)) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Bảo hộ tài khoản", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

            Text(
                "Khi bị mất thiết bị, 2/3 người bảo hộ duyệt là bạn lấy lại được tài khoản",
                color    = Color.Gray,
                fontSize = 12.sp
            )

            WireTextField(guardianId, { guardianId = it }, "ID người bảo hộ", keyboardType = KeyboardType.Number)

            error?.let   { Text(it, color = Color.Red,           fontSize = 13.sp) }
            success?.let { Text(it, color = Color(0xFF4CAF50),   fontSize = 13.sp) }

            WirePrimaryButton("THÊM", loading = loading, enabled = guardianId.isNotEmpty()) {
                scope.launch {
                    val id = guardianId.toLongOrNull() ?: run { error = "ID không hợp lệ"; return@launch }
                    loading = true; error = null; success = null
                    try {
                        client.addGuardian(id)
                        success = "Đã thêm #$id làm người bảo hộ ✓"
                        guardianId = ""
                    } catch (e: WireError) {
                        error = if (e.code == WireCode.ERR_GUARDIAN_FULL) "Đã đủ 3 người bảo hộ." else "Lỗi: ${e.code}"
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        loading = false
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
