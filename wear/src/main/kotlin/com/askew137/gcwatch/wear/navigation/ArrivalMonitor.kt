package com.askew137.gcwatch.wear.navigation

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.askew137.gcwatch.wear.WearApplication
import com.askew137.gcwatch.wear.ui.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Notices that the wearer has reached the target: the watch buzzes (also with the screen off, it needs no UI)
 * and, if the user wants it, the app shows the compass the next time it is in front ([pendingCompass]).
 *
 * It fires once per target (a new target re-arms it). Within [APPROACH_M] of the target it also asks the GPS for
 * a fix every few seconds even in the saving mode, so a cache is not walked past between two slow fixes.
 */
class ArrivalMonitor(private val app: WearApplication) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var arrivedKey: String? = null
    private var approaching = false
    private var approachStartedAt = 0L
    /** The cache the faster GPS was already used for: after the time limit it is not switched on again for it. */
    private var approachSpentKey: String? = null

    private val _pendingCompass = MutableStateFlow(false)
    /** True after an arrival when "switch to the compass" is on; the UI consumes it with [consumeCompass]. */
    val pendingCompass: StateFlow<Boolean> = _pendingCompass.asStateFlow()

    fun consumeCompass() { _pendingCompass.value = false }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(app.gnssRepository.location, app.navigationRepository.activeTarget) { loc, target -> loc to target }
                .collect { (loc, target) ->
                    if (target == null) {
                        arrivedKey = null
                        approachSpentKey = null
                        setApproaching(false)
                        return@collect
                    }
                    if (loc == null) return@collect
                    // A poor fix could put the wearer "at" the cache from a hundred metres away.
                    if (loc.accuracyM.isFinite() && loc.accuracyM > MAX_ACCURACY_M) return@collect
                    val key = "${target.lat},${target.lon}"
                    val d = distanceM(loc.lat, loc.lon, target.lat, target.lon)
                    // Approach zone with hysteresis (no restarting of the GPS when hovering around the border).
                    var zone = if (approaching) d <= APPROACH_LEAVE_M else d <= APPROACH_M
                    // A path that winds around may keep the wearer "near" as the crow flies for a long time:
                    // the faster GPS has a time limit per target.
                    if (approaching && System.currentTimeMillis() - approachStartedAt > APPROACH_MAX_MS) approachSpentKey = key
                    if (approachSpentKey == key) zone = false
                    setApproaching(zone && arrivedKey != key)
                    if (d <= ARRIVAL_M && arrivedKey != key) {
                        arrivedKey = key
                        setApproaching(false)
                        Timber.i("ArrivalMonitor: arrived at %s (%.0f m)", target.code.ifEmpty { target.label }, d)
                        buzz(app)
                        if (UiPrefs.autoCompassAtTarget(app)) _pendingCompass.value = true
                    }
                }
        }
    }

    private fun setApproaching(value: Boolean) {
        if (approaching == value) return
        approaching = value
        if (value) approachStartedAt = System.currentTimeMillis()
        Timber.i("ArrivalMonitor: approach mode %s", if (value) "on" else "off")
        app.gnssRepository.setApproaching(value)
    }

    private fun buzz(context: Context) {
        try {
            val v = context.getSystemService(Vibrator::class.java) ?: return
            // Two short and one long: clearly "you are here", unlike the single buzz of an arriving target.
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 650), -1))
        } catch (e: Exception) {
            Timber.w(e, "ArrivalMonitor: could not vibrate")
        }
    }

    companion object {
        /** Within this distance of the target the wearer counts as "there" (typical GPS error is 5-10 m). */
        const val ARRIVAL_M = 20.0
        /** Inside this distance the GPS runs faster in the saving mode; it stays on until [APPROACH_LEAVE_M]. */
        const val APPROACH_M = 60.0
        const val APPROACH_LEAVE_M = 90.0
        /** The faster GPS runs for one target at most this long. */
        const val APPROACH_MAX_MS = 10 * 60 * 1000L
        private const val MAX_ACCURACY_M = 40f

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
            return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }
}
