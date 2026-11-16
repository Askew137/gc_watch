package com.askew137.gcwatch.wear.navigation

import android.content.Context
import android.os.PowerManager
import com.askew137.gcwatch.core.communication.MapTransferProtocol
import com.askew137.gcwatch.wear.WearApplication
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Receives `.map` files from the phone (see [MapTransferProtocol]).
 *
 * The file is built as `<name>.map.part` next to a `<name>.map.part.size` note (the size the phone
 * announced). A transfer that breaks keeps the partial file, so the phone can resume it; a file only
 * becomes `<name>.map` when it has exactly the announced size. Before, any early end of the stream
 * (the phone app killed, the link dropped) renamed a truncated file into a "finished" map.
 */
class MapReceiver(private val context: Context) {

    private val mapsDir: File get() = File(context.filesDir, "maps").apply { mkdirs() }

    private fun partOf(name: String) = File(mapsDir, "$name.part")
    private fun sizeNoteOf(name: String) = File(mapsDir, "$name.part.size")

    /** How many bytes of this file are already here and valid for resuming; stale leftovers are removed. */
    private fun resumableBytes(name: String, size: Long): Long {
        val part = partOf(name)
        val note = sizeNoteOf(name)
        val noted = runCatching { note.readText().trim().toLong() }.getOrNull()
        return if (part.isFile && noted == size && part.length() in 1 until size) {
            part.length()
        } else {
            part.delete()
            note.delete()
            0L
        }
    }

    /** Phone → watch: "do you have a part of this file, and room for the rest?" */
    fun answerResumeQuery(payload: ByteArray, phoneNodeId: String) {
        val q = MapTransferProtocol.decode(payload)
        if (q == null || !MapTransferProtocol.isSafeMapName(q.first) || q.second <= 0) {
            Timber.w("MapReceiver: malformed resume query")
            return
        }
        val (name, size) = q.first to q.second
        val offset = resumableBytes(name, size)
        // Room for the rest, plus a margin: a full storage would break the whole watch.
        val answer = if (context.filesDir.usableSpace < (size - offset) + FREE_SPACE_MARGIN) {
            MapTransferProtocol.NO_SPACE
        } else {
            offset
        }
        Timber.i("MapReceiver: %s (%d bytes): have %d, answer %d", name, size, offset, answer)
        send(phoneNodeId, MapTransferProtocol.RESUME_ANSWER, MapTransferProtocol.encode(name, answer))
    }

    /** Called for a channel opened with [MapTransferProtocol.CHANNEL_PREFIX]; blocks until the stream ends. */
    fun receive(channel: ChannelClient.Channel) {
        val t = MapTransferProtocol.parseChannelPath(channel.path)
        val client = Wearable.getChannelClient(context)
        if (t == null) {
            Timber.w("MapReceiver: rejecting channel %s", channel.path)
            runCatching { Tasks.await(client.close(channel)) }
            return
        }

        val part = partOf(t.name)
        val note = sizeNoteOf(t.name)
        val dest = File(mapsDir, t.name)

        // The screen is usually off during a long transfer.
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gcwatch:map-receive")
            .apply { acquire(WAKE_LOCK_MS) }

        var outcome = "incomplete"
        try {
            val have = resumableBytes(t.name, t.size)
            if (t.offset != have) {
                // The phone resumes from somewhere the watch does not agree with: it will ask again.
                Timber.w("MapReceiver: offset mismatch for %s (phone %d, watch %d)", t.name, t.offset, have)
                return
            }
            note.writeText(t.size.toString())
            Timber.i("MapReceiver: receiving %s, %d of %d bytes already here", t.name, have, t.size)

            val input = Tasks.await(client.getInputStream(channel))
            outcome = store(t.name, t.size, have, input)
        } catch (e: Exception) {
            // The partial file stays: the phone resumes from it.
            Timber.e(e, "MapReceiver: transfer of %s broke", t.name)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            send(channel.nodeId, MapTransferProtocol.STORED, MapTransferProtocol.encode(t.name, t.size, outcome))
            runCatching { Tasks.await(client.close(channel)) }
        }
    }

