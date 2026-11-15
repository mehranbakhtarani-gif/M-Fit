package com.mfit.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BodyMeasurement(val id: String, val weightKg: Double, val recordedAtMillis: Long)

object BodyMeasurementPreferences {
    private const val PREFS = "mfit_profile_measurements"
    private const val KEY_HISTORY = "weight_history"

    fun history(context: Context): List<BodyMeasurement> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val weight = item.optDouble("weightKg", Double.NaN)
                val time = item.optLong("recordedAtMillis", 0L)
                if (weight in 20.0..250.0 && time > 0L) {
                    BodyMeasurement(item.optString("id").ifBlank { UUID.randomUUID().toString() }, weight, time)
                } else null
            }.sortedBy { it.recordedAtMillis }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, weightKg: Double, nowMillis: Long = System.currentTimeMillis()): List<BodyMeasurement> {
        if (weightKg !in 20.0..250.0) return history(context)
        val current = history(context)
        val last = current.lastOrNull()
        val next = if (last != null && nowMillis - last.recordedAtMillis < 60_000L) {
            current.dropLast(1) + last.copy(weightKg = weightKg, recordedAtMillis = nowMillis)
        } else {
            current + BodyMeasurement(UUID.randomUUID().toString(), weightKg, nowMillis)
        }.takeLast(500)
        val array = JSONArray()
        next.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("weightKg", item.weightKg).put("recordedAtMillis", item.recordedAtMillis))
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HISTORY, array.toString()).apply()
        return next
    }
}
