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
        if (root.optString("format") == "mfit-pwa-backup") return importLegacyPwa(context, root)
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
    private suspend fun importLegacyPwa(context: Context, root: JSONObject): String {
        require(root.optInt("version", -1) == 1) { "نسخه فایل پشتیبان PWA پشتیبانی نمی‌شود." }
        val appData = root.optJSONObject("appData") ?: JSONObject()
        val days = appData.optJSONObject("days") ?: JSONObject()
        val dailyPrefs = context.getSharedPreferences("mfit_daily_health", Context.MODE_PRIVATE)
        var restoredDailyValues = 0
        days.keys().forEach { day ->
            val record = days.optJSONObject(day) ?: return@forEach
            val editor = dailyPrefs.edit()
            val stepsKey = "steps_$day"
            val waterKey = "water_$day"
            if (!dailyPrefs.contains(stepsKey) && record.has("steps")) {
                editor.putInt(stepsKey, record.optInt("steps", 0).coerceAtLeast(0))
                restoredDailyValues++
            }
            if (!dailyPrefs.contains(waterKey) && record.has("water")) {
                editor.putInt(waterKey, record.optInt("water", 0).coerceIn(0, 100))
                restoredDailyValues++
            }
            val foodsKey = "foods_$day"
            if (!dailyPrefs.contains(foodsKey) && record.optJSONArray("food") != null) {
                val foods = JSONArray()
                val sourceFoods = record.optJSONArray("food")!!
                for (i in 0 until sourceFoods.length()) {
                    val food = sourceFoods.optJSONObject(i) ?: continue
                    val name = food.optString("name").trim().take(80)
                    val calories = food.optInt("cal", food.optInt("calories", 0))
                    if (name.isNotEmpty() && calories in 0..10000) {
                        foods.put(
                            JSONObject()
                                .put("id", "pwa-$day-$i")
                                .put("name", name)
                                .put("calories", calories)
                        )
                    }
                }
                editor.putString(foodsKey, foods.toString())
                restoredDailyValues++
            }
            editor.apply()
        }

        var restoredMeasurements = 0
        val measurementArray = appData.optJSONArray("bodyMeasurements") ?: JSONArray()
        val measurementPrefs = context.getSharedPreferences("mfit_profile_measurements", Context.MODE_PRIVATE)
        val existingHistory = runCatching {
            JSONArray(measurementPrefs.getString("weight_history", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val mergedMeasurements = linkedMapOf<String, JSONObject>()
        for (i in 0 until existingHistory.length()) {
            val item = existingHistory.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) mergedMeasurements[id] = item
        }
        var latestImportedWeight: Pair<Double, Long>? = null
        for (i in 0 until measurementArray.length()) {
            val item = measurementArray.optJSONObject(i) ?: continue
            val weight = item.optDouble("weight", Double.NaN)
            val date = item.optString("date")
            val recordedAt = runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)?.time
            }.getOrNull() ?: continue
            if (!weight.isFinite() || weight !in 20.0..250.0) continue
            val id = "pwa-measure-$date-$i"
            if (!mergedMeasurements.containsKey(id)) {
                mergedMeasurements[id] = JSONObject()
                    .put("id", id)
                    .put("weightKg", weight)
                    .put("recordedAtMillis", recordedAt)
                restoredMeasurements++
            }
            if (latestImportedWeight == null || recordedAt > latestImportedWeight!!.second) {
                latestImportedWeight = weight to recordedAt
            }
        }
        if (restoredMeasurements > 0) {
            val mergedArray = JSONArray()
            mergedMeasurements.values
                .sortedBy { it.optLong("recordedAtMillis", 0L) }
                .takeLast(500)
                .forEach { mergedArray.put(it) }
            measurementPrefs.edit()
                .putString("weight_history", mergedArray.toString())
                .apply()
        }
        val profilePrefs = context.getSharedPreferences("mfit_profile", Context.MODE_PRIVATE)
        if (!profilePrefs.contains("body_weight_kg") && latestImportedWeight != null) {
            profilePrefs.edit()
                .putFloat("body_weight_kg", latestImportedWeight!!.first.toFloat())
                .apply()
        }
        if (measurementArray.length() > 0) {
            context.getSharedPreferences("mfit_legacy_body_measurements", Context.MODE_PRIVATE)
                .edit()
                .putString("records", measurementArray.toString())
                .apply()
        }

        var restoredFasting = 0
        val fasting = root.optJSONObject("fastingState")
        if (fasting != null && fasting.optBoolean("fasting", false)) {
            val startedAt = fasting.optLong("start", 0L)
            val targetHours = fasting.optInt("target", 16)
            val fastingPrefs = context.getSharedPreferences("mfit_fasting", Context.MODE_PRIVATE)
            if (startedAt > 0L && targetHours in 1..48 && !fastingPrefs.contains("started_at")) {
                fastingPrefs.edit()
                    .putLong("started_at", startedAt)
                    .putLong("ends_at", startedAt + targetHours * 60L * 60L * 1000L)
                    .apply()
                restoredFasting = 1
            }
        }

        val dao = ActivityDatabase.get(context).activityDao()
        var restoredSessions = 0
        var restoredPoints = 0

        suspend fun restoreLegacySession(
            id: String,
            mode: String,
            timestamp: Long,
            durationSeconds: Long,
            distanceKm: Double,
            calories: Int,
            coordinates: JSONArray?,
            interrupted: Boolean
        ) {
            if (timestamp <= 0L) return
            val durationMillis = durationSeconds.coerceAtLeast(0L) * 1000L
            val startedAt = (timestamp - durationMillis).coerceAtLeast(1L)
            val sessionId = "pwa-$id"
            val type = when (mode.lowercase()) {
                "run" -> "RUN"
                "ride", "cycling", "bike" -> "RIDE"
                else -> "WALK"
            }
            val inserted = dao.restoreSessionIfMissing(
                ActivitySessionEntity(
                    id = sessionId,
                    activityType = type,
                    status = if (interrupted) "INTERRUPTED" else "FINISHED",
                    startedAtMillis = startedAt,
                    endedAtMillis = if (interrupted) null else timestamp,
                    elapsedMillis = durationMillis,
                    distanceMeters = (distanceKm * 1000.0).coerceAtLeast(0.0),
                    calories = calories.coerceAtLeast(0)
                )
            )
            if (inserted != -1L) restoredSessions++
            if (coordinates == null) return
            for (index in 0 until coordinates.length()) {
                val point = coordinates.optJSONObject(index) ?: continue
                val lat = point.optDouble("lat", Double.NaN)
                val lon = point.optDouble("lon", Double.NaN)
                val pointTime = point.optLong("time", startedAt + point.optDouble("elapsedSeconds", 0.0).toLong() * 1000L)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || pointTime <= 0L) continue
                val insertedPoint = dao.insertPoint(
                    ActivityTrackPointEntity(
                        sessionId = sessionId,
                        latitude = lat,
                        longitude = lon,
                        timestampMillis = pointTime,
                        accuracyMeters = point.optDouble("accuracy", 0.0).toFloat().coerceAtLeast(0f),
                        segmentDistanceMeters = 0.0
                    )
                )
                if (insertedPoint != -1L) restoredPoints++
            }
        }

        val activityMap = linkedMapOf<String, JSONObject>()
        val legacyHistory = appData.optJSONArray("history") ?: JSONArray()
        val indexedActivities = root.optJSONArray("activities") ?: JSONArray()
        listOf(legacyHistory, indexedActivities).forEach { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val timestamp = item.optLong("timestamp", 0L)
                val id = item.optString("id").ifBlank { "activity-$timestamp-$index" }
                activityMap.putIfAbsent(id, item)
            }
        }
        activityMap.forEach { (id, item) ->
            restoreLegacySession(
                id = id,
                mode = item.optString("mode", "Walk"),
                timestamp = item.optLong("timestamp", 0L),
                durationSeconds = item.optLong("duration", 0L),
                distanceKm = item.optDouble("distance", 0.0),
                calories = item.optDouble("kcal", 0.0).toInt(),
                coordinates = item.optJSONArray("coords"),
                interrupted = false
            )
        }

        val draft = root.optJSONObject("activeSession")
        if (draft != null) {
            val savedAt = draft.optLong("savedAt", 0L)
            val elapsedSeconds = draft.optDouble("elapsed", 0.0).toLong().coerceAtLeast(0L)
            if (savedAt > 0L && (elapsedSeconds > 0L || (draft.optJSONArray("coords")?.length() ?: 0) > 0)) {
                restoreLegacySession(
                    id = "draft-$savedAt",
                    mode = draft.optString("mode", "Walk"),
                    timestamp = savedAt,
                    durationSeconds = elapsedSeconds,
                    distanceKm = draft.optDouble("distance", 0.0) / 1000.0,
                    calories = 0,
                    coordinates = draft.optJSONArray("coords"),
                    interrupted = true
                )
            }
        }

        return "$restoredDailyValues مورد داده روزانه، $restoredMeasurements اندازه‌گیری وزن، $restoredFasting تایمر روزه‌داری، $restoredSessions فعالیت و $restoredPoints نقطه مسیر از PWA وارد شد. داده‌های موجود بازنویسی نشدند."
    }

}
