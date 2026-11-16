package com.askew137.gcwatch.wear.ui

import android.content.Context
import android.hardware.SensorManager

/** How quickly the view-direction cone follows the wrist (sensor rate and smoothing). */
enum class HeadingReaction(val label: String, val sensorDelay: Int, val smoothing: Float) {
    /** Raw sensor, highest rate: snappy, can jitter, uses the most battery. */
    FAST("Rychlá", SensorManager.SENSOR_DELAY_GAME, 1.0f),
    NORMAL("Normální", SensorManager.SENSOR_DELAY_UI, 0.35f),
    /** Lower rate and strong smoothing: calm and frugal. */
    ECO("Úsporná", SensorManager.SENSOR_DELAY_NORMAL, 0.15f),
}

/** Watch-local display/battery settings (not synced to the phone). */
object UiPrefs {
    private const val PREFS = "ui_prefs"
    private const val KEY_REACTION = "heading_reaction"
    private const val KEY_AMBIENT_GPS_S = "ambient_gps_seconds"

    /** The ambient GPS interval slider: 5 s ... 60 s in steps of 5. */
    const val AMBIENT_GPS_MIN_S = 5
    const val AMBIENT_GPS_MAX_S = 60
    const val AMBIENT_GPS_STEP_S = 5

    private const val KEY_SERVICE_TIMEOUT_H = "service_timeout_hours"
    private const val KEY_AUTO_COMPASS = "auto_compass_at_target"

    /** Choices of "stop the navigation service after ... hours without opening the app"; 0 = never. */
    val SERVICE_TIMEOUT_CHOICES_H = listOf(1, 2, 3, 4, 6, 8, 12, 0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun reaction(context: Context): HeadingReaction =
        HeadingReaction.entries.firstOrNull { it.name == prefs(context).getString(KEY_REACTION, null) }
            ?: HeadingReaction.NORMAL

    fun setReaction(context: Context, value: HeadingReaction) {
        prefs(context).edit().putString(KEY_REACTION, value.name).apply()
    }

    /** How often the watch GPS asks for a fix while the screen is in ambient mode. */
    fun ambientGpsSeconds(context: Context): Int =
        prefs(context).getInt(KEY_AMBIENT_GPS_S, 10).coerceIn(AMBIENT_GPS_MIN_S, AMBIENT_GPS_MAX_S)

    fun setAmbientGpsSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_AMBIENT_GPS_S, seconds).apply()
    }

    /** Hours the app may stay in the background (service running) before the service stops itself; 0 = never. */
    fun serviceTimeoutHours(context: Context): Int =
        prefs(context).getInt(KEY_SERVICE_TIMEOUT_H, 3).takeIf { it in SERVICE_TIMEOUT_CHOICES_H } ?: 3

    fun setServiceTimeoutHours(context: Context, hours: Int) {
        prefs(context).edit().putInt(KEY_SERVICE_TIMEOUT_H, hours).apply()
    }

    /** Switch to the compass by itself when the wearer reaches the target (default on). */
    fun autoCompassAtTarget(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_COMPASS, true)

    fun setAutoCompassAtTarget(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_COMPASS, on).apply()
    }
}
