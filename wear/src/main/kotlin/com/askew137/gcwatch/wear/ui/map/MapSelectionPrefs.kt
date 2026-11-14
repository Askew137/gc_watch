package com.askew137.gcwatch.wear.ui.map

import android.content.Context

/**
 * Which map files the watch uses: automatic (by position) or an explicit list. Local to the
 * watch (file names), so it is not part of the synced AppConfig.
 */
object MapSelectionPrefs {
    private const val PREFS = "map_selection"
    private const val KEY_AUTO = "automatic"
    private const val KEY_SELECTED = "selected"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun automatic(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun selectedNames(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED, emptySet()) ?: emptySet()

    fun save(context: Context, automatic: Boolean, selectedNames: Set<String>) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO, automatic)
            .putStringSet(KEY_SELECTED, selectedNames)
            .apply()
    }
}
