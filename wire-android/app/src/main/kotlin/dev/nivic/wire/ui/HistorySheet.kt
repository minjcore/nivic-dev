package dev.nivic.wire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nivic.wire.data.SavingClient
import dev.nivic.wire.data.Transaction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(client: SavingClient, onDismiss: () -> Unit) {
    var txs     by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        txs     = runCatching { client.history() }.getOrDefault(emptyList())
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111111)
    ) {
        Column(Modifier.fillMaxWidth().height(500.dp)) {
            Text(
                "Lịch sử",
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 16.sp
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                }
                txs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Chưa có giao dịch nào", color = Color.Gray)
                }
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(txs) { tx -> TxRow(tx) }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TxRow(tx: Transaction) {
    val received = tx.direction == Transaction.Direction.RECEIVED
    Row(
        modifier          = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = if (received) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = null,
                tint               = if (received) Color(0xFF4CAF50) else Color(0xFFFF9800),
                modifier           = Modifier.size(28.dp)
            )
            Column {
                Text(if (received) "Nhận từ" else "Gửi đến", color = Color.Gray, fontSize = 11.sp)
                Text("#${tx.counterpartId}", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            (if (received) "+" else "−") + tx.amount.vndFormatted(),
            color      = if (received) Color(0xFF4CAF50) else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp
        )
    }
}
