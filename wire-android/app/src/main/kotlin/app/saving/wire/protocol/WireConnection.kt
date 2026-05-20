package app.saving.wire.protocol

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    fun disconnect() {
        recvJob?.cancel()
        socket?.close()
        socket = null
        pending.values.forEach { it.completeExceptionally(Exception("disconnected")) }
        pending.clear()
    }

    suspend fun send(frame: WireFrame): WireFrame {
        val raw  = frame.encode(secret)
        val resp = CompletableDeferred<WireFrame>()
        pending[frame.seq] = resp
        withContext(Dispatchers.IO) {
            val out = socket?.getOutputStream() ?: throw Exception("not connected")
            out.write(raw)
            out.flush()
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
                pending.values.forEach { it.completeExceptionally(Exception("disconnected")) }
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
