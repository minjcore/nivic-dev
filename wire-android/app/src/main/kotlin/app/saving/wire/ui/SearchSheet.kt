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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Store
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
import app.saving.wire.data.MenuItem
import app.saving.wire.data.MerchantInfo
import app.saving.wire.data.MerchantsClient
import app.saving.wire.data.SavingClient
import app.saving.wire.data.Transaction
import app.saving.wire.protocol.AccountID
import app.saving.wire.protocol.WireCode
import app.saving.wire.protocol.WireError
import app.saving.wire.util.vndFormatted
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    client:          SavingClient,
    merchantsClient: MerchantsClient,
    onTransfer:      (String) -> Unit,
    onChat:          (mid: Long, name: String) -> Unit = { _, _ -> },
    onFrontStore:    (mid: Long) -> Unit = {},
    onDismiss:       () -> Unit,
) {
    var tab     by remember { mutableStateOf(0) } // 0=Tài khoản, 1=Cửa hàng
    val focus   = remember { FocusRequester() }

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
            // Tab row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Tài khoản", "Cửa hàng").forEachIndexed { i, label ->
                    val selected = tab == i
                    Surface(
                        onClick      = { tab = i },
                        shape        = RoundedCornerShape(20.dp),
                        color        = if (selected) Color.White else Color.White.copy(alpha = 0.08f),
                    ) {
                        Text(
                            label,
                            color    = if (selected) Color.Black else Color.Gray,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            if (tab == 0) {
                AccountTab(client, focus, onTransfer, onDismiss)
            } else {
                MerchantTab(merchantsClient, focus, onTransfer, onChat, onFrontStore, onDismiss)
            }
        }
    }
}

