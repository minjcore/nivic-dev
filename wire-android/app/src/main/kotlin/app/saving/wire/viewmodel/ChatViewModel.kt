package app.saving.wire.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.saving.wire.data.SavingClient
import app.saving.wire.data.Transaction
import app.saving.wire.protocol.WireError
import app.saving.wire.util.vndFormatted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMsg(val text: String, val isSent: Boolean)

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMsg>>(
        listOf(ChatMsg("Wire terminal — gõ `help` để xem lệnh.", false))
    )
    val messages: StateFlow<List<ChatMsg>> = _messages

    fun send(input: String, client: SavingClient) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        push(trimmed, isSent = true)
        viewModelScope.launch {
            val tokens = trimmed.split(Regex("\\s+"))
            runCatching {
                when (tokens[0].lowercase()) {
                    "help", "?" -> reply(HELP)

                    "balance", "bal", "số dư" -> {
                        val b = client.balance()
                        reply("Số dư: ${b.vndFormatted()}")
                    }

                    "send", "chuyển", "transfer" -> {
                        if (tokens.size < 3) { reply("Cú pháp: send <id> <số tiền>"); return@launch }
                        val toId   = tokens[1].toLongOrNull() ?: run { reply("ID không hợp lệ"); return@launch }
                        val amount = parseAmount(tokens[2])  ?: run { reply("Số tiền không hợp lệ"); return@launch }
                        client.transfer(toId, amount)
                        reply("✓ Chuyển ${amount.vndFormatted()} → #$toId thành công")
                    }

                    "history", "ls", "lịch sử" -> {
                        val txns = client.history()
                        if (txns.isEmpty()) { reply("Chưa có giao dịch nào."); return@launch }
                        val n = if (tokens.size > 1) tokens[1].toIntOrNull() ?: 10 else 10
                        val sb = StringBuilder()
                        txns.takeLast(n).forEachIndexed { i, tx ->
                            val arrow = when (tx.direction) {
                                Transaction.Direction.SENT             -> "↑ Chuyển → #${tx.counterpartId}"
                                Transaction.Direction.RECEIVED         -> "↓ Nhận ← #${tx.counterpartId}"
                                Transaction.Direction.PAYMENT_SENT     -> "↑ Thanh toán → #${tx.counterpartId}"
                                Transaction.Direction.PAYMENT_RECEIVED -> "↓ Thu tiền ← #${tx.counterpartId}"
                                Transaction.Direction.CASH_IN          -> "↓ Nạp tiền"
                                Transaction.Direction.CASH_OUT         -> "↑ Rút tiền"
                            }
                            sb.append("${i + 1}. $arrow  ${tx.amount.vndFormatted()}\n")
                        }
                        reply(sb.trimEnd().toString())
                    }

                    "ping" -> reply("pong — kết nối: OK")

                    "clear", "cls" -> _messages.value = emptyList()

                    else -> reply("Lệnh không nhận ra. Gõ `help`.")
                }
            }.onFailure { e ->
                val msg = if (e is WireError) "lỗi 0x${(e.code.toInt() and 0xFF).toString(16)}"
                          else (e.message ?: "lỗi không xác định")
                reply("⚠ $msg")
            }
        }
    }

    private fun push(text: String, isSent: Boolean) =
        _messages.update { it + ChatMsg(text, isSent) }

    private fun reply(text: String) = push(text, isSent = false)

    private fun parseAmount(s: String): Long? {
        val clean = s.replace(".", "").replace(",", "")
        return clean.toLongOrNull()?.let { if (it > 0) it else null }
    }

    companion object {
        private val HELP = """
Lệnh khả dụng:
  balance          — xem số dư
  send <id> <số>   — chuyển tiền
  history [n]      — lịch sử giao dịch (mặc định 10)
  ping             — kiểm tra kết nối
  clear            — xoá màn hình
        """.trimIndent()
    }
}
