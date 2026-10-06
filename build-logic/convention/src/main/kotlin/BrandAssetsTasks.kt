// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.register
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Regenerates every committed brand asset from `media-sources/` (01 Brand-asset generator, D97). The outputs
 * are committed source files, so this task is a generator, not part of `check`. With
 * `--preview-dir=<dir>` it additionally renders the review previews of `BrandAssetsPreviews`.
 */
abstract class GenerateBrandAssetsTask : DefaultTask() {
    @get:InputFile
    abstract val iconSource: RegularFileProperty

    @get:InputFile
    abstract val svgSource: RegularFileProperty

    @get:OutputFiles
    abstract val outputFiles: MapProperty<String, RegularFile>

    @get:Internal
    @set:Option(option = "preview-dir", description = "Additionally render review previews into this directory")
    var previewDir: String? = null

    init {
        group = "brand assets"
        description = "Regenerates the committed brand assets from media-sources/ (D97); rerun with --rerun-tasks" +
            " when --preview-dir should refresh existing previews"
    }

    @TaskAction
    fun generate() {
        val assetSet = BrandAssets.build(iconSource.get().asFile.readBytes(), svgSource.get().asFile.readText())
        for ((relativePath, bytes) in assetSet.files) {
            val target = outputFiles.get()[relativePath] ?: error("no output location declared for '$relativePath'")
            val file = target.asFile
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        val previewPath = previewDir
        if (previewPath != null) BrandAssetsPreviews.render(assetSet, File(previewPath))
    }
}

/**
 * Regenerates the brand assets into `build/brand-assets/` and fails on any byte difference against the
 * committed files, so hand edits to generated files are impossible to keep (01 Gradle-side policy tasks).
 */
abstract class CheckBrandAssetsTask : DefaultTask() {
    @get:InputFile
    abstract val iconSource: RegularFileProperty

    @get:InputFile
    abstract val svgSource: RegularFileProperty

    @get:InputFiles
    abstract val committedOutputs: ConfigurableFileCollection

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:OutputFiles
    abstract val generatedOutputs: MapProperty<String, RegularFile>

    init {
        group = "verification"
        description = "Fails when the committed brand assets differ from a fresh generation"
    }

    @TaskAction
    fun checkAssets() {
        val assetSet = BrandAssets.build(iconSource.get().asFile.readBytes(), svgSource.get().asFile.readText())
        val problems = mutableListOf<String>()
        for ((relativePath, bytes) in assetSet.files) {
            val generated = generatedOutputs.get()[relativePath]
                ?: error("no generated-output location declared for '$relativePath'")
            generated.asFile.parentFile.mkdirs()
            generated.asFile.writeBytes(bytes)
            val committed = repoRoot.get().file(relativePath).asFile
            when {
                !committed.isFile -> problems += "missing committed output: $relativePath"
                !committed.readBytes().contentEquals(bytes) -> problems += "differs from generation: $relativePath"
            }
        }
        check(problems.isEmpty()) {
            "committed brand assets are stale (regenerate and commit: ./gradlew generateBrandAssets):\n  " +
                problems.joinToString("\n  ")
        }
    }
}

/** Registers the two root tasks and wires `checkBrandAssets` into the root `check` (01 Convention plugins). */
internal fun Project.registerBrandAssetTasks() {
    val rootDirectory = layout.projectDirectory
    val iconFile = rootDirectory.file("media-sources/icon.png")
    val svgFile = rootDirectory.file("media-sources/neutrodyne-mono.svg")
    val outputLocations = BrandAssets.OUTPUT_PATHS.associateWith { rootDirectory.file(it) }

    val generateTask = tasks.register<GenerateBrandAssetsTask>("generateBrandAssets") {
        iconSource.set(iconFile)
        svgSource.set(svgFile)
        outputFiles.set(outputLocations)
    }

    val brandAssetsCheck = tasks.register<CheckBrandAssetsTask>("checkBrandAssets") {
        iconSource.set(iconFile)
        svgSource.set(svgFile)
        committedOutputs.setFrom(outputLocations.values.map { it.asFile })
        repoRoot.set(rootDirectory)
        val generatedDirectory = layout.buildDirectory.dir("brand-assets")
        for (path in BrandAssets.OUTPUT_PATHS) {
            generatedOutputs.put(path, generatedDirectory.map { it.file(path) })
        }
        // the check compares the committed files as they are; when both tasks run, generate goes first
        // (also satisfies Gradle's implicit-dependency validation on the shared paths)
        mustRunAfter(generateTask)
    }

    val rootCheck = try {
        tasks.named("check")
    } catch (_: UnknownTaskException) {
        null
    } ?: tasks.register("check") {
        group = "verification"
        description = "Runs the root project's verification checks"
    }
    rootCheck.configure { dependsOn(brandAssetsCheck) }
}

/** Review previews for the M0b brand review (08 Brand assets); rendered on demand, never committed. */
internal object BrandAssetsPreviews {
    private const val ADAPTIVE_PREVIEW_PX = 432
    private const val NOTIFICATION_PREVIEW_PX = 96

