package app.saving.wire.util

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.nio.ByteBuffer

/**
 * Device-local merchant key + order store.
 * No server needed — privkey lives in SharedPreferences, orders in local JSON.
 *
 * ed25519 is available via Java 15+ JCA; works on Android 13 (API 33)+.
 * On older devices the keygen call will throw and the caller falls back to
 * MODE_REST with server-side signing.
 */
object LocalMerchant {

    // ── Key management ──────────────────────────────────────────────────────

    fun hasLocalKey(prefs: SharedPreferences): Boolean =
        prefs.getString("local_merchant_privkey", null) != null

    /** Generate ed25519 keypair, store privkey in prefs, return base64 pubkey. */
    fun generateKey(prefs: SharedPreferences): String {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp  = kpg.generateKeyPair()
        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val pubB64  = Base64.encodeToString(kp.public.encoded,  Base64.NO_WRAP)
        prefs.edit()
            .putString("local_merchant_privkey", privB64)
            .putString("local_merchant_pubkey",  pubB64)
            .apply()
        return pubB64
    }

    fun pubKeyB64(prefs: SharedPreferences): String? =
        prefs.getString("local_merchant_pubkey", null)

    // ── Payment request signing ─────────────────────────────────────────────

    /**
     * Build a signed PaymentRequest JSON and base64url-encode it for QR embedding.
     * Format mirrors the Go server: mid(4 BE) || amount(8 BE) || ts(8 BE) || orderId(utf8)
     */
    fun buildSignedPR(prefs: SharedPreferences, mid: Long, orderId: String, amount: Long): String {
        val privB64 = prefs.getString("local_merchant_privkey", null)
            ?: error("No local merchant key — call generateKey() first")
        val privBytes = Base64.decode(privB64, Base64.NO_WRAP)
        val privKey   = KeyFactory.getInstance("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(privBytes))

        val ts  = System.currentTimeMillis()
        val msg = signMsg(mid, amount, ts, orderId)

        val sig = Signature.getInstance("Ed25519").also {
            it.initSign(privKey)
            it.update(msg)
        }.sign()

        val pr = JSONObject().apply {
            put("mid",      mid)
            put("order_id", orderId)
            put("amount",   amount)
            put("ts",       ts)
            put("sig",      Base64.encodeToString(sig, Base64.NO_WRAP))
        }

        return Base64.encodeToString(pr.toString().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')
    }

    private fun signMsg(mid: Long, amount: Long, ts: Long, orderId: String): ByteArray {
        val idBytes = orderId.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + 8 + 8 + idBytes.size).apply {
            putInt(mid.toInt())   // 4 bytes, BE
            putLong(amount)       // 8 bytes, BE
            putLong(ts)           // 8 bytes, BE
            put(idBytes)
        }.array()
    }

    // ── Local order store ───────────────────────────────────────────────────

    data class LocalOrder(
        val id:        String,
        val mid:       Long,
        val amount:    Long,
        val note:      String,
        val status:    String,   // "pending" | "paid"
        val createdAt: Long,
    )

    private const val PREF_ORDERS = "local_merchant_orders"

    fun saveOrder(prefs: SharedPreferences, order: LocalOrder) {
        val arr = loadRaw(prefs)
        arr.put(JSONObject().apply {
            put("id",         order.id)
            put("mid",        order.mid)
            put("amount",     order.amount)
            put("note",       order.note)
            put("status",     order.status)
            put("created_at", order.createdAt)
        })
        prefs.edit().putString(PREF_ORDERS, arr.toString()).apply()
    }

    fun markPaid(prefs: SharedPreferences, orderId: String) {
        val arr = loadRaw(prefs)
        val updated = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == orderId) o.put("status", "paid")
            updated.put(o)
        }
        prefs.edit().putString(PREF_ORDERS, updated.toString()).apply()
    }

    fun listOrders(prefs: SharedPreferences): List<LocalOrder> {
        val arr = loadRaw(prefs)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LocalOrder(
                id        = o.getString("id"),
                mid       = o.getLong("mid"),
                amount    = o.getLong("amount"),
                note      = o.optString("note", ""),
                status    = o.getString("status"),
                createdAt = o.getLong("created_at"),
            )
        }.sortedByDescending { it.createdAt }
    }

    fun stats(prefs: SharedPreferences): Pair<Long, Int> {
        val orders = listOrders(prefs)
        val paid   = orders.filter { it.status == "paid" }
        return paid.sumOf { it.amount } to paid.size
    }

    private fun loadRaw(prefs: SharedPreferences): JSONArray =
        runCatching { JSONArray(prefs.getString(PREF_ORDERS, "[]") ?: "[]") }
            .getOrDefault(JSONArray())
}
