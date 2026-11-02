// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.math.abs

/**
 * `FfmpegCorpusTest`: MD0 AC1 on this host (11 Testing, PLAN MD0 AC1).
 *
 * Every corpus file is demuxed and decoded through the hand-written FFM bindings
 * against the FFmpeg build `buildFfmpeg` produced for the host this test runs on,
 * and the measured sample counts, chapters and seek positions are compared with
 * the reference manifest that `scripts/fixtures/corpus_reference.py` generated
 * from the containers' own structure — never by decoding them, so the expectation
 * is independent of the code under test.
 *
 * The suite needs the built libraries, so the `ffmpegTest` task runs
 * `:playback:native:buildFfmpeg` first and passes its output directory here. A
 * missing build fails the suite: an empty or skipped suite must never look like
 * success.
 */
class FfmpegCorpusTest {
    /**
     * One file's measured result, so a failure names the file, the expectation
     * and what the bindings actually produced.
     */
    private data class Measurement(
        val file: String,
        val expectedSamples: Long,
        val decodedSamples: Long,
        val rawSamples: Long,
        val container: String,
        val chapters: List<EngineChapter>,
        val expectedChapters: List<CorpusChapter>,
        val seekErrorMs: Long,
        val durationMs: Long?,
    ) {
        fun describe(): String =
            "$file [$container] expected=$expectedSamples decoded=$decodedSamples " +
                "raw=$rawSamples duration=${durationMs}ms chapters=${chapters.size} seekError=${seekErrorMs}ms"
    }

    @Test
    fun `every corpus file demuxes and decodes with the reference sample count`() {
        val manifest = readManifest()
        val measurements = mutableListOf<Measurement>()

        for (entry in manifest.files) {
            val bytes = corpusFile(entry.file).readBytes()
            FfmpegPipeline.open(MemoryByteSource(bytes), hostFfmpegDirectory()).use { pipeline ->
                val blocks = pipeline.decodeAll()
                val decoded = blocks.sumOf { it.samples.size.toLong() / it.channels }
                measurements +=
                    Measurement(
                        file = entry.file,
                        expectedSamples = entry.expectedSamples,
                        decodedSamples = decoded,
                        rawSamples = pipeline.rawDecodedSamples,
                        container = pipeline.demuxer.containerName,
                        chapters = pipeline.demuxer.chapters,
                        expectedChapters = entry.chapters,
                        seekErrorMs = seekError(pipeline, entry),
                        durationMs = pipeline.demuxer.durationMs,
                    )
            }
        }

        measurements.forEach { println("MD0 corpus: ${it.describe()}") }

        val failures =
            measurements.mapNotNull { measurement ->
                val problems = mutableListOf<String>()
                if (measurement.decodedSamples != measurement.expectedSamples) {
                    problems +=
                        "decoded ${measurement.decodedSamples} samples, " +
                        "expected ${measurement.expectedSamples} (raw ${measurement.rawSamples})"
                }
                chapterProblemOf(measurement)?.let { problems += it }
                if (measurement.seekErrorMs > SEEK_TOLERANCE_MS) {
                    problems += "the first sample after a seek is ${measurement.seekErrorMs} ms off"
                }
                if (problems.isEmpty()) null else "${measurement.file}: ${problems.joinToString("; ")}"
            }

        val report =
            "the corpus did not decode as the manifest expects:\n" +
                failures.joinToString("\n") +
                "\n" +
                measurements.joinToString("\n") { it.describe() }
        assertTrue(report, failures.isEmpty())
        assertEquals(manifest.files.size, measurements.size)
    }

    @Test
    fun `seeks at the start middle and near the end produce timestamped audio`() {
        for (entry in readManifest().files) {
            val duration = entry.expectedSamples * 1000 / entry.sampleRate
            FfmpegPipeline.open(MemoryByteSource(corpusFile(entry.file).readBytes()), hostFfmpegDirectory()).use { pipeline ->
                for (target in listOf(0L, duration / 2, (duration - 100).coerceAtLeast(0))) {
                    val actual = pipeline.seekAndDecode(target)
                    assertTrue("${entry.file}: seek $target returned $actual", abs(actual - target) <= SEEK_TOLERANCE_MS)
                }
            }
        }
    }

    @Test
    fun `a corrupt packet reports a decode failure instead of being skipped`() {
        val bytes = corpusFile("flac_2s.flac").readBytes()
        FfmpegPipeline.open(MemoryByteSource(bytes), hostFfmpegDirectory()).use { pipeline ->
            val packet = requireNotNull(pipeline.demuxer.readPacket())
            packet.data.reinterpret(packet.size.toLong()).fill(0)
            val failed = runCatching {
                if (pipeline.decoder.send(packet)) pipeline.decoder.receive()
                Unit
            }.isFailure
            assertTrue("a corrupt FLAC packet was silently discarded", failed)
        }
    }

    @Test
    fun `failed opens do not poison a subsequent valid pipeline`() {
        repeat(10) {
            assertTrue(runCatching {
                FfmpegPipeline.open(MemoryByteSource(ByteArray(32)), hostFfmpegDirectory()).close()
            }.isFailure)
        }
        FfmpegPipeline.open(MemoryByteSource(corpusFile("wav_2s.wav").readBytes()), hostFfmpegDirectory()).use { pipeline ->
            assertEquals(88_200L, pipeline.decodeAll().sumOf { it.samples.size.toLong() / it.channels })
        }
    }