    // Launcher masks show about the inner 72 dp of the 108 dp layer (08, adaptive icons)
    private const val MASK_DIAMETER_FRACTION = 72.0 / 108.0
    private const val SQUIRCLE_EXPONENT = 5.0
    private const val SQUIRCLE_STEPS = 720

    private val TINT = java.awt.Color(0xFFFFFF)
    private val TILE_DARK = java.awt.Color(0x0E1621)
    private val TILE_LIGHT = java.awt.Color(0xF2F4F7)
    private val SHEET_BACKGROUND = java.awt.Color(0xFFFFFF)
    private val SHEET_TEXT = java.awt.Color(0x333333)

    fun render(assetSet: BrandAssetSet, directory: File) {
        directory.mkdirs()
        val mark = assetSet.mark
        val silhouette = assetSet.silhouette

        // The adaptive foreground over navy, masked like a launcher would mask it
        writePng(directory, "adaptive-foreground-circle-432.png") {
            maskedAdaptivePreview(mark, circle(ADAPTIVE_PREVIEW_PX))
        }
        writePng(directory, "adaptive-foreground-squircle-432.png") {
            maskedAdaptivePreview(mark, squircle(ADAPTIVE_PREVIEW_PX))
        }

        // The monochrome layer as a themed icon: tinted, on navy, circle-masked
        writePng(directory, "monochrome-tinted-432.png") {
            val image = BrandAssets.canvas(ADAPTIVE_PREVIEW_PX) {
                color = java.awt.Color(BrandAssets.NAVY_RGB)
                fillRect(0, 0, ADAPTIVE_PREVIEW_PX, ADAPTIVE_PREVIEW_PX)
                BrandAssets.fillSilhouette(
                    this,
                    silhouette,
                    TINT,
                    FitTarget.BOUNDING_DIAGONAL,
                    BrandAssets.SAFE_CIRCLE_DP / BrandAssets.ADAPTIVE_LAYER_DP * ADAPTIVE_PREVIEW_PX,
                    ADAPTIVE_PREVIEW_PX,
                )
            }
            applyMask(image, circle(ADAPTIVE_PREVIEW_PX))
        }

        // The notification small icon at 4x its 24 dp viewport
        writePng(directory, "notification-96.png") {
            BrandAssets.canvas(NOTIFICATION_PREVIEW_PX) {
                color = java.awt.Color(BrandAssets.NAVY_RGB)
                fillRect(0, 0, NOTIFICATION_PREVIEW_PX, NOTIFICATION_PREVIEW_PX)
                BrandAssets.fillSilhouette(
                    this,
                    silhouette,
                    TINT,
                    FitTarget.LONGER_SIDE,
                    (BrandAssets.NOTIFICATION_VIEWPORT_DP - 2 * BrandAssets.NOTIFICATION_PADDING_DP) /
                        BrandAssets.NOTIFICATION_VIEWPORT_DP * NOTIFICATION_PREVIEW_PX,
                    NOTIFICATION_PREVIEW_PX,
                )
            }
        }

        writePng(directory, "desktop-contact-sheet.png") { contactSheet(assetSet) }
    }

    private fun maskedAdaptivePreview(mark: SeparatedMark, mask: java.awt.Shape): BufferedImage {
        val size = ADAPTIVE_PREVIEW_PX
        val icon = BrandAssets.canvas(size) {
            color = java.awt.Color(BrandAssets.NAVY_RGB)
            fillRect(0, 0, size, size)
            BrandAssets.drawMark(
                this,
                mark,
                FitTarget.BOUNDING_DIAGONAL,
                BrandAssets.SAFE_CIRCLE_DP / BrandAssets.ADAPTIVE_LAYER_DP * size,
                size,
            )
        }
        return applyMask(icon, mask)
    }

    private fun applyMask(image: BufferedImage, mask: java.awt.Shape): BufferedImage {
        // the mask as a full-canvas image: DstIn applied by fill() would leave the area outside the shape
        // untouched instead of clearing it (Java2D only composites within the filled shape's bounds)
        val maskImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val maskGraphics = maskImage.createGraphics()
        try {
            maskGraphics.applyBrandRenderingHints()
            maskGraphics.color = java.awt.Color.WHITE
            maskGraphics.fill(mask)
        } finally {
            maskGraphics.dispose()
        }
        val masked = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val g = masked.createGraphics()
        try {
            g.applyBrandRenderingHints()
            g.drawImage(image, 0, 0, null)
            g.composite = java.awt.AlphaComposite.DstIn
            g.drawImage(maskImage, 0, 0, null)
        } finally {
            g.dispose()
        }
        return masked
    }

    private fun circle(sizePx: Int): java.awt.Shape =
        java.awt.geom.Ellipse2D.Double(
            sizePx * (1.0 - MASK_DIAMETER_FRACTION) / 2.0,
            sizePx * (1.0 - MASK_DIAMETER_FRACTION) / 2.0,
            sizePx * MASK_DIAMETER_FRACTION,
            sizePx * MASK_DIAMETER_FRACTION,
        )

