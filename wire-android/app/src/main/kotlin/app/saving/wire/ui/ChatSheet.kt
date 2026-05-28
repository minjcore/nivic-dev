package app.saving.wire.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saving.wire.viewmodel.ChatMsg
import app.saving.wire.viewmodel.ChatViewModel
import app.saving.wire.viewmodel.WireViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(vm: WireViewModel, onDismiss: () -> Unit) {
    val chatVm: ChatViewModel = viewModel()
    val messages by chatVm.messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input   by remember { mutableStateOf("") }
    var toIdStr by remember { mutableStateOf("") }
    val toId = toIdStr.toLongOrNull()

    // Wire incoming messages → ChatViewModel
    LaunchedEffect(vm) {
        val prev = vm.client.onEvent
        vm.client.onEvent = { event ->
            prev?.invoke(event)
            if (event is app.saving.wire.data.SavingEvent.MsgIn) {
                chatVm.onIncoming(event.fromId, event.text)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = Color(0xFF0D0D0D),
        dragHandle       = {
            Box(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Nhắn tin", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {

            // Recipient field
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Đến #", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                TextField(
                    value         = toIdStr,
                    onValueChange = { toIdStr = it },
                    modifier      = Modifier.weight(1f),
                    placeholder   = { Text("ID người nhận", color = Color.Gray, fontSize = 14.sp) },
                    colors        = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        cursorColor             = Color.White
                    ),
                    textStyle       = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // Message list
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Wire chat — nhắn thẳng đến ID khác qua server relay.",
                        color = Color.Gray, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 24.dp))
                }
            } else {
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding      = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages) { msg -> ChatBubble(msg) }
                }
            }

            // Input row
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value         = input,
                    onValueChange = { input = it },
                    modifier      = Modifier.weight(1f),
                    placeholder   = {
                        Text("> nhắn tin...", fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, color = Color.Gray)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        cursorColor             = Color.White
                    ),
                    textStyle       = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (toId != null && input.isNotBlank()) {
                            chatVm.send(toId, input, vm.client); input = ""
                        }
                    })
                )
                IconButton(
                    onClick  = {
                        if (toId != null && input.isNotBlank()) {
                            chatVm.send(toId, input, vm.client); input = ""
                        }
                    },
                    enabled = input.isNotBlank() && toId != null
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gửi",
                        tint = if (input.isNotBlank() && toId != null) Color.White else Color.Gray)
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg) {
    val isSent    = msg.fromId == null
    val isSystem  = msg.fromId == 0L
    val bgColor   = when {
        isSystem -> Color(0xFF2A1A1A)
        isSent   -> Color(0xFF1A3A5C)
        else     -> Color(0xFF1A1A1A)
    }
    val textColor = when {
        isSystem -> Color(0xFFFF6B6B)
        isSent   -> Color(0xFFB3D4FF)
        else     -> Color(0xFFD0D0D0)
    }
    val align = if (isSent) Alignment.End else Alignment.Start

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        if (!isSent && !isSystem && msg.fromId != null) {
            Text("#${msg.fromId}", color = Color.Gray,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart    = if (isSent) 14.dp else 4.dp,
                topEnd      = if (isSent) 4.dp else 14.dp,
                bottomStart = 14.dp, bottomEnd = 14.dp
            ),
            color = bgColor
        ) {
            Text(text = msg.text, color = textColor,
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}
