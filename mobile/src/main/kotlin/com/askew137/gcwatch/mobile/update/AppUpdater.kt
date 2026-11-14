package com.askew137.gcwatch.mobile.update

import android.content.Context
import com.askew137.gcwatch.core.communication.ApkInstaller
import com.askew137.gcwatch.core.communication.MessagePaths
import com.askew137.gcwatch.mobile.ui.MobileApplication
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

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
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var watchAck: CompletableDeferred<Unit>? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun reset() {
        if (_state.value !is State.Working) _state.value = State.Idle
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
        _state.value = State.Working("Starting")
        app.appScope.launch {
            try {
                run(app, mobileUrl, wearUrl)
            } catch (e: Exception) {
                Timber.e(e, "Update failed")
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun run(app: Context, mobileUrl: String, wearUrl: String) {
        val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val mobile = File(dir, "mobile.apk")
        val wear = File(dir, "wear.apk")

        download(mobileUrl, mobile, "Downloading phone app")
        download(wearUrl, wear, "Downloading watch app")

        ApkInstaller.verifyUpdate(app, mobile)?.let { error("Phone APK rejected: $it") }
        val wearInfo = app.packageManager.getPackageArchiveInfo(wear.absolutePath, 0)
        if (wearInfo?.packageName != app.packageName) error("Watch APK has the wrong package")

        _state.value = State.Working("Sending to the watch")
        sendToWatch(app, wear)

        _state.value = State.AwaitingConfirmation
        ApkInstaller.install(app, mobile)
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

    private suspend fun sendToWatch(context: Context, apk: File) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val node = nodes.firstOrNull() ?: error("The watch is not connected")

        val ack = CompletableDeferred<Unit>().also { watchAck = it }
        val channelClient = Wearable.getChannelClient(context)
        val channel = channelClient.openChannel(node.id, MessagePaths.CHANNEL_UPDATE_WEAR_APK).await()
        try {
            channelClient.getOutputStream(channel).await().use { out ->
                apk.inputStream().use { it.copyTo(out) }
            }
            // The watch answers once the file is stored; only then is it safe to close.
            withTimeout(60_000) { ack.await() }
        } finally {
            watchAck = null
            runCatching { withTimeout(2_000) { channelClient.close(channel).await() } }
        }
    }
}