    /** A squircle as the superellipse |x|^n + |y|^n = 1 with n = 5, the common Android approximation. */
    private fun squircle(sizePx: Int): java.awt.Shape {
        val half = sizePx * MASK_DIAMETER_FRACTION / 2.0
        val center = sizePx / 2.0
        val path = java.awt.geom.Path2D.Double()
        for (step in 0 until SQUIRCLE_STEPS) {
            val angle = 2.0 * Math.PI * step / SQUIRCLE_STEPS
            val cosine = Math.cos(angle)
            val sine = Math.sin(angle)
            val x = center + half * Math.signum(cosine) * Math.pow(Math.abs(cosine), 2.0 / SQUIRCLE_EXPONENT)
            val y = center + half * Math.signum(sine) * Math.pow(Math.abs(sine), 2.0 / SQUIRCLE_EXPONENT)
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.closePath()
        return path
    }

    /** Every desktop size at 1:1 on one sheet: hicolor/ICO squares, the ICNS grid, tray and template tiles. */
    private fun contactSheet(assetSet: BrandAssetSet): BufferedImage {
        val margin = 24
        val gap = 24
        val labelHeight = 30
        val rowHeight = 256
        val tile = 44
        fun hicolor(size: Int) = ImageIO.read(byteInputStream(assetSet, "desktopApp/icons/png/neutrodyne-$size.png"))

        val sheetWidth = margin * 2 + listOf(
            BrandAssets.HICOLOR_SIZES_PX.filter { it < 512 }.sum() + gap * BrandAssets.HICOLOR_SIZES_PX.size + 256,
            256 + gap + 256 + gap + 128,
            (BrandAssets.TRAY_SIZES_PX.size * 2 + 1) * (tile + gap),
        ).max()
        val sheetHeight = margin * 2 + 2 * (labelHeight + rowHeight) + labelHeight + tile + 2 * gap
        val sheet = BufferedImage(sheetWidth, sheetHeight, BufferedImage.TYPE_INT_ARGB)
        val g = sheet.createGraphics()
        try {
            g.applyBrandRenderingHints()
            g.color = SHEET_BACKGROUND
            g.fillRect(0, 0, sheetWidth, sheetHeight)

            var y = margin
            fun label(text: String) {
                try {
                    g.color = SHEET_TEXT
                    g.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 14)
                    g.drawString(text, margin, y + 18)
                } catch (_: Throwable) {
                    // hosts without fontconfig cannot draw the labels; the sheet still compares the pixels
                }
                y += labelHeight
            }

            label("ICO + Linux hicolor PNG (512 shown at 50 %)")
            var x = margin
            for (size in BrandAssets.HICOLOR_SIZES_PX) {
                val shown = if (size == 512) size / 2 else size
                val image = hicolor(size)
                g.drawImage(image, x, y + rowHeight - shown, shown, shown, null)
                x += shown + gap
            }
            y += rowHeight + gap

            label("macOS ICNS (1024 shown at 25 %, 256 and 128 at 1:1)")
            x = margin
            for (size in listOf(1024, 256, 128)) {
                val image = BrandAssets.icnsImage(assetSet.mark, size)
                val shown = when (size) {
                    1024 -> 256
                    else -> size
                }
                g.drawImage(image, x, y + rowHeight - shown, shown, shown, null)
                x += shown + gap
            }
            y += rowHeight + gap

            label("Tray (dark / light tiles) and macOS menu-bar template")
            x = margin
            for (size in BrandAssets.TRAY_SIZES_PX) {
                val image = ImageIO.read(
                    byteInputStream(assetSet, "desktopApp/icons/tray/neutrodyne-tray-$size.png"),
                )
                drawTile(g, image, x, y, tile, TILE_DARK)
                drawTile(g, image, x + tile + gap / 2, y, tile, TILE_LIGHT)
                x += 2 * tile + gap + gap / 2
            }
            val template = ImageIO.read(byteInputStream(assetSet, BrandAssets.TRAY_TEMPLATE_PATH))
            drawTile(g, template, x, y, tile, TILE_DARK)
            drawTile(g, template, x + tile + gap / 2, y, tile, TILE_LIGHT)
        } finally {
            g.dispose()
        }
        return sheet
    }

    private fun drawTile(g: java.awt.Graphics2D, image: BufferedImage, x: Int, y: Int, tile: Int, background: java.awt.Color) {
        g.color = background
        g.fillRect(x, y, tile, tile)
        g.drawImage(image, x + (tile - image.width) / 2, y + (tile - image.height) / 2, null)
    }

    private fun byteInputStream(assetSet: BrandAssetSet, path: String) =
        assetSet.files.getValue(path).inputStream()

    private fun writePng(directory: File, name: String, image: () -> BufferedImage) {
        ImageIO.write(image(), "png", File(directory, name))
    }
}
