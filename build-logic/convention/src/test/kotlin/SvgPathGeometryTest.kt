// SPDX-License-Identifier: Unlicense
import java.awt.geom.AffineTransform
import org.junit.Assert.assertEquals
import org.junit.Test

/** Geometry of the parsed silhouette: bounds, the AWT shape, and reproducible `pathData` output. */
class SvgPathGeometryTest {
    @Test
    fun boundsCoverEndpointsAndControlPoints() {
        val bounds = SvgPathParser.parse("M0 100 C10 0 20 200 30 100").bounds()

        assertEquals(0.0, bounds.minX, 0.0)
        assertEquals(30.0, bounds.maxX, 0.0)
        assertEquals(0.0, bounds.minY, 1e-9)
        assertEquals(200.0, bounds.maxY, 0.0)
        assertEquals(15.0, bounds.centerX, 0.0)
        assertEquals(100.0, bounds.centerY, 0.0)
    }

    @Test
    fun diagonalIsTheHypotenuseOfTheBounds() {
        assertEquals(5.0, SvgPathParser.parse("M0 0 L3 4").bounds().diagonal, 1e-9)
    }

    @Test
    fun shapeBoundsMatchPathBoundsForStraightLines() {
        val path = SvgPathParser.parse("M1 2 L11 2 L11 12 Z")

        val shapeBounds = path.toShape().bounds2D

        assertEquals(path.bounds().toRectangle(), shapeBounds)
    }

    @Test
    fun pathDataTransformsCoordinatesWithFixedPrecision() {
        val path = SvgPathParser.parse("M0 0 L10 10 C20 20 30 30 40 40 Z")
        val transform = AffineTransform(0.5, 0.0, 0.0, 0.5, 1.0, 2.0)

        assertEquals(
            "M1.00,2.00 L6.00,7.00 C11.00,12.00 16.00,17.00 21.00,22.00 Z",
            path.toPathData(transform, 2),
        )
    }

    @Test
    fun fittedPathDataCentresTheSilhouetteInTheViewport() {
        val silhouette = SvgPathParser.parse("M0 0 L8 0 L8 8 Z")
        val data = BrandAssets.fittedPathData(
            silhouette,
            FitTarget.BOUNDING_DIAGONAL,
            targetSize = 4.0 * Math.sqrt(2.0),
            viewportSize = 16.0,
        )

        // the 8x8 square's diagonal (8*sqrt2) maps to 4*sqrt2, so scale is 0.5 and the box centres at (8, 8)
        assertEquals("M6.00,6.00 L10.00,6.00 L10.00,10.00 Z", data)
    }
}
