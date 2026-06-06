package app.saving.wire.data

import app.saving.wire.protocol.*
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
    data class TransferIn(val transfer: TransferEvent)                                 : SavingEvent()
    data class RecoveryRequested(val accountId: Long)                                  : SavingEvent()
    data class RecoveryGranted(val accountId: Long)                                    : SavingEvent()
    data class GuardianAdded(val accountId: Long)                                      : SavingEvent()
    data class IntentPaid(val requestId: Long, val customerId: Long, val amount: Long) : SavingEvent()
    data class MsgIn(val fromId: Long, val text: String)                               : SavingEvent()
}

class SavingClient(
    host: String = "wire.nivic.dev",
    port: Int    = 7474
) {
    private val conn  = WireConnection(host, port, SECRET.toByteArray())
    private var token: ByteArray? = null

    // Cached credentials so we can transparently re-login after a dropped socket
    // (idle-kill / Doze / broken pipe) or an expired server session (BAD_TOKEN).
    private var authId:       Long?   = null
    private var authPassword: String? = null

    private val _connected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> get() = _connected

    var onEvent: ((SavingEvent) -> Unit)? = null

    suspend fun connect() {
        conn.onEvent = { frame -> handlePush(frame) }
        conn.connect()
        _connected.value = true
    }

    fun disconnect() {
        conn.disconnect()
        token = null
        _connected.value = false
    }

    /** Re-establish a working, logged-in session on a fresh socket. */
    private suspend fun reconnectAndRelogin() {
        conn.reconnect()
        _connected.value = true
        val id = authId; val pw = authPassword
        if (id != null && pw != null) {
            val ack = conn.send(WireFrame.login(id, sha256(pw), conn.nextSeq())).parseLoginAck()
            if (ack.code != WireCode.OK) throw WireError(ack.code)
            token = ack.token
        }
    }

    /** Run [block]; if the transport died or the server session expired, rebuild
     *  the connection + session once and retry. [block] re-reads [token] each call
     *  so the retry signs with the fresh token.
     *
     *  Auto-retry happens when it cannot double-execute: either the frame never
     *  reached the server ([ConnectionDead.delivered] == false), or the op is
     *  [idempotent] (read-only), or the server rejected the token before acting
     *  (BAD_TOKEN — no state changed). A money op whose ack was lost mid-flight
     *  is NOT retried silently; it surfaces so the caller can reconcile. */
    private suspend fun <T> resilient(idempotent: Boolean = false, block: suspend () -> T): T =
        try {
            block()
        } catch (e: ConnectionDead) {
            if (!e.delivered || idempotent) {
                reconnectAndRelogin(); block()
            } else throw e
        } catch (e: WireError) {
            if (e.code == WireCode.ERR_BAD_TOKEN && authId != null) {
                reconnectAndRelogin(); block()
            } else throw e
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
        authId = id; authPassword = password
    }

    suspend fun logout() {
        val t = requireToken()
        runCatching { conn.send(WireFrame.logout(t, conn.nextSeq())) }
        token = null
        authId = null; authPassword = null
    }

    // ─── Balance ─────────────────────────────────────────────────────────────

    suspend fun balance(): Long = resilient(idempotent = true) {
        val ack = conn.send(WireFrame.getBalance(requireToken(), conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
        ack.data.getLong(0)
    }

    // ─── Transfer ────────────────────────────────────────────────────────────

    suspend fun transfer(toId: Long, amount: Money) = resilient {
        val ack = conn.send(WireFrame.transfer(requireToken(), toId, amount, conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    suspend fun payMerchant(mid: Long, amount: Money) = transfer(mid, amount)

    suspend fun sendMsg(toId: Long, text: String) = resilient {
        val ack = conn.send(WireFrame.sendMsg(requireToken(), toId, text, conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    // ─── Payment Intent ───────────────────────────────────────────────────────

    suspend fun totpCharge(customerId: Long, totpCode: Int, amount: Money) = resilient {
        val ack = conn.send(
            WireFrame.totpCharge(requireToken(), customerId, totpCode, amount, conn.nextSeq())
        ).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    suspend fun enrollTotp(customerId: Long, secretB32: String) = resilient {
        val secret = app.saving.wire.ui.base32Decode(secretB32)
        val ack = conn.send(
            WireFrame.enrollTotp(requireToken(), customerId, secret, conn.nextSeq())
        ).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    suspend fun registerMerchant(name: String) = resilient {
        val ack = conn.send(
            WireFrame.registerMerchant(requireToken(), name, conn.nextSeq())
        ).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    data class IntentResult(val mid: Long, val requestId: Long, val amount: Long)
    data class ConfirmIntentResult(val txnId: Long, val afterBalance: Long)

    suspend fun createIntent(amount: Money, orderId: Long = System.currentTimeMillis()): IntentResult = resilient {
        val requestId = System.currentTimeMillis()
        val ack = conn.send(
            WireFrame.createIntent(requireToken(), requestId, orderId, amount, conn.nextSeq())
        ).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
        // ACK extra: [status 1B][mid 4B][request_id 8B][amount 8B] = 21 bytes
        val d = ack.data
        val mid = d.getInt(1).toLong() and 0xFFFFFFFFL
        val rid = d.getLong(5)
        val amt = d.getLong(13)
        IntentResult(mid, rid, amt)
    }

    suspend fun payIntent(merchantId: Long, requestId: Long, totpCode: Int) = resilient {
        val ack = conn.send(
            WireFrame.payIntent(requireToken(), merchantId, requestId, totpCode, conn.nextSeq())
        ).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    suspend fun confirmIntent(merchantId: Long, requestId: Long): ConfirmIntentResult = resilient {
        val ack = conn.send(
            WireFrame.confirmIntent(requireToken(), merchantId, requestId, conn.nextSeq())
        ).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
        val d = ack.data
        // ACK extra: [txn_id 8B][after_balance 8B]
        ConfirmIntentResult(
            txnId        = if (d.size >= 8)  d.getLong(0) else 0L,
            afterBalance = if (d.size >= 16) d.getLong(8) else 0L
        )
    }

    suspend fun qrPay(merchantId: Long, amount: Money, ts: Long,
                      ref: String, sig: ByteArray, acsUrl: String): ConfirmIntentResult = resilient {
        val ack = conn.send(
            WireFrame.qrPay(requireToken(), merchantId, amount, ts, ref, sig, acsUrl, conn.nextSeq())
        ).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
        val d = ack.data
        ConfirmIntentResult(
            txnId        = if (d.size >= 8)  d.getLong(0) else 0L,
            afterBalance = if (d.size >= 16) d.getLong(8) else 0L
        )
    }

    // ─── History ─────────────────────────────────────────────────────────────

    suspend fun history(): List<Transaction> = resilient(idempotent = true) {
        val ack = conn.send(WireFrame.getHistory(requireToken(), conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
        val data = ack.data
        if (data.isEmpty()) return@resilient emptyList()
        val count = data[0].toInt() and 0xFF
        return@resilient (0 until count).mapNotNull { i ->
            val base = 1 + i * 21  // 1B direction + 4B counterpart + 8B amount + 8B after_balance
            if (base + 21 > data.size) return@mapNotNull null
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

    suspend fun addGuardian(id: Long) = resilient {
        val ack = conn.send(WireFrame.addGuardian(requireToken(), id, conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    // ─── Recovery ────────────────────────────────────────────────────────────

    suspend fun requestRecovery(id: Long) {
        val ack = conn.send(WireFrame.recoveryReq(id, conn.nextSeq())).parseAck()
        if (ack.code != WireCode.OK) throw WireError(ack.code)
    }

    suspend fun approveRecovery(targetId: Long) = resilient {
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
            WireCmd.EVT_INTENT_PAID ->
                if (frame.body.size >= 20) SavingEvent.IntentPaid(
                    requestId  = frame.body.getLong(0),
                    customerId = frame.body.getInt(8).toLong() and 0xFFFFFFFFL,
                    amount     = frame.body.getLong(12)
                ) else return
            WireCmd.EVT_MSG_IN -> {
                val b = frame.parseEvtMsgIn()
                SavingEvent.MsgIn(fromId = b.fromId, text = b.text)
            }
            else -> return
        }
        onEvent?.invoke(event)
    }

    private fun requireToken() = token ?: throw WireError(WireCode.ERR_BAD_TOKEN)

    companion object {
        private const val SECRET = "saving_wire_secret_changeme"
    }
}
