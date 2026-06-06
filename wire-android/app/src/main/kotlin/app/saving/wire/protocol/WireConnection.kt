package app.saving.wire.protocol

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The socket is dead (closed by server, idle-killed, broken pipe). The caller
 *  is expected to reconnect + re-login and retry. Distinct from a protocol-level
 *  [WireError] so the resilient layer can tell "transport died" from "server said no".
 *
 *  [delivered] = false  → the frame never left the device (write failed): retrying
 *  is always safe. [delivered] = true → the frame may have reached the server but
 *  the ack was lost: retrying a money-moving op risks a double-spend, so only
 *  idempotent reads may be retried automatically. */
class ConnectionDead(msg: String, val delivered: Boolean = false) : Exception(msg)

class WireConnection(
    private val host:   String,
    private val port:   Int,
    private val secret: ByteArray
) {
    private var socket: Socket?          = null
    private var input:  DataInputStream? = null
    private val seqGen  = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<WireFrame>>()
    private var recvJob: Job? = null

    var onEvent: ((WireFrame) -> Unit)? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        val s = Socket(host, port)
        socket = s
        input  = DataInputStream(s.inputStream)
        startReceiving()
    }

    /** Tear the old socket down and dial a fresh one. Any in-flight calls are
     *  failed with [ConnectionDead] so the resilient layer retries them on the
     *  new socket. [onEvent] is preserved (it's a field, not re-set here). */
    suspend fun reconnect() = withContext(Dispatchers.IO) {
        recvJob?.cancel()
        runCatching { socket?.close() }
        pending.values.forEach { it.completeExceptionally(ConnectionDead("reconnecting", delivered = true)) }
        pending.clear()
        val s = Socket(host, port)
        socket = s
        input  = DataInputStream(s.inputStream)
        startReceiving()
    }

    fun disconnect() {
        recvJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        pending.values.forEach { it.completeExceptionally(ConnectionDead("disconnected", delivered = true)) }
        pending.clear()
    }

    suspend fun send(frame: WireFrame): WireFrame {
        val raw  = frame.encode(secret)
        val resp = CompletableDeferred<WireFrame>()
        pending[frame.seq] = resp
        try {
            withContext(Dispatchers.IO) {
                val out = socket?.getOutputStream() ?: throw ConnectionDead("not connected")
                out.write(raw)
                out.flush()
            }
        } catch (e: ConnectionDead) {
            pending.remove(frame.seq)
            throw e
        } catch (e: Exception) {
            // broken pipe / reset / closed socket — surface as ConnectionDead
            pending.remove(frame.seq)
            throw ConnectionDead(e.message ?: "write failed")
        }
        return resp.await()
    }

    fun nextSeq(): Int = seqGen.incrementAndGet()

    private fun startReceiving() {
        recvJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive) {
                    val lenBuf = ByteArray(4)
                    input!!.readFully(lenBuf)
                    val totalLen = lenBuf.getInt(0)
                    check(totalLen in 41..4096) { "bad frame size: $totalLen" }
                    val raw = ByteArray(totalLen)
                    lenBuf.copyInto(raw)
                    input!!.readFully(raw, 4, totalLen - 4)
                    val frame = try { WireFrame.decode(raw, secret) } catch (_: Exception) { continue }
                    dispatch(frame)
                }
            } catch (_: Exception) {
                pending.values.forEach { it.completeExceptionally(ConnectionDead("recv ended", delivered = true)) }
                pending.clear()
            }
        }
    }

    private fun dispatch(frame: WireFrame) {
        if (frame.type.toInt() and 0xFF >= 0xC0) {
            onEvent?.invoke(frame)
            return
        }
        pending.remove(frame.seq)?.complete(frame)
    }
}
