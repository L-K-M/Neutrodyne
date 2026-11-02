// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * `FfDemuxer`: the v1.0 demuxer over the minimal FFmpeg build (11 Demux and
 * decode). One instance owns one open item: a custom [AvioBridge] over the
 * item's [ByteSource], the format context, the audio stream and the packet the
 * read loop reuses.
 *
 * Open (11 Demux and decode steps 1–6):
 * 1. `avformat_alloc_context`, a custom AVIO and `AVFMT_FLAG_CUSTOM_IO`, so no
 *    FFmpeg file or network protocol is ever used.
 * 2. `avformat_open_input` with a 1-MiB probe size; `avformat_find_stream_info`
 *    only when the best audio stream lacks its sample rate or channel layout.
 * 3. `av_find_best_stream(AUDIO)`; every other stream gets `discard = AVDISCARD_ALL`
 *    (video plays as audio; attached pictures are ignored).
 * 4. Chapters, duration and the track's trimming information are published once.
 *
 * The demuxer is single-threaded: `nd-prepare` opens it and `nd-engine` owns it
 * afterwards. `close` releases every native handle, in the order FFmpeg requires.
 */
class FfDemuxer
    internal constructor(
        private val library: FfmpegLibrary,
        private val arena: Arena,
        private val source: ByteSource,
    ) : Demuxer {
        private val layout = library.layout

        private val avio = AvioBridge.open(library, source)

        /** Holds the context pointer, because `avformat_open_input` may replace it. */
        private val contextSlot: MemorySegment = arena.allocate(ValueLayout.ADDRESS)

        private val formatContext: MemorySegment

        override val audioTrack: AudioTrackInfo

        override val durationMs: Long?

        override val chapters: List<EngineChapter>

        /** The track's trimming information, published once with the track (11 Demux and decode step 6). */
        override val gapless: Gapless
            get() =
                Gapless(
                    initialPadding = audioTrack.initialPadding,
                    trailingPadding = audioTrack.trailingPadding,
                    seekPreroll = audioTrack.seekPreroll,
                )

        /** The container's own name, as its demuxer identifies itself. */
        val containerName: String

        /** The stream index `av_read_frame`'s packets are filtered by. */
        private val streamIndex: Int

        /** The audio stream's time base, which converts a seek target to FFmpeg's units. */
        private val timeBase: Pair<Int, Int>

        /** The audio stream's time base, for a caller converting timestamps. */
        val streamTimeBase: Pair<Int, Int> get() = timeBase

        /** The audio stream's decode parameters, which carry the codec's private data. */
        val codecParameters: MemorySegment get() = codecParametersOf

        private val packetSlot: MemorySegment = library.allocPacketSlot(arena)

        /** The audio stream's `AVCodecParameters`, held while the pipeline is open. */
        private val codecParametersOf: MemorySegment

        /** True until the first packet of the audio stream has been read. */
        private var firstPacket = true

        init {
            val allocated = library.allocFormatContext()
            contextSlot.set(ValueLayout.ADDRESS, 0L, allocated)
            allocated.writeInt(
                layout,
                "AVFormatContext",
                "flags",
                allocated.readInt(layout, "AVFormatContext", "flags") or layout.intConstant("AVFMT_FLAG_CUSTOM_IO"),
            )
            allocated.writeAddress(layout, "AVFormatContext", "pb", avio.context)

            val opened = library.openInput(contextSlot, library.cString(SOURCE_NAME))
            require(opened >= 0) { "the demuxer could not open the source: ${library.errorString(opened)}" }
            formatContext = requireNotNull(contextSlot.dereference()) { "avformat_open_input released the context" }
            containerName =
                formatContext
                    .readAddress(layout, "AVFormatContext", "iformat")
                    .takeIf { it.address() != 0L }
                    ?.let { library.readCString(it.readAddress(layout, "AVInputFormat", "name")) }
                    .orEmpty()

            val streamCount = formatContext.readInt(layout, "AVFormatContext", "nb_streams")
            streamIndex = library.findBestStream(formatContext, layout.intConstant("AVMEDIA_TYPE_AUDIO"))
            require(streamIndex >= 0) { "the source has no audio stream" }

            val stream = formatContext.readAddress(layout, "AVFormatContext", "streams").elementAt(streamIndex)
            val parameters = stream.readAddress(layout, "AVStream", "codecpar")
            require(
                parameters.readInt(layout, "AVCodecParameters", "codec_type") ==
                    layout.intConstant("AVMEDIA_TYPE_AUDIO"),
            ) {
                "the best stream of the source is not audio"
            }

            // 11 Demux and decode step 3: probe only when the parameters are incomplete.
            if (parameters.readInt(layout, "AVCodecParameters", "sample_rate") <= 0) {
                val found = library.findStreamInfo(formatContext)
                require(found >= 0) { "the demuxer could not read the stream info: ${library.errorString(found)}" }
            }

            for (index in 0 until streamCount) {
                if (index == streamIndex) continue
                formatContext
                    .readAddress(layout, "AVFormatContext", "streams")
                    .elementAt(index)
                    .writeInt(layout, "AVStream", "discard", layout.intConstant("AVDISCARD_ALL"))
            }

            timeBase =
                stream
                    .readRational(layout, "AVStream", "time_base")
                    .let { (num, den) ->
                        if (den > 0) {
                            num to den
                        } else {
                            1 to AV_TIME_BASE
                        }
                    }
            codecParametersOf = stream.readAddress(layout, "AVStream", "codecpar")
            audioTrack =
                AudioTrackInfo(
                    codec = codecOf(parameters.readInt(layout, "AVCodecParameters", "codec_id")),
                    sampleRate = parameters.readInt(layout, "AVCodecParameters", "sample_rate"),
                    channels = parameters.readChannelCount(layout, "AVCodecParameters"),
                    initialPadding = parameters.readInt(layout, "AVCodecParameters", "initial_padding"),
                    trailingPadding = parameters.readInt(layout, "AVCodecParameters", "trailing_padding"),
                    seekPreroll = parameters.readInt(layout, "AVCodecParameters", "seek_preroll"),
                    skipSamplesPresent = false,
                )
            durationMs = durationOf()
            chapters = chaptersOf()
        }

        private fun codecOf(codecId: Int): AudioCodec {
            val name =
                layout.codecIds.entries
                    .firstOrNull { it.value == codecId }
                    ?.key
                    ?: error("FFmpeg reports codec id $codecId, which D86's build does not enable")
            return requireNotNull(AudioCodec.fromName(name)) { "FFmpeg reports $name, which D86 does not list" }
        }

        /** The container duration, in milliseconds; null when the container does not know it. */
        private fun durationOf(): Long? {
            val units = formatContext.readLong(layout, "AVFormatContext", "duration")
            if (units <= 0 || units == AV_NOPTS_VALUE) return null
            return units / (AV_TIME_BASE / MILLIS_PER_SECOND)
        }

        private fun chaptersOf(): List<EngineChapter> {
            val count = formatContext.readInt(layout, "AVFormatContext", "nb_chapters")
            if (count <= 0) return emptyList()
            val array = formatContext.readAddress(layout, "AVFormatContext", "chapters")
            val titleKey = library.cString("title")
            return (0 until count).map { index ->
                val chapter = array.elementAt(index)
                val (num, den) = chapter.readRational(layout, "AVChapter", "time_base")
                val start = chapter.readLong(layout, "AVChapter", "start")
                val end = chapter.readLong(layout, "AVChapter", "end")
                val title =
                    library
                        .dictGet(chapter.readAddress(layout, "AVChapter", "metadata"), titleKey)
                        ?.let { entry ->
                            library.readCString(entry.readAddress(layout, "AVDictionaryEntry", "value"))
                        }
                EngineChapter(
                    startMs = start * num * MILLIS_PER_SECOND / den,
                    endMs = end * num * MILLIS_PER_SECOND / den,
                    title = title?.takeIf { it.isNotBlank() },
                )
            }
        }

        override fun seekTo(positionMs: Long) {
            avio.abort()
            val (num, den) = timeBase
            val target = positionMs * den / (MILLIS_PER_SECOND * num)
            val result = library.seekFile(formatContext, streamIndex, Long.MIN_VALUE, target, Long.MAX_VALUE, 0)
            require(result >= 0) { "the seek to $positionMs ms failed: ${library.errorString(result)}" }
            // The seek is done, so the abort it raised must not abort the reads
            // that follow it.
            avio.resumeReads()
        }

        override fun readPacket(): AudioPacket? {
            while (true) {
                val packet = requireNotNull(packetSlot.dereference()) { "the packet was released" }
                library.unrefPacket(packet)
                val result = library.readFrame(formatContext, packet)
                if (result < 0) {
                    if (result == layout.intConstant("AVERROR_EOF")) return null
                    require(result != layout.intConstant("AVERROR_EXIT")) { "the read was aborted" }
                    error("reading a packet failed: ${library.errorString(result)}")
                }
                if (packet.readInt(layout, "AVPacket", "stream_index") != streamIndex) continue

                val (skipSamples, discardPadding) = skipSamplesOf(packet)
                val priming = primingOf(skipSamples)
                return AudioPacket(
                    data = packet.readAddress(layout, "AVPacket", "data"),
                    size = packet.readInt(layout, "AVPacket", "size"),
                    pts = packet.readLong(layout, "AVPacket", "pts").takeIf { it != AV_NOPTS_VALUE },
                    skipSamples = skipSamples,
                    discardPadding = discardPadding,
                    primingSamples = priming,
                )
            }
        }

        /**
         * The packet's `AV_PKT_DATA_SKIP_SAMPLES` side data: two `uint32_t`s of
         * leading and trailing samples. A container that carries none leaves the
         * decoder to output every sample it decodes.
         */
        private fun skipSamplesOf(packet: MemorySegment): Pair<Int, Int> {
            val elements = packet.readInt(layout, "AVPacket", "side_data_elems")
            if (elements <= 0) return 0 to 0
            val wanted = layout.intConstant("AV_PKT_DATA_SKIP_SAMPLES")
            val sideData = packet.readAddress(layout, "AVPacket", "side_data")
            for (index in 0 until elements) {
                val entry = sideData.elementAt(index)
                if (entry.readInt(layout, "AVPacketSideData", "type") != wanted) continue
                val data = entry.readAddress(layout, "AVPacketSideData", "data")
                if (data.address() == 0L) continue
                val values = data.reinterpret(2L * Int.SIZE_BYTES)
                return values.get(ValueLayout.JAVA_INT, 0L) to values.get(ValueLayout.JAVA_INT, Int.SIZE_BYTES.toLong())
            }
            return 0 to 0
        }

        /**
         * AAC in MP4 without an edit list carries no priming information at all,
         * so the first [AAC_PRIMING_SAMPLES] decoded samples are the encoder delay
         * and are dropped (11 Demux and decode, Gapless trimming). The rule is
         * verified only for AAC-LC: HE-AAC and real itag-140 files are unverified,
         * and MD0 AC1 records the measured counts.
         *
         * The decision is made on the first packet, because that is the first
         * moment the container's own skip-samples side data is visible: a
         * container that carries it has already told the decoder what to trim, and
         * dropping anything here as well would trim twice.
         */
        private fun primingOf(skipSamples: Int): Int {
            if (!firstPacket) return 0
            firstPacket = false
            if (skipSamples > 0) return 0
            if (audioTrack.codec != AudioCodec.AAC) return 0
            if (!containerName.contains(MP4_MARKER)) return 0
            return AAC_PRIMING_SAMPLES
        }

        /**
         * Releases the FFmpeg objects this demuxer owns. The caller's arena is not
         * closed: it also holds the decoder's handles and the caller decides when
         * the whole pipeline is done.
         */
        override fun close() {
            library.freePacket(packetSlot)
            // The format context owns the custom AVIOContext and its buffer and
            // releases both here, which is why the bridge is closed after it.
            library.closeInput(contextSlot)
            avio.close()
        }

        private companion object {
            /** The name `avformat_open_input` is given; the source is the custom AVIO. */
            const val SOURCE_NAME = "nd-source"

            /** FFmpeg's "no value" sentinel, as `int64_t`. */
            const val AV_NOPTS_VALUE = Long.MIN_VALUE

            /** `AV_TIME_BASE`, the unit of `AVFormatContext.duration`. */
            const val AV_TIME_BASE = 1_000_000

            const val MILLIS_PER_SECOND = 1000

            /** The mov demuxer's own name, as it reports itself to the engine. */
            const val MP4_MARKER = "mp4"

            /**
             * AAC priming in samples: the standard encoder delay of an AAC stream
             * whose container carries no edit list.
             */
            const val AAC_PRIMING_SAMPLES = 1024
        }
    }
