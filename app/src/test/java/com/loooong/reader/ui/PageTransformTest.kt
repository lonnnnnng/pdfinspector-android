package com.loooong.reader.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransformTest {

    @Test
    fun unrotatedBitmapDeltaMapsBackToPdfAxes() {
        val transform = PageTransform(0f, 0f, 300f, 400f, 0, 2f)

        val delta = transform.toUserDelta(Offset(20f, -10f))

        assertEquals(10f, delta.x, 1e-4f)
        assertEquals(5f, delta.y, 1e-4f)
    }

    @Test
    fun rotatedBitmapDeltaMapsBackToPdfAxes() {
        val transform = PageTransform(0f, 0f, 300f, 400f, 90, 2f)

        val delta = transform.toUserDelta(Offset(20f, 10f))

        assertEquals(5f, delta.x, 1e-4f)
        assertEquals(10f, delta.y, 1e-4f)
        assertEquals(-30f, transform.toUserRotation(30f), 1e-4f)
    }
}
