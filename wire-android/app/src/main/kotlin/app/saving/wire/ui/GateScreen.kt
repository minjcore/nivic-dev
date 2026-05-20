package app.saving.wire.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saving.wire.viewmodel.GateViewModel
import app.saving.wire.viewmodel.WireViewModel

@Composable
fun GateScreen(wireVm: WireViewModel) {
    val gateVm: GateViewModel = viewModel()
    val state by gateVm.uiState.collectAsStateWithLifecycle()

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
                value         = state.idText,
                onValueChange = { gateVm.updateId(it) },
                placeholder   = "ID tài khoản",
                keyboardType  = KeyboardType.Number
            )
            WirePasswordField(
                value         = state.password,
                onValueChange = { gateVm.updatePassword(it) },
                placeholder   = "Mật khẩu"
            )

            state.error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

            WirePrimaryButton(
                title   = if (state.isNew) "TẠO VÍ" else "VÀO VÍ",
                loading = state.loading,
                enabled = state.idText.isNotEmpty() && state.password.isNotEmpty()
            ) {
                gateVm.submit { id, password, isNew -> wireVm.login(id, password, isNew) }
            }

            TextButton(onClick = { gateVm.toggleIsNew() }) {
                Text(
                    if (state.isNew) "Đã có ID? Đăng nhập" else "Tạo ID mới",
                    color    = Color.Gray,
                    fontSize = 13.sp
                )
            }
        }
    }
}
