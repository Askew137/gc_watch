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
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        var callback: android.net.ConnectivityManager.NetworkCallback? = null
        var outcome = "incomplete"
        try {
            val have = resumableBytes(name, size)
            if (offset != have) {
                Timber.w("MapReceiver: offset mismatch for %s (phone %d, watch %d)", name, offset, have)
                return
            }
            // Wi-Fi may be idle: ask for it explicitly, and use exactly that network for the (LAN) address.
            val latch = java.util.concurrent.CountDownLatch(1)
            var wifi: android.net.Network? = null
            callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { wifi = network; latch.countDown() }
            }
            cm.requestNetwork(
                android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback,
            )
            if (!latch.await(WIFI_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) || wifi == null) {
                Timber.w("MapReceiver: no Wi-Fi network for the download")
                outcome = "unreachable:the watch has no Wi-Fi connection (it is connected only through the phone)"
                return
            }
            note.writeText(size.toString())
            val conn = try {
                (wifi!!.openConnection(java.net.URL(url)) as java.net.HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }.also { it.connect() }
            } catch (e: java.io.IOException) {
                Timber.w(e, "MapReceiver: cannot reach the phone at %s", url)
                outcome = "unreachable:the watch could not connect to the phone (" +
                    (e.message ?: e.javaClass.simpleName).replace('|', ' ').take(80) + ")"
                return
            }
            val code = conn.responseCode
            if (code != 200 && code != 206 || (have > 0 && code != 206)) {
                Timber.w("MapReceiver: unexpected HTTP %d", code)
                return
            }
            Timber.i("MapReceiver: downloading %s from the phone, %d of %d bytes already here", name, have, size)
            outcome = store(name, size, have, conn.inputStream)
        } catch (e: Exception) {
            Timber.e(e, "MapReceiver: Wi-Fi download of %s broke", name)
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            if (wakeLock.isHeld) wakeLock.release()
            send(phoneNodeId, MapTransferProtocol.STORED, MapTransferProtocol.encode(name, size, outcome))
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
        const val STALE_MS = 7L * 24 * 3600 * 1000
    }
}
