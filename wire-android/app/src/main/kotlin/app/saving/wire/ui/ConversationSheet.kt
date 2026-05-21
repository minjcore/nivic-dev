package app.saving.wire.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.saving.wire.data.ChatMessage
import app.saving.wire.data.MerchantsClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// merchantToken = null  → customer mode  (send via POST /chat/{mid})
// merchantToken = "xxx" → merchant mode  (reply via POST /chat/{mid}/reply, bubbles flipped)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSheet(
    merchantsClient: MerchantsClient,
    mid:             Long,
    uid:             Long,
    merchantName:    String,
    merchantToken:   String? = null,
    onDismiss:       () -> Unit,
) {
    val isMerchantMode = merchantToken != null
    var messages  by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input     by remember { mutableStateOf("") }
    var sending   by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    // track latest message id seen so incremental fetch avoids re-loading
    var lastId by remember { mutableLongStateOf(0L) }

    suspend fun fetchNew() {
        val since = messages.lastOrNull()?.createdAt ?: 0L
        val news  = runCatching { merchantsClient.getThread(mid, uid, since) }.getOrDefault(emptyList())
        if (news.isNotEmpty()) {
            messages = messages + news
            lastId   = messages.last().id
        }
    }

    // initial load
    LaunchedEffect(Unit) { fetchNew() }

    // poll every 3s while sheet is visible
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            fetchNew()
        }
    }

    // scroll to bottom whenever messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        input   = ""
        sending = true
        scope.launch {
            runCatching {
                if (isMerchantMode) merchantsClient.replyMessage(mid, uid, merchantToken!!, text)
                else                merchantsClient.sendMessage(mid, uid, text)
            }
            fetchNew()
            sending = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = Color(0xFF0D0D0D),
        dragHandle       = {
            Box(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isMerchantMode) "Khách #$uid" else merchantName,
                    color      = Color.White,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            if (messages.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Bắt đầu cuộc trò chuyện…", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding      = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages, key = { it.id }) { msg -> MsgBubble(msg, isMerchantMode) }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value         = input,
                    onValueChange = { input = it },
                    modifier      = Modifier.weight(1f),
                    placeholder   = { Text("Nhắn tin…", color = Color.Gray, fontSize = 14.sp) },
                    colors        = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        cursorColor             = Color.White
                    ),
                    shape         = RoundedCornerShape(20.dp),
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() })
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick  = { sendMessage() },
                    enabled  = input.isNotBlank() && !sending
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            color     = Color.White,
                            modifier  = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Gửi",
                            tint = if (input.isNotBlank()) Color.White else Color.Gray
                        )
                    }
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
        }
    }
}

@Composable
private fun MsgBubble(msg: ChatMessage, isMerchantMode: Boolean) {
    // merchant mode: merchant's own messages (fromMerchant=true) are on the right
    // customer mode: customer's own messages (fromMerchant=false) are on the right
    val isSent = if (isMerchantMode) msg.fromMerchant else !msg.fromMerchant
    val bgColor   = if (isSent) Color(0xFF1A3A5C) else Color(0xFF222222)
    val textColor = if (isSent) Color(0xFFB3D4FF) else Color(0xFFE0E0E0)
    val align     = if (isSent) Alignment.End else Alignment.Start

    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart    = if (isSent) 18.dp else 4.dp,
                topEnd      = if (isSent) 4.dp  else 18.dp,
                bottomStart = 18.dp,
                bottomEnd   = 18.dp
            ),
            color = bgColor
        ) {
            Text(
                text     = msg.body,
                color    = textColor,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}
