package app.saving.wire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.saving.wire.util.vndFormatted
import app.saving.wire.viewmodel.WireViewModel
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: WireViewModel, accountId: Long) {
    val homeState    by vm.homeState.collectAsStateWithLifecycle()
    val balance      = homeState.balance
    val toast        = homeState.toast

    var showTransfer by remember { mutableStateOf(false) }
    var showHistory  by remember { mutableStateOf(false) }
    var showQRRecv   by remember { mutableStateOf(false) }
    var showQRScan   by remember { mutableStateOf(false) }
    var showGuardian by remember { mutableStateOf(false) }
    var showMerchant by remember { mutableStateOf(false) }
    var showTOTP     by remember { mutableStateOf(false) }
    var showLoyalty  by remember { mutableStateOf(false) }
    var showSearch   by remember { mutableStateOf(false) }
    var transferToId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshBalance() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // ── Balance card ──────────────────────────────────────────────────
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text("#$accountId", color = Color.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(balance.vndFormatted(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
                IconButton(onClick = { vm.logout() }) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Color.Gray)
                }
            }

            // ── Quick actions ─────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickBtn(Icons.Default.ArrowUpward,   "GỬI",     Modifier.weight(1f)) { showTransfer = true }
                QuickBtn(Icons.Default.QrCodeScanner, "QUÉT QR", Modifier.weight(1f)) { showQRScan   = true }
                QuickBtn(Icons.Default.QrCode,        "QR NHẬN", Modifier.weight(1f)) { showQRRecv   = true }
            }

            // ── Mini-app grid ─────────────────────────────────────────────────
            Text("Ứng dụng", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniTile(Icons.AutoMirrored.Filled.List, "Lịch sử",  Modifier.weight(1f)) { showHistory  = true }
                    MiniTile(Icons.Default.QrCode,     "QR nhận",  Modifier.weight(1f)) { showQRRecv   = true }
                    MiniTile(Icons.Default.Group,      "Bảo hộ",   Modifier.weight(1f)) { showGuardian = true }
                    MiniTile(Icons.Default.Store, "Bán hàng", Modifier.weight(1f)) { showMerchant = true }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniTile(Icons.Default.VpnKey,  "Mã TT",     Modifier.weight(1f)) { showTOTP    = true }
                    MiniTile(Icons.Default.Star,    "Tích điểm", Modifier.weight(1f)) { showLoyalty = true }
                    MiniTile(Icons.Default.Refresh, "Phục hồi",  Modifier.weight(1f)) { }
                    Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(120.dp))
        }

        // ── Search FAB ────────────────────────────────────────────────────────
        FloatingActionButton(
            onClick        = { showSearch = true },
            modifier       = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = Color.White,
            contentColor   = Color.Black,
            shape          = androidx.compose.foundation.shape.CircleShape,
        ) {
            Icon(Icons.Default.Search, contentDescription = "Tìm kiếm", modifier = Modifier.size(24.dp))
        }

        // ── Toast ─────────────────────────────────────────────────────────────
        toast?.let { msg ->
            LaunchedEffect(msg) { delay(3000); vm.clearToast() }
            Surface(
                modifier        = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                shape           = RoundedCornerShape(50),
                color           = Color.White,
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

    if (showTransfer) TransferSheet(
        client      = vm.client,
        initialToId = transferToId,
        onDone      = { vm.refreshBalance() },
        onDismiss   = { showTransfer = false; transferToId = "" }
    )
    if (showHistory)  HistorySheet(vm.client)                                                                        { showHistory  = false }
    if (showQRRecv)   QRReceiveSheet(accountId)                                                                      { showQRRecv   = false }
    if (showQRScan)   QRScanSheet(vm.client, vm.prefs, vm.merchantsClient, accountId, onDone = { vm.refreshBalance() }) { showQRScan = false }
    if (showGuardian) GuardianSheet(vm.client)                                                                       { showGuardian = false }
    if (showMerchant) MerchantSheet(accountId, vm.merchantsClient, vm.client, vm.prefs)                             { showMerchant = false }
    if (showLoyalty)  MyLoyaltySheet(vm.merchantsClient, accountId)                                                  { showLoyalty  = false }
    if (showTOTP)     TOTPPaySheet(accountId, vm.prefs)                                                              { showTOTP     = false }
    if (showSearch)   SearchSheet(
        client     = vm.client,
        onTransfer = { id -> transferToId = id; showSearch = false; showTransfer = true },
        onDismiss  = { showSearch = false }
    )
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
        shape          = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MiniTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick        = onClick,
        modifier       = modifier.aspectRatio(1f),
        colors         = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.05f),
            contentColor   = Color.White
        ),
        shape          = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 9.sp, color = Color.Gray)
        }
    }
}
