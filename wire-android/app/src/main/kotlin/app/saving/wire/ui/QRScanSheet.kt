package app.saving.wire.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import app.saving.wire.util.vndFormatted
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import app.saving.wire.data.SavingClient
import app.saving.wire.deeplink.SavingDeeplink
import app.saving.wire.deeplink.SavingDeeplinkParser
import app.saving.wire.protocol.WireCode
import app.saving.wire.protocol.WireError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// ─── QR payload types ────────────────────────────────────────────────────────
// saving://pay?mid=12345&amount=50000&ref=ORDER_REF   → simple merchant payment
// saving://pay?pr=BASE64URL(PaymentRequest JSON)      → signed merchant payment
// saving://totp-enroll?uid=X&secret=BASE32            → enroll user TOTP
// saving://totp-pay?uid=X&token=32CHARTOKEN           → TOTP payment token
// saving://intent?mid=X&rid=Y&amount=Z               → Wire payment intent

data class MerchantPayload(val mid: Long, val amount: Long?, val ref: String?) {
    companion object {
        fun parse(raw: String): MerchantPayload? {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != "saving" || uri.host != "pay") return null

            // Signed PR format: saving://pay?pr=BASE64URL(JSON)
            uri.getQueryParameter("pr")?.let { pr ->
                return try {
                    val json = org.json.JSONObject(
                        String(android.util.Base64.decode(
                            pr.replace('-', '+').replace('_', '/'),
                            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                        ))
                    )
                    MerchantPayload(
                        mid    = json.getLong("mid"),
                        amount = json.optLong("amount").takeIf { it > 0 },
                        ref    = json.optString("order_id").ifEmpty { null }
                    )
                } catch (_: Exception) { null }
            }

            // Simple format: saving://pay?mid=...&amount=...&ref=...
            val mid = uri.getQueryParameter("mid")?.toLongOrNull() ?: return null
            return MerchantPayload(
                mid    = mid,
                amount = uri.getQueryParameter("amount")?.toLongOrNull(),
                ref    = uri.getQueryParameter("ref")
            )
        }
    }
}

data class TOTPEnrollPayload(val uid: Long, val secretB32: String) {
    companion object {
        fun parse(raw: String): TOTPEnrollPayload? {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != "saving" || uri.host != "totp-enroll") return null
            val uid = uri.getQueryParameter("uid")?.toLongOrNull() ?: return null
            val secret = uri.getQueryParameter("secret") ?: return null
            return TOTPEnrollPayload(uid, secret)
        }
    }
}

data class TOTPPayPayload(val uid: Long, val token: String, val amount: Long = 0) {
    companion object {
        fun parse(raw: String): TOTPPayPayload? {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != "saving" || uri.host != "totp-pay") return null
            val uid = uri.getQueryParameter("uid")?.toLongOrNull() ?: return null
            val token = uri.getQueryParameter("token") ?: return null
            val amount = uri.getQueryParameter("amount")?.toLongOrNull() ?: 0L
            return TOTPPayPayload(uid, token, amount)
        }
    }
}

data class IntentPayload(val mid: Long, val requestId: Long, val amount: Long, val orderID: String?) {
    companion object {
        fun parse(raw: String): IntentPayload? {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != "saving" || uri.host != "intent") return null
            val mid = uri.getQueryParameter("mid")?.toLongOrNull() ?: return null
            val rid = uri.getQueryParameter("rid")?.toLongOrNull() ?: return null
            val amt = uri.getQueryParameter("amount")?.toLongOrNull() ?: return null
            val oid = uri.getQueryParameter("oid")
            return IntentPayload(mid, rid, amt, oid)
        }
    }
}

