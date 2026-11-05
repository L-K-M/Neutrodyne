// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
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
 * 2. `avformat_open_input` with a 1-MiB probe size.
 * 3. `av_find_best_stream(AUDIO)`; when it cannot pick a stream FFmpeg 9's
 *    rule for an unset rate or channel layout is why, so
 *    `avformat_find_stream_info` runs then and selection is retried. Every
 *    other stream gets `discard = AVDISCARD_ALL`.
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

        override val chapters: List<EpisodeChapter>

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

        /** The audio stream's own duration in [timeBase] units, `AV_NOPTS_VALUE` when unknown. */
        private val streamDuration: Long

        /** The audio stream's time base, for a caller converting timestamps. */
        val streamTimeBase: Pair<Int, Int> get() = timeBase

        /** The audio stream's decode parameters, which carry the codec's private data. */
        val codecParameters: MemorySegment get() = codecParametersOf

        private val packetSlot: MemorySegment = library.allocPacketSlot(arena)

        /**
         * The native packet [readPacket] last decoded, valid until the next
         * call: the decoder references it instead of copying data, so every
         * property FFmpeg put on it reaches `avcodec_send_packet`.
         */
        val currentPacket: MemorySegment
            get() = requireNotNull(packetSlot.dereference()) { "the packet was released" }

        /** The audio stream's `AVCodecParameters`, held while the pipeline is open. */
        private val codecParametersOf: MemorySegment

        private enum class PrimingOrigin { OPEN, SEEK_ZERO, CONTINUING }

        private var primingOrigin = PrimingOrigin.OPEN

        init {
            try {
                val allocated = library.allocFormatContext()
                contextSlot.set(ValueLayout.ADDRESS, 0L, allocated)
                allocated.writeInt(
                    layout,
                    "AVFormatContext",
                    "flags",
                    allocated.readInt(layout, "AVFormatContext", "flags") or layout.intConstant("AVFMT_FLAG_CUSTOM_IO"),
                )
                allocated.writeAddress(layout, "AVFormatContext", "pb", avio.context)
                allocated.writeAddress(layout, "AVFormatContext", "interrupt_callback.callback", avio.interruptStub)

                check(library.setProbeSize(allocated, PROBE_BYTES) >= 0) { "FFmpeg refused the probe limit" }
                val opened = library.openInput(contextSlot, library.cString(SOURCE_NAME))
                avio.throwIfFailed()
                require(opened >= 0) { "the demuxer could not open the source: ${library.errorString(opened)}" }
                formatContext = requireNotNull(contextSlot.dereference()) { "avformat_open_input released the context" }
                containerName =
                    formatContext
                        .readAddress(layout, "AVFormatContext", "iformat")
                        .takeIf { it.address() != 0L }
                        ?.let { library.readCString(it.readAddress(layout, "AVInputFormat", "name")) }
                        .orEmpty()

                val streamCount = formatContext.readInt(layout, "AVFormatContext", "nb_streams")
                val picked = library.findBestStream(formatContext, layout.intConstant("AVMEDIA_TYPE_AUDIO"))
                streamIndex =
                    if (picked < 0) {
                        // FFmpeg 9's av_find_best_stream skips an audio stream whose
                        // sample rate or channel count is still zero, and demuxers
                        // like mp3 and adts leave both to their parser: the probe
                        // runs only when stream selection says the header alone
                        // was not enough.
                        val found = library.findStreamInfo(formatContext)
                        avio.throwIfFailed()
                        require(found >= 0) {
                            "the demuxer could not read the stream info: ${library.errorString(found)}"
                        }
                        library.findBestStream(formatContext, layout.intConstant("AVMEDIA_TYPE_AUDIO"))
                    } else {
                        picked
                    }
                // The message carries what FFmpeg actually found, because a spike
                // must fail loudly and a wrong offset here is otherwise invisible.
                require(streamIndex >= 0) {
                    val streams = formatContext.readAddress(layout, "AVFormatContext", "streams")
                    val types =
                        if (streams.isNull()) {
                            "null"
                        } else {
                            (0 until minOf(streamCount, MAX_REPORTED_STREAMS)).joinToString(",") { index ->
                                streams
                                    .elementAt(index)
                                    .readAddress(layout, "AVStream", "codecpar")
                                    .readInt(layout, "AVCodecParameters", "codec_type")
                                    .toString()
                            }
                        }
                    "the source has no audio stream " +
                        "(container=$containerName, streams=$streamCount, types=[$types], " +
                        "findBestStream=${library.errorString(streamIndex)})"
                }

                val stream = formatContext.readAddress(layout, "AVFormatContext", "streams").elementAt(streamIndex)
                val parameters = stream.readAddress(layout, "AVStream", "codecpar")
                require(
                    parameters.readInt(layout, "AVCodecParameters", "codec_type") ==
                        layout.intConstant("AVMEDIA_TYPE_AUDIO"),
                ) {
                    "the best stream of the source is not audio"
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
                streamDuration = stream.readLong(layout, "AVStream", "duration")
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
            } catch (failure: Throwable) {
                library.freePacket(packetSlot)
                library.closeInput(contextSlot)
                avio.close()
                throw failure
            }
        }

        private fun codecOf(codecId: Int): AudioCodec {
            val name =
                layout.codecIds.entries
                    .firstOrNull { it.value == codecId }
                    ?.key
                    ?: error("FFmpeg reports codec id $codecId, which D86's build does not enable")
            return requireNotNull(AudioCodec.fromName(name)) { "FFmpeg reports $name, which D86 does not list" }
        }

        /**
         * The media duration in milliseconds: the container's, else the selected audio stream's;
         * null when neither is known. Preparation skips `avformat_find_stream_info`, which is what
         * fills the container duration for headers like WAV, so the stream's own value covers it.
         */
        private fun durationOf(): Long? {
            val units = formatContext.readLong(layout, "AVFormatContext", "duration")
            if (units > 0 && units != AV_NOPTS_VALUE) return units / (AV_TIME_BASE / MILLISECONDS_PER_SECOND)
            if (streamDuration <= 0 || streamDuration == AV_NOPTS_VALUE) return null
            val (num, den) = timeBase
            return FfmpegTime.ticksToScaled(streamDuration, num, den, FfmpegTime.MILLISECONDS_PER_SECOND)
        }

        /**
         * Chapters carry 06's source: ID3 `CHAP` frames of an MP3 and Nero/QuickTime
         * chapters of an MP4 family container. Other containers' chapter metadata has no
         * source in the priority table, so it is not published (Android drops it too).
         */
        private fun chaptersOf(): List<EpisodeChapter> {
            val source =
                when {
                    containerName.contains(MP4_MARKER) -> ChapterSource.MP4
                    containerName.contains(MP3_MARKER) -> ChapterSource.ID3
                    else -> return emptyList()
                }
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
                EpisodeChapter(
                    startMs = FfmpegTime.ticksToScaled(start, num, den, FfmpegTime.MILLISECONDS_PER_SECOND),
                    endMs = FfmpegTime.ticksToScaled(end, num, den, FfmpegTime.MILLISECONDS_PER_SECOND),
                    title = title?.takeIf { it.isNotBlank() },
                    imageUrl = null,
                    linkUrl = null,
                    source = source,
                    hidden = false,
                )
            }
        }

        override fun seekTo(positionMs: Long) {
            require(positionMs >= 0) { "seek position must be non-negative" }
            // The abort flag stays set until the seek's own reads begin, so the
            // pre-seek resume is the one that matters; a second call after
            // avformat_seek_file would be dead weight (the flag is a clear, not
            // a permit release).
            avio.resumeReads()
            val (num, den) = timeBase
            val target = FfmpegTime.msToTicks(positionMs, num, den)
            val result = library.seekFile(formatContext, streamIndex, Long.MIN_VALUE, target, Long.MAX_VALUE, 0)
            require(result >= 0) { "the seek to $positionMs ms failed: ${library.errorString(result)}" }
            // Only a return to the beginning encounters container priming again.
            primingOrigin = if (positionMs == 0L) PrimingOrigin.SEEK_ZERO else PrimingOrigin.CONTINUING
        }

        override fun readPacket(): AudioPacket? {
            while (true) {
                val packet = requireNotNull(packetSlot.dereference()) { "the packet was released" }
                library.unrefPacket(packet)
                val result = library.readFrame(formatContext, packet)
                if (result < 0) {
                    avio.throwIfFailed()
                    if (result == layout.intConstant("AVERROR_EOF")) return null
                    require(result != layout.intConstant("AVERROR_EXIT")) { "the read was aborted" }
                    error("reading a packet failed: ${library.errorString(result)}")
                }
                if (packet.readInt(layout, "AVPacket", "stream_index") != streamIndex) continue
                // avcodec_send_packet treats a NULL, zero-size packet as the
                // end-of-stream drain signal. av_read_frame never returns NULL
                // data, but a corrupt container can emit a zero-size packet;
                // forwarding it would drain the decoder mid-item. An empty
                // packet carries no samples, so its side data cannot trim
                // anything either.
                if (packet.readInt(layout, "AVPacket", "size") <= 0) continue

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
            val origin = primingOrigin
            if (origin == PrimingOrigin.CONTINUING) return 0
            primingOrigin = PrimingOrigin.CONTINUING
            if (skipSamples > 0) return 0
            // A flushed Opus decoder does not repeat codecpar's initial delay;
            // Ogg may omit the first-open skip side data when seeking to zero.
            if (origin == PrimingOrigin.SEEK_ZERO && audioTrack.codec == AudioCodec.OPUS) {
                return audioTrack.initialPadding
            }
            // initial_padding reaches the decoder through codecpar and is
            // trimmed inside avcodec: our own trim on top would double it.
            if (audioTrack.initialPadding > 0) return 0
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
            // CUSTOM_IO keeps the AVIO context alive until our bridge closes it.
            library.closeInput(contextSlot)
            avio.close()
        }

        private companion object {
            /** The name `avformat_open_input` is given; the source is the custom AVIO. */
            const val PROBE_BYTES = 1024L * 1024

            const val SOURCE_NAME = "nd-source"

            /** FFmpeg's "no value" sentinel, as `int64_t`. */
            const val AV_NOPTS_VALUE = Long.MIN_VALUE

            /** `AV_TIME_BASE`, the unit of `AVFormatContext.duration`. */
            const val AV_TIME_BASE = 1_000_000

            const val MILLISECONDS_PER_SECOND = 1000

            /** The mov demuxer's own name, as it reports itself to the engine. */
            const val MP4_MARKER = "mp4"

            /** The mp3 demuxer's own name, which carries ID3 `CHAP` frames. */
            const val MP3_MARKER = "mp3"

            /**
             * AAC priming in samples: the standard encoder delay of an AAC stream
             * whose container carries no edit list.
             */
            const val AAC_PRIMING_SAMPLES = 1024

            /** How many streams the no-audio-stream message names; enough to diagnose. */
            const val MAX_REPORTED_STREAMS = 8
        }
    }
