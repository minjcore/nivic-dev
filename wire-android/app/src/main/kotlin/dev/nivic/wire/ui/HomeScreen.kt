package dev.nivic.wire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nivic.wire.data.SavingClient
import dev.nivic.wire.data.SavingEvent
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(client: SavingClient, accountId: Long, onLogout: () -> Unit) {
    var balance      by remember { mutableStateOf(0L) }
    var showTransfer by remember { mutableStateOf(false) }
    var showHistory  by remember { mutableStateOf(false) }
    var showQRRecv   by remember { mutableStateOf(false) }
    var showQRScan   by remember { mutableStateOf(false) }
    var showGuardian by remember { mutableStateOf(false) }
    var toast        by remember { mutableStateOf<String?>(null) }
    val scope        = rememberCoroutineScope()

    suspend fun refresh() { balance = runCatching { client.balance() }.getOrDefault(balance) }

    LaunchedEffect(Unit) {
        refresh()
        client.onEvent = { event ->
            if (event is SavingEvent.TransferIn) {
                balance = event.transfer.balance
                toast   = "+${event.transfer.amount.vndFormatted()} từ #${event.transfer.fromId}"
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // ── Balance card ──────────────────────────────────────────────────
            Row(
                modifier            = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment   = Alignment.CenterVertically
            ) {
                Column {
                    Text("#$accountId", color = Color.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(balance.vndFormatted(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
                IconButton(onClick = {
                    scope.launch { runCatching { client.logout() }; onLogout() }
                }) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color.Gray)
                }
            }

            // ── Quick actions ─────────────────────────────────────────────────
            Row(
                modifier            = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickBtn(Icons.Default.ArrowUpward,   "GỬI",     Modifier.weight(1f)) { showTransfer = true }
                QuickBtn(Icons.Default.QrCodeScanner, "QUÉT QR", Modifier.weight(1f)) { showQRScan   = true }
                QuickBtn(Icons.Default.QrCode,        "QR NHẬN", Modifier.weight(1f)) { showQRRecv   = true }
            }

            // ── Mini-app grid ─────────────────────────────────────────────────
            Text("Ứng dụng", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))

            Row(
                modifier            = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MiniTile(Icons.Default.List,    "Lịch sử", Modifier.weight(1f)) { showHistory  = true }
                MiniTile(Icons.Default.QrCode,  "QR nhận", Modifier.weight(1f)) { showQRRecv   = true }
                MiniTile(Icons.Default.Group,   "Bảo hộ",  Modifier.weight(1f)) { showGuardian = true }
                MiniTile(Icons.Default.Refresh, "Phục hồi",Modifier.weight(1f)) { }
            }

            Spacer(Modifier.height(120.dp))
        }

        // ── Toast ─────────────────────────────────────────────────────────────
        toast?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(3000); toast = null }
            Surface(
                modifier      = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                shape         = RoundedCornerShape(50),
                color         = Color.White,
                shadowElevation = 8.dp
            ) {
                Text(
                    msg,
                    modifier   = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color      = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp
                )
            }
        }
    }

    if (showTransfer) TransferSheet(client, onDone = { scope.launch { refresh() } })  { showTransfer = false }
    if (showHistory)  HistorySheet(client)                                              { showHistory  = false }
    if (showQRRecv)   QRReceiveSheet(accountId)                                         { showQRRecv   = false }
    if (showQRScan)   QRScanSheet(client, onDone = { scope.launch { refresh() } })     { showQRScan   = false }
    if (showGuardian) GuardianSheet(client)                                             { showGuardian = false }
}

@Composable
private fun QuickBtn(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick  = onClick,
        modifier = modifier.height(52.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor   = Color.White
        ),
        shape           = RoundedCornerShape(12.dp),
        contentPadding  = PaddingValues(horizontal = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MiniTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick         = onClick,
        modifier        = modifier.aspectRatio(1f),
        colors          = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.05f),
            contentColor   = Color.White
        ),
        shape           = RoundedCornerShape(14.dp),
        contentPadding  = PaddingValues(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 9.sp, color = Color.Gray)
        }
    }
}