// saving://acs?mid=X&amount=Y&ts=T&sig=BASE64&acs=URL[&note=N] → ACS payment
data class AcsPayload(
    val mid:    Long,
    val amount: Long,
    val ts:     Long,
    val sig:    ByteArray,      // 64-byte Ed25519 sig
    val acsUrl: String,
    val note:   String,
) {
    companion object {
        fun parse(raw: String): AcsPayload? {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != "saving" || uri.host != "acs") return null
            val mid    = uri.getQueryParameter("mid")?.toLongOrNull() ?: return null
            val amount = uri.getQueryParameter("amount")?.toLongOrNull() ?: return null
            val ts     = uri.getQueryParameter("ts")?.toLongOrNull() ?: return null
            val sigB64 = uri.getQueryParameter("sig") ?: return null
            val acsUrl = uri.getQueryParameter("acs") ?: return null
            val note   = uri.getQueryParameter("note") ?: ""
            val sig = try {
                android.util.Base64.decode(
                    sigB64.replace('-', '+').replace('_', '/'),
                    android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
            } catch (_: Exception) { return null }
            if (sig.size != 64) return null
            return AcsPayload(mid, amount, ts, sig, acsUrl, note)
        }
    }
}

/* saving://store?mid=X  OR  https://<slug>.nivic.dev  → open FrontStoreSheet */
data class StorePayload(val mid: Long?, val slug: String?) {
    companion object {
        fun parse(raw: String): StorePayload? {
            val uri = android.net.Uri.parse(raw) ?: return null
            return when {
                uri.scheme == "saving" && uri.host == "store" -> {
                    val mid = uri.getQueryParameter("mid")?.toLongOrNull()
                    val slug = uri.getQueryParameter("slug")
                    if (mid == null && slug == null) null else StorePayload(mid, slug)
                }
                uri.scheme == "https" && uri.host?.endsWith(".nivic.dev") == true -> {
                    val mid = uri.getQueryParameter("mid")?.toLongOrNull()
                    val slug = uri.host!!.removeSuffix(".nivic.dev").takeIf { it.isNotEmpty() && it != "www" && it != "api" }
                    StorePayload(mid, slug)
                }
                else -> null
            }
        }
    }
}