@Composable
private fun AccountTab(
    client:     SavingClient,
    focus:      FocusRequester,
    onTransfer: (String) -> Unit,
    onDismiss:  () -> Unit,
) {
    var query   by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    val id      = query.toLongOrNull()
    val isValid = id != null && AccountID.isValid(id)

    val contacts by remember(history) {
        derivedStateOf { history.map { it.counterpartId }.distinct() }
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

    if (query.isNotEmpty() && !isValid && id != null) {
        Text("ID không hợp lệ (16.777.216 – 4.294.967.295)",
            color = Color.Red.copy(alpha = 0.8f), fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp))
    }

    Spacer(Modifier.height(20.dp))
    Text(
        if (query.isEmpty()) "Lịch sử giao dịch" else "Kết quả",
        color = Color.Gray, fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )

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

@Composable
private fun MerchantTab(
    merchantsClient: MerchantsClient,
    focus:           FocusRequester,
    onTransfer:      (String) -> Unit,
    onChat:          (Long, String) -> Unit,
    onFrontStore:    (Long) -> Unit,
    onDismiss:       () -> Unit,
) {
    var query   by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MerchantInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(query) {
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        loading = true
        results = runCatching { merchantsClient.searchMerchants(query) }.getOrDefault(emptyList())
        loading = false
    }

    OutlinedTextField(
        value         = query,
        onValueChange = { query = it },
        placeholder   = { Text("Tên cửa hàng…", color = Color.Gray) },
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(14.dp)
    )

    Spacer(Modifier.height(20.dp))

    if (loading) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else {
        Text(
            if (query.length < 2) "Nhập ít nhất 2 ký tự" else if (results.isEmpty()) "Không tìm thấy" else "Kết quả",
            color = Color.Gray, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(
            modifier            = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(results) { m ->
                MerchantRow(
                    m             = m,
                    onClick       = { onFrontStore(m.mid); onDismiss() },
                    onTransfer    = { onTransfer(m.mid.toString()); onDismiss() },
                    onChat        = { onChat(m.mid, m.name); onDismiss() }
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
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
        Icon(Icons.Default.ArrowUpward, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}

@Composable
private fun MerchantRow(
    m:          MerchantInfo,
    onClick:    () -> Unit,    // open frontstore
    onTransfer: () -> Unit,    // direct transfer
    onChat:     () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Store, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            Column {
                Text(m.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (m.address.isNotEmpty()) {
                    Text(m.address, color = Color.Gray, fontSize = 11.sp)
                }
                Text("#${m.mid}", color = Color.Gray.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onChat, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.Chat, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onTransfer, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ArrowUpward, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}

// ─── FrontStoreSheet ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrontStoreSheet(
    mid:           Long,
    intentPayload: IntentPayload?,
    client:        SavingClient,
    merchantsClient: MerchantsClient,
    prefs:         android.content.SharedPreferences,
    accountId:     Long,
    onDone:        () -> Unit,
    onDismiss:     () -> Unit,
) {
    var mcName    by remember { mutableStateOf("") }
    var mcAddress by remember { mutableStateOf("") }
    var menu      by remember { mutableStateOf<List<MenuItem>>(emptyList()) }
    var error     by remember { mutableStateOf<String?>(null) }
    var loading   by remember { mutableStateOf(false) }
    var success   by remember { mutableStateOf(false) }
    val scope     = rememberCoroutineScope()

    LaunchedEffect(mid) {
        runCatching { val m = merchantsClient.getMerchant(mid); mcName = m.name; mcAddress = m.address }
        runCatching { menu = merchantsClient.listMenu(mid) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color.Black,
        sheetMaxWidth    = 600.dp,
    ) {
        LazyColumn(
            modifier            = Modifier.fillMaxWidth(),
            contentPadding      = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                       modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Store, null,
                        modifier = Modifier.size(56.dp), tint = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.height(8.dp))
                    if (mcName.isNotEmpty())
                        Text(mcName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    else
                        Text("#$mid", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace)
                    if (mcAddress.isNotEmpty())
                        Text(mcAddress, color = Color.Gray, fontSize = 12.sp)
                }
            }

            if (menu.isNotEmpty()) {
                item { Text("Menu", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth()) }
                items(menu) { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = Color.White, fontSize = 14.sp)
                            if (item.description.isNotEmpty())
                                Text(item.description, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                        }
                        Text("${item.price / 1000}k ₫", color = Color.Gray, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f),
                        modifier = Modifier.padding(top = 8.dp))
                }
            }

            if (intentPayload != null) {
                item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.06f)) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                               verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Tổng thanh toán", color = Color.Gray, fontSize = 12.sp)
                            Text(intentPayload.amount.vndFormatted(),
                                color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }

                error?.let { item { Text(it, color = Color.Red, fontSize = 13.sp) } }

                item {
                    if (success) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.CheckCircleOutline, null, tint = Color(0xFF4CAF50))
                            Text("Thanh toán thành công!", color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        WirePrimaryButton(title = "XÁC NHẬN THANH TOÁN", loading = loading, enabled = !loading) {
                            scope.launch {
                                loading = true; error = null
                                try {
                                    client.confirmIntent(intentPayload.mid, intentPayload.requestId)
                                    intentPayload.orderID?.let { oid ->
                                        merchantsClient.confirmPaid(oid, accountId.toInt())
                                    }
                                    success = true
                                    kotlinx.coroutines.delay(1500)
                                    onDone()
                                } catch (e: WireError) {
                                    error = when (e.code) {
                                        WireCode.ERR_LOW_BALANCE    -> "Không đủ số dư"
                                        WireCode.ERR_INTENT_SETTLED -> "Đơn đã thanh toán rồi"
                                        WireCode.ERR_NOT_FOUND      -> "Không tìm thấy đơn hàng"
                                        WireCode.ERR_SYSTEM_OFFLINE,
                                        WireCode.ERR_MAINTENANCE    -> "Hệ thống tạm thời không khả dụng"
                                        else -> "Lỗi: 0x${e.code.toInt().and(0xFF).toString(16)}"
                                    }
                                } catch (e: Exception) {
                                    error = e.message
                                } finally { loading = false }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
