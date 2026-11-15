package com.mfit.nativeapp.core.activity

class ActivitySessionEngine(
    private val routeFilter: RoutePointFilter = RoutePointFilter()
) {
    private var type: ActivityType = ActivityType.WALK
    private var status: SessionStatus = SessionStatus.IDLE
    private var startedAtMillis: Long? = null
    private var lastResumedAtMillis: Long? = null
    private var accumulatedElapsedMillis: Long = 0L
    private var distanceMeters: Double = 0.0
    private val points = mutableListOf<TrackPoint>()
    private val recentSpeeds = ArrayDeque<Double>()

    fun start(activityType: ActivityType, nowMillis: Long): Boolean {
        if (status == SessionStatus.RUNNING || status == SessionStatus.PAUSED) return false
        reset()
        type = activityType
        status = SessionStatus.RUNNING
        startedAtMillis = nowMillis
        lastResumedAtMillis = nowMillis
        return true
    }

    fun pause(nowMillis: Long): Boolean {
        if (status != SessionStatus.RUNNING) return false
        val resumedAt = lastResumedAtMillis ?: return false
        accumulatedElapsedMillis += (nowMillis - resumedAt).coerceAtLeast(0L)
        lastResumedAtMillis = null
        status = SessionStatus.PAUSED
        routeFilter.reset()
        recentSpeeds.clear()
        return true
    }

    fun resume(nowMillis: Long): Boolean {
        if (status != SessionStatus.PAUSED) return false
        lastResumedAtMillis = nowMillis
        status = SessionStatus.RUNNING
        routeFilter.reset()
        return true
    }

    fun finish(nowMillis: Long): ActivitySnapshot? {
        if (status != SessionStatus.RUNNING && status != SessionStatus.PAUSED) return null
        if (status == SessionStatus.RUNNING) {
            val resumedAt = lastResumedAtMillis ?: nowMillis
            accumulatedElapsedMillis += (nowMillis - resumedAt).coerceAtLeast(0L)
            lastResumedAtMillis = null
        }
        status = SessionStatus.FINISHED
        return snapshot(nowMillis)
    }

    fun addLocation(fix: GeoFix): FixDecision {
        if (status != SessionStatus.RUNNING) {
            return FixDecision(false, rejection = FixRejection.OUT_OF_ORDER)
        }
        val decision = routeFilter.evaluate(fix)
        if (!decision.accepted) return decision

        if (points.isNotEmpty() && decision.segmentDistanceMeters > 0.0) {
            distanceMeters += decision.segmentDistanceMeters
        }
        points += TrackPoint(
            latitude = fix.latitude,
            longitude = fix.longitude,
            timestampMillis = fix.timestampMillis,
            accuracyMeters = fix.accuracyMeters,
            segmentDistanceMeters = decision.segmentDistanceMeters
        )

        if (decision.segmentDistanceMeters > 0.0 && points.size >= 2) {
            val previous = points[points.lastIndex - 1]
            val seconds = (fix.timestampMillis - previous.timestampMillis) / 1000.0
            if (seconds > 0.0) {
                val speed = decision.segmentDistanceMeters / seconds
                recentSpeeds.addLast(speed)
                while (recentSpeeds.size > 5) recentSpeeds.removeFirst()
            }
        }
        return decision
    }

    fun snapshot(nowMillis: Long): ActivitySnapshot {
        val elapsed = elapsedMillis(nowMillis)
        val averageSpeed = if (elapsed > 0L) distanceMeters / (elapsed / 1000.0) else 0.0
        val currentSpeed = if (recentSpeeds.isEmpty()) 0.0 else recentSpeeds.average()
        val hours = elapsed / 3_600_000.0
        // MET-based estimate; caller should supply body mass in a later profile-aware layer.
        val calories = (type.met * 70.0 * hours).roundToInt()
        return ActivitySnapshot(
            type = type,
            status = status,
            elapsedMillis = elapsed,
            distanceMeters = distanceMeters,
            averageSpeedMetersPerSecond = averageSpeed,
            currentSpeedMetersPerSecond = currentSpeed,
            calories = calories,
            points = points.toList()
        )
    }

    private fun elapsedMillis(nowMillis: Long): Long {
        val running = if (status == SessionStatus.RUNNING) {
            (nowMillis - (lastResumedAtMillis ?: nowMillis)).coerceAtLeast(0L)
        } else 0L
        return accumulatedElapsedMillis + running
    }

    private fun reset() {
        routeFilter.reset()
        points.clear()
        recentSpeeds.clear()
        accumulatedElapsedMillis = 0L
        distanceMeters = 0.0
        startedAtMillis = null
        lastResumedAtMillis = null
        status = SessionStatus.IDLE
    }

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
}
