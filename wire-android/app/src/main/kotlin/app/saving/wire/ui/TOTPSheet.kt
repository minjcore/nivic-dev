package app.saving.wire.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TOTPPaySheet(
    accountId: Long,
    prefs:     android.content.SharedPreferences,
    onDismiss: () -> Unit,
) {
    val secretB32    = remember { TOTPStore.getOrCreateOwnSecretB32(prefs) }
    val secret       = remember { base32Decode(secretB32) }
    var code         by remember { mutableStateOf("") }
    var secs         by remember { mutableStateOf(TOTP.secondsRemaining()) }
    var payQR        by remember { mutableStateOf<Bitmap?>(null) }
    var enrollQR     by remember { mutableStateOf<Bitmap?>(null) }
    var showEnroll   by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        enrollQR = makeTotpQR("saving://totp-enroll?uid=$accountId&secret=$secretB32")
        while (true) {
            val newCode = TOTP.generate(secret)
            if (newCode != code) {
                code  = newCode
                payQR = makeTotpQR("saving://totp-pay?uid=$accountId&token=$newCode")
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
            // Countdown ring
            val progress by animateFloatAsState(
                targetValue = secs / 30f,
                animationSpec = tween(durationMillis = 900),
                label = "countdown"
            )
            val ringColor = when {
                secs > 15 -> Color(0xFF4CAF50)
                secs > 7  -> Color(0xFFFFC107)
                else      -> Color(0xFFFF5252)
            }

            Box(
                modifier          = Modifier.size(88.dp),
                contentAlignment  = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress         = { progress },
                    modifier         = Modifier.fillMaxSize(),
                    color            = ringColor,
                    trackColor       = Color.White.copy(alpha = 0.08f),
                    strokeWidth      = 5.dp,
                    strokeCap        = StrokeCap.Round,
                )
                Text("${secs}s", color = Color.Gray, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace)
            }

            Text("MÃ THANH TOÁN",
                color = Color.Gray, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)

            // Code text: 8 groups of 4
            Text(
                code.chunked(4).joinToString("  "),
                color      = Color.White,
                fontSize   = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )

            // Payment QR
            val bmp = payQR
            if (bmp != null) {
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                    Image(
                        bitmap             = bmp.asImageBitmap(),
                        contentDescription = "TOTP Payment QR",
                        modifier           = Modifier.size(220.dp).padding(12.dp)
                    )
                }
                Text("Merchant quét QR này để xác nhận",
                    color = Color.Gray, fontSize = 12.sp)
            } else {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            // Enrollment QR toggle
            Button(
                onClick = { showEnroll = !showEnroll },
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    contentColor   = Color.White
                ),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCode2, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (showEnroll) "Ẩn QR đăng ký" else "Đăng ký với cửa hàng mới",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
            }

            if (showEnroll) {
                val eqr = enrollQR
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Merchant quét QR này một lần để đăng ký",
                        color = Color.Gray, fontSize = 12.sp)
                    if (eqr != null) {
                        Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                            Image(
                                bitmap             = eqr.asImageBitmap(),
                                contentDescription = "Enroll QR",
                                modifier           = Modifier.size(180.dp).padding(10.dp)
                            )
                        }
                    }
                }
            }

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
