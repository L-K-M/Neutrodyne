// SPDX-License-Identifier: Unlicense
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.TreeMap
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** The full-colour mark extracted from the owner's artwork: un-blended colours, soft alpha, its bounding box. */
internal class SeparatedMark(val image: BufferedImage, val bounds: Bounds)

/** Everything `generateBrandAssets` produces, plus the intermediates the review previews are rendered from. */
internal class BrandAssetSet(
    val files: Map<String, ByteArray>,
    internal val mark: SeparatedMark,
    internal val silhouette: SvgPath,
)

/** How a mark is fitted into its target area (08 Brand assets). */
internal enum class FitTarget {
    /** The bounding box's diagonal equals the target size — the mark fits the 66-dp safe circle. */
    BOUNDING_DIAGONAL,

    /**
     * The longer bounding-box side equals the target size — the mark at 75 % of a rounded square's side (the
     * near-square N is taller than wide, so fitting by width alone let its caps touch the square's edge),
     * tray glyphs and the notification silhouette.
     */
    LONGER_SIDE,
}

/**
 * The brand-asset generator (01 Brand-asset generator, 08 Brand assets, D97): JDK `java.awt`/`javax.imageio`
 * only, fixed bicubic resampling, no timestamps — byte-for-byte reproducible. Pure helpers are `internal` so
 * build-logic's tests cover them.
 */
internal object BrandAssets {
    // Colours measured 2026-10-05 from media-sources/icon.png (D97)
    const val NAVY_RGB = 0x00192E
    const val AMBER_RGB = 0xF3881C
    private val NAVY = Color(NAVY_RGB)
    private val AMBER = Color(AMBER_RGB)
    private val TEMPLATE_BLACK = Color(0x000000)

    // Soft-alpha separation against navy (08 Brand assets): divisor 96, cut-off 0.10 (tuned in the M0b review)
    const val ALPHA_DIVISOR = 96.0
    const val ALPHA_CUTOFF = 0.10

    // Android adaptive icon (08): 108 dp layers, the mark inside a centred 66 dp safe circle
    const val ADAPTIVE_LAYER_DP = 108.0
    const val SAFE_CIRCLE_DP = 66.0
    val FOREGROUND_DENSITY_PX: List<Pair<String, Int>> = listOf(
        "mdpi" to 108,
        "hdpi" to 162,
        "xhdpi" to 216,
        "xxhdpi" to 324,
        "xxxhdpi" to 432,
    )

    // Notification small icon (08): 24 dp viewport with 2 dp padding, white on transparent
    const val NOTIFICATION_VIEWPORT_DP = 24.0
    const val NOTIFICATION_PADDING_DP = 2.0

    // Desktop icon sets (08): ICO sizes, Linux hicolor sizes, tray sizes, template image
    val ICO_SIZES_PX = listOf(16, 24, 32, 48, 64, 128, 256)
    val HICOLOR_SIZES_PX = listOf(16, 22, 24, 32, 48, 64, 128, 256, 512)
    val TRAY_SIZES_PX = listOf(16, 22, 32)
    const val SILHOUETTE_MAX_PX = 32
    const val TRAY_PADDING_PX = 1
    const val TEMPLATE_CANVAS_PX = 36 // 18 x 18 pt content at @2x (08)
    const val TEMPLATE_GLYPH_PX = 32 // 1 pt padding at @2x, mirroring the tray rule (fixed 2026-10-06)

    // Rounded-square base of the ICO and Linux PNG icons (08; corner ratio and mark width fixed 2026-10-06
    // to the macOS grid's proportions)
    const val SQUARE_MARGIN_FRACTION = 0.06
    const val SQUARE_CORNER_FRACTION = 185.4 / 824.0
    const val SQUARE_MARK_WIDTH_FRACTION = 0.75

    // macOS 11-15 icon grid (08): 824 px rounded rectangle, 185.4 px corners, 100 px margin, mark at 75 %
    const val ICNS_CANVAS_PX = 1024.0
    const val ICNS_RECT_PX = 824.0
    const val ICNS_CORNER_PX = 185.4
    const val ICNS_MARK_WIDTH_FRACTION = 0.75

