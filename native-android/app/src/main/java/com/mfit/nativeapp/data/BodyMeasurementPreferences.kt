package com.mfit.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BodyMeasurement(
    val id: String,
    val weightKg: Double,
    val recordedAtMillis: Long,
    val heightCm: Double? = null,
    val waistCm: Double? = null
)

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
                val height = item.optDouble("heightCm", Double.NaN).takeIf { it.isFinite() && it in 80.0..250.0 }
                val waist = item.optDouble("waistCm", Double.NaN).takeIf { it.isFinite() && it in 30.0..250.0 }
                if (weight in 20.0..350.0 && time > 0L) {
                    BodyMeasurement(item.optString("id").ifBlank { UUID.randomUUID().toString() }, weight, time, height, waist)
                } else null
            }.sortedBy { it.recordedAtMillis }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, weightKg: Double, nowMillis: Long = System.currentTimeMillis()): List<BodyMeasurement> =
        addMeasurement(context, weightKg, null, null, nowMillis)

    fun addMeasurement(
        context: Context,
        weightKg: Double?,
        heightCm: Double?,
        waistCm: Double?,
        nowMillis: Long = System.currentTimeMillis()
    ): List<BodyMeasurement> {
        require(weightKg == null || weightKg.isFinite() && weightKg in 20.0..350.0) { "وزن باید بین ۲۰ تا ۳۵۰ کیلوگرم باشد." }
        require(heightCm == null || heightCm.isFinite() && heightCm in 80.0..250.0) { "قد باید بین ۸۰ تا ۲۵۰ سانتی‌متر باشد." }
        require(waistCm == null || waistCm.isFinite() && waistCm in 30.0..250.0) { "دور کمر باید بین ۳۰ تا ۲۵۰ سانتی‌متر باشد." }
        require(weightKg != null || heightCm != null || waistCm != null) { "حداقل یک اندازه‌گیری وارد کنید." }

        val current = history(context)
        val last = current.lastOrNull()
        val next = if (last != null && nowMillis - last.recordedAtMillis < 60_000L) {
            current.dropLast(1) + last.copy(
                weightKg = weightKg ?: last.weightKg,
                heightCm = heightCm ?: last.heightCm,
                waistCm = waistCm ?: last.waistCm,
                recordedAtMillis = nowMillis
            )
        } else {
            current + BodyMeasurement(
                UUID.randomUUID().toString(),
                weightKg ?: current.lastOrNull()?.weightKg ?: 75.0,
                nowMillis,
                heightCm,
                waistCm
            )
        }.takeLast(500)

        val array = JSONArray()
        next.forEach { item ->
            val json = JSONObject()
                .put("id", item.id)
                .put("weightKg", item.weightKg)
                .put("recordedAtMillis", item.recordedAtMillis)
            item.heightCm?.let { json.put("heightCm", it) }
            item.waistCm?.let { json.put("waistCm", it) }
            array.put(json)
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HISTORY, array.toString()).apply()
        return next
    }

    fun latestHeightCm(context: Context): Double? = history(context).lastOrNull { it.heightCm != null }?.heightCm
    fun latestWaistCm(context: Context): Double? = history(context).lastOrNull { it.waistCm != null }?.waistCm
}
