package app.saving.wire.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

object VietQR {

    data class Bank(val bin: String, val name: String, val shortCode: String)

    val BANKS = listOf(
        Bank("970436", "Vietcombank", "VCB"),
        Bank("970415", "VietinBank",  "VTB"),
        Bank("970418", "BIDV",        "BIDV"),
        Bank("970407", "Techcombank", "TCB"),
        Bank("970422", "MB Bank",     "MBB"),
        Bank("970403", "Sacombank",   "SCB"),
        Bank("970432", "VPBank",      "VPB"),
        Bank("970416", "ACB",         "ACB"),
        Bank("970423", "TPBank",      "TPB"),
        Bank("970448", "OCB",         "OCB"),
        Bank("970441", "VIB",         "VIB"),
        Bank("970426", "MSB",         "MSB"),
        Bank("970454", "Agribank",    "AGR"),
    )

    /**
     * Fetch a VietQR PNG from img.vietqr.io — guaranteed compatible with all VN banking apps.
     * Call on a background thread (IO dispatcher).
     *
     * @param bankBin     6-digit bank BIN, e.g. "970441"
     * @param accountNo   account number
     * @param amount      amount in VND
     * @param note        transfer note shown in banking app (max 25 chars, no special chars)
     * @param holderName  account holder name (optional, shown in banking app)
     */
    fun fetchBitmap(
        bankBin:    String,
        accountNo:  String,
        amount:     Long,
        note:       String = "",
        holderName: String = "",
    ): Bitmap {
        val addInfo = java.net.URLEncoder.encode(note.take(25).replace(" ", "+"), "UTF-8")
        val name    = java.net.URLEncoder.encode(holderName.take(25).uppercase(), "UTF-8")
        val url     = "https://img.vietqr.io/image/$bankBin-$accountNo-compact2.png" +
                      "?amount=$amount&addInfo=$addInfo&accountName=$name"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.setRequestProperty("Accept", "image/png")
        val code = conn.responseCode
        if (code != 200) throw Exception("VietQR.io HTTP $code")
        return BitmapFactory.decodeStream(conn.inputStream)
            ?: throw Exception("VietQR.io: failed to decode image")
    }
}
