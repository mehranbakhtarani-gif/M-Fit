package com.mfit.nativeapp.core.activity

import org.junit.Assert.*
import org.junit.Test

class ActivitySessionEngineTest {
    @Test fun haversineDistanceIsApproximatelyCorrect() {
        val distance = GeoMath.distanceMeters(52.5200, 13.4050, 52.5200, 13.4060)
        assertEquals(68.5, distance, 2.0)
    }

    @Test fun routeFilterRejectsPoorAccuracyAndTinyJitter() {
        val filter = RoutePointFilter()
        assertFalse(filter.evaluate(GeoFix(52.0, 13.0, 40f, 1_000L)).accepted)
        assertTrue(filter.evaluate(GeoFix(52.0, 13.0, 5f, 2_000L)).accepted)
        val jitter = filter.evaluate(GeoFix(52.000001, 13.0, 5f, 3_000L))
        assertFalse(jitter.accepted)
        assertEquals(FixRejection.TOO_CLOSE, jitter.rejection)
    }

    @Test fun routeFilterRejectsOutOfOrderAndImpossibleJump() {
        val filter = RoutePointFilter()
        assertTrue(filter.evaluate(GeoFix(52.0, 13.0, 5f, 10_000L)).accepted)
        assertEquals(
            FixRejection.OUT_OF_ORDER,
            filter.evaluate(GeoFix(52.0001, 13.0, 5f, 9_000L)).rejection
        )
        assertEquals(
            FixRejection.IMPLAUSIBLE_SPEED,
            filter.evaluate(GeoFix(52.01, 13.0, 5f, 11_000L)).rejection
        )
    }

    @Test fun pauseResumeDoesNotCountPausedTime() {
        val engine = ActivitySessionEngine()
        assertTrue(engine.start(ActivityType.WALK, 1_000L))
        assertTrue(engine.pause(11_000L))
        assertTrue(engine.resume(101_000L))
        val snapshot = engine.finish(111_000L)!!
        assertEquals(20_000L, snapshot.elapsedMillis)
        assertEquals(SessionStatus.FINISHED, snapshot.status)
    }

    @Test fun distanceOnlyAddsAcceptedSegments() {
        val engine = ActivitySessionEngine()
        engine.start(ActivityType.WALK, 0L)
        assertTrue(engine.addLocation(GeoFix(52.5200, 13.4050, 5f, 1_000L)).accepted)
        assertTrue(engine.addLocation(GeoFix(52.5200, 13.4060, 5f, 11_000L)).accepted)
        assertTrue(engine.snapshot(11_000L).distanceMeters > 60.0)
        assertEquals(2, engine.snapshot(11_000L).points.size)
    }

    @Test fun invalidStateTransitionsAreRejected() {
        val engine = ActivitySessionEngine()
        assertFalse(engine.pause(1_000L))
        assertTrue(engine.start(ActivityType.RUN, 2_000L))
        assertFalse(engine.start(ActivityType.RIDE, 3_000L))
        assertFalse(engine.resume(4_000L))
    }
}
