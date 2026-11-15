package com.mfit.nativeapp.data

import android.content.Context

/** Local-only profile storage for values used by native activity calculations. */
object UserProfilePreferences {
    private const val PREFS_NAME = "mfit_profile"
    private const val KEY_WEIGHT_KG = "body_weight_kg"

    fun getWeightKg(context: Context): Double? {
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!preferences.contains(KEY_WEIGHT_KG)) return null
        return preferences.getFloat(KEY_WEIGHT_KG, Float.NaN)
            .toDouble()
            .takeIf { it.isFinite() && it in MIN_WEIGHT_KG..MAX_WEIGHT_KG }
    }

    fun saveWeightKg(context: Context, weightKg: Double): Boolean {
        if (!weightKg.isFinite() || weightKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) return false
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_WEIGHT_KG, weightKg.toFloat())
            .commit()
    }

    const val MIN_WEIGHT_KG = 20.0
    const val MAX_WEIGHT_KG = 250.0
}
