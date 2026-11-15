package com.mfit.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FoodLogEntry(val id: String, val name: String, val calories: Int)

object DailyHealthPreferences {
    private const val PREFS = "mfit_daily_health"
    private const val KEY_DAY = "step_day"
    private const val KEY_LAST_COUNTER = "step_last_counter"
    private const val KEY_ACCUMULATED = "step_accumulated"

    fun todayKey(nowMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordStepCounter(context: Context, totalSinceBoot: Long, day: String = todayKey()): Int {
        val p = prefs(context)
        if (p.getString(KEY_DAY, null) != day) {
            p.edit().putString(KEY_DAY, day).putLong(KEY_LAST_COUNTER, totalSinceBoot).putInt(KEY_ACCUMULATED, 0).apply()
            p.edit().putInt("steps_$day", 0).apply()
            return 0
        }
        val last = p.getLong(KEY_LAST_COUNTER, totalSinceBoot)
        val accumulated = p.getInt(KEY_ACCUMULATED, 0)
        val increment = if (totalSinceBoot >= last) (totalSinceBoot - last).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        val updated = (accumulated.toLong() + increment).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        p.edit().putLong(KEY_LAST_COUNTER, totalSinceBoot).putInt(KEY_ACCUMULATED, updated).putInt("steps_$day", updated).apply()
        return updated
    }

    fun resetSteps(context: Context, day: String = todayKey()) {
        val p = prefs(context)
        p.edit().putString(KEY_DAY, day).putInt(KEY_ACCUMULATED, 0).putInt("steps_$day", 0).apply()
    }

    fun steps(context: Context, day: String = todayKey()): Int {
        val p = prefs(context)
        return if (p.getString(KEY_DAY, null) == day) p.getInt(KEY_ACCUMULATED, 0) else p.getInt("steps_$day", 0)
    }

    fun water(context: Context, day: String = todayKey()): Int =
        prefs(context).getInt("water_$day", 0).coerceAtLeast(0)

    fun addWater(context: Context, delta: Int, day: String = todayKey()): Int {
        val next = (water(context, day) + delta).coerceIn(0, 100)
        prefs(context).edit().putInt("water_$day", next).apply()
        return next
    }

    fun foods(context: Context, day: String = todayKey()): List<FoodLogEntry> {
        val raw = prefs(context).getString("foods_$day", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                FoodLogEntry(item.optString("id"), item.optString("name"), item.optInt("calories").coerceAtLeast(0))
            }
        }.getOrDefault(emptyList())
    }

    fun addFood(context: Context, name: String, calories: Int, day: String = todayKey()): List<FoodLogEntry> {
        val safeName = name.trim().take(80)
        if (safeName.isEmpty() || calories !in 0..10000) return foods(context, day)
        val next = foods(context, day) + FoodLogEntry(java.util.UUID.randomUUID().toString(), safeName, calories)
        val array = JSONArray()
        next.forEach { item -> array.put(JSONObject().put("id", item.id).put("name", item.name).put("calories", item.calories)) }
        prefs(context).edit().putString("foods_$day", array.toString()).apply()
        return next
    }

    fun removeFood(context: Context, id: String, day: String = todayKey()): List<FoodLogEntry> {
        val next = foods(context, day).filterNot { it.id == id }
        val array = JSONArray()
        next.forEach { item -> array.put(JSONObject().put("id", item.id).put("name", item.name).put("calories", item.calories)) }
        prefs(context).edit().putString("foods_$day", array.toString()).apply()
        return next
    }
}