    @Test
    fun `the AAC priming of a fragmented MP4 is trimmed once`() {
        val manifest = readManifest()
        val trimmed = manifest.files.single { it.trimRule == AAC_PRIMING_RULE }

        val bytes = corpusFile(trimmed.file).readBytes()
        FfmpegPipeline.open(MemoryByteSource(bytes), hostFfmpegDirectory()).use { pipeline ->
            val decoded = pipeline.decodeAll().sumOf { it.samples.size.toLong() / it.channels }
            assertEquals(trimmed.expectedSamples, decoded)
            // The container reports no trimming of its own, so the 1,024-sample
            // priming came from the demuxer's rule and was applied exactly once.
            assertEquals(requireNotNull(trimmed.decodedSamples), pipeline.rawDecodedSamples)
        }
    }

    @Test
    fun `the libraries are LGPL, separate and carry no protocol`() {
        val library = FfmpegLibrary.load(hostFfmpegDirectory())
        val layout = library.layout

        assertEquals("LGPL version 2.1 or later", library.license)
        assertEquals(4, layout.libraries.size)
        for (name in layout.libraries.keys) {
            assertTrue(
                "$name is not one of D86's four libraries",
                name in setOf("avcodec", "avformat", "avutil", "swresample"),
            )
        }
        // AC2's protocol inventory: the build registers none, so nothing can reach
        // the network through FFmpeg itself.
        val protocols = hostFfmpegDirectory().resolve("ffmpeg-protocols.txt")
        assertTrue("ffmpeg-protocols.txt is missing: run buildFfmpeg", protocols.exists())
        assertEquals("", protocols.readText().trim())
    }

    /** Seeks to a quarter of the item and measures where the first sample lands. */
    private fun seekError(
        pipeline: FfmpegPipeline,
        entry: CorpusFile,
    ): Long {
        val target = entry.expectedSamples * 1000 / entry.sampleRate / 4
        return abs(pipeline.seekAndDecode(target) - target)
    }

    private fun chapterProblemOf(measurement: Measurement): String? {
        if (measurement.expectedChapters.isEmpty()) {
            if (measurement.chapters.isEmpty()) {
                return null
            }
            return "${measurement.file} reports chapters the manifest does not"
        }
        if (measurement.chapters.size != measurement.expectedChapters.size) {
            return "${measurement.file} reports ${measurement.chapters.size} chapters, " +
                "expected ${measurement.expectedChapters.size}"
        }
        measurement.chapters.zip(measurement.expectedChapters).forEachIndexed { index, (actual, expected) ->
            if (actual.title != expected.title) {
                return "${measurement.file} chapter $index is '${actual.title}', expected '${expected.title}'"
            }
            if (abs(actual.startMs - expected.startMs) > CHAPTER_TOLERANCE_MS) {
                return "${measurement.file} chapter $index starts at ${actual.startMs} ms, " +
                    "expected ${expected.startMs} ms"
            }
        }
        return null
    }

    private fun corpusFile(name: String): Path = corpusDirectory().resolve(name)

    private fun corpusDirectory(): Path =
        Path.of(requireNotNull(System.getProperty(MODULE_DIR)), "src/test/resources/media")

    private fun readManifest(): CorpusManifest =
        Json.decodeFromString(CorpusManifest.serializer(), corpusDirectory().resolve("corpus.json").readText())

    companion object {
        /** The test convention that names this module's directory. */
        private const val MODULE_DIR = "neutrodyne.moduleDir"

        /** MD0 AC1's accuracy goal at the first audible sample. */
        private const val SEEK_TOLERANCE_MS = 50L

        /** A chapter's start is rounded to the container's time base. */
        private const val CHAPTER_TOLERANCE_MS = 20L

        private const val AAC_PRIMING_RULE = "aac-fmp4-priming-1024"

        /**
         * The FFmpeg build is a prerequisite, not an optional extra: without it
         * there is nothing to measure and the suite must not report success.
         */
        @BeforeClass
        @JvmStatic
        fun requireABuild() {
            assertTrue(
                "no ${FfmpegLayout.FILE_NAME} in the FFmpeg build: run :playback:native:buildFfmpeg",
                hostFfmpegDirectory().resolve(FfmpegLayout.FILE_NAME).exists(),
            )
        }
    }
}

/**
 * The FFmpeg build of this host. `buildFfmpeg` installs into
 * `<root>/playback/native/build/ffmpeg/<target>/`, one target per host, so the
 * directory that carries a layout file is the build this host can load. A missing
 * build fails the suite: an empty or skipped suite must never look like success.
 */
private fun hostFfmpegDirectory(): Path {
    System.getProperty(FFMPEG_DIR)?.let { return Path.of(it) }
    val root = Path.of(requireNotNull(System.getProperty(ROOT_DIR)))
    val builds = root.resolve("playback/native/build/ffmpeg")
    require(Files.isDirectory(builds)) { "no FFmpeg build in $builds: run :playback:native:buildFfmpeg" }
    val candidates =
        Files.list(builds).use { stream ->
            stream.filter { it.resolve(FfmpegLayout.FILE_NAME).isRegularFile() }.toList()
        }
    require(candidates.isNotEmpty()) { "no ${FfmpegLayout.FILE_NAME} in $builds: run :playback:native:buildFfmpeg" }
    require(candidates.size == 1) {
        "more than one FFmpeg build in $builds: ${candidates.map { it.fileName }}"
    }
    return candidates.single()
}

/** The test properties the FFmpeg build is resolved from. */
private const val ROOT_DIR = "neutrodyne.rootDir"
private const val FFMPEG_DIR = "neutrodyne.ffmpegDir"
