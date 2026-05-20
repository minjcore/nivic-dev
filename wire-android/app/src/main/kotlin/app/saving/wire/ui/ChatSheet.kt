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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saving.wire.viewmodel.ChatMsg
import app.saving.wire.viewmodel.ChatViewModel
import app.saving.wire.viewmodel.WireViewModel

private val SUGGESTIONS = listOf("balance", "history", "help", "ping", "clear")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(vm: WireViewModel, onDismiss: () -> Unit) {
    val chatVm: ChatViewModel = viewModel()
    val messages by chatVm.messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = Color(0xFF0D0D0D),
        dragHandle       = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Terminal",
                    color      = Color.White,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            // ── Message list ──────────────────────────────────────────────
            LazyColumn(
                state            = listState,
                modifier         = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding   = PaddingValues(vertical = 8.dp)
            ) {
                items(messages) { msg -> ChatBubble(msg) }
            }

            // ── Suggestion chips ──────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SUGGESTIONS.forEach { sug ->
                    SuggestionChip(
                        onClick = { input = sug },
                        label   = {
                            Text(
                                sug,
                                fontSize   = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color      = Color.White.copy(alpha = 0.7f)
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color.White.copy(alpha = 0.08f)
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled      = true,
                            borderColor  = Color.White.copy(alpha = 0.15f),
                            borderWidth  = 0.5.dp
                        )
                    )
                }
            }

            // ── Input row ─────────────────────────────────────────────────
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
                        Text(
                            "> nhập lệnh...",
                            fontSize   = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color      = Color.Gray
                        )
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
                    textStyle     = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 13.sp
                    ),
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        chatVm.send(input, vm.client)
                        input = ""
                    })
                )
                IconButton(
                    onClick  = { chatVm.send(input, vm.client); input = "" },
                    enabled  = input.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Gửi",
                        tint = if (input.isNotBlank()) Color.White else Color.Gray
                    )
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg) {
    val bgColor   = if (msg.isSent) Color(0xFF1A3A5C) else Color(0xFF1A1A1A)
    val textColor = if (msg.isSent) Color(0xFFB3D4FF) else Color(0xFFD0D0D0)
    val align     = if (msg.isSent) Alignment.End else Alignment.Start

    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart    = if (msg.isSent) 14.dp else 4.dp,
                topEnd      = if (msg.isSent) 4.dp  else 14.dp,
                bottomStart = 14.dp,
                bottomEnd   = 14.dp
            ),
            color = bgColor
        ) {
            Text(
                text       = msg.text,
                color      = textColor,
                fontSize   = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
                modifier   = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
