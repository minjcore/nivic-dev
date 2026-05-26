package app.saving.wire.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MerchantInfo(val mid: Long, val name: String, val address: String = "")
data class MerchantStats(val totalEarned: Long, val orderCount: Int)
data class LoyaltyBalance(val uid: Long, val mid: Long, val points: Long, val valueVnd: Long)
data class LoyaltyMember(val uid: Long, val points: Long)
data class UserLoyaltyEntry(val mid: Long, val merchantName: String, val points: Long)

data class MerchantOrder(
    val id: String,
    val mid: Long,
    val amount: Long,
    val note: String,
    val status: String,  // "pending" | "paid" | "expired"
    val createdAt: Long,
)

data class CreateOrderResult(val orderID: String, val pr: String, val qrURL: String)

/** Mcs `/pay/{order_id}/wire` — open Wire mini-app with intent. */
data class OrderWireDeeplink(
    val orderId: String,
    val mid: Long,
    val amount: Long,
    val requestId: Long,
    val intentUrl: String,
)

data class ChatMessage(
    val id: Long,
    val fromMerchant: Boolean,
    val body: String,
    val createdAt: Long
)

data class MenuItem(
    val id:          Long,
    val mid:         Long,
    val name:        String,
    val price:       Long,
    val description: String = "",
    val sortOrder:   Int    = 0,
)

data class ChatInboxItem(
    val uid:         Long,
    val lastMessage: String,
    val lastAt:      Long,
    val unread:      Int,
)

class MerchantsClient(private val baseURL: String = "https://saving.nivic.dev") {

