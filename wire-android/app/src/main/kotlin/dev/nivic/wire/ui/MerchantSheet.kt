package dev.nivic.wire.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import dev.nivic.wire.data.MerchantOrder
import dev.nivic.wire.data.MerchantStats
import dev.nivic.wire.data.MerchantsClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─── Entry point ─────────────────────────────────────────────────────────────

@Composable
fun MerchantSheet(
    uid:     Long,
    client:  MerchantsClient,
    prefs:   android.content.SharedPreferences,
    onClose: () -> Unit,
) {
    val savedToken = prefs.getString("merchant_token", "") ?: ""
    val savedName  = prefs.getString("merchant_name",  "") ?: ""

    if (savedToken.isEmpty()) {
        MerchantOnboardingView(uid, client, onClose) { name, token ->
            prefs.edit().putString("merchant_token", token).putString("merchant_name", name).apply()
        }
    } else {
        MerchantDashboardView(uid, savedName, savedToken, client, onClose)
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
            Icon(Icons.Default.Storefront, null,
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
            Icon(Icons.Default.CheckCircle, null,
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
    uid:     Long,
    name:    String,
    token:   String,
    client:  MerchantsClient,
    onClose: () -> Unit,
) {
    var stats          by remember { mutableStateOf<MerchantStats?>(null) }
    var orders         by remember { mutableStateOf<List<MerchantOrder>>(emptyList()) }
    var showCreateOrder by remember { mutableStateOf(false) }
    val scope          = rememberCoroutineScope()

    suspend fun load() {
        runCatching { stats  = client.stats(uid, token) }
        runCatching { orders = client.listOrders(uid, token) }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
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
            // ── Stats card ───────────────────────────────────────────────────
            item {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.06f)
                ) {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Doanh thu", color = Color.Gray, fontSize = 13.sp)
                        Text(
                            "${vndFmt(stats?.totalEarned ?: 0)} ₫",
                            color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black
                        )
                        Text("${stats?.orderCount ?: 0} đơn thành công",
                            color = Color.Gray, fontSize = 13.sp)
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

    if (showCreateOrder) {
        CreateOrderDialog(uid, token, client,
            onDismiss = { showCreateOrder = false },
            onDone    = { showCreateOrder = false; scope.launch { load() } })
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
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}

// ─── Create Order Dialog ──────────────────────────────────────────────────────

@Composable
private fun CreateOrderDialog(
    mid:       Long,
    token:     String,
    client:    MerchantsClient,
    onDismiss: () -> Unit,
    onDone:    () -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var note       by remember { mutableStateOf("") }
    var loading    by remember { mutableStateOf(false) }
    var error      by remember { mutableStateOf<String?>(null) }
    var qrBitmap   by remember { mutableStateOf<Bitmap?>(null) }
    val scope      = rememberCoroutineScope()

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
                    error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(220.dp).padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap             = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR",
                            modifier           = Modifier.fillMaxSize()
                        )
                    }
                    Text(note.ifEmpty { "Đang chờ thanh toán..." },
                        color = Color.Gray, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (qrBitmap == null) {
                Button(
                    onClick = {
                        val amount = amountText.toLongOrNull() ?: return@Button
                        scope.launch {
                            loading = true; error = null
                            runCatching { client.createOrder(mid, token, amount, note) }
                                .onSuccess { result ->
                                    qrBitmap = generateQR("saving://pay?pr=${result.pr}")
                                }
                                .onFailure { error = it.message }
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
                Button(onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) { Text("Xong") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Đóng", color = Color.Gray) }
        }
    )
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
