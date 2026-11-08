package org.geonav.app.domain.engine

import org.geonav.app.data.model.GeoPoint
import org.geonav.app.data.model.ManeuverType
import org.geonav.app.data.model.Route
import org.geonav.app.data.model.RouteStep
import org.geonav.app.data.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the guidance engine that drives maneuver advancement, arrival and
 * off-route detection on the map screen.
 *
 * Distances use real Haversine math on a small equatorial patch: 0.001° of latitude is
 * ~111 m, which keeps every threshold (25 m advance, 20 m arrival, 250 m off-route)
 * unambiguous in the fixtures below.
 */
class TurnByTurnEngineTest {

    private val stepA = GeoPoint(0.0, 0.0)
    private val stepB = GeoPoint(0.0, 0.001)
    private val stepC = GeoPoint(0.0, 0.002)

    private fun step(instruction: String, at: GeoPoint) = RouteStep(
        instruction = instruction,
        maneuverType = ManeuverType.CONTINUE_STRAIGHT,
        distanceMeters = 111.0,
        durationSeconds = 10L,
        roadName = "Main St",
        location = at
    )

    private fun routeWithSteps(steps: List<RouteStep>) = Route(
        id = "test-route",
        summary = "Test route",
        distanceMeters = 333.0,
        durationSeconds = 30L,
        points = listOf(stepA, stepB, stepC),
        steps = steps,
        mode = TransportMode.DRIVE
    )

    private val threeStepRoute = routeWithSteps(
        listOf(step("Depart", stepA), step("Continue on Main St", stepB), step("Arrive", stepC))
    )

    @Test
    fun `updateProgress without a started route returns null`() {
        val engine = TurnByTurnEngine()
        assertNull(engine.updateProgress(stepA))
    }

    @Test
    fun `route with no steps returns null`() {
        val engine = TurnByTurnEngine()
        engine.startRoute(routeWithSteps(emptyList()))
        assertNull(engine.updateProgress(stepA))
    }

    @Test
    fun `first update reports the first step without advancing`() {
        val engine = TurnByTurnEngine()
        engine.startRoute(threeStepRoute)

        // ~55 m from step A: beyond the 25 m advance threshold, within the 250 m
        // off-route threshold, and nowhere near the destination.
        val state = engine.updateProgress(GeoPoint(0.0005, 0.0))

        assertNotNull(state)
        assertEquals("Depart", state!!.currentStep.instruction)
        assertEquals("Continue on Main St", state.nextStep!!.instruction)
        assertFalse(state.isArrived)
        assertFalse(state.isOffRoute)
    }

    @Test
    fun `approaching the current maneuver point advances to the next step`() {
        val engine = TurnByTurnEngine()
        engine.startRoute(threeStepRoute)

        // ~1 m from the departure point A: crosses the 25 m threshold, so the engine
        // advances to B and reports the maneuver the driver is now approaching.
        val state = engine.updateProgress(GeoPoint(0.0, 0.00001))

        assertNotNull(state)
        assertEquals("Continue on Main St", state!!.currentStep.instruction)
        assertEquals("Arrive", state.nextStep!!.instruction)
        assertFalse(state.isArrived)
        assertFalse(state.isOffRoute)
    }

    @Test
    fun `arrival is detected within 20 m of the final step`() {
        val engine = TurnByTurnEngine()
        engine.startRoute(threeStepRoute)
        engine.updateProgress(GeoPoint(0.0, 0.00001)) // advance past A to B

        val state = engine.updateProgress(GeoPoint(0.0, 0.00199)) // ~1 m from C

        assertNotNull(state)
        assertTrue(state!!.isArrived)
    }

    @Test
    fun `deviation beyond 250 m flags off-route`() {
        val engine = TurnByTurnEngine()
        engine.startRoute(threeStepRoute)

        // ~1.1 km from step A.
        val state = engine.updateProgress(GeoPoint(0.01, 0.0))

        assertNotNull(state)
        assertTrue(state!!.isOffRoute)
        assertFalse(state.isArrived)
    }

    @Test
    fun `stopRoute clears guidance`() {
        val engine = TurnByTurnEngine()
        engine.startRoute(threeStepRoute)
        assertNotNull(engine.updateProgress(GeoPoint(0.0005, 0.0)))

        engine.stopRoute()

        assertNull(engine.updateProgress(GeoPoint(0.0005, 0.0)))
    }

    @Test
    fun `remaining distance shrinks as the user advances`() {
        val engine = TurnByTurnEngine()
        engine.startRoute(threeStepRoute)

        // 55 m before the departure point: 55 + 111 + 111 ≈ 277 m remaining.
        val early = engine.updateProgress(GeoPoint(0.0005, 0.0))!!
        // On top of A: advances to B, leaving ~110 m to B plus 111 m to C.
        val late = engine.updateProgress(GeoPoint(0.0, 0.00001))!!

        assertTrue(
            "remaining distance should decrease as the user progresses " +
                "(${early.remainingDistanceMeters} -> ${late.remainingDistanceMeters})",
            late.remainingDistanceMeters < early.remainingDistanceMeters
        )
    }
}
