package app.saving.wire.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import app.saving.wire.data.LoyaltyMember
import app.saving.wire.data.MerchantOrder
import app.saving.wire.data.MerchantStats
import app.saving.wire.data.MerchantsClient
import app.saving.wire.data.SavingClient
import app.saving.wire.data.SavingEvent
import app.saving.wire.util.VietQR
import app.saving.wire.util.vndFormatted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// ─── Entry point ─────────────────────────────────────────────────────────────

@Composable
fun MerchantSheet(
    uid:          Long,
    client:       MerchantsClient,
    wireClient:   SavingClient,
    prefs:        android.content.SharedPreferences,
    intentPaid:   SharedFlow<SavingEvent.IntentPaid>,
    onClose:      () -> Unit,
) {
    val savedToken = prefs.getString("merchant_token", "") ?: ""
    val savedName  = prefs.getString("merchant_name",  "") ?: ""

    if (savedToken.isEmpty()) {
        MerchantOnboardingView(uid, client, onClose) { name, token ->
            prefs.edit().putString("merchant_token", token).putString("merchant_name", name).apply()
        }
    } else {
        MerchantDashboardView(uid, savedName, savedToken, prefs, client, wireClient, intentPaid, onClose)
    }
}

// ─── Onboarding ───────────────────────────────────────────────────────────────

