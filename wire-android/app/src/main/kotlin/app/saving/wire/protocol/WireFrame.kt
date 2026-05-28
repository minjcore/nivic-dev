package app.saving.wire.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ─── Command codes ─────────────────────────────────────────────────────────

object WireCmd {
    const val PING:              Byte = 0x01
    const val LOGIN:             Byte = 0x02
    const val LOGOUT:            Byte = 0x03
    const val CREATE_ACCOUNT:    Byte = 0x10.toByte()
    const val TRANSFER:          Byte = 0x11.toByte()
    const val GET_BALANCE:       Byte = 0x12.toByte()
    const val ADD_GUARDIAN:      Byte = 0x13.toByte()
    const val RECOVERY_REQ:      Byte = 0x14.toByte()
    const val RECOVERY_APPROVE:  Byte = 0x15.toByte()
    const val GET_HISTORY:       Byte = 0x16.toByte()
    const val CREATE_INTENT:       Byte = 0x20.toByte()
    const val PAY_INTENT:          Byte = 0x21.toByte()
    const val ENROLL_TOTP:         Byte = 0x22.toByte()
    const val REGISTER_MERCHANT:   Byte = 0x23.toByte()
    const val TOTP_CHARGE:         Byte = 0x25.toByte()
    const val CONFIRM_INTENT:      Byte = 0x29.toByte()
    const val QR_PAY:              Byte = 0x2B.toByte()
    const val SEND_MSG:            Byte = 0x31.toByte()

    const val PONG:              Byte = 0x80.toByte()
    const val LOGIN_ACK:         Byte = 0x81.toByte()
    const val ACK:               Byte = 0x82.toByte()

    const val EVT_TRANSFER_IN:   Byte = 0xC0.toByte()
    const val EVT_RECOVERY_REQ:  Byte = 0xC1.toByte()
    const val EVT_RECOVERY_OK:   Byte = 0xC2.toByte()
    const val EVT_GUARDIAN_ADD:  Byte = 0xC3.toByte()
    const val EVT_INTENT_PAID:   Byte = 0xC4.toByte()
    const val EVT_MSG_IN:        Byte = 0xC9.toByte()
}

object WireCode {
    const val OK:                Byte = 0x00
    const val ERR_BAD_FRAME:     Byte = 0x01
    const val ERR_BAD_SIG:       Byte = 0x02
    const val ERR_ID_TAKEN:      Byte = 0x03
    const val ERR_ID_RESERVED:   Byte = 0x04
    const val ERR_NOT_FOUND:     Byte = 0x05
    const val ERR_BAD_PASSWORD:  Byte = 0x06
    const val ERR_BAD_TOKEN:     Byte = 0x07
    const val ERR_LOW_BALANCE:   Byte = 0x08
    const val ERR_GUARDIAN_FULL: Byte = 0x09
    const val ERR_NOT_GUARDIAN:  Byte = 0x0A
    const val ERR_NEED_GUARDIANS:  Byte = 0x0B
    const val ERR_TOTP_INVALID:    Byte = 0x0C
    const val ERR_INTENT_SETTLED:  Byte = 0x0D
    const val ERR_NOT_MERCHANT:    Byte = 0x0E
    const val ERR_SYSTEM_OFFLINE:  Byte = 0x0F
    const val ERR_MAINTENANCE:     Byte = 0x10
    const val ERR_INTERNAL:        Byte = 0xFF.toByte()
}

object AccountID {
    const val VIP_MAX:  Long = 16_777_215L
    const val USER_MIN: Long = 16_777_216L
    const val USER_MAX: Long = 4_294_967_295L
    fun isValid(id: Long) = id in USER_MIN..USER_MAX
}

// ─── Frame layout: len(4) | type(1) | seq(4) | body | sig(32) ─────────────

private const val OVERHEAD  = 41
private const val SIG_SIZE  = 32

data class WireFrame(
    val type: Byte,
    val seq:  Int,
    val body: ByteArray = ByteArray(0)
) {
    companion object {}

    override fun equals(other: Any?): Boolean =
        other is WireFrame && type == other.type && seq == other.seq && body.contentEquals(other.body)
    override fun hashCode(): Int = 31 * (31 * type.hashCode() + seq) + body.contentHashCode()
}

// ─── Encode ────────────────────────────────────────────────────────────────

fun WireFrame.encode(secret: ByteArray): ByteArray {
    val totalLen = OVERHEAD + body.size
    val buf = ByteArray(totalLen - SIG_SIZE)
    buf.putInt(0, totalLen)
    buf[4] = type
    buf.putInt(5, seq)
    body.copyInto(buf, destinationOffset = 9)
    return buf + hmacSha256(buf, secret)
}

