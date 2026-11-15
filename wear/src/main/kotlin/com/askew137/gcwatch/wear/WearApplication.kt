package com.askew137.gcwatch.wear

import android.app.Application
import com.askew137.gcwatch.wear.config.ConfigRepository
import com.askew137.gcwatch.wear.navigation.NavigationRepository
import com.askew137.gcwatch.core.communication.ApkInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber
import java.io.File

class WearApplication : Application() {

    /**
     * Process-wide singletons. The WearableListenerService (which receives messages from the
     * phone) and the UI ViewModels MUST share the same instances, otherwise incoming targets,
     * routes and config updates never reach the UI.
     */
    val navigationRepository: NavigationRepository by lazy { NavigationRepository(this) }
    val configRepository: ConfigRepository by lazy { ConfigRepository(this) }

    /** One GPS for the whole process (UI and the navigation service share it). */
    val gnssRepository: com.askew137.gcwatch.wear.gnss.GnssRepository by lazy {
        com.askew137.gcwatch.wear.gnss.GnssRepository(this)
    }

    /** A newer watch APK that the phone sent; the UI offers to install it. */
    val pendingUpdate = MutableStateFlow<File?>(null)

    /** Bumped when a map file arrives, so the map screen re-reads the list without a restart. */
    val mapsRevision = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        restorePendingUpdate()
        Thread { com.askew137.gcwatch.wear.navigation.MapReceiver(this).cleanupStale() }.start()
    }

    /** The process may have been restarted between receiving the APK and the user's tap. */
    private fun restorePendingUpdate() {
        val apk = File(filesDir, "updates/wear.apk")
        if (!apk.exists()) return
        if (ApkInstaller.verifyUpdate(this, apk) == null) pendingUpdate.value = apk else apk.delete()
    }
}
