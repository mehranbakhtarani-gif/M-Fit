package com.mfit.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object NativeBackupManager {
    private const val VERSION = 1
    private val preferenceFiles = listOf(
        "mfit_profile",
        "mfit_daily_health",
        "mfit_fasting",
        "mfit_profile_measurements"
    )

    suspend fun export(context: Context): String {
        val root = JSONObject()
            .put("format", "mfit-native-backup")
            .put("version", VERSION)
            .put("createdAtMillis", System.currentTimeMillis())
        val prefsObject = JSONObject()
        preferenceFiles.forEach { name ->
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val objectForFile = JSONObject()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> objectForFile.put(key, value)
                    is Boolean -> objectForFile.put(key, value)
                    is Int -> objectForFile.put(key, value)
                    is Long -> objectForFile.put(key, value)
                    is Float -> objectForFile.put(key, value.toDouble())
                    is Set<*> -> objectForFile.put(key, JSONArray(value.filterIsInstance<String>()))
                }
            }
            prefsObject.put(name, objectForFile)
        }
        root.put("preferences", prefsObject)

        val dao = ActivityDatabase.get(context).activityDao()
        val sessions = dao.getSessions()
        val sessionArray = JSONArray()
        sessions.forEach { session ->
            val points = dao.getPoints(session.id)
            val pointArray = JSONArray()
            points.forEach { point ->
                pointArray.put(
                    JSONObject()
                        .put("latitude", point.latitude)
                        .put("longitude", point.longitude)
                        .put("timestampMillis", point.timestampMillis)
                        .put("accuracyMeters", point.accuracyMeters.toDouble())
                        .put("segmentDistanceMeters", point.segmentDistanceMeters)
                )
            }
            sessionArray.put(
                JSONObject()
                    .put("id", session.id)
                    .put("activityType", session.activityType)
                    .put("status", session.status)
                    .put("startedAtMillis", session.startedAtMillis)
                    .put("endedAtMillis", session.endedAtMillis ?: JSONObject.NULL)
                    .put("elapsedMillis", session.elapsedMillis)
                    .put("distanceMeters", session.distanceMeters)
                    .put("calories", session.calories)
                    .put("points", pointArray)
            )
        }
        root.put("sessions", sessionArray)
        return root.toString(2)
    }

    suspend fun import(context: Context, raw: String): String {
        val root = JSONObject(raw)
        require(root.optString("format") == "mfit-native-backup") { "فایل پشتیبان M-Fit معتبر نیست." }
        require(root.optInt("version", -1) == VERSION) { "نسخه فایل پشتیبان پشتیبانی نمی‌شود." }

        val prefsObject = root.optJSONObject("preferences") ?: JSONObject()
        var restoredPreferenceValues = 0
        preferenceFiles.forEach { name ->
            val source = prefsObject.optJSONObject(name) ?: return@forEach
            val target = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = target.edit()
            source.keys().forEach { key ->
                if (target.contains(key)) return@forEach
                when (val value = source.opt(key)) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.optString(it) }.toSet())
                }
                restoredPreferenceValues++
            }
            editor.apply()
        }

        val dao = ActivityDatabase.get(context).activityDao()
        var restoredSessions = 0
        var restoredPoints = 0
        val sessions = root.optJSONArray("sessions") ?: JSONArray()
        for (index in 0 until sessions.length()) {
            val item = sessions.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val started = item.optLong("startedAtMillis", 0L)
            if (id.isBlank() || started <= 0L) continue
            val originalStatus = item.optString("status")
            val status = if (originalStatus == "RUNNING" || originalStatus == "PAUSED") "INTERRUPTED" else originalStatus
            val insertedSession = dao.restoreSessionIfMissing(
                ActivitySessionEntity(
                    id = id,
                    activityType = item.optString("activityType", "WALK"),
                    status = status,
                    startedAtMillis = started,
                    endedAtMillis = if (item.isNull("endedAtMillis")) null else item.optLong("endedAtMillis"),
                    elapsedMillis = item.optLong("elapsedMillis", 0L).coerceAtLeast(0L),
                    distanceMeters = item.optDouble("distanceMeters", 0.0).coerceAtLeast(0.0),
                    calories = item.optInt("calories", 0).coerceAtLeast(0)
                )
            )
            if (insertedSession != -1L) restoredSessions++
            val points = item.optJSONArray("points") ?: JSONArray()
            for (pointIndex in 0 until points.length()) {
                val point = points.optJSONObject(pointIndex) ?: continue
                val lat = point.optDouble("latitude", Double.NaN)
                val lon = point.optDouble("longitude", Double.NaN)
                val timestamp = point.optLong("timestampMillis", 0L)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || timestamp <= 0L) continue
                val insertedPoint = dao.insertPoint(
                    ActivityTrackPointEntity(
                        sessionId = id,
                        latitude = lat,
                        longitude = lon,
                        timestampMillis = timestamp,
                        accuracyMeters = point.optDouble("accuracyMeters", 0.0).toFloat().coerceAtLeast(0f),
                        segmentDistanceMeters = point.optDouble("segmentDistanceMeters", 0.0).coerceAtLeast(0.0)
                    )
                )
                if (insertedPoint != -1L) restoredPoints++
            }
        }
        return "$restoredPreferenceValues تنظیم، $restoredSessions فعالیت و $restoredPoints نقطه مسیر بازیابی شد. داده‌های موجود بازنویسی نشدند."
    }
}
