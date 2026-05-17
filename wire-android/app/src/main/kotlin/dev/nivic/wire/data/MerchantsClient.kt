package dev.nivic.wire.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

class MerchantsClient(private val baseURL: String = "http://10.0.2.2:8090") {

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
        val resp = conn.inputStream.bufferedReader().readText()
        val j    = JSONObject(resp)
        MerchantStats(j.getLong("total_earned"), j.getInt("order_count"))
    }

    suspend fun listOrders(mid: Long, token: String): List<MerchantOrder> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/merchants/$mid/orders", token)
        val resp = conn.inputStream.bufferedReader().readText()
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
        val j    = JSONObject(conn.inputStream.bufferedReader().readText())
        LoyaltyBalance(j.getLong("uid"), j.getLong("mid"), j.getLong("points"), j.getLong("value_vnd"))
    }

    suspend fun loyaltyMembers(mid: Long, token: String): List<LoyaltyMember> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/merchants/$mid/loyalty", token)
        val arr  = JSONArray(conn.inputStream.bufferedReader().readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LoyaltyMember(o.getLong("uid"), o.getLong("points"))
        }
    }

    suspend fun userLoyalty(uid: Long): List<UserLoyaltyEntry> = withContext(Dispatchers.IO) {
        val conn = get("$baseURL/loyalty/user/$uid")
        val arr  = JSONArray(conn.inputStream.bufferedReader().readText())
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
