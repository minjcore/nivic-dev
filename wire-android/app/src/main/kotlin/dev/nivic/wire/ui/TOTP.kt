package dev.nivic.wire.ui

import android.content.SharedPreferences
import org.json.JSONObject
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// RFC 6238 TOTP using HMAC-SHA256.
// Token = base32(HMAC-SHA256(secret, counter)[0:20]) — 32 uppercase chars.

// ─── Base32 ───────────────────────────────────────────────────────────────────

private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

fun base32Decode(input: String): ByteArray {
    val out = mutableListOf<Byte>()
    var buf = 0; var bits = 0
    for (c in input.uppercase()) {
        val idx = B32.indexOf(c)
        if (idx < 0) continue
        buf = (buf shl 5) or idx; bits += 5
        if (bits >= 8) { bits -= 8; out.add(((buf shr bits) and 0xff).toByte()) }
    }
    return out.toByteArray()
}

fun base32Encode(data: ByteArray): String {
    val sb = StringBuilder()
    var buf = 0; var bits = 0
    for (b in data) {
        buf = (buf shl 8) or (b.toInt() and 0xff); bits += 8
        while (bits >= 5) { bits -= 5; sb.append(B32[(buf shr bits) and 0x1f]) }
    }
    if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 0x1f])
    return sb.toString()
}

// ─── TOTP ─────────────────────────────────────────────────────────────────────

object TOTP {
    private const val STEP = 30L

    fun generate(secret: ByteArray, timeMs: Long = System.currentTimeMillis()): String {
        val counter = timeMs / 1000 / STEP
        val msg = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val hash = mac.doFinal(msg)
        return base32Encode(hash.copyOf(20))   // 20 bytes → 32 base32 chars
    }

    // Allow ±1 window for clock drift.
    fun verify(secret: ByteArray, token: String): Boolean {
        val now = System.currentTimeMillis()
        val step = STEP * 1000
        for (delta in -1L..1L) {
            if (generate(secret, now + delta * step) == token.uppercase()) return true
        }
        return false
    }

    /* RFC 6238 dynamic truncation → 6-digit Int, matches C server totp_verify */
    fun generateCode(secret: ByteArray, timeMs: Long = System.currentTimeMillis()): Int {
        val counter = timeMs / 1000 / STEP
        val msg = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val hash = mac.doFinal(msg)
        val offset = hash.last().toInt() and 0x0f
        val code = ((hash[offset].toInt() and 0x7f) shl 24) or
                   ((hash[offset + 1].toInt() and 0xff) shl 16) or
                   ((hash[offset + 2].toInt() and 0xff) shl 8) or
                    (hash[offset + 3].toInt() and 0xff)
        return code % 1_000_000
    }

    fun secondsRemaining(): Int {
        val elapsed = (System.currentTimeMillis() / 1000 % STEP).toInt()
        return STEP.toInt() - elapsed
    }
}

// ─── Enrolled customer store ──────────────────────────────────────────────────
// Stored as JSON in SharedPreferences: key = "totp_customers"
// Value: {"uid_12345678": "BASE32SECRET", ...}

object TOTPStore {
    private const val PREF_KEY = "totp_customers"

    fun save(prefs: SharedPreferences, uid: Long, secretB32: String) {
        val existing = load(prefs)
        existing["uid_$uid"] = secretB32
        val json = JSONObject().apply { existing.forEach { (k, v) -> put(k, v) } }
        prefs.edit().putString(PREF_KEY, json.toString()).apply()
    }

    fun getSecret(prefs: SharedPreferences, uid: Long): ByteArray? {
        val map = load(prefs)
        val b32 = map["uid_$uid"] ?: return null
        return base32Decode(b32)
    }

    fun listEnrolled(prefs: SharedPreferences): List<Long> {
        return load(prefs).keys.mapNotNull { it.removePrefix("uid_").toLongOrNull() }
    }

    // Device-owned secret — auto-generated on first call, persisted in prefs
    fun getOrCreateOwnSecretB32(prefs: SharedPreferences): String {
        prefs.getString("own_totp_secret", null)?.let { return it }
        val secret = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        val b32 = base32Encode(secret)
        prefs.edit().putString("own_totp_secret", b32).apply()
        return b32
    }

    private fun load(prefs: SharedPreferences): MutableMap<String, String> {
        val raw = prefs.getString(PREF_KEY, null) ?: return mutableMapOf()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, String>()
        json.keys().forEach { map[it] = json.getString(it) }
        return map
    }
}
