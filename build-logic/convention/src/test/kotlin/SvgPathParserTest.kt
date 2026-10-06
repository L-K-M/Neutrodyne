// SPDX-License-Identifier: Unlicense
import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.Test

/** The parser of the silhouette's SVG path subset: absolute `M`, `L`, `C`, `Z` (01 Brand-asset generator). */
class SvgPathParserTest {
    @Test
    fun parsesAbsoluteCommandsAndSubpaths() {
        val path = SvgPathParser.parse("M0 0 L10 0 L10 10 Z M20,20 L30,20 C31,21 32,22 33,23 Z")

        assertEquals(2, path.subpaths.size)
        assertEquals(
            listOf(
                SvgSegment.MoveTo(0.0, 0.0),
                SvgSegment.LineTo(10.0, 0.0),
                SvgSegment.LineTo(10.0, 10.0),
                SvgSegment.Close,
            ),
            path.subpaths[0].segments,
        )
        assertEquals(
            listOf(
                SvgSegment.MoveTo(20.0, 20.0),
                SvgSegment.LineTo(30.0, 20.0),
                SvgSegment.CurveTo(31.0, 21.0, 32.0, 22.0, 33.0, 23.0),
                SvgSegment.Close,
            ),
            path.subpaths[1].segments,
        )
    }

    @Test
    fun repeatedMovetoPairsBecomeImplicitLines() {
        val path = SvgPathParser.parse("M0 0 10 10 20 20")

        assertEquals(
            listOf(
                SvgSegment.MoveTo(0.0, 0.0),
                SvgSegment.LineTo(10.0, 10.0),
                SvgSegment.LineTo(20.0, 20.0),
            ),
            path.subpaths.single().segments,
        )
    }

    @Test
    fun repeatedCoordinateSetsRepeatTheCommand() {
        val path = SvgPathParser.parse("M0 0 L1 1 2 2 C1 1 2 2 3 3 4 4 5 5 6 6 Z")

        val segments = path.subpaths.single().segments
        assertEquals(SvgSegment.LineTo(1.0, 1.0), segments[1])
        assertEquals(SvgSegment.LineTo(2.0, 2.0), segments[2])
        assertEquals(SvgSegment.CurveTo(1.0, 1.0, 2.0, 2.0, 3.0, 3.0), segments[3])
        assertEquals(SvgSegment.CurveTo(4.0, 4.0, 5.0, 5.0, 6.0, 6.0), segments[4])
    }

    @Test
    fun parsesDecimalsNegativeNumbersAndExponents() {
        val path = SvgPathParser.parse("M-1.5,2e1 L-3,-4.25")

        assertEquals(SvgSegment.MoveTo(-1.5, 20.0), path.subpaths.single().segments[0])
        assertEquals(SvgSegment.LineTo(-3.0, -4.25), path.subpaths.single().segments[1])
    }

    @Test
    fun rejectsCommandsOutsideTheSubset() {
        for (d in listOf("m0 0", "M0 0 H10", "M0 0 A5 5 0 0 1 10 10", "M0 0 v10", "M0 0 Q5 5 10 10")) {
            assertThrows<IllegalStateException> { SvgPathParser.parse(d) }
        }
    }

    @Test
    fun rejectsDanglingNumbersAndMissingMoveto() {
        assertThrows<IllegalStateException> { SvgPathParser.parse("M1 2 3") }
        assertThrows<IllegalStateException> { SvgPathParser.parse("L1 2") }
        assertThrows<IllegalStateException> { SvgPathParser.parse("Z") }
        assertThrows<IllegalStateException> { SvgPathParser.parse("10 10") }
    }

    @Test
    fun parseDocumentReadsTheSinglePathElement() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 10 10">
              <path fill="#000000" d="M0 0 L10 0 L10 10 Z"/>
            </svg>
        """.trimIndent()

        assertEquals(1, SvgPathParser.parseDocument(svg).subpaths.size)
        assertThrows<IllegalStateException> {
            SvgPathParser.parseDocument("<svg><path d=\"M0 0 Z\"/><path d=\"M1 1 Z\"/></svg>")
        }
    }

    /** The committed silhouette must keep parsing with the documented subset (its bbox is fixed by the drawing). */
    @Test
    fun parsesTheCommittedSilhouette() {
        val d =
            "M299 204 L299 150 C299 128 310 116 326 116 C342 116 353 128 353 150 L353 204 Z " +
                "M208 866 L208 300 C208 226 260 192 326 192 C392 192 444 226 444 300 L444 866 Z " +
                "M204 872 C204 865 209 860 216 860 L436 860 C443 860 448 865 448 872 L448 964 " +
                "C448 971 443 976 436 976 L216 976 C209 976 204 971 204 964 Z " +
                "M245 974 L271 974 L271 1050 C271 1058 265 1065 258 1065 C251 1065 245 1058 245 1050 Z " +
                "M458 306 L852 700 L794 758 L400 364 Z"

        val path = SvgPathParser.parse(d)

        assertEquals(5, path.subpaths.size)
        assertTrue(path.subpaths.all { it.segments.first() is SvgSegment.MoveTo })
    }
}