    // In-app mark (08): one 512 px PNG on a navy rounded square with 28 % corner radius
    const val BRAND_MARK_PX = 512
    const val BRAND_MARK_CORNER_FRACTION = 0.28

    // ICNS entries: pixel size and ICNS type code (16..512 @1x plus the @2x set to 1024)
    val ICNS_ENTRIES_PX: List<Pair<String, Int>> = listOf(
        "icp4" to 16,
        "icp5" to 32,
        "ic11" to 32, // 16 @2x
        "ic12" to 64, // 32 @2x
        "ic07" to 128,
        "ic08" to 256,
        "ic13" to 256, // 128 @2x
        "ic09" to 512,
        "ic14" to 512, // 256 @2x
        "ic10" to 1024, // 512 @2x
    )

    private const val RES_ROOT = "app/src/main/res"
    const val BRAND_MARK_PATH = "core/designsystem/src/commonMain/composeResources/drawable/brand_mark.png"
    const val ICO_PATH = "desktopApp/icons/neutrodyne.ico"
    const val ICNS_PATH = "desktopApp/icons/neutrodyne.icns"
    const val TRAY_TEMPLATE_PATH = "desktopApp/icons/tray/neutrodyne-template.png"

    /** Every committed output, repo-relative; `build` produces exactly this set. */
    val OUTPUT_PATHS: List<String> = buildList {
        add("$RES_ROOT/drawable/ic_launcher_background.xml")
        add("$RES_ROOT/drawable/ic_launcher_monochrome.xml")
        add("$RES_ROOT/drawable/ic_stat_neutrodyne.xml")
        add("$RES_ROOT/drawable/ic_splash_neutrodyne.xml")
        add("$RES_ROOT/mipmap-anydpi-v26/ic_launcher.xml")
        add("$RES_ROOT/mipmap-anydpi-v26/ic_launcher_round.xml")
        for ((density, _) in FOREGROUND_DENSITY_PX) add("$RES_ROOT/mipmap-$density/ic_launcher_foreground.png")
        add(BRAND_MARK_PATH)
        add(ICO_PATH)
        add(ICNS_PATH)
        for (size in HICOLOR_SIZES_PX) add("desktopApp/icons/png/neutrodyne-$size.png")
        add(TRAY_TEMPLATE_PATH)
        for (size in TRAY_SIZES_PX) add("desktopApp/icons/tray/neutrodyne-tray-$size.png")
    }

    /** Reads the two committed sources and produces every output as repo-relative path -> bytes. */
    fun build(iconPng: ByteArray, svgText: String): BrandAssetSet {
        val source = ImageIO.read(ByteArrayInputStream(iconPng))
        check(source.width > 0 && source.width == source.height) {
            "media-sources/icon.png must be square, got ${source.width}x${source.height}"
        }
        val mark = separateMarkFromNavy(source)
        val silhouette = SvgPathParser.parseDocument(svgText)
        val files = TreeMap<String, ByteArray>()

        fun put(path: String, bytes: ByteArray) {
            check(files.put(path, bytes) == null) { "duplicate brand-asset output '$path'" }
        }

        fun putXml(path: String, xml: String) = put(path, xml.toByteArray(Charsets.UTF_8))

        putXml("$RES_ROOT/drawable/ic_launcher_background.xml", adaptiveIconBackgroundXml())
        putXml("$RES_ROOT/drawable/ic_launcher_monochrome.xml", monochromeLauncherXml(silhouette))
        putXml("$RES_ROOT/drawable/ic_stat_neutrodyne.xml", notificationIconXml(silhouette))
        putXml("$RES_ROOT/drawable/ic_splash_neutrodyne.xml", splashIconXml())
        putXml("$RES_ROOT/mipmap-anydpi-v26/ic_launcher.xml", adaptiveIconXml())
        putXml("$RES_ROOT/mipmap-anydpi-v26/ic_launcher_round.xml", adaptiveIconXml())
        for ((density, px) in FOREGROUND_DENSITY_PX) {
            put("$RES_ROOT/mipmap-$density/ic_launcher_foreground.png", PngBytes.of(adaptiveForegroundImage(mark, px)))
        }
        put(BRAND_MARK_PATH, PngBytes.of(brandMarkImage(mark)))
        put(ICO_PATH, IcoWriter.write(ICO_SIZES_PX.map { squareIconImage(mark, silhouette, it) }))
        put(ICNS_PATH, IcnsWriter.write(ICNS_ENTRIES_PX.map { (type, px) -> IcnsEntry(type, icnsImage(mark, px)) }))
        for (size in HICOLOR_SIZES_PX) put("desktopApp/icons/png/neutrodyne-$size.png", PngBytes.of(squareIconImage(mark, silhouette, size)))
        put(TRAY_TEMPLATE_PATH, PngBytes.of(trayTemplateImage(silhouette)))
        for (size in TRAY_SIZES_PX) put("desktopApp/icons/tray/neutrodyne-tray-$size.png", PngBytes.of(trayImage(silhouette, size)))

        check(files.keys == OUTPUT_PATHS.toSet()) {
            "generated outputs ${files.keys} do not match the declared OUTPUT_PATHS"
        }
        return BrandAssetSet(files, mark, silhouette)
    }

