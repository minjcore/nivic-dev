package app.saving.wire.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.saving.wire.data.MerchantsClient
import app.saving.wire.data.UserLoyaltyEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyLoyaltySheet(
    client:    MerchantsClient,
    uid:       Long,
    onDismiss: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<UserLoyaltyEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        entries = runCatching { client.userLoyalty(uid) }.getOrDefault(emptyList())
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111111),
    ) {
        Column(Modifier.fillMaxWidth().height(480.dp)) {
            Text(
                "Thẻ tích điểm",
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 16.sp
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.StarBorder, null,
                            tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Text("Chưa có điểm thưởng nào", color = Color.Gray)
                        Text("Thanh toán tại merchant để tích điểm",
                            color = Color.Gray, fontSize = 12.sp)
                    }
                }
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(entries) { e -> LoyaltyEntryRow(e) }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LoyaltyEntryRow(e: UserLoyaltyEntry) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column {
            Text(e.merchantName, color = Color.White,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("#${e.mid}", color = Color.Gray,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Star, null,
                    tint = Color(0xFFFFC107), modifier = Modifier.size(14.dp))
                Text("${e.points} điểm",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text("= ${e.points * 100} ₫", color = Color.Gray, fontSize = 11.sp)
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}
