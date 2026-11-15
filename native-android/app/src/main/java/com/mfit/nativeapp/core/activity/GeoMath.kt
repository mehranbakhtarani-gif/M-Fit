package com.mfit.nativeapp.core.activity

import kotlin.math.*

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val lat1 = Math.toRadians(latitude1)
        val lat2 = Math.toRadians(latitude2)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(longitude2 - longitude1)
        val a = sin(deltaLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

enum class FixRejection {
    INVALID_COORDINATES, INVALID_ACCURACY, INACCURATE, OUT_OF_ORDER,
    TOO_CLOSE, IMPLAUSIBLE_SPEED
}

data class FixDecision(
    val accepted: Boolean,
    val segmentDistanceMeters: Double = 0.0,
    val rejection: FixRejection? = null
)

/**
 * Conservative route filter. It does not invent/interpolate coordinates:
 * rejected fixes never contribute to distance or the visible route.
 */
class RoutePointFilter(
    private val maxAccuracyMeters: Float = 25f,
    private val minDisplacementMeters: Double = 2.0,
    private val maxPlausibleSpeedMetersPerSecond: Double = 35.0
) {
    private var previous: GeoFix? = null

    fun reset() {
        previous = null
    }

    fun evaluate(fix: GeoFix): FixDecision {
        if (!fix.hasValidCoordinates()) {
            return FixDecision(false, rejection = FixRejection.INVALID_COORDINATES)
        }
        if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0f) {
            return FixDecision(false, rejection = FixRejection.INVALID_ACCURACY)
        }
        if (fix.accuracyMeters > maxAccuracyMeters) {
            return FixDecision(false, rejection = FixRejection.INACCURATE)
        }

        val old = previous
        if (old == null) {
            previous = fix
            return FixDecision(true)
        }

        val deltaMillis = fix.timestampMillis - old.timestampMillis
        if (deltaMillis <= 0L) {
            return FixDecision(false, rejection = FixRejection.OUT_OF_ORDER)
        }

        val distance = GeoMath.distanceMeters(
            old.latitude, old.longitude, fix.latitude, fix.longitude
        )
        if (distance < minDisplacementMeters) {
            return FixDecision(false, rejection = FixRejection.TOO_CLOSE)
        }

        val speed = distance / (deltaMillis / 1000.0)
        if (!speed.isFinite() || speed > maxPlausibleSpeedMetersPerSecond) {
            return FixDecision(false, rejection = FixRejection.IMPLAUSIBLE_SPEED)
        }

        previous = fix
        return FixDecision(true, segmentDistanceMeters = distance)
    }
}
