package com.askew137.gcwatch.wear.navigation

import com.askew137.gcwatch.core.communication.ApkInstaller
import com.askew137.gcwatch.core.communication.MessagePaths
import com.askew137.gcwatch.wear.WearApplication
import com.askew137.gcwatch.wear.config.ConfigRepository
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import timber.log.Timber
import java.io.File

/**
 * Wearable listener service that dispatches incoming messages to the appropriate repository.
 *
 * Message routing:
 * - [MessagePaths.SET_TARGET]   -> [NavigationRepository.onSetTarget]
 * - [MessagePaths.ROUTE_RESULT] -> [NavigationRepository.onRouteResult]
 * - [MessagePaths.CONFIG_SYNC]  -> [ConfigRepository.onRemoteSync]
 * - channel `/map_transfer/<name>.map` -> stored in `filesDir/maps/`
 */
class WearMessageService : WearableListenerService() {

    // Shared with the UI via WearApplication (see there for why this must be one instance).
    private val navigationRepo: NavigationRepository
        get() = (applicationContext as WearApplication).navigationRepository
    private val configRepo: ConfigRepository
        get() = (applicationContext as WearApplication).configRepository

    override fun onMessageReceived(event: MessageEvent) {
        Timber.d("WearMessageService: received message on path=%s size=%d", event.path, event.data.size)
        when (event.path) {
            MessagePaths.SET_TARGET -> navigationRepo.onSetTarget(event.data)
            MessagePaths.ROUTE_RESULT -> navigationRepo.onRouteResult(event.data)
            MessagePaths.CONFIG_SYNC -> configRepo.onRemoteSync(event.data)
            MessagePaths.VERSION_REQUEST -> answerVersion(event.sourceNodeId)
            else -> Timber.d("WearMessageService: unhandled path %s", event.path)
        }
    }

    /** Tells the phone which version runs here, so it can warn about a mismatch. */
    private fun answerVersion(nodeId: String) {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            // The name the user gave the watch (Settings > About), else the model.
            val deviceName = (android.provider.Settings.Global.getString(contentResolver, "device_name")
                ?: android.os.Build.MODEL).replace('|', ' ')
            val payload = "${info.longVersionCode}|${info.versionName}|$deviceName".toByteArray(Charsets.UTF_8)
            Tasks.await(Wearable.getMessageClient(this).sendMessage(nodeId, MessagePaths.VERSION_RESULT, payload))
        } catch (e: Exception) {
            Timber.e(e, "WearMessageService: could not answer the version request")
        }
    }

    /** Receives a Mapsforge `.map` file streamed from the phone's MapTransferService. */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val path = channel.path
        if (path == MessagePaths.CHANNEL_UPDATE_WEAR_APK) {
            receiveUpdate(channel)
            return
        }
        if (!path.startsWith(MAP_TRANSFER_PREFIX)) {
            Timber.d("WearMessageService: ignoring channel %s", path)
            return
        }

        // Keep only the file name; never trust the remote path for directory traversal.
        val fileName = File(path.removePrefix(MAP_TRANSFER_PREFIX)).name
        if (!fileName.endsWith(".map")) {
            Timber.w("WearMessageService: rejecting non-.map transfer %s", path)
            return
        }

        val mapsDir = File(filesDir, "maps").apply { mkdirs() }
        val tmp = File(mapsDir, "$fileName.part")
        val dest = File(mapsDir, fileName)
        val client = Wearable.getChannelClient(this)

        try {
            Timber.i("WearMessageService: receiving map %s", fileName)
            val input = Tasks.await(client.getInputStream(channel))
            var total = 0L
            input.use { src ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) {
                Timber.i("WearMessageService: map %s received (%d bytes)", fileName, total)
                (applicationContext as WearApplication).mapsRevision.value++
            } else {
                Timber.e("WearMessageService: could not move %s into place", fileName)
            }
        } catch (e: Exception) {
            Timber.e(e, "WearMessageService: map transfer failed for %s", fileName)
            tmp.delete()
        } finally {
            runCatching { Tasks.await(client.close(channel)) }
        }
    }

    /** Stores the new watch APK from the phone and lets the UI offer the installation. */
    private fun receiveUpdate(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        val dir = File(filesDir, "updates").apply { mkdirs() }
        val tmp = File(dir, "wear.apk.part")
        val dest = File(dir, "wear.apk")
        try {
            Timber.i("WearMessageService: receiving update APK")
            val input = Tasks.await(client.getInputStream(channel))
            var total = 0L
            input.use { src ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        total += n
                        if (total > MAX_UPDATE_BYTES) throw IllegalStateException("update too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            val problem = ApkInstaller.verifyUpdate(this, tmp)
            when {
                problem == null -> {
                    if (dest.exists()) dest.delete()
                    tmp.renameTo(dest)
                    (applicationContext as WearApplication).pendingUpdate.value = dest
                    Timber.i("WearMessageService: update APK stored (%d bytes)", total)
                }
                problem.startsWith("not newer") -> {
                    // Nothing to install, but let the phone continue with its own update.
                    tmp.delete()
                    Timber.i("WearMessageService: watch is already up to date (%s)", problem)
                }
                else -> {
                    tmp.delete()
                    Timber.w("WearMessageService: update APK rejected: %s", problem)
                    return // no acknowledgement: the phone reports a failed update
                }
            }
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(channel.nodeId, MessagePaths.UPDATE_WEAR_RECEIVED, ByteArray(0))
            )
        } catch (e: Exception) {
            Timber.e(e, "WearMessageService: update transfer failed")
            tmp.delete()
        } finally {
            runCatching { Tasks.await(client.close(channel)) }
        }
    }

    private companion object {
        const val MAX_UPDATE_BYTES = 100L * 1024 * 1024
        const val MAP_TRANSFER_PREFIX = "/map_transfer/"
    }
}
