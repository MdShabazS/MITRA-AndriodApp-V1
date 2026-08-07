package com.unique.visionmate

import android.content.Context

object MitraRuntime {
    private const val KEY_MITRA_ACTIVE = "mitra_active"

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MITRA_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_MITRA_ACTIVE, active).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
}
