package com.sanepo.offgrid

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class OffGridApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applySavedThemeMode(this)
    }

    companion object {
        private const val PREFS_NAME = "sanepo_offgrid_prefs"
        private const val KEY_NIGHT_MODE = "night_mode_enabled"

        fun isDarkModePreferred(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_NIGHT_MODE, true)
        }

        fun setDarkModePreferred(context: Context, dark: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NIGHT_MODE, dark)
                .apply()
            applySavedThemeMode(context)
        }

        fun applySavedThemeMode(context: Context) {
            val mode = if (isDarkModePreferred(context)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
