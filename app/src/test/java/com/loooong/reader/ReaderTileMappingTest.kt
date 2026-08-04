package com.loooong.reader

import com.loooong.reader.ui.mapReaderTileToRenderCoordinates
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTileMappingTest {

    @Test
    fun readerBitmapCoordinatesAreMappedToRenderDpiCoordinates() {
        val mapped = mapReaderTileToRenderCoordinates(
            source = Rect(100f, 120f, 900f, 1080f),
            bitmapWidth = 1000,
            bitmapHeight = 1200,
            pageWidthPoints = 600f,
            pageHeightPoints = 800f,
            renderDpi = 144f,
        )

        assertRectEquals(Rect(120f, 160f, 1080f, 1440f), mapped)
    }

    @Test
    fun renderDpiBitmapKeepsTileCoordinatesUnchanged() {
        val source = Rect(80f, 120f, 720f, 1080f)

        val mapped = mapReaderTileToRenderCoordinates(
            source = source,
            bitmapWidth = 1200,
            bitmapHeight = 1600,
            pageWidthPoints = 600f,
            pageHeightPoints = 800f,
            renderDpi = 144f,
        )

        assertRectEquals(source, mapped)
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.001f)
        assertEquals(expected.top, actual.top, 0.001f)
        assertEquals(expected.right, actual.right, 0.001f)
        assertEquals(expected.bottom, actual.bottom, 0.001f)
    }
}