    /**
     * Appends [src] to `<name>.part` and, only if the file then has exactly [size] bytes, turns it into the
     * map. Returns `ok` or `incomplete` (the part is kept for resuming).
     */
    private fun store(name: String, size: Long, have: Long, src: java.io.InputStream): String {
        val part = partOf(name)
        val dest = File(mapsDir, name)
        var total = have
        src.use { input ->
            FileOutputStream(part, /* append = */ have > 0).use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > size) throw IllegalStateException("more data than announced")
                    out.write(buf, 0, n)
                }
                out.flush()
                out.fd.sync()
            }
        }
        if (total != size) {
            Timber.w("MapReceiver: stream ended after %d of %d bytes; keeping the part", total, size)
            return "incomplete"
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            Timber.e("MapReceiver: could not move %s into place", name)
            return "incomplete"
        }
        sizeNoteOf(name).delete()
        (context.applicationContext as WearApplication).mapsRevision.value++
        Timber.i("MapReceiver: %s stored (%d bytes)", name, total)
        return "ok"
    }

    /** A connection to the phone over the local Wi-Fi; [failure] is set when there is none. Close it when done. */
    private class Lan(val conn: java.net.HttpURLConnection?, val failure: String?, private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }

    /**
     * Asks Android for the Wi-Fi network (it may be idle) and opens [url] on exactly that network, with a
     * `Range` header from [rangeFrom] when > 0. [Lan.failure] says why not (no Wi-Fi / cannot connect).
     */
    private fun connectLan(url: String, rangeFrom: Long): Lan {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val latch = java.util.concurrent.CountDownLatch(1)
        var wifi: android.net.Network? = null
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { wifi = network; latch.countDown() }
        }
        val release = { runCatching { cm.unregisterNetworkCallback(callback) }; Unit }
        try {
            cm.requestNetwork(
                android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback,
            )
        } catch (e: Exception) {
            return Lan(null, "the watch cannot use Wi-Fi (" + (e.message ?: e.javaClass.simpleName).replace('|', ' ').take(60) + ")", release)
        }
        if (!latch.await(WIFI_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) || wifi == null) {
            Timber.w("MapReceiver: no Wi-Fi network")
            return Lan(null, "the watch has no Wi-Fi connection (it is connected only through the phone)", release)
        }
        return try {
            val conn = (wifi!!.openConnection(java.net.URL(url)) as java.net.HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 20_000
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            conn.connect()
            Lan(conn, null, release)
        } catch (e: java.io.IOException) {
            Timber.w(e, "MapReceiver: cannot reach the phone at %s", url)
            Lan(null, "the watch could not connect to the phone (" + (e.message ?: e.javaClass.simpleName).replace('|', ' ').take(80) + ")", release)
        }
    }

    /**
     * Phone → watch: download the rest of a map from the phone over the local Wi-Fi (see
     * [MapTransferProtocol.HTTP_OFFER]). Blocks until done. The Data Layer channel moves only a few KB/s in bursts.
     */
    fun downloadHttp(payload: ByteArray, phoneNodeId: String) {
        val p = String(payload, Charsets.UTF_8).split('|', limit = 4)
        val name = p.getOrNull(0).orEmpty()
        val size = p.getOrNull(1)?.toLongOrNull() ?: 0L
        val offset = p.getOrNull(2)?.toLongOrNull() ?: -1L
        val url = p.getOrNull(3).orEmpty()
        if (!MapTransferProtocol.isSafeMapName(name) || size <= 0 || offset !in 0 until size || !MapTransferProtocol.isPrivateLanUrl(url)) {
            Timber.w("MapReceiver: rejecting a malformed or non-LAN download offer")
            return
        }
        val note = sizeNoteOf(name)
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gcwatch:map-download")
            .apply { acquire(WAKE_LOCK_MS) }
        var outcome = "incomplete"
        try {
            val have = resumableBytes(name, size)
            if (offset != have) {
                Timber.w("MapReceiver: offset mismatch for %s (phone %d, watch %d)", name, offset, have)
                return
            }
            connectLan(url, have).use { lan ->
                val conn = lan.conn
                if (conn == null) {
                    outcome = "unreachable:" + lan.failure
                    return
                }
                val code = conn.responseCode
                if (code != 200 && code != 206 || (have > 0 && code != 206)) {
                    Timber.w("MapReceiver: unexpected HTTP %d", code)
                    return
                }
                note.writeText(size.toString())
                Timber.i("MapReceiver: downloading %s from the phone, %d of %d bytes already here", name, have, size)
                outcome = store(name, size, have, conn.inputStream)
            }
        } catch (e: Exception) {
            Timber.e(e, "MapReceiver: Wi-Fi download of %s broke", name)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            send(phoneNodeId, MapTransferProtocol.STORED, MapTransferProtocol.encode(name, size, outcome))
        }
    }

    /**
     * Phone → watch: the new watch APK over the local Wi-Fi (`"<size>|<url>"`) instead of the slow Data Layer
     * channel. Same outcome as the channel path: the APK is verified and offered for installation, then
     * [com.askew137.gcwatch.core.communication.MessagePaths.UPDATE_WEAR_RECEIVED]; when the phone cannot be reached the
     * phone is told with [com.askew137.gcwatch.core.communication.MessagePaths.UPDATE_HTTP_UNREACHABLE] and falls back.
     */
    fun downloadUpdate(payload: ByteArray, phoneNodeId: String) {
        val p = String(payload, Charsets.UTF_8).split('|', limit = 2)
        val size = p.getOrNull(0)?.toLongOrNull() ?: 0L
        val url = p.getOrNull(1).orEmpty()
        if (size <= 0 || size > MAX_UPDATE_BYTES || !MapTransferProtocol.isPrivateLanUrl(url)) {
            Timber.w("MapReceiver: rejecting a malformed or non-LAN update offer")
            return
        }
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val tmp = File(dir, "wear.apk.part")
        val dest = File(dir, "wear.apk")
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gcwatch:update-download")
            .apply { acquire(10 * 60 * 1000L) }
        try {
            connectLan(url, 0).use { lan ->
                val conn = lan.conn
                if (conn == null) {
                    send(phoneNodeId, com.askew137.gcwatch.core.communication.MessagePaths.UPDATE_HTTP_UNREACHABLE,
                        (lan.failure ?: "unreachable").toByteArray(Charsets.UTF_8))
                    return
                }
                if (conn.responseCode != 200) {
                    Timber.w("MapReceiver: update download answered HTTP %d", conn.responseCode)
                    return
                }
                var total = 0L
                conn.inputStream.use { src ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = src.read(buf)
                            if (n == -1) break
                            total += n
                            if (total > size) throw IllegalStateException("more data than announced")
                            out.write(buf, 0, n)
                        }
                        out.flush(); out.fd.sync()
                    }
                }
                if (total != size) {
                    Timber.w("MapReceiver: update ended after %d of %d bytes", total, size)
                    tmp.delete()
                    return // no acknowledgement: the phone reports the failure
                }
                val problem = com.askew137.gcwatch.core.communication.ApkInstaller.verifyUpdate(context, tmp)
                when {
                    problem == null -> {
                        if (dest.exists()) dest.delete()
                        tmp.renameTo(dest)
                        (context.applicationContext as WearApplication).pendingUpdate.value = dest
                        Timber.i("MapReceiver: update APK stored over Wi-Fi (%d bytes)", total)
                    }
                    problem.startsWith("not newer") -> { tmp.delete(); Timber.i("MapReceiver: watch already up to date") }
                    else -> { tmp.delete(); Timber.w("MapReceiver: update APK rejected: %s", problem); return }
                }
                send(phoneNodeId, com.askew137.gcwatch.core.communication.MessagePaths.UPDATE_WEAR_RECEIVED, ByteArray(0))
            }
        } catch (e: Exception) {
            Timber.e(e, "MapReceiver: update download broke")
            tmp.delete()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /** Removes leftovers of transfers that were never finished (they would eat the watch's storage). */
    fun cleanupStale() {
        val limit = System.currentTimeMillis() - STALE_MS
        mapsDir.listFiles { f -> f.name.endsWith(".map.part") || f.name.endsWith(".map.part.size") }
            ?.filter { it.lastModified() < limit }
            ?.forEach { Timber.i("MapReceiver: removing stale %s", it.name); it.delete() }
    }

    private fun send(nodeId: String, path: String, payload: ByteArray) {
        try {
            Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, path, payload))
        } catch (e: Exception) {
            Timber.w(e, "MapReceiver: could not send %s", path)
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val FREE_SPACE_MARGIN = 30L * 1024 * 1024
        const val WAKE_LOCK_MS = 30L * 60 * 1000
        const val WIFI_WAIT_MS = 10_000L
        const val MAX_UPDATE_BYTES = 100L * 1024 * 1024
        const val STALE_MS = 7L * 24 * 3600 * 1000
    }
}
