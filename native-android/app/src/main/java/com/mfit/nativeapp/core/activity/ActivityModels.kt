package com.mfit.nativeapp.core.activity

enum class ActivityType(val met: Double) {
    WALK(3.5),
    RUN(8.0),
    RIDE(6.8)
}

enum class SessionStatus {
    IDLE, RUNNING, PAUSED, FINISHED
}

data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val altitudeMeters: Double? = null
) {
    fun hasValidCoordinates(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Float,
    val segmentDistanceMeters: Double
)

data class ActivitySnapshot(
    val type: ActivityType,
    val status: SessionStatus,
    val elapsedMillis: Long,
    val distanceMeters: Double,
    val averageSpeedMetersPerSecond: Double,
    val currentSpeedMetersPerSecond: Double,
    val calories: Int,
    val points: List<TrackPoint>
)
