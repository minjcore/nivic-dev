package dev.nivic.wire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nivic.wire.data.SavingClient
import dev.nivic.wire.protocol.AccountID
import dev.nivic.wire.protocol.WireCode
import dev.nivic.wire.protocol.WireError
import kotlinx.coroutines.launch

@Composable
fun GateScreen(client: SavingClient, onLogin: (Long) -> Unit) {
    var idText   by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isNew    by remember { mutableStateOf(false) }
    var error    by remember { mutableStateOf<String?>(null) }
    var loading  by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()

    Box(
        modifier           = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment   = Alignment.Center
    ) {
        Column(
            modifier                = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment     = Alignment.CenterHorizontally,
            verticalArrangement     = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text       = "WIRE",
                fontSize   = 42.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color      = Color.White
            )

            Spacer(Modifier.height(8.dp))

            WireTextField(
                value         = idText,
                onValueChange = { idText = it },
                placeholder   = "ID tài khoản",
                keyboardType  = KeyboardType.Number
            )
            WirePasswordField(
                value         = password,
                onValueChange = { password = it },
                placeholder   = "Mật khẩu"
            )

            error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

            WirePrimaryButton(
                title   = if (isNew) "TẠO VÍ" else "VÀO VÍ",
                loading = loading,
                enabled = idText.isNotEmpty() && password.isNotEmpty()
            ) {
                scope.launch {
                    val id = idText.toLongOrNull()
                    if (id == null || !AccountID.isValid(id)) {
                        error = "ID phải từ 16.777.216 đến 4.294.967.295"; return@launch
                    }
                    loading = true; error = null
                    try {
                        if (isNew) client.createAccount(id, password)
                        client.login(id, password)
                        onLogin(id)
                    } catch (e: WireError) {
                        error = when (e.code) {
                            WireCode.ERR_ID_TAKEN     -> "ID này đã có chủ."
                            WireCode.ERR_ID_RESERVED  -> "ID này nằm trong kho VIP."
                            WireCode.ERR_BAD_PASSWORD -> "Sai mật khẩu."
                            WireCode.ERR_NOT_FOUND    -> "Tài khoản không tồn tại."
                            else -> "Lỗi: 0x${e.code.toInt().and(0xFF).toString(16)}"
                        }
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        loading = false
                    }
                }
            }

            TextButton(onClick = { isNew = !isNew; error = null }) {
                Text(
                    if (isNew) "Đã có ID? Đăng nhập" else "Tạo ID mới",
                    color    = Color.Gray,
                    fontSize = 13.sp
                )
            }
        }
    }
}
