package app.saving.wire.util

fun Long.vndFormatted(): String {
    val s  = this.toString()
    val sb = StringBuilder()
    s.forEachIndexed { i, c ->
        if (i > 0 && (s.length - i) % 3 == 0) sb.append('.')
        sb.append(c)
    }
    return "$sb ₫"
}
