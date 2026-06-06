package app.saving.wire.protocol

import app.saving.wire.util.vndFormatted

/**
 * A monetary amount on the wire: an unsigned 8-byte big-endian integer of minor
 * units (VND has none, so this is whole đồng). Owning the byte (de)serialization
 * here keeps amounts from being mixed up with the other raw `Long`s flying around
 * the protocol (account ids, timestamps, request ids).
 *
 *   amount  ──toBytes()──▶  8 bytes (BE)
 *   8 bytes ──fromBytes()─▶  Money   (validated: rejects negative / truncated)
 *
 * Construction always validates, so a `Money` in hand is by definition a valid,
 * non-negative amount.
 */
@JvmInline
value class Money(val raw: Long) {

    init {
        require(raw >= 0) { "Money must be non-negative, got $raw" }
    }

    /** 8-byte big-endian encoding, matching the Wire protocol's int64 amount field. */
    fun toBytes(): ByteArray = byteArrayOf(
        (raw shr 56).toByte(), (raw shr 48).toByte(), (raw shr 40).toByte(), (raw shr 32).toByte(),
        (raw shr 24).toByte(), (raw shr 16).toByte(), (raw shr 8).toByte(), raw.toByte()
    )

    operator fun plus(other: Money): Money  = Money(raw + other.raw)
    operator fun compareTo(other: Money): Int = raw.compareTo(other.raw)

    /** "30.000" — grouped đồng, no symbol (caller appends ₫ as needed). */
    fun vndFormatted(): String = raw.vndFormatted()

    companion object {
        val ZERO = Money(0)

        /**
         * Decode an amount from [bytes] at [offset] (8-byte big-endian) into a
         * validated [Money]. Throws if the buffer is too short or the value is
         * negative — i.e. the output is always a valid amount.
         */
        fun fromBytes(bytes: ByteArray, offset: Int = 0): Money {
            require(offset + 8 <= bytes.size) { "amount truncated: need 8 bytes at $offset, have ${bytes.size}" }
            return Money(bytes.getLong(offset))
        }
    }
}
