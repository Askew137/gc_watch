package com.askew137.gcwatch.mobile.maps

import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A tiny HTTP server that serves exactly one file, only under `/<token>/<name>`, for the watch to download
 * over the local Wi-Fi (supports `Range: bytes=N-`, so an interrupted download resumes). It lives only for
 * the duration of one transfer. The Wear OS Data Layer channel is far slower for big files.
 */
class MapHttpServer(private val file: File, private val token: String) {

    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)

    /** Bytes of the file the watch has been sent so far (counted from the start of the file). */
    val servedUpTo = AtomicLong(0)

    /** True once the watch has opened a connection with a valid token. */
    @Volatile var contacted = false
        private set

    /** Last time any byte moved (elapsedRealtime-like, in ms of System.nanoTime/1e6). */
    val lastActivityMs = AtomicLong(now())

    fun start(port: Int = 0): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port)) // all interfaces; 0 = ephemeral port
        server = s
        running.set(true)
        Thread({ acceptLoop(s) }, "map-http-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val c = try { s.accept() } catch (e: Exception) { break }
            Thread({ handle(c) }, "map-http-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 30_000
                val input = sock.getInputStream()
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                val out = sock.getOutputStream()
                if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) return respond(out, 405, "Method Not Allowed")
                val path = parts[1].substringBefore('?')
                if (!path.startsWith("/$token/")) return respond(out, 404, "Not Found")
                contacted = true

                val total = file.length()
                var start = 0L
                headers["range"]?.let { r ->
                    Regex("""bytes=(\d+)-""").find(r)?.let { start = it.groupValues[1].toLong() }
                }
                if (start >= total) return respond(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$total")

                val partial = start > 0
                val head = StringBuilder()
                head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                head.append("Content-Type: application/octet-stream\r\n")
                head.append("Accept-Ranges: bytes\r\n")
                head.append("Content-Length: ${total - start}\r\n")
                if (partial) head.append("Content-Range: bytes $start-${total - 1}/$total\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                if (parts[0] == "HEAD") return out.flush()

                Timber.i("MapHttpServer: serving %s from byte %d of %d", file.name, start, total)
                file.inputStream().use { fin ->
                    var skipped = 0L
                    while (skipped < start) {
                        val n = fin.skip(start - skipped)
                        if (n <= 0) return
                        skipped += n
                    }
                    val buf = ByteArray(64 * 1024)
                    var sent = start
                    while (true) {
                        val n = fin.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        sent += n
                        servedUpTo.set(sent)
                        lastActivityMs.set(now())
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                Timber.w("MapHttpServer: connection ended: %s", e.message)
            }
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, text: String, extra: String? = null) {
        out.write(("HTTP/1.1 $code $text\r\n" + (extra?.let { "$it\r\n" } ?: "") + "Content-Length: 0\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    private fun now() = System.nanoTime() / 1_000_000
}
