package app.saving.wire.deeplink

import android.content.Intent
import android.net.Uri

/** Parsed customer deeplink: store front or payment intent. */
sealed class SavingDeeplink {
    data class Store(val mid: Long?, val slug: String?) : SavingDeeplink()
    data class PaymentIntent(
        val mid: Long,
        val requestId: Long,
        val amount: Long,
        val orderId: String?,
    ) : SavingDeeplink()
    /** https://host/pay/{order_id} — resolve via Mcs JSON. */
    data class PayOrder(val orderId: String) : SavingDeeplink()
}

object SavingDeeplinkParser {

    fun fromIntent(intent: Intent?): SavingDeeplink? {
        val data = intent?.data ?: return null
        if (intent.action != Intent.ACTION_VIEW) return null
        return fromUri(data)
    }

    fun fromUri(uri: Uri): SavingDeeplink? {
        val raw = uri.toString()
        parsePaymentIntent(uri)?.let { return it }
        parseStore(uri)?.let { return it }
        parsePayOrder(uri)?.let { return it }
        return null
    }

    fun fromQr(raw: String): SavingDeeplink? {
        val uri = Uri.parse(raw)
        parsePaymentIntent(uri)?.let { return it }
        parseStore(uri)?.let { return it }
        return null
    }

    private fun parsePaymentIntent(uri: Uri): SavingDeeplink.PaymentIntent? {
        if (uri.scheme != "saving" || uri.host != "intent") return null
        val mid = uri.getQueryParameter("mid")?.toLongOrNull() ?: return null
        val rid = uri.getQueryParameter("rid")?.toLongOrNull() ?: return null
        val amt = uri.getQueryParameter("amount")?.toLongOrNull() ?: return null
        val oid = uri.getQueryParameter("oid")
        return SavingDeeplink.PaymentIntent(mid, rid, amt, oid)
    }

    private fun parseStore(uri: Uri): SavingDeeplink.Store? {
        return when {
            uri.scheme == "saving" && uri.host == "store" -> {
                val mid = uri.getQueryParameter("mid")?.toLongOrNull()
                val slug = uri.getQueryParameter("slug")
                if (mid == null && slug.isNullOrBlank()) null
                else SavingDeeplink.Store(mid, slug?.takeIf { it.isNotBlank() })
            }
            uri.scheme == "https" && uri.host?.endsWith(".nivic.dev") == true -> {
                val mid = uri.getQueryParameter("mid")?.toLongOrNull()
                val host = uri.host ?: return null
                val slug = host.removeSuffix(".nivic.dev").takeIf {
                    it.isNotEmpty() && it !in RESERVED_SUBDOMAINS
                }
                if (mid == null && slug == null) null
                else SavingDeeplink.Store(mid, slug)
            }
            else -> null
        }
    }

    private fun parsePayOrder(uri: Uri): SavingDeeplink.PayOrder? {
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val path = uri.path ?: return null
        if (!path.startsWith("/pay/")) return null
        val orderId = path.removePrefix("/pay/").trim('/')
        if (orderId.isEmpty()) return null
        return SavingDeeplink.PayOrder(orderId)
    }

    private val RESERVED_SUBDOMAINS = setOf("saving", "www", "api", "wire", "bmap")
}
