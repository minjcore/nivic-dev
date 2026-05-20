package app.saving.wire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.saving.wire.data.SavingClient
import app.saving.wire.data.Transaction
import app.saving.wire.protocol.AccountID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    client:     SavingClient,
    onTransfer: (String) -> Unit,
    onDismiss:  () -> Unit,
) {
    var query   by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    val id      = query.toLongOrNull()
    val isValid = id != null && AccountID.isValid(id)
    val focus   = remember { FocusRequester() }

    // Unique counterparts from history, most recent first
    val contacts by remember(history) {
        derivedStateOf {
            history.map { it.counterpartId }.distinct()
        }
    }
    val filtered by remember(query, contacts) {
        derivedStateOf {
            if (query.isEmpty()) contacts
            else contacts.filter { it.toString().contains(query) }
        }
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        history = runCatching { client.history() }.getOrDefault(emptyList())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111111),
        sheetMaxWidth    = 600.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
        ) {
            Text("Tìm tài khoản", color = Color.White,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp))

            // Search input
            OutlinedTextField(
                value         = query,
                onValueChange = { query = it.filter { c -> c.isDigit() } },
                placeholder   = { Text("Nhập ID tài khoản…", color = Color.Gray) },
                leadingIcon   = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth().focusRequester(focus),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedTextColor     = Color.White,
                    unfocusedTextColor   = Color.White,
                    focusedBorderColor   = Color.White,
                    unfocusedBorderColor = Color.Gray,
                    cursorColor          = Color.White,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction    = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { if (isValid) { onTransfer(query); onDismiss() } }
                ),
                shape = RoundedCornerShape(14.dp)
            )

            // Validation error
            if (query.isNotEmpty() && !isValid && id != null) {
                Text("ID không hợp lệ (16.777.216 – 4.294.967.295)",
                    color = Color.Red.copy(alpha = 0.8f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(20.dp))

            // Section header
            Text(
                if (query.isEmpty()) "Lịch sử giao dịch" else "Kết quả",
                color = Color.Gray, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Contact list from history
            LazyColumn(
                modifier            = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (history.isEmpty()) "Chưa có giao dịch nào" else "Không tìm thấy",
                            color = Color.Gray, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(filtered) { contactId ->
                        ContactRow(
                            contactId = contactId,
                            onClick   = { onTransfer(contactId.toString()); onDismiss() }
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ContactRow(contactId: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            "#$contactId",
            color      = Color.White,
            fontSize   = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
        Icon(
            Icons.Default.ArrowUpward, null,
            tint     = Color.Gray,
            modifier = Modifier.size(18.dp)
        )
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}