@Composable
private fun MerchantOnboardingView(
    uid:    Long,
    client: MerchantsClient,
    onClose: () -> Unit,
    onDone: (String, String) -> Unit,
) {
    var shopName by remember { mutableStateOf("") }
    var loading  by remember { mutableStateOf(false) }
    var error    by remember { mutableStateOf<String?>(null) }
    var newToken by remember { mutableStateOf("") }
    var done     by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()
    val ctx      = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, null, tint = Color.Gray)
            }
        }

        Spacer(Modifier.height(32.dp))

        if (!done) {
            Icon(Icons.Default.Store, null,
                modifier = Modifier.size(72.dp), tint = Color.White)
            Spacer(Modifier.height(24.dp))
            Text("Mở gian hàng của bạn", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Nhận thanh toán qua QR code\ntrực tiếp vào tài khoản Saving",
                color = Color.Gray, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value         = shopName,
                onValueChange = { shopName = it },
                label         = { Text("Tên cửa hàng") },
                placeholder   = { Text("VD: Quán Cà Phê ABC") },
                modifier      = Modifier.fillMaxWidth(),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedTextColor    = Color.White,
                    unfocusedTextColor  = Color.White,
                    focusedBorderColor  = Color.White,
                    unfocusedBorderColor= Color.Gray,
                    focusedLabelColor   = Color.White,
                    unfocusedLabelColor = Color.Gray,
                )
            )

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color.Red, fontSize = 13.sp)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick  = {
                    scope.launch {
                        loading = true; error = null
                        runCatching { client.onboard(uid, shopName.trim()) }
                            .onSuccess { token -> newToken = token; done = true; onDone(shopName.trim(), token) }
                            .onFailure { error = it.message }
                        loading = false
                    }
                },
                enabled  = shopName.isNotBlank() && !loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape    = RoundedCornerShape(14.dp)
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                else Text("Bắt đầu bán hàng", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Icon(Icons.Default.CheckCircleOutline, null,
                modifier = Modifier.size(80.dp), tint = Color(0xFF4CAF50))
            Spacer(Modifier.height(20.dp))
            Text("Chào mừng, $shopName! 🎉", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Merchant ID: $uid", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text("Lưu token bên dưới — chỉ hiện một lần",
                color = Color(0xFFFFA726), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                color    = Color.White.copy(alpha = 0.08f)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(newToken, color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("token", newToken))
                    }) {
                        Icon(Icons.Default.ContentCopy, null, tint = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick  = onClose,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape    = RoundedCornerShape(14.dp)
            ) { Text("Vào Dashboard", fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

@Composable
private fun MerchantDashboardView(
    uid:         Long,
    name:        String,
    token:       String,
    prefs:       android.content.SharedPreferences,
    client:      MerchantsClient,
    wireClient:  SavingClient,
    intentPaid:  SharedFlow<SavingEvent.IntentPaid>,
    onClose:     () -> Unit,
) {
    var stats           by remember { mutableStateOf<MerchantStats?>(null) }
    var orders          by remember { mutableStateOf<List<MerchantOrder>>(emptyList()) }
    var members         by remember { mutableStateOf<List<LoyaltyMember>>(emptyList()) }
    var showCreateOrder  by remember { mutableStateOf(false) }
    var showLoyalty      by remember { mutableStateOf(false) }
    var showBankSetup    by remember { mutableStateOf(false) }
    var showChatInbox    by remember { mutableStateOf(false) }
    var chatCustomerUid  by remember { mutableLongStateOf(0L) }
    var inbox            by remember { mutableStateOf<List<app.saving.wire.data.ChatInboxItem>>(emptyList()) }
    var toast           by remember { mutableStateOf<String?>(null) }
    val scope           = rememberCoroutineScope()

    suspend fun load() {
        runCatching { stats   = client.stats(uid, token) }
        runCatching { orders  = client.listOrders(uid, token) }
        runCatching { members = client.loyaltyMembers(uid, token) }
        runCatching { inbox   = client.getInbox(uid, token) }
    }

    LaunchedEffect(Unit) {
        runCatching { wireClient.registerMerchant(name) }
        load()
    }

    LaunchedEffect(Unit) {
        intentPaid.collect { event ->
            toast = "✓ Thanh toán ${event.amount.vndFormatted()} từ #${event.customerId}"
            load()
        }
    }

    toast?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(4000); toast = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
    Column(Modifier.fillMaxSize()) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, null, tint = Color.Gray)
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            // ── Stats row ────────────────────────────────────────────────────
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.06f)) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Doanh thu", color = Color.Gray, fontSize = 12.sp)
                            Text("${vndFmt(stats?.totalEarned ?: 0)} ₫",
                                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                            Text("${stats?.orderCount ?: 0} đơn", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                    Surface(
                        Modifier.weight(1f).clickable { showLoyalty = true },
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.06f)
                    ) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107),
                                modifier = Modifier.size(24.dp))
                            Text("${members.size}", color = Color.White, fontSize = 22.sp,
                                fontWeight = FontWeight.Black)
                            Text("Thành viên", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                    val totalUnread = inbox.sumOf { it.unread }
                    Surface(
                        Modifier.weight(1f).clickable { showChatInbox = true },
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.06f)
                    ) {
                        Box(Modifier.padding(16.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null,
                                    tint = if (totalUnread > 0) Color(0xFF4FC3F7) else Color.Gray,
                                    modifier = Modifier.size(24.dp))
                                Text("${inbox.size}", color = Color.White, fontSize = 22.sp,
                                    fontWeight = FontWeight.Black)
                                Text("Tin nhắn", color = Color.Gray, fontSize = 12.sp)
                            }
                            if (totalUnread > 0) {
                                Surface(
                                    modifier = Modifier.align(Alignment.TopEnd),
                                    shape    = androidx.compose.foundation.shape.CircleShape,
                                    color    = Color(0xFFE53935)
                                ) {
                                    Text("$totalUnread",
                                        color    = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ── Bank account ─────────────────────────────────────────────────
            item {
                val bin  = prefs.getString("merchant_bank_bin",     "") ?: ""
                val acct = prefs.getString("merchant_bank_account", "") ?: ""
                val bankName = if (bin.isNotEmpty())
                    VietQR.BANKS.find { it.bin == bin }?.shortCode ?: bin
                else ""
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clickable { showBankSetup = true },
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.04f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.AccountBalance, null,
                                tint = if (acct.isNotEmpty()) Color(0xFF4CAF50) else Color.Gray,
                                modifier = Modifier.size(20.dp))
                            Column {
                                Text("VietQR Ngân hàng", color = Color.White, fontSize = 13.sp)
                                Text(
                                    if (acct.isNotEmpty()) "$bankName ···${acct.takeLast(4)}"
                                    else "Chưa thiết lập",
                                    color = if (acct.isNotEmpty()) Color.Gray else Color(0xFFFFA726),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // ── Create order button ──────────────────────────────────────────
            item {
                Button(
                    onClick  = { showCreateOrder = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(52.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tạo đơn hàng", fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Orders ───────────────────────────────────────────────────────
            if (orders.isEmpty()) {
                item {
                    Text("Chưa có đơn hàng nào",
                        color = Color.Gray, fontSize = 13.sp,
                        modifier = Modifier.padding(20.dp))
                }
            } else {
                items(orders) { order -> OrderRow(order) }
            }
        }
    }

    // ── Intent paid toast ─────────────────────────────────────────────────────
    toast?.let { msg ->
        Surface(
            modifier        = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            shape           = RoundedCornerShape(50),
            color           = Color(0xFF4CAF50),
            shadowElevation = 8.dp
        ) {
            Text(
                msg,
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp
            )
        }
    }
    } // end Box

    if (showCreateOrder) {
        CreateOrderDialog(
            mid        = uid,
            token      = token,
            prefs      = prefs,
            bankBin    = prefs.getString("merchant_bank_bin",     "") ?: "",
            bankAcct   = prefs.getString("merchant_bank_account", "") ?: "",
            bankHolder = prefs.getString("merchant_bank_holder",  "") ?: "",
            client     = client,
            wireClient = wireClient,
            onDismiss  = { showCreateOrder = false },
            onDone     = { showCreateOrder = false; scope.launch { load() } }
        )
    }
    if (showLoyalty) {
        LoyaltyMembersDialog(members, onDismiss = { showLoyalty = false })
    }
    if (showBankSetup) {
        BankSetupDialog(prefs = prefs, onDismiss = { showBankSetup = false })
    }
    if (showChatInbox) {
        ChatInboxSheet(
            inbox    = inbox,
            onSelect = { customerUid ->
                chatCustomerUid = customerUid
                showChatInbox = false
            },
            onDismiss = { showChatInbox = false }
        )
    }
    if (chatCustomerUid != 0L) {
        ConversationSheet(
            merchantsClient = client,
            mid             = uid,
            uid             = chatCustomerUid,
            merchantName    = name,
            merchantToken   = token,
            onDismiss       = { chatCustomerUid = 0L; scope.launch { load() } }
        )
    }
}

@Composable
private fun OrderRow(order: MerchantOrder) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column {
            Text(
                order.note.ifEmpty { order.id },
                color = Color.White, fontSize = 14.sp,
                maxLines = 1
            )
            Text(
                SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                    .format(Date(order.createdAt)),
                color = Color.Gray, fontSize = 11.sp
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${vndFmt(order.amount)} ₫", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when (order.status) { "paid" -> "✓ Đã TT"; "expired" -> "Hết hạn"; else -> "⏳ Chờ" },
                color = if (order.status == "paid") Color(0xFF4CAF50) else Color(0xFFFFA726),
                fontSize = 11.sp
            )
            if (order.status == "paid") {
                val pts = order.amount / 1_000L
                if (pts > 0) Text("+$pts điểm KH", color = Color(0xFFFFC107), fontSize = 11.sp)
            }
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}

// ─── Loyalty Members Dialog ───────────────────────────────────────────────────

@Composable
private fun LoyaltyMembersDialog(
    members:   List<LoyaltyMember>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A1A),
        title            = { Text("Thành viên tích điểm", color = Color.White) },
        text = {
            if (members.isEmpty()) {
                Text("Chưa có thành viên nào", color = Color.Gray, fontSize = 14.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(members) { m ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, null,
                                    tint = Color.Gray, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("UID ${m.uid}", color = Color.White, fontSize = 14.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, null,
                                    tint = Color(0xFFFFC107), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("${m.points} điểm", color = Color(0xFFFFC107),
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Đóng", color = Color.White) }
        }
    )
}

// ─── Create Order Dialog ──────────────────────────────────────────────────────

// mode: 0 = REST (Merchants Host), 1 = Wire Intent, 2 = VietQR
private const val MODE_REST = 0
private const val MODE_WIRE = 1
private const val MODE_BANK = 2

@Composable
private fun CreateOrderDialog(
    mid:        Long,
    token:      String,
    prefs:      android.content.SharedPreferences,
    bankBin:    String,
    bankAcct:   String,
    bankHolder: String,
    client:     MerchantsClient,
    wireClient: SavingClient,
    onDismiss:  () -> Unit,
    onDone:     () -> Unit,
) {
    var amountText    by remember { mutableStateOf("") }
    var note          by remember { mutableStateOf("") }
    var discountText  by remember { mutableStateOf("") }
    var loading       by remember { mutableStateOf(false) }
    var error         by remember { mutableStateOf<String?>(null) }
    var qrBitmap       by remember { mutableStateOf<Bitmap?>(null) }
    var createdOrderId by remember { mutableStateOf("") }
    var customerUidText by remember { mutableStateOf("") }
    var confirmDone    by remember { mutableStateOf(false) }
    var pointsAwarded  by remember { mutableIntStateOf(0) }
    var mode          by remember { mutableIntStateOf(if (bankBin.isNotEmpty()) MODE_BANK else MODE_REST) }
    val scope         = rememberCoroutineScope()

    val amount        = amountText.toLongOrNull() ?: 0L
    val discountPts   = discountText.toLongOrNull() ?: 0L
    val discountVnd   = discountPts * 100L
    val finalAmount   = maxOf(1L, amount - discountVnd)
    val hasBankAcct   = bankBin.isNotEmpty() && bankAcct.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A1A),
        title            = {
            Text(if (qrBitmap == null) "Tạo đơn hàng" else "QR thanh toán",
                color = Color.White)
        },
        text = {
            if (qrBitmap == null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // ── Mode toggle ────────────────────────────────────────────
                    val modes = buildList {
                        if (hasBankAcct) add(MODE_BANK to "VietQR")
                        add(MODE_REST to "REST")
                        add(MODE_WIRE to "Wire")
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                            .padding(4.dp)
                    ) {
                        modes.forEach { (m, label) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .background(
                                        if (mode == m) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { mode = m; error = null }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (mode == m) Color.White else Color.Gray,
                                    fontSize = 13.sp, fontWeight = if (mode == m) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }

                    OutlinedTextField(
                        value         = amountText,
                        onValueChange = { amountText = it.filter { c -> c.isDigit() } },
                        label         = { Text("Số tiền (₫)") },
                        placeholder   = { Text("50000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White,
                            focusedBorderColor   = Color.White,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor    = Color.White,
                            unfocusedLabelColor  = Color.Gray,
                        )
                    )

                    when (mode) {
                        MODE_REST -> {
                            OutlinedTextField(
                                value         = note,
                                onValueChange = { note = it },
                                label         = { Text("Ghi chú") },
                                placeholder   = { Text("Cà phê x2") },
                                modifier      = Modifier.fillMaxWidth(),
                                colors        = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor     = Color.White,
                                    unfocusedTextColor   = Color.White,
                                    focusedBorderColor   = Color.White,
                                    unfocusedBorderColor = Color.Gray,
                                    focusedLabelColor    = Color.White,
                                    unfocusedLabelColor  = Color.Gray,
                                )
                            )
                            OutlinedTextField(
                                value         = discountText,
                                onValueChange = { discountText = it.filter { c -> c.isDigit() } },
                                label         = { Text("Dùng điểm tích lũy") },
                                placeholder   = { Text("0") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier      = Modifier.fillMaxWidth(),
                                colors        = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor     = Color(0xFFFFC107),
                                    unfocusedTextColor   = Color(0xFFFFC107),
                                    focusedBorderColor   = Color(0xFFFFC107),
                                    unfocusedBorderColor = Color.Gray,
                                    focusedLabelColor    = Color(0xFFFFC107),
                                    unfocusedLabelColor  = Color.Gray,
                                ),
                                trailingIcon  = {
                                    if (discountPts > 0) {
                                        Text("- ${vndFmt(discountVnd)} ₫",
                                            color = Color(0xFF4CAF50), fontSize = 12.sp,
                                            modifier = Modifier.padding(end = 8.dp))
                                    }
                                }
                            )
                            if (discountPts > 0 && amount > 0) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(Color(0xFF1E2A1A), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Thực thu:", color = Color.Gray, fontSize = 13.sp)
                                    Text("${vndFmt(finalAmount)} ₫",
                                        color = Color(0xFF4CAF50), fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        MODE_WIRE -> {
                            Text("Đơn Wire — thanh toán tức thì, thông báo TCP push.",
                                color = Color.Gray, fontSize = 12.sp)
                        }
                        MODE_BANK -> {
                            val bank = VietQR.BANKS.find { it.bin == bankBin }
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(Color(0xFF1A2A1A), RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.AccountBalance, null,
                                    tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                                Column {
                                    Text(bank?.name ?: bankBin, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(bankAcct, color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    if (bankHolder.isNotEmpty())
                                        Text(bankHolder, color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                            OutlinedTextField(
                                value         = note,
                                onValueChange = { note = it },
                                label         = { Text("Nội dung chuyển khoản") },
                                placeholder   = { Text("Thanh toan xe") },
                                modifier      = Modifier.fillMaxWidth(),
                                colors        = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor     = Color.White,
                                    unfocusedTextColor   = Color.White,
                                    focusedBorderColor   = Color.White,
                                    unfocusedBorderColor = Color.Gray,
                                    focusedLabelColor    = Color.White,
                                    unfocusedLabelColor  = Color.Gray,
                                )
                            )
                        }
                    }

                    error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(240.dp).padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap             = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR",
                            modifier           = Modifier.fillMaxSize()
                        )
                    }
                    if (mode == MODE_BANK) {
                        Text("Quét bằng app ngân hàng bất kỳ",
                            color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${vndFmt(amount)} ₫", color = Color.Gray, fontSize = 12.sp)
                        if (createdOrderId.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF1A2A1A)
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Nội dung CK", color = Color.Gray, fontSize = 11.sp)
                                    Text(createdOrderId.takeLast(8),
                                        color = Color(0xFFFFA726), fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        if (confirmDone) {
                            Spacer(Modifier.height(8.dp))
                            Text("✓ Đã xác nhận thanh toán",
                                color = Color(0xFF4CAF50), fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                            if (pointsAwarded > 0) {
                                Text("+$pointsAwarded điểm tích lũy cho khách",
                                    color = Color(0xFFFFC107), fontSize = 12.sp)
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value         = customerUidText,
                                onValueChange = { customerUidText = it.filter { c -> c.isDigit() } },
                                label         = { Text("UID khách hàng (tuỳ chọn)") },
                                placeholder   = { Text("16777219") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier      = Modifier.fillMaxWidth(),
                                colors        = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor     = Color.White,
                                    unfocusedTextColor   = Color.White,
                                    focusedBorderColor   = Color.White,
                                    unfocusedBorderColor = Color.Gray,
                                    focusedLabelColor    = Color.White,
                                    unfocusedLabelColor  = Color.Gray,
                                )
                            )
                        }
                    } else {
                        Text(note.ifEmpty { "Đang chờ thanh toán..." }, color = Color.Gray, fontSize = 13.sp)
                        if (mode == MODE_REST && discountPts > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text("Giảm ${vndFmt(discountVnd)} ₫ (${discountPts} điểm)",
                                color = Color(0xFF4CAF50), fontSize = 12.sp)
                        }
                        // Local-signed order — show confirm flow
                        if (mode == MODE_REST && createdOrderId.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1A1A2A)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Mã đơn", color = Color.Gray, fontSize = 11.sp)
                                    Text(createdOrderId.takeLast(8),
                                        color = Color(0xFF90CAF9), fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                            }
                            if (confirmDone) {
                                Spacer(Modifier.height(8.dp))
                                Text("✓ Đã xác nhận", color = Color(0xFF4CAF50), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (pointsAwarded > 0)
                                    Text("+$pointsAwarded điểm tích lũy", color = Color(0xFFFFC107), fontSize = 12.sp)
                            } else {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = customerUidText,
                                    onValueChange = { customerUidText = it.filter { c -> c.isDigit() } },
                                    label = { Text("UID khách (tuỳ chọn)") },
                                    placeholder = { Text("16777219") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color.White, unfocusedBorderColor = Color.Gray,
                                        focusedLabelColor = Color.White, unfocusedLabelColor = Color.Gray,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (qrBitmap == null) {
                Button(
                    onClick = {
                        val amt = amountText.toLongOrNull() ?: return@Button
                        scope.launch {
                            loading = true; error = null
                            when (mode) {
                                MODE_WIRE -> {
                                    runCatching {
                                        val order  = client.createOrder(mid, token, amt, "", 0)
                                        val intent = wireClient.createIntent(amt)
                                        order to intent
                                    }.onSuccess { (order, intent) ->
                                        val oid = java.net.URLEncoder.encode(order.orderID, "UTF-8")
                                        val qrUrl = "saving://intent?mid=${intent.mid}&rid=${intent.requestId}&amount=${intent.amount}&oid=$oid"
                                        qrBitmap = generateQR(qrUrl)
                                    }.onFailure { error = it.message }
                                }
                                MODE_REST -> {
                                    val serverResult = runCatching {
                                        client.createOrder(mid, token, amt, note, discountPts)
                                    }
                                    if (serverResult.isSuccess) {
                                        val pr = serverResult.getOrThrow().pr
                                        qrBitmap = generateQR("saving://pay?pr=$pr")
                                    } else {
                                        // Server unreachable — sign locally with device key
                                        runCatching {
                                            val orderId = "$mid-${System.currentTimeMillis()}"
                                            if (!app.saving.wire.util.LocalMerchant.hasLocalKey(prefs)) {
                                                app.saving.wire.util.LocalMerchant.generateKey(prefs)
                                            }
                                            val pr = app.saving.wire.util.LocalMerchant.buildSignedPR(prefs, mid, orderId, amt)
                                            app.saving.wire.util.LocalMerchant.saveOrder(prefs,
                                                app.saving.wire.util.LocalMerchant.LocalOrder(
                                                    id = orderId, mid = mid, amount = amt,
                                                    note = note, status = "pending",
                                                    createdAt = System.currentTimeMillis()
                                                )
                                            )
                                            createdOrderId = orderId
                                            qrBitmap = generateQR("saving://pay?pr=$pr")
                                        }.onFailure { error = "Server lỗi & không có key cục bộ: ${it.message}" }
                                    }
                                }
                                MODE_BANK -> {
                                    val orderResult = runCatching {
                                        client.createOrder(mid, token, amt, note.ifEmpty { "VietQR" }, 0)
                                    }.getOrNull()
                                    if (orderResult != null) createdOrderId = orderResult.orderID
                                    val qrNote = orderResult?.orderID?.takeLast(8) ?: note.ifEmpty { "Thanh toan" }
                                    qrBitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        VietQR.fetchBitmap(
                                            bankBin    = bankBin,
                                            accountNo  = bankAcct,
                                            amount     = amt,
                                            note       = qrNote,
                                            holderName = bankHolder,
                                        )
                                    }
                                }
                            }
                            loading = false
                        }
                    },
                    enabled = amountText.toLongOrNull() != null && !loading,
                    colors  = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                    else Text("Tạo QR", fontWeight = FontWeight.SemiBold)
                }
            } else {
                if ((mode == MODE_BANK || mode == MODE_REST) && !confirmDone && createdOrderId.isNotEmpty()) {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                val paidBy = customerUidText.toIntOrNull() ?: 0
                                // Try server confirm; fall back to local mark-paid
                                val serverOk = runCatching {
                                    val pts = client.merchantConfirmPaid(mid, token, createdOrderId, paidBy)
                                    pointsAwarded = pts
                                }.isSuccess
                                if (!serverOk) {
                                    app.saving.wire.util.LocalMerchant.markPaid(prefs, createdOrderId)
                                }
                                confirmDone = true
                                loading = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor   = Color.White
                        )
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Đã nhận tiền", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) { Text("Xong") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Đóng", color = Color.Gray) }
        }
    )
}

// ─── Bank Setup Dialog ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankSetupDialog(
    prefs:     android.content.SharedPreferences,
    onDismiss: () -> Unit,
) {
    var selectedBin by remember { mutableStateOf(prefs.getString("merchant_bank_bin",     "") ?: "") }
    var accountNo   by remember { mutableStateOf(prefs.getString("merchant_bank_account", "") ?: "") }
    var holder      by remember { mutableStateOf(prefs.getString("merchant_bank_holder",  "") ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A1A),
        title            = { Text("Tài khoản ngân hàng", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Khách hàng quét QR bằng app ngân hàng bất kỳ.",
                    color = Color.Gray, fontSize = 12.sp)

                // Bank selector
                Text("Chọn ngân hàng", color = Color.Gray, fontSize = 11.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(VietQR.BANKS) { bank ->
                        Surface(
                            onClick = { selectedBin = bank.bin },
                            shape   = RoundedCornerShape(8.dp),
                            color   = if (selectedBin == bank.bin)
                                          Color.White.copy(alpha = 0.2f)
                                      else Color.White.copy(alpha = 0.06f)
                        ) {
                            Text(
                                bank.shortCode,
                                color    = if (selectedBin == bank.bin) Color.White else Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = if (selectedBin == bank.bin) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value         = accountNo,
                    onValueChange = { accountNo = it.filter { c -> c.isDigit() } },
                    label         = { Text("Số tài khoản") },
                    modifier      = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        focusedBorderColor   = Color.White,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor    = Color.White,
                        unfocusedLabelColor  = Color.Gray,
                    )
                )

                OutlinedTextField(
                    value         = holder,
                    onValueChange = { holder = it },
                    label         = { Text("Tên chủ tài khoản") },
                    placeholder   = { Text("NGUYEN VAN A") },
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        focusedBorderColor   = Color.White,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor    = Color.White,
                        unfocusedLabelColor  = Color.Gray,
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit()
                        .putString("merchant_bank_bin",     selectedBin)
                        .putString("merchant_bank_account", accountNo)
                        .putString("merchant_bank_holder",  holder)
                        .apply()
                    onDismiss()
                },
                enabled = selectedBin.isNotEmpty() && accountNo.isNotEmpty(),
                colors  = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ", color = Color.Gray) }
        }
    )
}

// ─── Chat Inbox Sheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInboxSheet(
    inbox:     List<app.saving.wire.data.ChatInboxItem>,
    onSelect:  (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111111),
    ) {
        Column(Modifier.fillMaxWidth().height(500.dp)) {
            Text(
                "Tin nhắn khách hàng",
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 16.sp,
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
            if (inbox.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Chưa có tin nhắn nào", color = Color.Gray)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(inbox) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item.uid) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = Color.White.copy(alpha = 0.1f),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "#",
                                            color = Color.Gray,
                                            fontSize = 16.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        "#${item.uid}",
                                        color      = Color.White,
                                        fontSize   = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        item.lastMessage,
                                        color    = Color.Gray,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (item.unread > 0) {
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = Color(0xFF4FC3F7)
                                ) {
                                    Text(
                                        "${item.unread}",
                                        color      = Color.Black,
                                        fontSize   = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier   = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

private fun generateQR(content: String, size: Int = 512): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size)
        bmp.setPixel(x, y, if (matrix[x, y]) AColor.BLACK else AColor.WHITE)
    return bmp
}

private fun vndFmt(n: Long): String {
    val s = "$n"; var out = ""; var i = 0
    for (c in s.reversed()) {
        if (i > 0 && i % 3 == 0) out = ".$out"
        out = "$c$out"; i++
    }
    return out
}
