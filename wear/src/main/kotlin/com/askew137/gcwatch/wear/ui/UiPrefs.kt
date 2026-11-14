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

    val AMBIENT_GPS_CHOICES_S = listOf(10, 30, 60)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun reaction(context: Context): HeadingReaction =
        HeadingReaction.entries.firstOrNull { it.name == prefs(context).getString(KEY_REACTION, null) }
            ?: HeadingReaction.NORMAL

    fun setReaction(context: Context, value: HeadingReaction) {
        prefs(context).edit().putString(KEY_REACTION, value.name).apply()
    }

    /** How often the watch GPS asks for a fix while the screen is in ambient mode. */
    fun ambientGpsSeconds(context: Context): Int =
        prefs(context).getInt(KEY_AMBIENT_GPS_S, 10).takeIf { it in AMBIENT_GPS_CHOICES_S } ?: 10

    fun setAmbientGpsSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_AMBIENT_GPS_S, seconds).apply()
    }
}