// ─── Decode ────────────────────────────────────────────────────────────────

fun WireFrame.Companion.decode(raw: ByteArray, secret: ByteArray): WireFrame {
    require(raw.size >= OVERHEAD) { "frame too short" }
    val totalLen = raw.getInt(0)
    require(raw.size == totalLen) { "len mismatch" }
    val covered     = raw.copyOfRange(0, totalLen - SIG_SIZE)
    val receivedSig = raw.copyOfRange(totalLen - SIG_SIZE, totalLen)
    require(receivedSig.contentEquals(hmacSha256(covered, secret))) { "bad sig" }
    val type = raw[4]
    val seq  = raw.getInt(5)
    val body = raw.copyOfRange(9, totalLen - SIG_SIZE)
    return WireFrame(type, seq, body)
}

// ─── Body builders ─────────────────────────────────────────────────────────

fun WireFrame.Companion.login(id: Long, hash: ByteArray, seq: Int) =
    WireFrame(WireCmd.LOGIN, seq, id.toUInt32Bytes() + hash)

fun WireFrame.Companion.createAccount(id: Long, hash: ByteArray, seq: Int) =
    WireFrame(WireCmd.CREATE_ACCOUNT, seq, id.toUInt32Bytes() + hash)

fun WireFrame.Companion.transfer(token: ByteArray, toId: Long, amount: Long, seq: Int) =
    WireFrame(WireCmd.TRANSFER, seq, token + toId.toUInt32Bytes() + amount.toInt64Bytes())

fun WireFrame.Companion.getBalance(token: ByteArray, seq: Int) =
    WireFrame(WireCmd.GET_BALANCE, seq, token)

fun WireFrame.Companion.addGuardian(token: ByteArray, guardianId: Long, seq: Int) =
    WireFrame(WireCmd.ADD_GUARDIAN, seq, token + guardianId.toUInt32Bytes())

fun WireFrame.Companion.recoveryReq(id: Long, seq: Int) =
    WireFrame(WireCmd.RECOVERY_REQ, seq, id.toUInt32Bytes())

fun WireFrame.Companion.recoveryApprove(token: ByteArray, targetId: Long, seq: Int) =
    WireFrame(WireCmd.RECOVERY_APPROVE, seq, token + targetId.toUInt32Bytes())

fun WireFrame.Companion.getHistory(token: ByteArray, seq: Int) =
    WireFrame(WireCmd.GET_HISTORY, seq, token)

fun WireFrame.Companion.logout(token: ByteArray, seq: Int) =
    WireFrame(WireCmd.LOGOUT, seq, token)

fun WireFrame.Companion.ping(seq: Int) =
    WireFrame(WireCmd.PING, seq)

/* ENROLL_TOTP  body: [merchant_token 32B][customer_id 4B][secret 20B] */
fun WireFrame.Companion.enrollTotp(token: ByteArray, customerId: Long, secret: ByteArray, seq: Int) =
    WireFrame(WireCmd.ENROLL_TOTP, seq, token + customerId.toUInt32Bytes() + secret)

/* TOTP_CHARGE  body: [merchant_token 32B][customer_uid 4B][totp_code 4B][amount 8B] */
fun WireFrame.Companion.totpCharge(token: ByteArray, customerId: Long, totpCode: Int, amount: Long, seq: Int) =
    WireFrame(WireCmd.TOTP_CHARGE, seq,
        token + customerId.toUInt32Bytes() + totpCode.toLong().toUInt32Bytes() + amount.toInt64Bytes())

/* REGISTER_MERCHANT  body: [token 32B][name N bytes] */
fun WireFrame.Companion.registerMerchant(token: ByteArray, name: String, seq: Int) =
    WireFrame(WireCmd.REGISTER_MERCHANT, seq, token + name.toByteArray(Charsets.UTF_8))

/* CREATE_INTENT  body: [merchant_token 32B][request_id 8B][order_id 8B][amount 8B] */
fun WireFrame.Companion.createIntent(token: ByteArray, requestId: Long, orderId: Long,
                                      amount: Long, seq: Int) =
    WireFrame(WireCmd.CREATE_INTENT, seq,
        token + requestId.toInt64Bytes() + orderId.toInt64Bytes() + amount.toInt64Bytes())

/* PAY_INTENT  body: [token 32B][merchant_id 4B][request_id 8B][totp_code 4B] */
fun WireFrame.Companion.payIntent(token: ByteArray, merchantId: Long, requestId: Long,
                                   totpCode: Int, seq: Int) =
    WireFrame(WireCmd.PAY_INTENT, seq,
        token + merchantId.toUInt32Bytes() + requestId.toInt64Bytes() + totpCode.toLong().toUInt32Bytes())

