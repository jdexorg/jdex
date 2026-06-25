package io.github.nitanmarcel.jdex.debug

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun rspChecksum(payload: String): String {
    var sum = 0
    for (c in payload) sum = (sum + c.code) and 0xff
    return "%02x".format(sum)
}

fun rspFrame(payload: String): String = "\$$payload#${rspChecksum(payload)}"

fun rspUnframe(packet: String): String? {
    if (packet.length < 4 || packet[0] != '$') return null
    val hash = packet.lastIndexOf('#')
    if (hash < 1 || hash + 2 >= packet.length) return null
    val body = packet.substring(1, hash)
    val cs = packet.substring(hash + 1, hash + 3)
    return if (cs.equals(rspChecksum(body), ignoreCase = true)) body else null
}

fun rspEscape(payload: String): String {
    val sb = StringBuilder()
    for (c in payload) {
        if (c == '}' || c == '$' || c == '#' || c == '*') { sb.append('}'); sb.append((c.code xor 0x20).toChar()) }
        else sb.append(c)
    }
    return sb.toString()
}

fun rspUnescape(payload: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < payload.length) {
        val c = payload[i]
        if (c == '}' && i + 1 < payload.length) { sb.append((payload[i + 1].code xor 0x20).toChar()); i += 2 }
        else { sb.append(c); i++ }
    }
    return sb.toString()
}

fun rspDecode(payload: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < payload.length) {
        val c = payload[i]
        when {
            c == '}' && i + 1 < payload.length -> { sb.append((payload[i + 1].code xor 0x20).toChar()); i += 2 }
            c == '*' && i + 1 < payload.length && sb.isNotEmpty() -> {
                repeat(payload[i + 1].code - 29) { sb.append(sb.last()) }; i += 2
            }
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString()
}

class RspClient(private val host: String, private val port: Int) {
    private lateinit var sock: Socket
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val replies = LinkedBlockingQueue<String>()
    private val writeLock = Any()
    private val commandLock = Any()
    @Volatile private var noAck = false
    @Volatile private var running = false
    @Volatile private var awaiting = false
    var onStop: (String) -> Unit = {}

    fun connect() {
        sock = Socket(host, port)
        sock.tcpNoDelay = true
        input = sock.getInputStream()
        output = sock.getOutputStream()
        running = true
        Thread({ readLoop() }, "jdex-rsp-reader").apply { isDaemon = true }.start()
        synchronized(writeLock) { output.write('+'.code); output.flush() }
        check(command("QStartNoAckMode") == "OK") { "lldb-server rejected QStartNoAckMode" }
        noAck = true
        runCatching { command("qSupported:xmlRegisters=i386,arm,aarch64,mips;qXfer:features:read+") }
    }

    fun command(payload: String): String = synchronized(commandLock) {
        replies.clear()
        awaiting = true
        try {
            send(payload)
            replies.poll(15, TimeUnit.SECONDS) ?: error("RSP timeout for: $payload")
        } finally {
            awaiting = false
        }
    }

    fun post(payload: String) = send(payload)

    fun sendRaw(b: Int) = synchronized(writeLock) { output.write(b); output.flush() }

    private fun send(payload: String) = synchronized(writeLock) {
        output.write(rspFrame(rspEscape(payload)).toByteArray()); output.flush()
    }

    private fun readLoop() {
        val buf = StringBuilder()
        while (running) {
            val r = runCatching { input.read() }.getOrDefault(-1)
            if (r < 0) break
            when (r.toChar()) {
                '+', '-' -> {}
                '$' -> {
                    buf.setLength(0)
                    var d = runCatching { input.read() }.getOrDefault(-1)
                    while (d >= 0 && d.toChar() != '#') { buf.append(d.toChar()); d = runCatching { input.read() }.getOrDefault(-1) }
                    if (d < 0) break
                    runCatching { input.read(); input.read() }
                    if (!noAck) synchronized(writeLock) { output.write('+'.code); output.flush() }
                    dispatch(rspDecode(buf.toString()))
                }
            }
        }
    }

    private fun dispatch(body: String) {
        if (awaiting) { replies.put(body); return }
        val k = body.firstOrNull()
        if (k == 'T' || k == 'S' || k == 'W' || k == 'X') onStop(body)
    }

    fun close() { running = false; runCatching { sock.close() } }
}
