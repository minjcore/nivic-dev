package dev.nivic.wire.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.nivic.wire.ui.TOTP
import dev.nivic.wire.ui.TOTPStore
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TOTPPaySheet(
    accountId: Long,
    prefs:     android.content.SharedPreferences,
    onDismiss: () -> Unit,
) {
    val secret   = remember { TOTPStore.getSecret(prefs, accountId) }
    var code     by remember { mutableStateOf("") }
    var secs     by remember { mutableStateOf(TOTP.secondsRemaining()) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(secret) {
        if (secret == null) return@LaunchedEffect
        while (true) {
            val newCode = TOTP.generate(secret)
            if (newCode != code) {
                code     = newCode
                qrBitmap = makeTotpQR("saving://totp-pay?uid=$accountId&token=$newCode")
            }
            secs = TOTP.secondsRemaining()
            delay(1_000)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color.Black,
        sheetMaxWidth    = 480.dp,
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Mã thanh toán", color = Color.White,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)

            if (secret == null) {
                Spacer(Modifier.height(16.dp))
                Icon(Icons.Default.QrCode2, null,
                    tint     = Color.Gray,
                    modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(8.dp))
                Text("Chưa đăng ký mã thanh toán",
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Yêu cầu cửa hàng quét QR đăng ký\n" +
                    "(saving://totp-enroll) để kích hoạt.",
                    color    = Color.Gray, fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                // QR code
                val bmp = qrBitmap
                if (bmp != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White
                    ) {
                        Image(
                            bitmap             = bmp.asImageBitmap(),
                            contentDescription = "TOTP QR",
                            modifier           = Modifier
                                .size(240.dp)
                                .padding(12.dp)
                        )
                    }
                } else {
                    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                // Code text
                Text(
                    code.chunked(8).joinToString("  "),
                    color      = Color.White,
                    fontSize   = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                // Countdown bar
                val fraction = secs / 30f
                val barColor = when {
                    secs > 15 -> Color(0xFF4CAF50)
                    secs > 7  -> Color(0xFFFFC107)
                    else      -> Color(0xFFFF5252)
                }
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress         = { fraction },
                        modifier         = Modifier.fillMaxWidth().height(4.dp),
                        color            = barColor,
                        trackColor       = Color.White.copy(alpha = 0.12f),
                        strokeCap        = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Làm mới sau $secs giây", color = Color.Gray, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss) {
                Text("Đóng", color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}

private fun makeTotpQR(content: String, size: Int = 512): Bitmap {
    val hints  = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp    = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size)
        bmp.setPixel(x, y, if (matrix[x, y]) AColor.BLACK else AColor.WHITE)
    return bmp
}