// ─── QR Scan screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScanSheet(
    client:          SavingClient,
    prefs:           android.content.SharedPreferences,
    merchantsClient: app.saving.wire.data.MerchantsClient,
    accountId:       Long,
    onDone:          () -> Unit,
    onFrontStore:    (Long, IntentPayload?) -> Unit = { _, _ -> },
    onDismiss:       () -> Unit,
) {
    var payload       by remember { mutableStateOf<MerchantPayload?>(null) }
    var enrollPayload by remember { mutableStateOf<TOTPEnrollPayload?>(null) }
    var totpPayload   by remember { mutableStateOf<TOTPPayPayload?>(null) }
    var intentPayload by remember { mutableStateOf<IntentPayload?>(null) }
    var acsPayload    by remember { mutableStateOf<AcsPayload?>(null) }
    var scanError     by remember { mutableStateOf<String?>(null) }
    val scope         = rememberCoroutineScope()
    val ctx          = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color.Black,
        sheetMaxWidth    = 600.dp
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().height(600.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier            = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment   = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        acsPayload    != null -> "Xác nhận thanh toán"
                        intentPayload != null -> "Xác nhận đơn hàng"
                        payload != null       -> "Xác nhận thanh toán"
                        enrollPayload != null -> "Đăng ký TOTP"
                        totpPayload != null   -> "Xác nhận TOTP"
                        else                  -> "Quét QR"
                    },
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                if (payload != null || enrollPayload != null || totpPayload != null || intentPayload != null || acsPayload != null) {
                    TextButton(onClick = { payload = null; enrollPayload = null; totpPayload = null; intentPayload = null; acsPayload = null; scanError = null }) {
                        Text("Quét lại", color = Color.Gray)
                    }
                }
            }

            when {
                acsPayload != null -> {
                    AcsPayContent(
                        client    = client,
                        accountId = accountId,
                        payload   = acsPayload!!,
                        onDone    = { onDone(); onDismiss() }
                    )
                }
                intentPayload != null -> {
                    LaunchedEffect(intentPayload) {
                        onFrontStore(intentPayload!!.mid, intentPayload)
                    }
                }
                enrollPayload != null -> {
                    TOTPEnrollContent(
                        p          = enrollPayload!!,
                        prefs      = prefs,
                        wireClient = client,
                        onDone     = { enrollPayload = null; onDismiss() }
                    )
                }
                totpPayload != null -> {
                    TOTPPayContent(
                        p          = totpPayload!!,
                        prefs      = prefs,
                        wireClient = client,
                        onDone     = { totpPayload = null; onDismiss() }
                    )
                }
                payload != null -> {
                    MerchantPayContent(
                        client          = client,
                        merchantsClient = merchantsClient,
                        accountId       = accountId,
                        payload         = payload!!,
                        onDone          = { onDone(); onDismiss() }
                    )
                }
                else -> {
                    CameraQRView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 20.dp)
                            .background(Color.Black, RoundedCornerShape(16.dp)),
                        onCode = { raw ->
                            // Payment QR URL: https://*.nivic.dev/qr/{token}
                            val rawUri = android.net.Uri.parse(raw)
                            if (rawUri.scheme == "https" &&
                                rawUri.host?.endsWith(".nivic.dev") == true &&
                                rawUri.path?.startsWith("/qr/") == true) {
                                scope.launch {
                                    val fetched = fetchAcsPayload(raw)
                                    if (fetched != null) { acsPayload = fetched; scanError = null }
                                    else scanError = "Không thể tải thông tin QR"
                                }
                            } else when (val dl = SavingDeeplinkParser.fromQr(raw)) {
                                is SavingDeeplink.PaymentIntent -> {
                                    intentPayload = IntentPayload(dl.mid, dl.requestId, dl.amount, dl.orderId)
                                    scanError = null
                                }
                                is SavingDeeplink.Store -> {
                                    scope.launch {
                                        val mid = dl.mid ?: dl.slug?.let { slug ->
                                            runCatching { merchantsClient.getMerchantBySlug(slug).mid }.getOrNull()
                                        }
                                        if (mid != null) {
                                            onFrontStore(mid, null)
                                        } else {
                                            scanError = "Không tìm thấy cửa hàng"
                                        }
                                    }
                                }
                                null -> {
                                    val ap = AcsPayload.parse(raw)
                                    val ep = TOTPEnrollPayload.parse(raw)
                                    val tp = TOTPPayPayload.parse(raw)
                                    val mp = MerchantPayload.parse(raw)
                                    when {
                                        ap != null -> { acsPayload = ap;   scanError = null }
                                        ep != null -> { enrollPayload = ep; scanError = null }
                                        tp != null -> { totpPayload = tp;  scanError = null }
                                        mp != null -> { payload = mp;      scanError = null }
                                        else       -> scanError = "QR không hợp lệ"
                                    }
                                }
                                is SavingDeeplink.PayOrder -> scanError = "Mở link thanh toán từ trình duyệt"
                            }
                        }
                    )
                    scanError?.let {
                        Text(it, color = Color.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    } ?: Text(
                        "Quét QR của người bán để thanh toán",
                        color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

// ─── TOTP enrollment ──────────────────────────────────────────────────────────

@Composable
private fun TOTPEnrollContent(
    p:            TOTPEnrollPayload,
    prefs:        android.content.SharedPreferences,
    wireClient:   SavingClient,
    onDone:       () -> Unit,
) {
    var enrollError by remember { mutableStateOf<String?>(null) }
    val merchantName = prefs.getString("merchant_name", "") ?: ""
    LaunchedEffect(Unit) {
        TOTPStore.save(prefs, p.uid, p.secretB32)
        runCatching {
            if (merchantName.isNotBlank()) wireClient.registerMerchant(merchantName)
            wireClient.enrollTotp(p.uid.toLong(), p.secretB32)
        }.onFailure { enrollError = it.message }
    }
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.CheckCircleOutline, null,
            tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
        Text("Đã đăng ký thành công!", color = Color.White,
            fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("UID #${p.uid} đã được lưu.\nLần sau chỉ cần quét mã thanh toán.",
            color = Color.Gray, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        enrollError?.let {
            Text("Wire enroll lỗi: $it", color = Color.Red, fontSize = 12.sp)
        }
        Button(
            onClick = onDone,
            colors  = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text("Xong", fontWeight = FontWeight.SemiBold) }
    }
}

// ─── TOTP payment verification (customer shows payment QR) ────────────────────

@Composable
private fun TOTPPayContent(
    p:          TOTPPayPayload,
    prefs:      android.content.SharedPreferences,
    wireClient: SavingClient,
    onDone:     () -> Unit,
) {
    val secret  = TOTPStore.getSecret(prefs, p.uid)
    val isValid = secret != null && TOTP.verify(secret, p.token)
    var loading by remember { mutableStateOf(false) }
    var error   by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    val scope   = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isValid) {
            Icon(Icons.Default.Error, null,
                tint = Color(0xFFFF5252), modifier = Modifier.size(64.dp))
            Text("Xác thực thất bại", color = Color(0xFFFF5252),
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (secret == null)
                Text("Chưa đăng ký người dùng này.\nYêu cầu quét QR đăng ký trước.",
                    color = Color.Gray, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            else
                Text("Mã đã hết hạn hoặc không đúng.\nYêu cầu người dùng làm mới mã.",
                    color = Color.Gray, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text("Đóng") }
            return@Column
        }

        if (success) {
            Icon(Icons.Default.CheckCircleOutline, null,
                tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
            Text("Thanh toán thành công!", color = Color.White,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text("Xong") }
            return@Column
        }

        Icon(Icons.Default.CheckCircleOutline, null,
            tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
        Text("Xác thực thành công", color = Color.White,
            fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("UID #${p.uid}", color = Color.Gray, fontSize = 14.sp)

        if (p.amount > 0) {
            Text("%,d ₫".format(p.amount),
                color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }
            Button(
                onClick = {
                    scope.launch {
                        loading = true; error = null
                        runCatching {
                            val code = TOTP.generateCode(secret!!)
                            wireClient.totpCharge(p.uid, code.toInt(), p.amount)
                        }.onSuccess { success = true }
                         .onFailure { error = it.message }
                        loading = false
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Text("Thu tiền", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text("UID #${p.uid} đã được xác nhận.\nTiến hành tạo đơn trong dashboard.",
                color = Color.Gray, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text("Đóng") }
        }
    }
}

// ─── Merchant pay confirmation ──────────────────────────────────────────────

// ─── Wire intent payment ──────────────────────────────────────────────────────

@Composable
private fun IntentPayContent(
    client:          SavingClient,
    merchantsClient: app.saving.wire.data.MerchantsClient,
    prefs:           android.content.SharedPreferences,
    accountId:       Long,
    payload:         IntentPayload,
    onDone:          () -> Unit,
) {
    var error        by remember { mutableStateOf<String?>(null) }
    var loading      by remember { mutableStateOf(false) }
    var success      by remember { mutableStateOf(false) }
    var mcName       by remember { mutableStateOf("") }
    var mcAddress    by remember { mutableStateOf("") }
    var menuItems    by remember { mutableStateOf<List<app.saving.wire.data.MenuItem>>(emptyList()) }
    val hasTOTP      = prefs.getString("own_totp_secret", null) != null
    val scope        = rememberCoroutineScope()

    LaunchedEffect(payload.mid) {
        runCatching {
            val info = merchantsClient.getMerchant(payload.mid)
            mcName = info.name; mcAddress = info.address
        }
        runCatching { menuItems = merchantsClient.listMenu(payload.mid) }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier            = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding      = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                   verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Store, null, modifier = Modifier.size(56.dp),
                    tint = Color.White.copy(alpha = 0.85f))
                if (mcName.isNotEmpty())
                    Text(mcName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                else
                    Text("#${payload.mid}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                if (mcAddress.isNotEmpty())
                    Text(mcAddress, color = Color.Gray, fontSize = 12.sp)
            }
        }

        if (menuItems.isNotEmpty()) {
            item {
                Text("Menu", color = Color.Gray, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth())
            }
            items(menuItems) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(item.name, color = Color.White, fontSize = 14.sp)
                        if (item.description.isNotEmpty())
                            Text(item.description, color = Color.Gray, fontSize = 11.sp)
                    }
                    Text("${item.price / 1000}k ₫", color = Color.Gray, fontSize = 13.sp)
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color    = Color.White.copy(alpha = 0.06f)
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tổng thanh toán", color = Color.Gray, fontSize = 12.sp)
                    Text(payload.amount.vndFormatted(), color = Color.White,
                        fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        error?.let { msg ->
            item { Text(msg, color = Color.Red, fontSize = 13.sp) }
        }

        item {
            if (success) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircleOutline, null, tint = Color(0xFF4CAF50))
                    Text("Thanh toán thành công!", color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                }
            } else {
                WirePrimaryButton(title = "XÁC NHẬN THANH TOÁN", loading = loading, enabled = !loading) {
                    scope.launch {
                        loading = true; error = null
                        try {
                            client.confirmIntent(payload.mid, payload.requestId)
                            payload.orderID?.let { oid -> merchantsClient.confirmPaid(oid, accountId.toInt()) }
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
                        } finally {
                            loading = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─── ACS payment confirmation ─────────────────────────────────────────────────

@Composable
private fun AcsPayContent(
    client:    SavingClient,
    accountId: Long,
    payload:   AcsPayload,
    onDone:    () -> Unit,
) {
    var loading by remember { mutableStateOf(false) }
    var error   by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    val scope   = rememberCoroutineScope()

    Column(
        modifier            = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Icon(Icons.Default.Store, null,
            modifier = Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.85f))

        Column(horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Người bán #${payload.mid}", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 18.sp,
                fontFamily = FontFamily.Monospace)
            if (payload.note.isNotEmpty())
                Text(payload.note, color = Color.Gray, fontSize = 13.sp)
        }

        Text(payload.amount.vndFormatted(), color = Color.White,
            fontSize = 32.sp, fontWeight = FontWeight.Black)

        error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

        if (success) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircleOutline, null, tint = Color(0xFF4CAF50))
                Text("Thanh toán thành công!", color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.SemiBold)
            }
        } else {
            WirePrimaryButton(title = "XÁC NHẬN THANH TOÁN", loading = loading, enabled = !loading) {
                scope.launch {
                    loading = true; error = null
                    try {
                        client.qrPay(payload.mid, payload.amount, payload.ts,
                                     payload.sig, payload.acsUrl)
                        success = true
                        kotlinx.coroutines.delay(1500)
                        onDone()
                    } catch (e: WireError) {
                        error = when (e.code) {
                            WireCode.ERR_LOW_BALANCE    -> "Không đủ số dư"
                            WireCode.ERR_BAD_SIG        -> "QR không hợp lệ hoặc đã hết hạn"
                            WireCode.ERR_INTENT_SETTLED -> "QR đã hết hạn (> 10 phút)"
                            WireCode.ERR_NOT_FOUND      -> "Không tìm thấy cửa hàng"
                            WireCode.ERR_SYSTEM_OFFLINE,
                            WireCode.ERR_MAINTENANCE    -> "Hệ thống tạm thời không khả dụng"
                            else -> "Lỗi: 0x${e.code.toInt().and(0xFF).toString(16)}"
                        }
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        loading = false
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MerchantPayContent(
    client:          SavingClient,
    merchantsClient: app.saving.wire.data.MerchantsClient,
    accountId:       Long,
    payload:         MerchantPayload,
    onDone:          () -> Unit,
) {
    var amountText by remember { mutableStateOf(payload.amount?.toString() ?: "") }
    var error      by remember { mutableStateOf<String?>(null) }
    var loading    by remember { mutableStateOf(false) }
    var success    by remember { mutableStateOf(false) }
    val scope      = rememberCoroutineScope()

    Column(
        modifier            = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Icon(
            imageVector   = Icons.Default.Store,
            contentDescription = null,
            modifier      = Modifier.size(64.dp),
            tint          = Color.White.copy(alpha = 0.85f)
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Người bán", color = Color.Gray, fontSize = 12.sp)
            Text("#${payload.mid}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            payload.ref?.let {
                Text("Ref: $it", color = Color.Gray, fontSize = 11.sp)
            }
        }

        if (payload.amount != null) {
            Text(payload.amount.vndFormatted(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
        } else {
            WireTextField(amountText, { amountText = it }, "Số tiền (VND)",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
        }

        error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

        if (success) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircleOutline, null, tint = Color(0xFF4CAF50))
                Text("Thanh toán thành công!", color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
            }
        } else {
            WirePrimaryButton(
                title   = "THANH TOÁN",
                loading = loading,
                enabled = payload.amount != null || amountText.isNotEmpty()
            ) {
                scope.launch {
                    val amt = payload.amount ?: amountText.toLongOrNull() ?: 0
                    if (amt <= 0) { error = "Nhập số tiền hợp lệ"; return@launch }
                    loading = true; error = null
                    try {
                        client.payMerchant(payload.mid, amt)
                        payload.ref?.let { orderId ->
                            merchantsClient.confirmPaid(orderId, accountId.toInt())
                        }
                        success = true
                        kotlinx.coroutines.delay(1500)
                        onDone()
                    } catch (e: WireError) {
                        error = if (e.code == WireCode.ERR_LOW_BALANCE) "Không đủ số dư." else "Lỗi: ${e.code}"
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        loading = false
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ─── Fetch saving-pay meta from a payment QR URL ─────────────────────────────

private suspend fun fetchAcsPayload(url: String): AcsPayload? = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout    = 5_000
        conn.setRequestProperty("User-Agent", "WireApp/1.0")
        val html = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        // Match <meta name="saving-pay" content="..."> (order of attributes may vary)
        val regex = Regex(
            """<meta\b[^>]*\bname=["']saving-pay["'][^>]*\bcontent=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val content = regex.find(html)?.groupValues?.get(1) ?: return@withContext null
        AcsPayload.parse("saving://acs?$content")
    } catch (_: Exception) { null }
}

// ─── CameraX QR scanner ────────────────────────────────────────────────────

@Composable
private fun CameraQRView(modifier: Modifier = Modifier, onCode: (String) -> Unit) {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPerm       by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPerm = it
    }

    LaunchedEffect(Unit) {
        if (!hasPerm) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPerm) {
        Box(modifier.background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
            Text("Cần quyền camera để quét QR", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    val fired = remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            val previewView = PreviewView(ctx)
            val executor    = Executors.newSingleThreadExecutor()
            val future      = ProcessCameraProvider.getInstance(ctx)

            future.addListener({
                val provider = future.get()
                val preview  = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(executor) { proxy ->
                            if (!fired.value) {
                                val img = InputImage.fromMediaImage(proxy.image ?: run { proxy.close(); return@setAnalyzer },
                                    proxy.imageInfo.rotationDegrees)
                                BarcodeScanning.getClient().process(img)
                                    .addOnSuccessListener { codes ->
                                        val str = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                                        if (str != null && !fired.value) {
                                            fired.value = true
                                            onCode(str)
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } else {
                                proxy.close()
                            }
                        }
                    }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
