package com.askew137.gcwatch.wear.gnss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.askew137.gcwatch.core.model.GpsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

private const val PHONE_TIMEOUT_MS = 10_000L

/**
 * Process-wide GPS source: arbitrates between [GpsSource.WATCH_HARDWARE] and
 * [GpsSource.PHONE_FUSED], automatically falling back to hardware if phone location
 * stops arriving for more than 10 seconds.
 *
 * It was tied to the activity before: every activity creation started another never-stopped GPS
 * flow, and nothing kept the process alive with the screen off. Now there is one instance
 * ([com.askew137.gcwatch.wear.WearApplication.gnssRepository]) that runs only while somebody needs it:
 * the visible UI and/or the foreground [com.askew137.gcwatch.wear.navigation.NavigationService]
 * (see [setActive]). Call everything from the main thread.
 */
class GnssRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val hardwareManager = HardwareGnssManager(context)
    private val phoneReceiver = PhoneGnssReceiver(context)

    private val _source = MutableStateFlow(GpsSource.WATCH_HARDWARE)
    val source: StateFlow<GpsSource> = _source.asStateFlow()

    private val _location = MutableStateFlow<WatchLocation?>(null)
    val location: StateFlow<WatchLocation?> = _location.asStateFlow()

    /** Ambient (screen dimmed): the picture only refreshes about once a minute, so ask the GPS less often. */
    private var ambient = false

    /** False while the screen is off / the app is in the background: nobody looks at the map. */
    private var uiVisible = true

    private val owners = mutableSetOf<String>()
    private val running get() = owners.isNotEmpty()

    private var activeJob: Job? = null
    private var watchdogJob: Job? = null

    /**
     * Declares that [owner] ("ui", "service", ...) needs (or no longer needs) the GPS. The GPS runs
     * while at least one owner needs it, and is switched off when the last one lets go.
     */
    fun setActive(owner: String, needed: Boolean) {
        val before = running
        if (needed) owners.add(owner) else owners.remove(owner)
        when {
            running && !before -> restart()
            !running && before -> stopFlows()
        }
    }

    /** Ambient mode: dimmed screen, GPS every [com.askew137.gcwatch.wear.ui.UiPrefs.ambientGpsSeconds]. */
    fun setAmbient(value: Boolean) {
        if (ambient == value) return
        ambient = value
        refreshInterval()
    }

    /** Screen off / app in background (but a foreground service may still need the GPS): save battery. */
    fun setUiVisible(value: Boolean) {
        if (uiVisible == value) return
        uiVisible = value
        refreshInterval()
    }

    /** The location permission was granted (or revoked) after the repository started. */
    fun onPermissionChanged() {
        if (running) restart()
    }

    private fun refreshInterval() {
        if (running && _source.value == GpsSource.WATCH_HARDWARE) restart()
    }

    /** Cancel the previous flow and start a new one for [src]. */
    fun switchSource(src: GpsSource) {
        Timber.d("GnssRepository: switching source to %s", src)
        stopFlows()
        _source.value = src
        if (running) restart()
    }

    private fun stopFlows() {
        activeJob?.cancel()
        activeJob = null
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun restart() {
        stopFlows()
        when (_source.value) {
            GpsSource.WATCH_HARDWARE -> startHardwareFlow()
            GpsSource.PHONE_FUSED -> startPhoneFlow()
        }
    }

    private fun startHardwareFlow() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Timber.w("GnssRepository: ACCESS_FINE_LOCATION not granted; cannot start hardware GPS")
            return
        }

        // Fix interval of the watch GPS: every second normally, every few seconds while dimmed / screen off.
        val lowPower = ambient || !uiVisible
        val intervalMs = if (lowPower) com.askew137.gcwatch.wear.ui.UiPrefs.ambientGpsSeconds(context) * 1_000L else 1_000L
        activeJob = scope.launch {
            @Suppress("MissingPermission")
            hardwareManager.locationFlow(intervalMs = intervalMs).collect { loc ->
                _location.value = WatchLocation.fromAndroidLocation(loc)
            }
        }
    }

    private fun startPhoneFlow() {
        activeJob = scope.launch {
            phoneReceiver.locationFlow().collect { loc ->
                _location.value = loc
                // Reset watchdog on every received update
                watchdogJob?.cancel()
                watchdogJob = scope.launch {
                    delay(PHONE_TIMEOUT_MS)
                    Timber.w("GnssRepository: phone location timed out; falling back to WATCH_HARDWARE")
                    switchSource(GpsSource.WATCH_HARDWARE)
                }
            }
        }

        // Also start the watchdog timer immediately in case no updates ever arrive
        watchdogJob = scope.launch {
            delay(PHONE_TIMEOUT_MS)
            Timber.w("GnssRepository: phone location never received; falling back to WATCH_HARDWARE")
            switchSource(GpsSource.WATCH_HARDWARE)
        }
    }
}
