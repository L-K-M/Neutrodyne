// SPDX-License-Identifier: Unlicense
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.util.Locale

/**
 * One absolute command of an SVG path, restricted to the subset the brand silhouette uses
 * (01 Brand-asset generator: `M`, `L`, `C`, `Z` only, so no SVG library is needed).
 */
internal sealed interface SvgSegment {
    data class MoveTo(val x: Double, val y: Double) : SvgSegment

    data class LineTo(val x: Double, val y: Double) : SvgSegment

    data class CurveTo(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val x: Double,
        val y: Double,
    ) : SvgSegment

    data object Close : SvgSegment
}

/** A series of commands starting with `M`; the nonzero fill rule unions the silhouette's clockwise subpaths. */
internal data class SvgSubpath(val segments: List<SvgSegment>)

/** A parsed `d` attribute: every subpath of the silhouette in source-canvas coordinates. */
internal data class SvgPath(val subpaths: List<SvgSubpath>) {
    /** Axis-aligned bounds over every endpoint and cubic control point (a slight superset of the true bounds). */
    fun bounds(): Bounds {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (subpath in subpaths) {
            for (segment in subpath.segments) {
                val xs: DoubleArray
                val ys: DoubleArray
                when (segment) {
                    is SvgSegment.MoveTo -> {
                        xs = doubleArrayOf(segment.x)
                        ys = doubleArrayOf(segment.y)
                    }
                    is SvgSegment.LineTo -> {
                        xs = doubleArrayOf(segment.x)
                        ys = doubleArrayOf(segment.y)
                    }
                    is SvgSegment.CurveTo -> {
                        xs = doubleArrayOf(segment.x1, segment.x2, segment.x)
                        ys = doubleArrayOf(segment.y1, segment.y2, segment.y)
                    }
                    SvgSegment.Close -> continue
                }
                for (x in xs) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                }
                for (y in ys) {
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        check(minX <= maxX && minY <= maxY) { "the SVG path has no drawable commands" }
        return Bounds(minX, minY, maxX, maxY)
    }

    /** The silhouette as an AWT shape for rasterisation; the nonzero rule unions the clockwise subpaths. */
    fun toShape(): Shape {
        val shape = Path2D.Float(Path2D.WIND_NON_ZERO)
        for (subpath in subpaths) {
            for (segment in subpath.segments) {
                when (segment) {
                    is SvgSegment.MoveTo -> shape.moveTo(segment.x, segment.y)
                    is SvgSegment.LineTo -> shape.lineTo(segment.x, segment.y)
                    is SvgSegment.CurveTo ->
                        shape.curveTo(segment.x1, segment.y1, segment.x2, segment.y2, segment.x, segment.y)
                    SvgSegment.Close -> shape.closePath()
                }
            }
            shape.closePath()
        }
        return shape
    }

    /**
     * The path as Android `pathData` in a target viewport: absolute `M`/`L`/`C`/`Z`, every coordinate passed
     * through [transform] and formatted with [decimals] fixed digits so regeneration is byte-identical.
     */
    fun toPathData(transform: AffineTransform, decimals: Int): String {
        val format = StringBuilder()
        for (subpath in subpaths) {
            for (segment in subpath.segments) {
                when (segment) {
                    is SvgSegment.MoveTo -> format.append(point("M", segment.x, segment.y, transform, decimals))
                    is SvgSegment.LineTo -> format.append(point("L", segment.x, segment.y, transform, decimals))
                    is SvgSegment.CurveTo -> format.append(
                        point(
                            "C",
                            segment.x1,
                            segment.y1,
                            transform,
                            decimals,
                            segment.x2,
                            segment.y2,
                            segment.x,
                            segment.y,
                        ),
                    )
                    SvgSegment.Close -> format.append("Z")
                }
                format.append(' ')
            }
        }
        return format.toString().trim()
    }

    private fun point(
        command: String,
        firstX: Double,
        firstY: Double,
        transform: AffineTransform,
        decimals: Int,
        vararg rest: Double,
    ): String {
        val out = StringBuilder(command)
        var x = firstX
        var y = firstY
        var index = 0
        while (true) {
            val point = transform.transform(java.awt.geom.Point2D.Double(x, y), null)
            out.append(number(point.x, decimals)).append(',').append(number(point.y, decimals))
            if (index >= rest.size) break
            out.append(' ') // a separator between coordinate pairs keeps the numbers unambiguous
            x = rest[index]
            y = rest[index + 1]
            index += 2
        }
        return out.toString()
    }

    private fun number(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)
}

/** Axis-aligned bounding box in source pixels; [diagonal] drives the 66-dp safe-circle fit (08 Brand assets). */
internal data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX

    val height: Double get() = maxY - minY

    val centerX: Double get() = (minX + maxX) / 2

    val centerY: Double get() = (minY + maxY) / 2

    val diagonal: Double get() = Math.hypot(width, height)

    fun toRectangle(): Rectangle2D.Double = Rectangle2D.Double(minX, minY, width, height)
}

/**
 * Minimal parser for the silhouette's path subset: absolute `M`, `L`, `C`, `Z` with commas, whitespace and
 * negative numbers; numbers may repeat implicitly per command (a repeated `M` pair means a line, per SVG).
 * Any other command fails loudly so a future hand edit cannot silently change the shape.
 */
internal object SvgPathParser {
    fun parse(d: String): SvgPath {
        val scanner = Scanner(d)
        val subpaths = mutableListOf<SvgSubpath>()
        var current: MutableList<SvgSegment>? = null
        while (scanner.skipSeparatorsAndPeek() != null) {
            val command = scanner.readCommand()
            when (command) {
                'M' -> {
                    val pairs = scanner.readNumberGroups(2)
                    for ((index, pair) in pairs.withIndex()) {
                        if (index == 0) {
                            current?.let { subpaths.add(SvgSubpath(it)) }
                            current = mutableListOf(SvgSegment.MoveTo(pair[0], pair[1]))
                        } else {
                            // SVG: coordinate pairs after the first M are implicit line-to commands
                            current?.let { it.add(SvgSegment.LineTo(pair[0], pair[1])) }
                                ?: error("implicit line-to before any moveto")
                        }
                    }
                }
                'L' -> for (pair in scanner.readNumberGroups(2)) {
                    current?.let { it.add(SvgSegment.LineTo(pair[0], pair[1])) }
                        ?: error("line-to before any moveto")
                }
                'C' -> for (sextet in scanner.readNumberGroups(6)) {
                    current?.let {
                        it.add(SvgSegment.CurveTo(sextet[0], sextet[1], sextet[2], sextet[3], sextet[4], sextet[5]))
                    } ?: error("curve-to before any moveto")
                }
                'Z' -> {
                    current?.let {
                        it.add(SvgSegment.Close)
                        subpaths.add(SvgSubpath(it))
                    } ?: error("closepath before any moveto")
                    current = null
                }
                else -> error("unsupported path command '$command' (the silhouette uses M, L, C, Z only)")
            }
        }
        current?.let { subpaths.add(SvgSubpath(it)) }
        check(subpaths.isNotEmpty()) { "the SVG path is empty" }
        return SvgPath(subpaths)
    }

