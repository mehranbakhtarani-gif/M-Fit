package com.mfit.nativeapp.data

import android.content.Context

data class FastingSession(
    val startedAtMillis: Long,
    val targetEndAtMillis: Long
) {
    val isComplete: Boolean get() = System.currentTimeMillis() >= targetEndAtMillis
}

object FastingPreferences {
    private const val PREFS = "mfit_fasting"
    private const val KEY_STARTED = "started_at"
    private const val KEY_ENDS = "ends_at"

    fun active(context: Context): FastingSession? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val started = prefs.getLong(KEY_STARTED, 0L)
        val ends = prefs.getLong(KEY_ENDS, 0L)
        return if (started > 0L && ends > started) FastingSession(started, ends) else null
    }

    fun start(context: Context, durationHours: Int, nowMillis: Long = System.currentTimeMillis()): FastingSession? = startForMinutes(context, durationHours * 60, nowMillis)

    fun startForMinutes(context: Context, durationMinutes: Int, nowMillis: Long = System.currentTimeMillis()): FastingSession? {
        if (durationMinutes !in 1..2880) return null
        val session = FastingSession(nowMillis, nowMillis + durationMinutes * 60L * 1000L)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STARTED, session.startedAtMillis)
            .putLong(KEY_ENDS, session.targetEndAtMillis)
            .apply()
        return session
    }

    fun stop(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_STARTED)
            .remove(KEY_ENDS)
            .apply()
    }
}