    // ---- Soft-alpha separation against the measured navy (08 Brand assets) ----

    /**
     * alpha = largest channel *increase* over navy / 96, clamped to 0..1; the glow keeps its falloff. Only
     * brightening counts: the artwork's background vignette is darker than the corner navy (up to 27 levels),
     * and an absolute difference would turn it into a faint rectangle behind the mark (M0b review, 2026-10-06).
     */
    internal fun softAlphaAgainstNavy(r: Int, g: Int, b: Int): Double {
        val navy = NAVY_RGB
        val increase = max(
            r - (navy shr 16 and 0xFF),
            max(g - (navy shr 8 and 0xFF), b - (navy and 0xFF)),
        ).coerceAtLeast(0)
        return minOf(increase / ALPHA_DIVISOR, 1.0)
    }

    /** fg = (px - (1 - alpha) * navy) / alpha per channel, clamped to 0..255. */
    internal fun unblendChannel(channel: Int, navyChannel: Int, alpha: Double): Int =
        (((channel - (1.0 - alpha) * navyChannel) / alpha)).roundToInt().coerceIn(0, 255)

    /** Separates the glowing mark from the navy background; the bounds are the union of kept pixels. */
    internal fun separateMarkFromNavy(source: BufferedImage): SeparatedMark {
        val width = source.width
        val height = source.height
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = source.getRGB(x, y)
                val r = rgb shr 16 and 0xFF
                val g = rgb shr 8 and 0xFF
                val b = rgb and 0xFF
                val alpha = softAlphaAgainstNavy(r, g, b)
                if (alpha < ALPHA_CUTOFF) {
                    out.setRGB(x, y, 0)
                    continue
                }
                val alphaByte = (alpha * 255.0).roundToInt().coerceIn(0, 255)
                val fgR = unblendChannel(r, NAVY.red, alpha)
                val fgG = unblendChannel(g, NAVY.green, alpha)
                val fgB = unblendChannel(b, NAVY.blue, alpha)
                out.setRGB(x, y, alphaByte shl 24 or (fgR shl 16) or (fgG shl 8) or fgB)
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        check(maxX >= minX && maxY >= minY) { "no pixel of icon.png is above the alpha cut-off $ALPHA_CUTOFF" }
        // exclusive max, so bounds.width is the bounding box's pixel count
        return SeparatedMark(out, Bounds(minX.toDouble(), minY.toDouble(), (maxX + 1).toDouble(), (maxY + 1).toDouble()))
    }

    // ---- Rendering ----

