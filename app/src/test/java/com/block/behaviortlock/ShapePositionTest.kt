package com.block.behaviortlock

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Regression tests for two false-accept bugs:
//
//  1. The 64 dots were normalised to a bounding box, which threw away WHERE
//     the shape was drawn and HOW BIG it was. An "M" drawn anywhere, at any
//     size, reduced to identical dots and matched.
//  2. shapeSimilarity took the best of 64 starting points and scaled the score
//     so generously that a loosely-aligned shape still scored well.
// ---------------------------------------------------------------------------

class ShapePositionTest {

    /** Build the touch points of a simple zig-zag ("M"-like) path. */
    private fun zigzag(
        startX: Float, startY: Float,
        width: Float, height: Float
    ): List<TouchPoint> {
        val pts = mutableListOf<TouchPoint>()
        val corners = listOf(
            0f to 0f, 0.25f to 1f, 0.5f to 0f, 0.75f to 1f, 1f to 0f
        )
        var i = 0
        for ((fx, fy) in corners) {
            val x = startX + width * fx
            val y = startY + height * fy
            for (step in 0..8) {
                val t = step / 8f
                val prev = corners.getOrNull(corners.indexOf(Pair(fx, fy)) - 1)
                val px = if (prev == null) x else startX + width * prev.first
                val py = if (prev == null) y else startY + height * prev.second
                pts += TouchPoint(
                    i * 16_000_000L,
                    px + (x - px) * t,
                    py + (y - py) * t,
                    1, 1f,
                    if (i == 0) TouchPhase.DOWN
                    else if (step == 8) TouchPhase.MOVE else TouchPhase.MOVE
                )
                i++
            }
        }
        return pts
    }

    private fun shapeOf(points: List<TouchPoint>) =
        ShapeBuilder.build(Trace(SegmentKind.DRAW, points, emptyList()))

    @Test
    fun `a shape records where it was drawn`() {
        // Drawn in the top-left quadrant.
        val topLeft = shapeOf(zigzag(100f, 200f, 300f, 400f))
        // Same outline, bottom-right quadrant.
        val bottomRight = shapeOf(zigzag(700f, 1500f, 300f, 400f))

        assertTrue("centres should differ", abs(topLeft.centreX - bottomRight.centreX) > 0.2f)
    }

    @Test
    fun `position score is high for the same place and low for elsewhere`() {
        val reference = shapeOf(zigzag(400f, 800f, 400f, 500f))
        val samePlace = shapeOf(zigzag(405f, 805f, 400f, 500f))   // almost identical
        val otherPlace = shapeOf(zigzag(100f, 200f, 400f, 500f))  // top-left instead

        val good = Scorer.positionScore(reference, samePlace)
        val bad = Scorer.positionScore(reference, otherPlace)

        assertTrue("same place should score well, was $good", good > 80f)
        assertTrue("different place must score low, was $bad", bad < 45f)
    }

    @Test
    fun `position score punishes a much larger drawing`() {
        val reference = shapeOf(zigzag(400f, 800f, 200f, 250f))
        val huge = shapeOf(zigzag(400f, 800f, 700f, 900f))

        val good = Scorer.positionScore(reference, reference)
        val bad = Scorer.positionScore(reference, huge)

        assertTrue("same size should score well, was $good", good > 85f)
        assertTrue("a very different size must score low, was $bad", bad < 55f)
    }

    @Test
    fun `loosely aligned shapes no longer score highly`() {
        // A circle-ish loop vs a zig-zag: same bounding box, different path.
        val zig = shapeOf(zigzag(100f, 100f, 800f, 800f))
        val loop = shapeOf(
            (0..64).map { i ->
                val a = i / 64.0 * 2 * Math.PI
                TouchPoint(
                    i * 16_000_000L,
                    (100f + 400f * Math.cos(a)).toFloat(),
                    (100f + 400f * Math.sin(a)).toFloat(),
                    1, 1f, TouchPhase.MOVE
                )
            }
        )
        val score = Scorer.shapeSimilarity(zig.dots, loop.dots)
        assertTrue("different outlines must not score highly, was $score", score < 62f)
    }
}