package dev.nivic.wire.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import dev.nivic.wire.data.SavingClient
import dev.nivic.wire.protocol.WireCode
import dev.nivic.wire.protocol.WireError
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

// ─── QR payload types ────────────────────────────────────────────────────────
// saving://pay?mid=12345&amount=50000&ref=ORDER_REF   → merchant payment
// saving://totp-enroll?uid=X&secret=BASE32            → enroll user TOTP
// saving://totp-pay?uid=X&token=32CHARTOKEN           → TOTP payment token

data class MerchantPayload(val mid: Long, val amount: Long?, val ref: String?) {
    companion object {
        fun parse(raw: String): MerchantPayload? {
            val uri   = android.net.Uri.parse(raw)
            if (uri.scheme != "saving" || uri.host != "pay") return null
            val mid   = uri.getQueryParameter("mid")?.toLongOrNull() ?: return null
            val amount = uri.getQueryParameter("amount")?.toLongOrNull()
            val ref   = uri.getQueryParameter("ref")
            return MerchantPayload(mid, amount, ref)
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

data class TOTPPayPayload(val uid: Long, val token: String) {
    companion object {
        fun parse(raw: String): TOTPPayPayload? {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != "saving" || uri.host != "totp-pay") return null
            val uid = uri.getQueryParameter("uid")?.toLongOrNull() ?: return null
            val token = uri.getQueryParameter("token") ?: return null
            return TOTPPayPayload(uid, token)
        }
    }
}

// ─── QR Scan screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScanSheet(
    client:    SavingClient,
    prefs:     android.content.SharedPreferences,
    onDone:    () -> Unit,
    onDismiss: () -> Unit,
) {
    var payload      by remember { mutableStateOf<MerchantPayload?>(null) }
    var enrollPayload by remember { mutableStateOf<TOTPEnrollPayload?>(null) }
    var totpPayload  by remember { mutableStateOf<TOTPPayPayload?>(null) }
    var scanError    by remember { mutableStateOf<String?>(null) }
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
                        payload != null      -> "Xác nhận thanh toán"
                        enrollPayload != null -> "Đăng ký TOTP"
                        totpPayload != null  -> "Xác nhận TOTP"
                        else                 -> "Quét QR"
                    },
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                if (payload != null || enrollPayload != null || totpPayload != null) {
                    TextButton(onClick = { payload = null; enrollPayload = null; totpPayload = null; scanError = null }) {
                        Text("Quét lại", color = Color.Gray)
                    }
                }
            }

            when {
                enrollPayload != null -> {
                    TOTPEnrollContent(
                        p      = enrollPayload!!,
                        prefs  = prefs,
                        onDone = { enrollPayload = null; onDismiss() }
                    )
                }
                totpPayload != null -> {
                    TOTPPayContent(
                        p      = totpPayload!!,
                        prefs  = prefs,
                        onDone = { totpPayload = null; onDismiss() }
                    )
                }
                payload != null -> {
                    MerchantPayContent(
                        client  = client,
                        payload = payload!!,
                        onDone  = { onDone(); onDismiss() }
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
                            val ep = TOTPEnrollPayload.parse(raw)
                            val tp = TOTPPayPayload.parse(raw)
                            val mp = MerchantPayload.parse(raw)
                            when {
                                ep != null -> { enrollPayload = ep; scanError = null }
                                tp != null -> { totpPayload = tp;  scanError = null }
                                mp != null -> { payload = mp;      scanError = null }
                                else       -> scanError = "QR không hợp lệ"
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
    p:      TOTPEnrollPayload,
    prefs:  android.content.SharedPreferences,
    onDone: () -> Unit,
) {
    LaunchedEffect(Unit) {
        TOTPStore.save(prefs, p.uid, p.secretB32)
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
        Button(
            onClick = onDone,
            colors  = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text("Xong", fontWeight = FontWeight.SemiBold) }
    }
}

// ─── TOTP payment verification (customer shows payment QR) ────────────────────

@Composable
private fun TOTPPayContent(
    p:      TOTPPayPayload,
    prefs:  android.content.SharedPreferences,
    onDone: () -> Unit,
) {
    val secret  = TOTPStore.getSecret(prefs, p.uid)
    val isValid = secret != null && TOTP.verify(secret, p.token)

    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isValid) {
            Icon(Icons.Default.CheckCircleOutline, null,
                tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
            Text("Xác thực thành công", color = Color.White,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("UID #${p.uid} đã được xác nhận.\nTiến hành tạo đơn trong dashboard.",
                color = Color.Gray, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(p.token.chunked(8).joinToString(" "),
                color = Color(0xFF4CAF50), fontSize = 13.sp,
                fontFamily = FontFamily.Monospace)
        } else {
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
        }
        Button(
            onClick = onDone,
            colors  = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text("Đóng") }
    }
}

// ─── Merchant pay confirmation ──────────────────────────────────────────────

@Composable
private fun MerchantPayContent(client: SavingClient, payload: MerchantPayload, onDone: () -> Unit) {
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
