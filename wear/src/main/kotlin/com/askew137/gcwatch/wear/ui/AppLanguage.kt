package com.askew137.gcwatch.wear.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language choice: "" = follow the system, otherwise "cs", "en" or "de".
 * Android 13+ keeps it in the system per-app language setting (so Settings and this switch agree);
 * older versions store it in preferences and apply it in [wrap].
 */
object AppLanguage {
    val TAGS = listOf("", "cs", "en", "de")
    private const val PREFS = "app_language"

    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            val list = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (list.isEmpty) "" else list[0].language
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tag", "") ?: ""
        }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system recreates the activity by itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tag", tag).apply()
            activity.recreate()
        }
    }

    /** For Android 12 and older: apply the stored language to a base context. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tag", "") ?: ""
        if (tag.isEmpty()) return base
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(Locale(tag))
        return base.createConfigurationContext(config)
    }
}
