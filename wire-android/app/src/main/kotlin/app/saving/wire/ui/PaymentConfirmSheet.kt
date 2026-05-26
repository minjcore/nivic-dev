package app.saving.wire.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.saving.wire.data.MerchantsClient
import app.saving.wire.data.SavingClient
import app.saving.wire.util.vndFormatted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Green = Color(0xFF4CAF50)
private val Warn  = Color(0xFFFF9800)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentConfirmSheet(
    intentPayload:   IntentPayload,
    merchantName:    String,
    merchantAddress: String,
    client:          SavingClient,
    merchantsClient: MerchantsClient,
    accountId:       Long,
    onDone:          () -> Unit,
    onDismiss:       () -> Unit,
) {
    var balance   by remember { mutableStateOf<Long?>(null) }
    var loading   by remember { mutableStateOf(false) }
    var error     by remember { mutableStateOf<String?>(null) }
    var success   by remember { mutableStateOf(false) }
    val scope     = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            balance = client.balance()
        } catch (e: Exception) {
            error = "Không lấy được số dư: ${e.message}"
        }
    }

    val after  = balance?.minus(intentPayload.amount)
    val canPay = balance != null && after != null && after >= 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF0A0A0A),
        sheetMaxWidth    = 600.dp,
        dragHandle       = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            )
        },
    ) {
        AnimatedContent(
            targetState = success,
            transitionSpec = {
                fadeIn() + slideInVertically { it / 4 } togetherWith
                fadeOut() + slideOutVertically { -it / 4 }
            },
            label = "pay_state"
        ) { done ->
            if (done) {
                SuccessContent(onDone)
            } else {
                Column(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // ── Merchant header ──────────────────────────────────────
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Store,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val displayName = merchantName.ifEmpty { "#${intentPayload.mid}" }
                    Text(
                        displayName,
                        color      = Color.White,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                    if (merchantAddress.isNotEmpty()) {
                        Text(
                            merchantAddress,
                            color     = Color.Gray,
                            fontSize  = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.padding(top = 2.dp)
                        )
                    }

                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(24.dp))

                    // ── Amount ───────────────────────────────────────────────
                    Text(
                        "THANH TOÁN",
                        color     = Color.Gray,
                        fontSize  = 11.sp,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        intentPayload.amount.vndFormatted(),
                        color      = Color.White,
                        fontSize   = 38.sp,
                        fontWeight = FontWeight.Black,
                        textAlign  = TextAlign.Center
                    )

                    if (intentPayload.orderID != null) {
                        Text(
                            "Đơn #${intentPayload.orderID}",
                            color    = Color.Gray,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(20.dp))

                    // ── Balance summary ──────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LedgerRow("Từ tài khoản", "#$accountId",
                            valueColor = Color.Gray, monospace = true)
                        LedgerRow("Đến", displayName)
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        if (balance != null) {
                            LedgerRow("Số dư hiện tại", balance!!.vndFormatted())
                            LedgerRow(
                                label      = "Sau thanh toán",
                                value      = after!!.vndFormatted(),
                                valueColor = if (canPay) Color.White else Warn
                            )
                        } else {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    color = Color.Gray,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // ── Error ────────────────────────────────────────────────
                    AnimatedVisibility(visible = error != null) {
                        Text(
                            error ?: "",
                            color    = Color(0xFFFF5252),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    // ── Confirm button ───────────────────────────────────────
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true; error = null
                                try {
                                    client.confirmIntent(intentPayload.mid, intentPayload.requestId)
                                    intentPayload.orderID?.let { oid ->
                                        merchantsClient.confirmPaid(oid, accountId.toInt())
                                    }
                                    success = true
                                } catch (e: Exception) {
                                    error = e.message
                                } finally { loading = false }
                            }
                        },
                        enabled  = !loading && canPay,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor         = Color.White,
                            contentColor           = Color.Black,
                            disabledContainerColor = Color.White.copy(alpha = 0.12f),
                            disabledContentColor   = Color.Gray,
                        )
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "XÁC NHẬN THANH TOÁN",
                                fontSize      = 14.sp,
                                fontWeight    = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily    = FontFamily.Monospace,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Huỷ", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(1800); onDone() }
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint     = Green,
            modifier = Modifier.size(72.dp)
        )
        Text(
            "Thanh toán thành công!",
            color      = Color.White,
            fontSize   = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )
        Text(
            "Giao dịch đã được ghi nhận",
            color     = Color.Gray,
            fontSize  = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LedgerRow(
    label:      String,
    value:      String,
    valueColor: Color   = Color.White,
    monospace:  Boolean = false,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(
            value,
            color      = valueColor,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
