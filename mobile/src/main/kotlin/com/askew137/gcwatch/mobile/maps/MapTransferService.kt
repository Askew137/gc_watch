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
    ) : MapTransferState {
        val percent: Int get() = if (totalBytes > 0) (sentBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else 0

        /** Seconds left at the current speed; null while unknown. */
        val secondsLeft: Long? get() = if (bytesPerSec > 0 && totalBytes > sentBytes) (totalBytes - sentBytes) / bytesPerSec else null
    }

    /** The watch confirmed that the complete file is stored. */
    data class Done(val fileName: String, val totalBytes: Long) : MapTransferState

    /** Tapping "Retry" resumes where the watch stopped, not from the beginning. */
    data class Failed(val fileName: String, val message: String) : MapTransferState

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
            job?.cancel()
            job = null
            if (_state.value is MapTransferState.Working) _state.value = MapTransferState.Idle
            stopSelf()
            return START_NOT_STICKY
        }

        val uriStr = intent?.getStringExtra(EXTRA_MAP_URI)
        val file = uriStr?.let { Uri.parse(it).path }?.let(::File)
        if (file == null || !file.isFile) {
            Timber.e("MapTransferService started without a usable file: %s", uriStr)
            _state.value = MapTransferState.Failed(file?.name ?: "map", "The map file is missing on the phone")
            startForeground(NOTIF_ID, notification(file?.name ?: "map", null, "Map transfer failed"))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, notification(file.name, 0, "Sending map to the watch"))
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
        if (!MapTransferProtocol.isSafeMapName(name)) error("The file name \"$name\" is not a valid .map name")
        setWorking(name, MapTransferState.Phase.ASKING_WATCH, 0, total, 0)

        val node = Wearable.getNodeClient(this).connectedNodes.await().firstOrNull()
            ?: return fail(name, "The watch is not connected")
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
            "The watch did not answer. Open GC Watch on the watch (it must run the same version as the phone) and try again.",
        )
        if (offset == MapTransferProtocol.NO_SPACE) return fail(name, "There is not enough free space on the watch for this map")
        val start = if (offset in 1 until total) offset else 0L
        Timber.i("Sending %s: %d of %d bytes are already on the watch", name, start, total)

        // 2. Stream the rest.
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
                ?: return fail(name, "The watch did not confirm the file. Open GC Watch on the watch and tap Retry (it resumes).")
            if (result == "ok") {
                Timber.i("Map transfer complete: %s", name)
                _state.value = MapTransferState.Done(name, total)
                finishNotification(name, "Map sent to the watch")
            } else {
                fail(name, "The file did not arrive completely. Tap Retry: it resumes where it stopped.")
            }
        } finally {
            pendingStored = null
            runCatching { withContext(NonCancellable) { withTimeout(2_000) { channelClient.close(channel).await() } } }
        }
    }

    private suspend fun stream(file: File, start: Long, total: Long, client: ChannelClient, channel: ChannelClient.Channel) {
        val name = file.name
        val lastProgress = AtomicLong(SystemClock.elapsedRealtime())
        val stalled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
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
                        runCatching { withTimeout(2_000) { client.close(channel).await() } }
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    client.getOutputStream(channel).await().use { out ->
                        file.inputStream().use { input ->
                            var skipped = 0L
                            while (skipped < start) {
                                val n = input.skip(start - skipped)
                                if (n <= 0) throw IOException("cannot seek in ${file.name}")
                                skipped += n
                            }
                            val buffer = ByteArray(BUFFER)
                            var sent = start
                            var windowStart = SystemClock.elapsedRealtime()
                            var windowBytes = 0L
                            var speed = 0L
                            var lastUi = 0L
                            while (true) {
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
                                    notify(name, (sent * 100 / total).toInt(), "Sending map to the watch")
                                }
                            }
                        }
                    }
                }
                finished.set(true)
            } catch (e: IOException) {
                if (stalled.get()) {
                    throw IOException("No data moved for ${STALL_MS / 1000} s: the connection to the watch is too weak or the watch app stopped")
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
            e is kotlinx.coroutines.TimeoutCancellationException -> "The watch did not respond in time. Open GC Watch on the watch and try again."
            "Channel closed" in m -> "The connection to the watch was interrupted ($m). Tap Retry: it resumes where it stopped."
            else -> m
        }
    }

    private fun fail(name: String, message: String) {
        _state.value = MapTransferState.Failed(name, message)
        finishNotification(name, "Map transfer failed")
    }

    private fun setWorking(name: String, phase: MapTransferState.Phase, sent: Long, total: Long, speed: Long) {
        _state.value = MapTransferState.Working(name, phase, sent, total, speed)
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Map Transfer", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows progress while sending map files to the watch"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private var lastNotify = 0L

    /** At most once a second: a notification update per 8 KB chunk slowed the transfer itself. */
    private fun notify(fileName: String, percent: Int, title: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 1_000) return
        lastNotify = now
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification(fileName, percent, title))
    }

    private fun finishNotification(fileName: String, title: String) {
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
        private const val RESUME_ANSWER_TIMEOUT_MS = 10_000L
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
                mapFile.lastPathSegment ?: "map", MapTransferState.Phase.ASKING_WATCH,
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
