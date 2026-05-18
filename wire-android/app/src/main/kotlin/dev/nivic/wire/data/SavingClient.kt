package dev.nivic.wire.data

import dev.nivic.wire.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Transaction(
    val direction:     Direction,
    val counterpartId: Long,
    val amount:        Long
) {
    enum class Direction {
        SENT, RECEIVED,          // transfer
        PAYMENT_SENT,            // customer paid merchant
        PAYMENT_RECEIVED,        // merchant received payment
        CASH_IN,                 // deposit
        CASH_OUT                 // withdrawal
    }
}

data class TransferEvent(val fromId: Long, val amount: Long, val balance: Long)

sealed class SavingEvent {
    data class TransferIn(val transfer: TransferEvent)  : SavingEvent()
    data class RecoveryRequested(val accountId: Long)   : SavingEvent()
    data class RecoveryGranted(val accountId: Long)     : SavingEvent()
    data class GuardianAdded(val accountId: Long)       : SavingEvent()
}

class SavingClient(
    host: String = "127.0.0.1",
    port: Int    = 7474
) {
    private val conn  = WireConnection(host, port, SECRET.toByteArray())
    private var token: ByteArray? = null

    private val _connected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> get() = _connected

    var onEvent: ((SavingEvent) -> Unit)? = null

    suspend fun connect() {
        conn.connect()
        _connected.value = true
        conn.onEvent = { frame -> handlePush(frame) }
    }

    fun disconnect() {
        conn.disconnect()
        token = null
        _connected.value = false
    }

    // ─── Account ─────────────────────────────────────────────────────────────

    suspend fun createAccount(id: Long, password: String) {
        val ack = conn.send(WireFrame.createAccount(id, sha256(password), conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    suspend fun login(id: Long, password: String) {
        val ack = conn.send(WireFrame.login(id, sha256(password), conn.nextSeq())).parseLoginAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
        token = ack.token
    }

    suspend fun logout() {
        val t = requireToken()
        conn.send(WireFrame.logout(t, conn.nextSeq()))
        token = null
    }

    // ─── Balance ─────────────────────────────────────────────────────────────

    suspend fun balance(): Long {
        val ack = conn.send(WireFrame.getBalance(requireToken(), conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
        return ack.data.getLong(0)
    }

    // ─── Transfer ────────────────────────────────────────────────────────────

    suspend fun transfer(toId: Long, amount: Long) {
        val ack = conn.send(WireFrame.transfer(requireToken(), toId, amount, conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    suspend fun payMerchant(mid: Long, amount: Long) = transfer(mid, amount)

    // ─── Payment Intent ───────────────────────────────────────────────────────

    suspend fun payIntent(merchantId: Long, requestId: Long, totpCode: Int) {
        val ack = conn.send(
            WireFrame.payIntent(requireToken(), merchantId, requestId, totpCode, conn.nextSeq())
        ).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    // ─── History ─────────────────────────────────────────────────────────────

    suspend fun history(): List<Transaction> {
        val ack = conn.send(WireFrame.getHistory(requireToken(), conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
        val data = ack.data
        if (data.isEmpty()) return emptyList()
        val count = data[0].toInt() and 0xFF
        return (0 until count).mapNotNull { i ->
            val base = 1 + i * 13
            if (base + 13 > data.size) return@mapNotNull null
            Transaction(
                direction     = when (data[base].toInt() and 0xFF) {
                    0 -> Transaction.Direction.SENT
                    1 -> Transaction.Direction.RECEIVED
                    2 -> Transaction.Direction.PAYMENT_SENT
                    3 -> Transaction.Direction.PAYMENT_RECEIVED
                    4 -> Transaction.Direction.CASH_IN
                    5 -> Transaction.Direction.CASH_OUT
                    else -> Transaction.Direction.SENT
                },
                counterpartId = data.getInt(base + 1).toLong() and 0xFFFFFFFFL,
                amount        = data.getLong(base + 5)
            )
        }
    }

    // ─── Guardians ───────────────────────────────────────────────────────────

    suspend fun addGuardian(id: Long) {
        val ack = conn.send(WireFrame.addGuardian(requireToken(), id, conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    // ─── Recovery ────────────────────────────────────────────────────────────

    suspend fun requestRecovery(id: Long) {
        val ack = conn.send(WireFrame.recoveryReq(id, conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    suspend fun approveRecovery(targetId: Long) {
        val ack = conn.send(WireFrame.recoveryApprove(requireToken(), targetId, conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    // ─── Push events ─────────────────────────────────────────────────────────

    private fun handlePush(frame: WireFrame) {
        val event: SavingEvent = when (frame.type) {
            WireCmd.EVT_TRANSFER_IN -> {
                val b = frame.parseEvtTransferIn()
                SavingEvent.TransferIn(TransferEvent(b.fromId, b.amount, b.balance))
            }
            WireCmd.EVT_RECOVERY_REQ ->
                if (frame.body.size >= 4) SavingEvent.RecoveryRequested(frame.body.getInt(0).toLong() and 0xFFFFFFFFL) else return
            WireCmd.EVT_RECOVERY_OK  ->
                if (frame.body.size >= 4) SavingEvent.RecoveryGranted(frame.body.getInt(0).toLong() and 0xFFFFFFFFL) else return
            WireCmd.EVT_GUARDIAN_ADD ->
                if (frame.body.size >= 4) SavingEvent.GuardianAdded(frame.body.getInt(0).toLong() and 0xFFFFFFFFL) else return
            else -> return
        }
        onEvent?.invoke(event)
    }

    private fun requireToken() = token ?: throw WireError(WireCode.ERR_BAD_TOKEN)

    companion object {
        private const val SECRET = "saving_wire_secret_changeme"
    }
}
