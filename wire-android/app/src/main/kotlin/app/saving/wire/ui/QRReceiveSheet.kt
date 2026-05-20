package app.saving.wire.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRReceiveSheet(accountId: Long, onDismiss: () -> Unit) {
    val bitmap = remember(accountId) { generateQR("saving://pay?mid=$accountId") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF111111)) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Nhận tiền", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Quét để gửi tiền cho tôi", color = Color.Gray, fontSize = 13.sp)

            bitmap?.let { bmp ->
                Box(
                    modifier         = Modifier
                        .size(220.dp)
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap             = bmp.asImageBitmap(),
                        contentDescription = "QR code",
                        modifier           = Modifier.fillMaxSize()
                    )
                }
            }

            Text(
                "#$accountId",
                color      = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize   = 22.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun generateQR(content: String, size: Int = 512): Bitmap? = runCatching {
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size)
        bmp.setPixel(x, y, if (bits[x, y]) AColor.BLACK else AColor.WHITE)
    bmp
}.getOrNull()