    /** Extracts the `d` attribute of the single `<path>` element of the silhouette SVG. */
    fun parseDocument(svgText: String): SvgPath {
        val matches = Regex("""\bd\s*=\s*"([^"]+)"""").findAll(svgText).toList()
        check(matches.size == 1) { "expected exactly one path element with a d attribute, found ${matches.size}" }
        return parse(matches[0].groupValues[1])
    }

    private class Scanner(private val text: String) {
        private var index = 0

        /** Skips whitespace and commas; returns the next command letter without consuming it, or null at the end. */
        fun skipSeparatorsAndPeek(): Char? {
            while (index < text.length && (text[index].isWhitespace() || text[index] == ',')) index++
            return if (index < text.length) text[index] else null
        }

        fun readCommand(): Char {
            val c = text[index++]
            check(c.isLetter()) { "expected a command letter at offset ${index - 1}, found '$c'" }
            return c
        }

        /** Reads every number that belongs to the current command, chunked in [arity]-sized groups. */
        fun readNumberGroups(arity: Int): List<DoubleArray> {
            val numbers = mutableListOf<Double>()
            while (true) {
                skipSeparators()
                if (index >= text.length || text[index].isLetter()) break
                numbers.add(readNumber())
            }
            check(numbers.size % arity == 0 && numbers.isNotEmpty()) {
                "expected coordinates in groups of $arity, found ${numbers.size} numbers"
            }
            return numbers.chunked(arity).map { it.toDoubleArray() }
        }

        private fun skipSeparators() {
            while (index < text.length && (text[index].isWhitespace() || text[index] == ',')) index++
        }

        private fun readNumber(): Double {
            val start = index
            if (index < text.length && (text[index] == '-' || text[index] == '+')) index++
            while (index < text.length && text[index].isDigit()) index++
            if (index < text.length && text[index] == '.') {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            // optional exponent, as written by SVG editors (0.5e-3)
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                val afterE = index + 1
                var cursor = afterE
                if (cursor < text.length && (text[cursor] == '-' || text[cursor] == '+')) cursor++
                if (cursor < text.length && text[cursor].isDigit()) {
                    index = cursor
                    while (index < text.length && text[index].isDigit()) index++
                }
            }
            check(index > start) { "expected a number at offset $start" }
            return text.substring(start, index).toDouble()
        }
    }
}