    suspend fun searchMerchants(query: String): List<MerchantInfo> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/merchants?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        val arr  = JSONArray(readResponse(conn))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MerchantInfo(o.getLong("mid"), o.getString("name"), o.optString("address", ""))
        }
    }

    suspend fun onboard(uid: Long, name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("uid", uid); put("name", name) }.toString()
        val conn = post("$baseURL/merchants/onboard", body)
        val code = conn.responseCode
        val resp = (if (code == 200) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        if (code == 409) throw Exception("Bạn đã là merchant rồi")
        if (code != 200) throw Exception(JSONObject(resp).optString("error", "onboard failed"))
        JSONObject(resp).getString("token")
    }

    suspend fun stats(mid: Long, token: String): MerchantStats = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/merchants/$mid/stats", token)
        val resp = readResponse(conn)
        val j    = JSONObject(resp)
        MerchantStats(j.getLong("total_earned"), j.getInt("order_count"))
    }

    suspend fun listOrders(mid: Long, token: String): List<MerchantOrder> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/merchants/$mid/orders", token)
        val resp = readResponse(conn)
        val arr  = JSONArray(resp)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MerchantOrder(
                id        = o.getString("id"),
                mid       = o.getLong("mid"),
                amount    = o.getLong("amount"),
                note      = o.optString("note", ""),
                status    = o.getString("status"),
                createdAt = o.getLong("created_at"),
            )
        }
    }

    suspend fun loyaltyBalance(mid: Long, uid: Long): LoyaltyBalance = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/merchants/$mid/loyalty/$uid")
        val j    = JSONObject(readResponse(conn))
        LoyaltyBalance(j.getLong("uid"), j.getLong("mid"), j.getLong("points"), j.getLong("value_vnd"))
    }

    suspend fun loyaltyMembers(mid: Long, token: String): List<LoyaltyMember> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/merchants/$mid/loyalty", token)
        val arr  = JSONArray(readResponse(conn))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LoyaltyMember(o.getLong("uid"), o.getLong("points"))
        }
    }

    suspend fun userLoyalty(uid: Long): List<UserLoyaltyEntry> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/loyalty/user/$uid")
        val arr  = JSONArray(readResponse(conn))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            UserLoyaltyEntry(o.getLong("mid"), o.getString("merchant_name"), o.getLong("points"))
        }
    }

    suspend fun createOrder(mid: Long, token: String, amount: Long, note: String, discountPoints: Long = 0): CreateOrderResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("amount", amount); put("note", note)
                if (discountPoints > 0) put("discount_points", discountPoints)
            }.toString()
            val conn = post("$baseURL/merchants/$mid/orders", body, token)
            if (conn.responseCode != 200) throw Exception("Tạo đơn thất bại")
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            CreateOrderResult(j.getString("order_id"), j.getString("pr"), j.getString("qr_url"))
        }

    suspend fun getInbox(mid: Long, token: String): List<ChatInboxItem> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/chat/$mid/inbox", token)
        val arr  = JSONArray(readResponse(conn))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChatInboxItem(
                uid         = o.getLong("uid"),
                lastMessage = o.getString("last_message"),
                lastAt      = o.getLong("last_at"),
                unread      = o.getInt("unread"),
            )
        }
    }

    suspend fun replyMessage(mid: Long, uid: Long, token: String, text: String) = withContext(Dispatchers.IO) {
        val body = org.json.JSONObject().apply { put("uid", uid); put("text", text) }.toString()
        val conn = post("$baseURL/chat/$mid/reply", body, token)
        readResponse(conn)
    }

    suspend fun sendMessage(mid: Long, uid: Long, text: String) = withContext(Dispatchers.IO) {
        val body = org.json.JSONObject().apply { put("uid", uid); put("text", text) }.toString()
        val conn = post("$baseURL/chat/$mid", body)
        readResponse(conn)
    }

    suspend fun getThread(mid: Long, uid: Long, since: Long = 0): List<ChatMessage> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/chat/$mid/$uid?since=$since")
        val arr  = JSONArray(readResponse(conn))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChatMessage(
                id           = o.getLong("id"),
                fromMerchant = o.getBoolean("from_merchant"),
                body         = o.getString("body"),
                createdAt    = o.getLong("created_at"),
            )
        }
    }

    suspend fun confirmPaid(orderID: String, paidBy: Int) = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply { put("paid_by", paidBy) }.toString()
            post("$baseURL/orders/$orderID/confirm", body)
        }
        // fire-and-forget: called by Wire server after Wire payment
    }

    /** Wire app relays txn_id from Wire TCP ACK — Merchants pull-verifies against Wire admin. */
    suspend fun wireConfirmPaid(orderID: String, txnId: Long, paidBy: Long) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("txn_id", txnId); put("paid_by", paidBy) }.toString()
        val conn = post("$baseURL/orders/$orderID/wire_confirm", body)
        readResponse(conn)
    }

    suspend fun merchantConfirmPaid(mid: Long, token: String, orderID: String, paidBy: Int): Int =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("paid_by", paidBy) }.toString()
            val conn = post("$baseURL/merchants/$mid/orders/$orderID/confirm", body, token)
            val resp = readResponse(conn)
            JSONObject(resp).optInt("points_awarded", 0)
        }

    suspend fun getMerchant(mid: Long): MerchantInfo = withContext(Dispatchers.IO) {
        val j = JSONObject(readResponse(get("$baseURL/merchants/$mid")))
        MerchantInfo(j.getLong("mid"), j.getString("name"), j.optString("address", ""))
    }

    suspend fun getMerchantBySlug(slug: String): MerchantInfo = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(slug, "UTF-8")
        val j = JSONObject(readResponse(get("$baseURL/merchants/by-slug/$enc")))
        MerchantInfo(j.getLong("mid"), j.getString("name"), j.optString("address", ""))
    }

    suspend fun fetchOrderWire(orderId: String): OrderWireDeeplink = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(orderId, "UTF-8")
        val j = JSONObject(readResponse(get("$baseURL/pay/$enc/wire")))
        OrderWireDeeplink(
            orderId    = j.getString("order_id"),
            mid        = j.getLong("mid"),
            amount     = j.getLong("amount"),
            requestId  = j.getLong("request_id"),
            intentUrl  = j.getString("intent_url"),
        )
    }

    suspend fun listMenu(mid: Long): List<MenuItem> = withContext(Dispatchers.IO) {
        val arr = JSONArray(readResponse(get("$baseURL/merchants/$mid/menu")))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MenuItem(o.getLong("id"), o.getLong("mid"), o.getString("name"),
                o.getLong("price"), o.optString("description", ""), o.optInt("sort_order", 0))
        }
    }

    suspend fun addMenuItem(mid: Long, token: String, name: String, price: Long, description: String = ""): Long =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("name", name); put("price", price)
                if (description.isNotEmpty()) put("description", description)
            }.toString()
            val conn = post("$baseURL/merchants/$mid/menu", body, token)
            JSONObject(readResponse(conn)).getLong("id")
        }

    suspend fun deleteMenuItem(mid: Long, token: String, itemId: Long) = withContext(Dispatchers.IO) {
        val conn = URL("$baseURL/merchants/$mid/menu/$itemId").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("X-Merchant-Token", token)
        conn.responseCode
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optString("error", body) }.getOrDefault(body)
            throw Exception(msg.ifBlank { "HTTP $code" })
        }
        return body
    }

    private fun get(url: String, token: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        token?.let { conn.setRequestProperty("X-Merchant-Token", it) }
        return conn
    }

    private fun post(url: String, body: String, token: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        token?.let { conn.setRequestProperty("X-Merchant-Token", it) }
        conn.outputStream.bufferedWriter().use { it.write(body) }
        return conn
    }
}
