package com.askew137.gcwatch.mobile.update

import android.content.Context
import com.askew137.gcwatch.core.communication.ApkInstaller
import com.askew137.gcwatch.core.communication.MessagePaths
import com.askew137.gcwatch.mobile.maps.LanAddress
import com.askew137.gcwatch.mobile.maps.MapHttpServer
import com.askew137.gcwatch.mobile.ui.MobileApplication
import android.os.SystemClock
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One-button update of phone AND watch from a GitHub release:
 *
 *  1. download both APKs,
 *  2. send the watch APK to the watch (it asks the user to install it there),
 *  3. hand the phone APK to the system installer (the user confirms).
 *
 * The watch goes first: installing the phone app restarts it and would cut the transfer.
 * Android only accepts an update signed with the same key, so a tampered APK is rejected by
 * the installer on both devices.
 */
object AppUpdater {

    sealed interface State {
        data object Idle : State
        /** [percent] < 0: unknown. */
        data class Working(val step: String, val percent: Int = -1) : State
        /** Phone installer dialog is open; the app restarts after the user confirms. */
        data object AwaitingConfirmation : State
        /** Watch-only update: the watch now asks the user to install. */
        data object AwaitingWatchConfirmation : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var watchAck: CompletableDeferred<Unit>? = null

    @Volatile
    private var job: Job? = null

    /** The watch could not reach the phone over Wi-Fi (reason inside); the phone then uses the slow channel. */
    @Volatile
    private var lanFailure: CompletableDeferred<String>? = null

    fun onWatchLanFailed(reason: String) {
        lanFailure?.complete(reason)
    }

