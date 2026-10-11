package com.block.behaviortlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the shape reduction step. Runs on the JVM, no device needed. */
class ShapeTest {

    private fun traceOf(vararg points: Pair<Float, Float>): Trace {
        val touches = points.mapIndexed { i, (x, y) ->
            TouchPoint(i * 16_000_000L, x, y, 1, 1f)
        }
        return Trace(SegmentKind.DRAW, touches, emptyList())
    }

    @Test
    fun `every shape is reduced to the same number of dots`() {
        val shape = ShapeBuilder.build(traceOf(0f to 0f, 10f to 10f, 20f to 5f))
        assertEquals(SHAPE_POINTS, shape.dots.size)
    }

    @Test
    fun `dots are normalised into the unit square`() {
        val shape = ShapeBuilder.build(traceOf(100f to 200f, 900f to 800f))
        for ((x, y) in shape.dots) {
            assertTrue("x out of range: $x", x in -0.01f..1.01f)
            assertTrue("y out of range: $y", y in -0.01f..1.01f)
        }
    }

    @Test
    fun `a flat line still produces a full set of dots`() {
        val flat = (0..5).map { (it * 10f) to 50f }
        val shape = ShapeBuilder.build(traceOf(*flat.toTypedArray()))
        assertEquals(SHAPE_POINTS, shape.dots.size)
    }

    @Test
    fun `duration is measured in milliseconds`() {
        val touches = listOf(
            TouchPoint(0L, 0f, 0f, 1, 1f),
            TouchPoint(1_000_000_000L, 100f, 100f, 1, 1f) // 1 second apart
        )
        val shape = ShapeBuilder.build(Trace(SegmentKind.DRAW, touches, emptyList()))
        assertEquals(1000f, shape.durationMs, 1f)
    }

    @Test
    fun `constant pressure is reported as unavailable`() {
        val shape = ShapeBuilder.build(traceOf(0f to 0f, 50f to 50f))
        // pressure 1.0 everywhere = a phone with no force touch
        assertTrue("constant 1.0 pressure should be detected as unavailable", !shape.hasPressure)
    }
}