    /** Fixed quality hints: bicubic resampling via RenderingHints, as 01 requires for reproducibility. */
    internal fun canvas(sizePx: Int, draw: Graphics2D.() -> Unit): BufferedImage {
        val image = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.applyBrandRenderingHints()
            g.draw()
        } finally {
            g.dispose()
        }
        return image
    }

    /** Draws the full-colour mark fitted per [fit] so that the fitted dimension equals [targetSize] px, centred. */
    internal fun drawMark(g: Graphics2D, mark: SeparatedMark, fit: FitTarget, targetSize: Double, canvasSizePx: Int) {
        val bounds = mark.bounds
        val dimension = when (fit) {
            FitTarget.BOUNDING_DIAGONAL -> bounds.diagonal
            FitTarget.LONGER_SIDE -> max(bounds.width, bounds.height)
        }
        val scale = targetSize / dimension
        val dstW = (bounds.width * scale).roundToInt().coerceAtLeast(1)
        val dstH = (bounds.height * scale).roundToInt().coerceAtLeast(1)
        val cropX = bounds.minX.toInt()
        val cropY = bounds.minY.toInt()
        val cropW = bounds.maxX.toInt() - cropX
        val cropH = bounds.maxY.toInt() - cropY
        val cropped = mark.image.getSubimage(cropX, cropY, cropW, cropH)
        val resampled = resample(cropped, dstW, dstH)
        // integer placement: the final draw is a pure translation, so no resampling sneaks in
        val x = ((canvasSizePx - dstW) / 2.0).roundToInt()
        val y = ((canvasSizePx - dstH) / 2.0).roundToInt()
        g.drawImage(resampled, AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()), null)
    }

    /** Draws the vector silhouette fitted per [fit], filled with [color], centred on the canvas. */
    internal fun fillSilhouette(
        g: Graphics2D,
        silhouette: SvgPath,
        color: Color,
        fit: FitTarget,
        targetSize: Double,
        canvasSizePx: Int,
    ) {
        val bounds = silhouette.bounds()
        val dimension = when (fit) {
            FitTarget.BOUNDING_DIAGONAL -> bounds.diagonal
            FitTarget.LONGER_SIDE -> max(bounds.width, bounds.height)
        }
        val scale = targetSize / dimension
        val tx = canvasSizePx / 2.0 - scale * bounds.centerX
        val ty = canvasSizePx / 2.0 - scale * bounds.centerY
        val transform = AffineTransform(scale, 0.0, 0.0, scale, tx, ty)
        g.color = color
        g.fill(transform.createTransformedShape(silhouette.toShape()))
    }

    /** Halves iteratively before the final bicubic step, so large downscales keep their detail. */
    internal fun resample(source: BufferedImage, dstW: Int, dstH: Int): BufferedImage {
        var current = source
        while (current.width >= dstW * 2 && current.height >= dstH * 2) {
            current = scaleTo(current, current.width / 2, current.height / 2)
        }
        return scaleTo(current, dstW, dstH)
    }

    private fun scaleTo(source: BufferedImage, dstW: Int, dstH: Int): BufferedImage {
        if (source.width == dstW && source.height == dstH) return source
        val out = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.applyBrandRenderingHints()
            val scale = AffineTransform.getScaleInstance(dstW.toDouble() / source.width, dstH.toDouble() / source.height)
            g.drawImage(source, scale, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun fillRoundedSquare(g: Graphics2D, color: Color, marginPx: Double, cornerPx: Double, canvasPx: Int) {
        val side = canvasPx - 2 * marginPx
        g.color = color
        g.fill(RoundRectangle2D.Double(marginPx, marginPx, side, side, cornerPx * 2, cornerPx * 2))
    }

    // ---- Output images ----

    /** The adaptive-icon foreground: the full-colour mark in the 66 dp safe circle, transparent elsewhere. */
    internal fun adaptiveForegroundImage(mark: SeparatedMark, sizePx: Int): BufferedImage = canvas(sizePx) {
        drawMark(this, mark, FitTarget.BOUNDING_DIAGONAL, SAFE_CIRCLE_DP / ADAPTIVE_LAYER_DP * sizePx, sizePx)
    }

    /** The in-app mark: the foreground on a navy rounded square with 28 % corner radius (08). */
    internal fun brandMarkImage(mark: SeparatedMark): BufferedImage = canvas(BRAND_MARK_PX) {
        fillRoundedSquare(this, NAVY, 0.0, BRAND_MARK_CORNER_FRACTION * BRAND_MARK_PX, BRAND_MARK_PX)
        drawMark(this, mark, FitTarget.BOUNDING_DIAGONAL, SAFE_CIRCLE_DP / ADAPTIVE_LAYER_DP * BRAND_MARK_PX, BRAND_MARK_PX)
    }

    /** ICO and Linux PNG icons: >= 48 px the full-colour mark on the navy rounded square, <= 32 px the silhouette. */
    internal fun squareIconImage(mark: SeparatedMark, silhouette: SvgPath, sizePx: Int): BufferedImage {
        val side = sizePx * (1.0 - 2 * SQUARE_MARGIN_FRACTION)
        val corner = SQUARE_CORNER_FRACTION * side
        val margin = sizePx * SQUARE_MARGIN_FRACTION
        return canvas(sizePx) {
            fillRoundedSquare(this, NAVY, margin, corner, sizePx)
            if (sizePx <= SILHOUETTE_MAX_PX) {
                fillSilhouette(this, silhouette, AMBER, FitTarget.LONGER_SIDE, SQUARE_MARK_WIDTH_FRACTION * side, sizePx)
            } else {
                drawMark(this, mark, FitTarget.LONGER_SIDE, SQUARE_MARK_WIDTH_FRACTION * side, sizePx)
            }
        }
    }

    /** macOS icons: the 1024 px grid (824 px rectangle, 185.4 px corners, 100 px margin) scaled to [sizePx]. */
    internal fun icnsImage(mark: SeparatedMark, sizePx: Int): BufferedImage {
        val scale = sizePx / ICNS_CANVAS_PX
        val side = ICNS_RECT_PX * scale
        val margin = (ICNS_CANVAS_PX - ICNS_RECT_PX) / 2.0 * scale
        val corner = ICNS_CORNER_PX * scale
        return canvas(sizePx) {
            fillRoundedSquare(this, NAVY, margin, corner, sizePx)
            drawMark(this, mark, FitTarget.LONGER_SIDE, ICNS_MARK_WIDTH_FRACTION * side, sizePx)
        }
    }

    /** Windows and Linux tray: the amber silhouette on transparent with 1 px padding (08). */
    internal fun trayImage(silhouette: SvgPath, sizePx: Int): BufferedImage = canvas(sizePx) {
        fillSilhouette(this, silhouette, AMBER, FitTarget.LONGER_SIDE, (sizePx - 2 * TRAY_PADDING_PX).toDouble(), sizePx)
    }

    /** macOS menu-bar template image: black with alpha only, 36 px (08). */
    internal fun trayTemplateImage(silhouette: SvgPath): BufferedImage =
        canvas(TEMPLATE_CANVAS_PX) {
            fillSilhouette(this, silhouette, TEMPLATE_BLACK, FitTarget.LONGER_SIDE, TEMPLATE_GLYPH_PX.toDouble(), TEMPLATE_CANVAS_PX)
        }

    // ---- Output XML ----

    private fun adaptiveIconXml(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<!-- SPDX-License-Identifier: Unlicense -->
        |<!-- Generated by generateBrandAssets from media-sources/ (D97); never edit by hand (08 Brand assets). -->
        |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        |  <background android:drawable="@drawable/ic_launcher_background"/>
        |  <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
        |  <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
        |</adaptive-icon>
        |
    """.trimMargin()

    private fun adaptiveIconBackgroundXml(): String {
        val layer = ADAPTIVE_LAYER_DP.toInt()
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- SPDX-License-Identifier: Unlicense -->
            |<!-- Generated by generateBrandAssets: solid measured navy #00192E (D97); never edit by hand. -->
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="${layer}dp"
            |    android:height="${layer}dp"
            |    android:viewportWidth="$layer"
            |    android:viewportHeight="$layer">
            |  <path
            |      android:fillColor="#${hex(NAVY_RGB)}"
            |      android:pathData="M0,0 L$layer,0 L$layer,$layer L0,$layer Z"/>
            |</vector>
            |
        """.trimMargin()
    }

    /** The monochrome layer: the silhouette at the foreground's size and position, one opaque colour (08). */
    private fun monochromeLauncherXml(silhouette: SvgPath): String {
        val layer = ADAPTIVE_LAYER_DP.toInt()
        val pathData = fittedPathData(silhouette, FitTarget.BOUNDING_DIAGONAL, SAFE_CIRCLE_DP, ADAPTIVE_LAYER_DP)
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- SPDX-License-Identifier: Unlicense -->
            |<!-- Generated by generateBrandAssets from media-sources/neutrodyne-mono.svg (D97); never edit. -->
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="${layer}dp"
            |    android:height="${layer}dp"
            |    android:viewportWidth="$layer"
            |    android:viewportHeight="$layer">
            |  <path
            |      android:fillColor="#FFFFFFFF"
            |      android:pathData="$pathData"/>
            |</vector>
            |
        """.trimMargin()
    }

    /** The notification small icon: the silhouette in a 24 dp viewport with 2 dp padding, white (08). */
    private fun notificationIconXml(silhouette: SvgPath): String {
        val viewport = NOTIFICATION_VIEWPORT_DP.toInt()
        val glyph = (NOTIFICATION_VIEWPORT_DP - 2 * NOTIFICATION_PADDING_DP)
        val pathData = fittedPathData(silhouette, FitTarget.LONGER_SIDE, glyph, NOTIFICATION_VIEWPORT_DP)
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- SPDX-License-Identifier: Unlicense -->
            |<!-- Generated by generateBrandAssets from media-sources/neutrodyne-mono.svg (D97); never edit. -->
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="${viewport}dp"
            |    android:height="${viewport}dp"
            |    android:viewportWidth="$viewport"
            |    android:viewportHeight="$viewport">
            |  <path
            |      android:fillColor="#FFFFFFFF"
            |      android:pathData="$pathData"/>
            |</vector>
            |
        """.trimMargin()
    }

    /**
     * The splash icon (08: "the foreground without an icon background"): a generated alias of the foreground
     * mipmap, so the splash ships no duplicate raster; `windowSplashScreenAnimatedIcon` references it.
     */
    private fun splashIconXml(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<!-- SPDX-License-Identifier: Unlicense -->
        |<!-- Generated by generateBrandAssets: the adaptive-icon foreground, the splash mark (08 Brand assets). -->
        |<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
        |    android:src="@mipmap/ic_launcher_foreground"/>
        |
    """.trimMargin()

    /** Transforms the silhouette into a [viewportSize] dp viewport: fitted per [fit], centred, 2 decimals. */
    internal fun fittedPathData(silhouette: SvgPath, fit: FitTarget, targetSize: Double, viewportSize: Double): String {
        val bounds = silhouette.bounds()
        val dimension = when (fit) {
            FitTarget.BOUNDING_DIAGONAL -> bounds.diagonal
            FitTarget.LONGER_SIDE -> max(bounds.width, bounds.height)
        }
        val scale = targetSize / dimension
        val tx = viewportSize / 2.0 - scale * bounds.centerX
        val ty = viewportSize / 2.0 - scale * bounds.centerY
        return silhouette.toPathData(AffineTransform(scale, 0.0, 0.0, scale, tx, ty), PATH_DATA_DECIMALS)
    }

    private const val PATH_DATA_DECIMALS = 2

    private fun hex(rgb: Int): String = String.format(Locale.ROOT, "%06X", rgb)
}

/** Fixed quality hints: bicubic resampling via RenderingHints, as 01 requires for reproducibility. */
internal fun Graphics2D.applyBrandRenderingHints() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
}