    /** The watch gets this long without a single byte moving before the transfer is given up. */
    private const val STALL_MS = 30_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Stops a running update (download or transfer to the watch). */
    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value is State.Working) _state.value = State.Idle
    }

    fun reset() {
        if (_state.value !is State.Working) _state.value = State.Idle
    }

    fun fail(message: String) {
        _state.value = State.Failed(message)
    }

    /** The watch confirmed that it received and stored the APK. */
    fun onWatchAck() {
        watchAck?.complete(Unit)
    }

    fun start(app: MobileApplication, info: UpdateInfo) {
        if (_state.value is State.Working) return
        val mobileUrl = info.mobileApkUrl
        val wearUrl = info.wearApkUrl
        if (mobileUrl == null || wearUrl == null) {
            _state.value = State.Failed("The release has no APK files")
            return
        }
        launch(app, mobileUrl, wearUrl)
    }

    /**
     * Brings phone and watch to the same version: updates only the side that is behind, using
     * the release [info] of the newer version.
     */
    fun catchUp(app: MobileApplication, info: UpdateInfo, updateWatch: Boolean, updatePhone: Boolean) {
        if (_state.value is State.Working) return
        val mobileUrl = if (updatePhone) info.mobileApkUrl else null
        val wearUrl = if (updateWatch) info.wearApkUrl else null
        if ((updatePhone && mobileUrl == null) || (updateWatch && wearUrl == null) || (mobileUrl == null && wearUrl == null)) {
            _state.value = State.Failed("The release has no APK files")
            return
        }
        launch(app, mobileUrl, wearUrl)
    }

    private fun launch(app: MobileApplication, mobileUrl: String?, wearUrl: String?) {
        _state.value = State.Working("Starting")
        job = app.appScope.launch {
            try {
                run(app, mobileUrl, wearUrl)
            } catch (e: CancellationException) {
                throw e // cancel() already reset the state
            } catch (e: Exception) {
                Timber.e(e, "Update failed")
                _state.value = State.Failed(friendly(e))
            }
        }
    }

    /** A null URL skips that side. The watch goes first (installing the phone app restarts it). */
    private suspend fun run(app: Context, mobileUrl: String?, wearUrl: String?) {
        val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val mobile = File(dir, "mobile.apk")
        val wear = File(dir, "wear.apk")

        if (mobileUrl != null) download(mobileUrl, mobile, "Downloading phone app")
        if (wearUrl != null) download(wearUrl, wear, "Downloading watch app")

        if (mobileUrl != null) {
            ApkInstaller.verifyUpdate(app, mobile)?.let { error("Phone APK rejected: $it") }
        }
        if (wearUrl != null) {
            val wearInfo = app.packageManager.getPackageArchiveInfo(wear.absolutePath, 0)
            if (wearInfo?.packageName != app.packageName) error("Watch APK has the wrong package")
            _state.value = State.Working("Sending to the watch")
            sendToWatch(app, wear)
        }

        if (mobileUrl != null) {
            _state.value = State.AwaitingConfirmation
            ApkInstaller.install(app, mobile)
        } else {
            _state.value = State.AwaitingWatchConfirmation
        }
    }

    private fun download(url: String, target: File, step: String) {
        _state.value = State.Working(step, 0)
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
            val body = response.body ?: error("Empty download")
            val total = body.contentLength()
            var written = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        written += n
                        val percent = if (total > 0) (written * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = State.Working(step, percent)
                        }
                    }
                }
            }
            if (total > 0 && written != total) error("Incomplete download")
        }
    }

    /** Turns Data Layer / timeout exceptions into something a user can act on. */
    private fun friendly(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            "Channel closed" in m || e is IOException ->
                "The transfer to the watch was interrupted ($m). Open GC Watch on the watch, keep the watch awake and close to the phone, and try again."
            e is kotlinx.coroutines.TimeoutCancellationException ->
                "The watch did not confirm the file in time. Open GC Watch on the watch and try again."
            else -> m
        }
    }

    /**
     * Fast path: the phone serves the APK on its Wi-Fi address and the watch downloads it directly (the
     * Data Layer channel moves only a few KB/s). Returns true when the watch confirmed the file; false when
     * it could not reach the phone, so the caller falls back to the channel.
     */
    private suspend fun viaLan(context: Context, apk: File, nodeId: String, ack: CompletableDeferred<Unit>): Boolean {
        val lan = LanAddress.find(context) ?: return false
        val failure = CompletableDeferred<String>().also { lanFailure = it }
        val token = LanAddress.token()
        val server = MapHttpServer(apk, token)
        try {
            val port = server.start()
            val url = "http://$lan:$port/$token/wear.apk"
            Wearable.getMessageClient(context).sendMessage(
                nodeId, MessagePaths.UPDATE_HTTP_OFFER, "${apk.length()}|$url".toByteArray(Charsets.UTF_8),
            ).await()
            val total = apk.length().coerceAtLeast(1)
            val began = SystemClock.elapsedRealtime()
            _state.value = State.Working("Sending to the watch (Wi-Fi)", 0)
            while (!ack.isCompleted) {
                delay(500)
                if (failure.isCompleted) {
                    Timber.i("Update over Wi-Fi not possible: %s", failure.getCompleted())
                    return false
                }
                val now = SystemClock.elapsedRealtime()
                if (!server.contacted && now - began > 20_000) {
                    Timber.i("The watch did not connect to the phone over Wi-Fi within 20 s")
                    return false
                }
                val served = server.servedUpTo.get()
                _state.value = State.Working("Sending to the watch (Wi-Fi)", (served * 100 / total).toInt().coerceIn(0, 100))
                val idle = System.nanoTime() / 1_000_000 - server.lastActivityMs.get()
                if (server.contacted && served < total && idle > STALL_MS) {
                    error("No data moved for ${STALL_MS / 1000} s over Wi-Fi: keep the watch awake and on the same Wi-Fi, then try again")
                }
                if (now - began > 10 * 60_000L) error("The watch did not confirm the update in time")
            }
            return true
        } finally {
            server.stop()
            lanFailure = null
        }
    }

    private suspend fun sendToWatch(context: Context, apk: File) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val node = nodes.firstOrNull() ?: error("The watch is not connected")

        val ack = CompletableDeferred<Unit>().also { watchAck = it }
        if (try { viaLan(context, apk, node.id, ack) } catch (e: CancellationException) { throw e }) {
            watchAck = null
            return
        }
        val channelClient = Wearable.getChannelClient(context)
        val channel = withTimeout(20_000) {
            channelClient.openChannel(node.id, MessagePaths.CHANNEL_UPDATE_WEAR_APK).await()
        }
        val total = apk.length().coerceAtLeast(1)
        val lastProgress = AtomicLong(System.currentTimeMillis())
        val stalled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        _state.value = State.Working("Sending to the watch", 0)
        try {
            coroutineScope {
                // A blocked write cannot be interrupted, only ended by closing the channel: the
                // watchdog does that when no data moves for STALL_MS or when the update is cancelled.
                val watchdog = launch {
                    try {
                        while (isActive) {
                            delay(2_000)
                            if (System.currentTimeMillis() - lastProgress.get() > STALL_MS) {
                                stalled.set(true)
                                break
                            }
                        }
                    } finally {
                        if (!finished.get()) withContext(NonCancellable) {
                            runCatching { withTimeout(2_000) { channelClient.close(channel).await() } }
                        }
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        channelClient.getOutputStream(channel).await().use { out ->
                            apk.inputStream().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                var written = 0L
                                var lastPercent = -1
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    out.write(buffer, 0, n)
                                    written += n
                                    lastProgress.set(System.currentTimeMillis())
                                    val percent = (written * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        _state.value = State.Working("Sending to the watch", percent)
                                    }
                                }
                            }
                        }
                    }
                    finished.set(true)
                    // The watch answers once the file is stored; only then is it safe to close.
                    _state.value = State.Working("Waiting for the watch to confirm")
                    withTimeout(60_000) { ack.await() }
                } catch (e: IOException) {
                    if (stalled.get()) {
                        error("The watch is not receiving the file (no data moved for ${STALL_MS / 1000} s). " +
                            "Open GC Watch on the watch, keep it awake and close to the phone, and try again.")
                    }
                    throw e
                } finally {
                    finished.set(true)
                    watchdog.cancel()
                }
            }
        } finally {
            watchAck = null
            runCatching { withContext(NonCancellable) { withTimeout(2_000) { channelClient.close(channel).await() } } }
        }
    }
}