/* CONFIRM_INTENT body: [customer_token 32B][merchant_id 4B][request_id 8B] */
fun WireFrame.Companion.confirmIntent(token: ByteArray, merchantId: Long, requestId: Long, seq: Int) =
    WireFrame(WireCmd.CONFIRM_INTENT, seq,
        token + merchantId.toUInt32Bytes() + requestId.toInt64Bytes())

/* QR_PAY body: [customer_token 32B][mid 4B][amount 8B][ts 8B][sig 64B][ref_len 1B][ref N][acs_url rest] */
fun WireFrame.Companion.qrPay(token: ByteArray, merchantId: Long, amount: Long,
                               ts: Long, ref: String, sig: ByteArray, acsUrl: String, seq: Int): WireFrame {
    val refBytes = ref.toByteArray(Charsets.UTF_8)
    return WireFrame(WireCmd.QR_PAY, seq,
        token + merchantId.toUInt32Bytes() + amount.toInt64Bytes() +
        ts.toInt64Bytes() + sig +
        byteArrayOf(refBytes.size.toByte()) + refBytes +
        acsUrl.toByteArray(Charsets.UTF_8))
}

// ─── Body parsers ──────────────────────────────────────────────────────────

data class LoginAckBody(val code: Byte, val token: ByteArray = ByteArray(0))
data class AckBody(val code: Byte, val data: ByteArray = ByteArray(0))
data class EvtTransferInBody(val fromId: Long, val amount: Long, val balance: Long)

fun WireFrame.parseLoginAck(): LoginAckBody {
    require(body.isNotEmpty())
    val token = if (body[0] == WireCode.OK && body.size > 1) body.copyOfRange(1, body.size) else ByteArray(0)
    return LoginAckBody(body[0], token)
}

fun WireFrame.parseAck(): AckBody {
    if (body.isEmpty()) return AckBody(WireCode.ERR_INTERNAL)
    val data = if (body.size > 1) body.copyOfRange(1, body.size) else ByteArray(0)
    return AckBody(body[0], data)
}

fun WireFrame.parseEvtTransferIn(): EvtTransferInBody {
    require(body.size >= 20)
    return EvtTransferInBody(
        fromId  = body.getInt(0).toLong() and 0xFFFFFFFFL,
        amount  = body.getLong(4),
        balance = body.getLong(12)
    )
}

/* SEND_MSG body: [token 32B][to_id 4B][text N bytes UTF-8] */
fun WireFrame.Companion.sendMsg(token: ByteArray, toId: Long, text: String, seq: Int) =
    WireFrame(WireCmd.SEND_MSG, seq, token + toId.toUInt32Bytes() + text.toByteArray(Charsets.UTF_8))

data class EvtMsgInBody(val fromId: Long, val text: String)

fun WireFrame.parseEvtMsgIn(): EvtMsgInBody {
    require(body.size >= 5)
    val fromId = body.getInt(0).toLong() and 0xFFFFFFFFL
    val text   = body.copyOfRange(4, body.size).toString(Charsets.UTF_8)
    return EvtMsgInBody(fromId, text)
}

// ─── Error ─────────────────────────────────────────────────────────────────

class WireError(val code: Byte) : Exception("Wire error 0x${code.toInt().and(0xFF).toString(16)}")

// ─── Crypto / byte helpers ──────────────────────────────────────────────────

fun sha256(input: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    copyInto(out); other.copyInto(out, size); return out
}

private fun Long.toUInt32Bytes() = byteArrayOf(
    (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte()
)
private fun Long.toInt64Bytes() = byteArrayOf(
    (this shr 56).toByte(), (this shr 48).toByte(), (this shr 40).toByte(), (this shr 32).toByte(),
    (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte()
)

fun ByteArray.getInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
    ((this[offset+1].toInt() and 0xFF) shl 16) or
    ((this[offset+2].toInt() and 0xFF) shl 8) or
    (this[offset+3].toInt() and 0xFF)

fun ByteArray.getLong(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 56) or
    ((this[offset+1].toLong() and 0xFF) shl 48) or
    ((this[offset+2].toLong() and 0xFF) shl 40) or
    ((this[offset+3].toLong() and 0xFF) shl 32) or
    ((this[offset+4].toLong() and 0xFF) shl 24) or
    ((this[offset+5].toLong() and 0xFF) shl 16) or
    ((this[offset+6].toLong() and 0xFF) shl 8) or
    (this[offset+7].toLong() and 0xFF)

private fun ByteArray.putInt(offset: Int, v: Int) {
    this[offset]   = (v shr 24).toByte()
    this[offset+1] = (v shr 16).toByte()
    this[offset+2] = (v shr 8).toByte()
    this[offset+3] = v.toByte()
}
