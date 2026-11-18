package com.askew137.gcwatch.mobile.maps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.askew137.gcwatch.core.communication.MapTransferProtocol
import com.askew137.gcwatch.mobile.R
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What the Maps screen shows about the transfer of a `.map` file to the watch. */
sealed interface MapTransferState {
    data object Idle : MapTransferState

    data class Working(
        val fileName: String,
        val phase: Phase,
        val sentBytes: Long = 0,
        val totalBytes: Long = 0,
        val bytesPerSec: Long = 0,
        /** Why Wi-Fi was not used, when the slow link is in use (already localized). */
        val note: String = "",
        /** How the file travels: true = Wi-Fi (direct, fast), false = Bluetooth link (the slow Data Layer channel). */
        val viaWifi: Boolean = false,
    ) : MapTransferState {
        val percent: Int get() = if (totalBytes > 0) (sentBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else 0

        /** Seconds left at the current speed; null while unknown. */
        val secondsLeft: Long? get() = if (bytesPerSec > 0 && totalBytes > sentBytes) (totalBytes - sentBytes) / bytesPerSec else null
    }

    /** The watch confirmed that the complete file is stored. */
    data class Done(val fileName: String, val totalBytes: Long) : MapTransferState

    /** Tapping "Retry" resumes where the watch stopped, not from the beginning. */
    data class Failed(val fileName: String, val message: String /* already localized */) : MapTransferState

    enum class Phase { ASKING_WATCH, SENDING, CONFIRMING }
}

/**
 * Foreground [Service] that sends a `.map` file to the connected Wear OS watch via [ChannelClient].
 * The protocol (resume, size check, confirmation) is in [MapTransferProtocol]; progress and the result
 * are published in [state].
 *
 * Start via [startTransfer]; stop it with [cancel].
 */
class MapTransferService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Timber.i("Map transfer cancelled")
            job?.cancel() // keep the reference: cancelled() looks at it
            if (_state.value is MapTransferState.Working) _state.value = MapTransferState.Idle
            stopSelf()
            return START_NOT_STICKY
        }

        val uriStr = intent?.getStringExtra(EXTRA_MAP_URI)
        val file = uriStr?.let { Uri.parse(it).path }?.let(::File)
        if (file == null || !file.isFile) {
            Timber.e("MapTransferService started without a usable file: %s", uriStr)
            _state.value = MapTransferState.Failed(file?.name ?: getString(R.string.map_default_name), getString(R.string.map_err_missing_file))
            startForeground(NOTIF_ID, notification(file?.name ?: getString(R.string.map_default_name), null, getString(R.string.notif_map_failed)))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, notification(file.name, 0, getString(R.string.notif_map_sending)))
        if (job?.isActive == true) return START_NOT_STICKY // one transfer at a time

        job = scope.launch {
            try {
                transfer(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Map transfer failed")
                fail(file.name, friendly(e))
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── Transfer logic ────────────────────────────────────────────────────────

    private suspend fun transfer(file: File) {
        val name = file.name
        val total = file.length()
        if (!MapTransferProtocol.isSafeMapName(name)) error(getString(R.string.map_err_bad_name, name))
        setWorking(name, MapTransferState.Phase.ASKING_WATCH, 0, total, 0)

        val node = Wearable.getNodeClient(this).connectedNodes.await().firstOrNull()
            ?: return fail(name, getString(R.string.map_err_not_connected))
        val messages = Wearable.getMessageClient(this)

        // 1. How much of this file does the watch already have?
        val answer = CompletableDeferred<Long>().also { pendingAnswer = it }
        val reply: Long? = try {
            messages.sendMessage(node.id, MapTransferProtocol.RESUME_QUERY, MapTransferProtocol.encode(name, total)).await()
            withTimeoutOrNull(RESUME_ANSWER_TIMEOUT_MS) { answer.await() }
        } finally {
            pendingAnswer = null
        }
        val offset = reply ?: return fail(
            name,
            getString(R.string.map_err_no_answer),
        )
        if (offset == MapTransferProtocol.NO_SPACE) return fail(name, getString(R.string.map_err_no_space))
        val start = if (offset in 1 until total) offset else 0L
        Timber.i("Sending %s: %d of %d bytes are already on the watch", name, start, total)

        // 2a. Fast path: the watch downloads the rest from the phone over the local Wi-Fi.
        val lan = if (com.askew137.gcwatch.mobile.BuildConfig.LAN_TEST_HOST.isNotEmpty()) "test" else lanAddress()
        note = ""
        if (lan != null && viaWifi(file, total, start, node.id, lan)) return
        viaWifiMode = false
        if (lan == null) note = getString(R.string.map_note_no_wifi)
        Timber.i("Wi-Fi download not possible (%s): using the Data Layer channel", if (lan == null) "no Wi-Fi on the phone" else "the watch could not reach the phone")

        // 2b. Slow path: stream through the Data Layer channel.
        val stored = CompletableDeferred<String>().also { pendingStored = it }
        val channelClient = Wearable.getChannelClient(this)
        val channel = withTimeout(20_000) {
            channelClient.openChannel(node.id, MapTransferProtocol.channelPath(name, start, total)).await()
        }
        try {
            stream(file, start, total, channelClient, channel)
            // 3. The watch says when the complete file is in place.
            setWorking(name, MapTransferState.Phase.CONFIRMING, total, total, 0)
            val result = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { stored.await() }
                ?: return fail(name, getString(R.string.map_err_no_confirm))
            if (result == "ok") {
                Timber.i("Map transfer complete: %s", name)
                if (!cancelled()) _state.value = MapTransferState.Done(name, total)
                finishNotification(name, getString(R.string.notif_map_sent))
            } else {
                fail(name, getString(R.string.map_err_incomplete))
            }
        } finally {
            pendingStored = null
            runCatching { withContext(NonCancellable) { withTimeout(2_000) { channelClient.close(channel).await() } } }
        }
    }

    private fun lanAddress(): String? = LanAddress.find(this)

    /**
     * Offers the file to the watch as a local HTTP download. Returns true when this path has decided the
     * outcome (done or failed, state already set), false when the watch could not reach the phone so the
     * caller should fall back to the channel.
     */
    private suspend fun viaWifi(file: File, total: Long, start: Long, nodeId: String, lan: String): Boolean {
        val name = file.name
        val token = LanAddress.token()
        val server = MapHttpServer(file, token)
        val stored = CompletableDeferred<String>().also { pendingStored = it }
        viaWifiMode = true
        try {
            val testHost = com.askew137.gcwatch.mobile.BuildConfig.LAN_TEST_HOST
            val port = server.start(testHost.substringAfter(':', "0").toIntOrNull() ?: 0)
            val host = if (testHost.isNotEmpty()) testHost.substringBefore(':') else lan
            val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val url = "http://$host:$port/$token/$encoded"
            Wearable.getMessageClient(this).sendMessage(
                nodeId, MapTransferProtocol.HTTP_OFFER, MapTransferProtocol.encode(name, total, start, url),
            ).await()
            setWorking(name, MapTransferState.Phase.SENDING, start, total, 0)

            val began = SystemClock.elapsedRealtime()
            var windowAt = began
            var windowBytes = server.servedUpTo.get().coerceAtLeast(start)
            var speed = 0L
            while (!stored.isCompleted) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                if (!server.contacted && now - began > WIFI_CONNECT_MS) {
                    note = getString(R.string.map_note_no_connect, "$lan:$port", (WIFI_CONNECT_MS / 1000).toInt())
                    return false
                }
                val served = server.servedUpTo.get().coerceAtLeast(start)
                if (now - windowAt >= 2_000) {
                    speed = (served - windowBytes) * 1000 / (now - windowAt)
                    windowAt = now
                    windowBytes = served
                }
                if (served >= total) {
                    setWorking(name, MapTransferState.Phase.CONFIRMING, total, total, 0)
                } else {
                    setWorking(name, MapTransferState.Phase.SENDING, served, total, speed)
                    notify(name, (served * 100 / total).toInt(), getString(R.string.notif_map_sending))
                    val idle = System.nanoTime() / 1_000_000 - server.lastActivityMs.get()
                    if (server.contacted && idle > STALL_MS) {
                        fail(name, getString(R.string.map_err_stall_wifi, (STALL_MS / 1000).toInt()))
                        return true
                    }
                }
                if (now - began > WIFI_CONFIRM_MAX_MS) break
            }
            return when (val result = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { stored.await() }) {
                "ok" -> {
                    Timber.i("Map transfer complete over Wi-Fi: %s", name)
                    if (!cancelled()) _state.value = MapTransferState.Done(name, total)
                    finishNotification(name, getString(R.string.notif_map_sent))
                    true
                }
                null -> { fail(name, getString(R.string.map_err_no_confirm)); true }
                else -> if (result.startsWith("unreachable")) {
                    note = getString(R.string.map_note_wifi_reason, result.removePrefix("unreachable").removePrefix(":").ifBlank { getString(R.string.map_note_unreachable) })
                    false
                } else {
                    fail(name, getString(R.string.map_err_incomplete))
                    true
                }
            }
        } finally {
            server.stop()
            pendingStored = null
        }
    }

    private suspend fun stream(file: File, start: Long, total: Long, client: ChannelClient, channel: ChannelClient.Channel) {
        val name = file.name
        val lastProgress = AtomicLong(SystemClock.elapsedRealtime())
        val stalled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        var outRef: java.io.OutputStream? = null
        coroutineScope {
            // A blocked write can only be ended by closing the channel: the watchdog does that when
            // no byte moves for STALL_MS, or when the transfer is cancelled.
            val watchdog = launch {
                try {
                    while (isActive) {
                        delay(2_000)
                        if (SystemClock.elapsedRealtime() - lastProgress.get() > STALL_MS) {
                            stalled.set(true)
                            break
                        }
                    }
                } finally {
                    if (!finished.get()) withContext(NonCancellable) {
                        runCatching { outRef?.close() }
                        runCatching { withTimeout(2_000) { client.close(channel).await() } }
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    client.getOutputStream(channel).await().also { outRef = it }.use { out ->
                        file.inputStream().use { input ->
                            var skipped = 0L
                            while (skipped < start) {
                                val n = input.skip(start - skipped)
                                if (n <= 0) throw IOException(getString(R.string.map_err_seek, file.name))
                                skipped += n
                            }
                            val buffer = ByteArray(BUFFER)
                            var sent = start
                            var windowStart = SystemClock.elapsedRealtime()
                            var windowBytes = 0L
                            var speed = 0L
                            var lastUi = 0L
                            while (true) {
                                // A blocked channel write cannot be interrupted, and closing the channel
                                // did not end it: so look at the cancellation between the chunks.
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n == -1) break
                                out.write(buffer, 0, n)
                                sent += n
                                windowBytes += n
                                val now = SystemClock.elapsedRealtime()
                                lastProgress.set(now)
                                // Speed over a sliding ~3 s window: smooth, but reacts to a stall.
                                if (now - windowStart >= 3_000) {
                                    speed = windowBytes * 1000 / (now - windowStart)
                                    windowStart = now
                                    windowBytes = 0
                                } else if (speed == 0L && now - windowStart >= 1_000) {
                                    speed = windowBytes * 1000 / (now - windowStart)
                                }
                                if (now - lastUi >= UI_INTERVAL_MS) {
                                    lastUi = now
                                    setWorking(name, MapTransferState.Phase.SENDING, sent, total, speed)
                                    notify(name, (sent * 100 / total).toInt(), getString(R.string.notif_map_sending))
                                }
                            }
                        }
                    }
                }
                finished.set(true)
            } catch (e: IOException) {
                if (stalled.get()) {
                    throw IOException(getString(R.string.map_err_stall, (STALL_MS / 1000).toInt()))
                }
                throw e
            } finally {
                finished.set(true)
                watchdog.cancel()
            }
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            e is kotlinx.coroutines.TimeoutCancellationException -> getString(R.string.map_err_timeout)
            "Channel closed" in m -> getString(R.string.map_err_interrupted, m)
            else -> m
        }
    }

    /** A cancelled transfer may still be stuck in a blocked channel write: it must not touch the state any more. */
    private fun cancelled() = job?.isCancelled == true

    private fun fail(name: String, message: String) {
        if (cancelled()) return
        _state.value = MapTransferState.Failed(name, message)
        finishNotification(name, getString(R.string.notif_map_failed))
    }

    private var viaWifiMode = false
    private var note = ""

    private fun setWorking(name: String, phase: MapTransferState.Phase, sent: Long, total: Long, speed: Long) {
        if (cancelled()) return
        _state.value = MapTransferState.Working(name, phase, sent, total, speed, note, viaWifiMode)
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_map_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notif_map_channel_desc)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private var lastNotify = 0L

    /** At most once a second: a notification update per 8 KB chunk slowed the transfer itself. */
    private fun notify(fileName: String, percent: Int, title: String) {
        if (cancelled()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 1_000) return
        lastNotify = now
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification(fileName, percent, title))
    }

    private fun finishNotification(fileName: String, title: String) {
        if (cancelled()) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification(fileName, null, title, ongoing = false))
    }

    private fun notification(fileName: String, percent: Int?, title: String, ongoing: Boolean = true) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(fileName)
            .apply { if (percent != null && ongoing) setProgress(100, percent, false) }
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "map_transfer"
        private const val NOTIF_ID = 1001
        private const val EXTRA_MAP_URI = "extra_map_uri"
        private const val ACTION_CANCEL = "com.askew137.gcwatch.mobile.CANCEL_MAP_TRANSFER"

        /** 64 KB: the old 8 KB chunks (each with its own notification update) made the transfer far slower. */
        private const val BUFFER = 64 * 1024
        private const val UI_INTERVAL_MS = 300L
        private const val STALL_MS = 30_000L
        private const val RESUME_ANSWER_TIMEOUT_MS = 25_000L
        private const val WIFI_CONNECT_MS = 20_000L
        private const val WIFI_CONFIRM_MAX_MS = 6L * 3600 * 1000
        private const val CONFIRM_TIMEOUT_MS = 180_000L

        private val _state = MutableStateFlow<MapTransferState>(MapTransferState.Idle)
        val state: StateFlow<MapTransferState> = _state.asStateFlow()

        @Volatile private var pendingAnswer: CompletableDeferred<Long>? = null
        @Volatile private var pendingStored: CompletableDeferred<String>? = null

        /** The watch told how many bytes of the file it already has (called by MobileMessageService). */
        fun onResumeAnswer(payload: ByteArray) {
            MapTransferProtocol.decode(payload)?.let { pendingAnswer?.complete(it.second) }
        }

        /** The watch finished receiving: `ok` or `incomplete` (called by MobileMessageService). */
        fun onStored(payload: ByteArray) {
            MapTransferProtocol.decode(payload)?.let { pendingStored?.complete(it.third) }
        }

        /** Hides a finished/failed result once the user has seen it. */
        fun dismissResult() {
            if (_state.value !is MapTransferState.Working) _state.value = MapTransferState.Idle
        }

        fun startTransfer(context: Context, mapFile: Uri) {
            _state.value = MapTransferState.Working(
                mapFile.lastPathSegment ?: context.getString(R.string.map_default_name), MapTransferState.Phase.ASKING_WATCH,
            )
            val intent = Intent(context, MapTransferService::class.java).apply {
                putExtra(EXTRA_MAP_URI, mapFile.toString())
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, MapTransferService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
