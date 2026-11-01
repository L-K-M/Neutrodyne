// SPDX-License-Identifier: Unlicense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** [01 Testing]: the native lock's allow-list, the dynamic-only LGPL rule and the schema guard. */
class NativeLicencePolicyTest {
    private fun lock(
        ffmpegLicence: String = "LGPL-2.1-or-later",
        miniaudioLicence: String = "MIT-0",
        schema: Long = 1,
    ): NativeLicencePolicy.Lock =
        NativeLicencePolicy.parse(
            buildString {
                append("schema = $schema\n")
                append("\n[ffmpeg]\n")
                append("name = \"FFmpeg\"\nversion = \"9.0.2\"\nspdx = \"$ffmpegLicence\"\n")
                append("sourceUrl = \"https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz\"\n")
                append("sha256 = \"${"a".repeat(64)}\"\n")
                append("aboutLibrariesId = \"neutrodyne:ffmpeg\"\n")
                append("libraries = [\"avcodec\", \"avformat\", \"avutil\", \"swresample\"]\n")
                append(
                    "configure = \"--disable-everything --enable-shared " +
                        "--enable-demuxer=mov,matroska,ogg,mp3,flac,wav,aac\"\n",
                )
                append("\n[miniaudio]\n")
                append("name = \"miniaudio\"\nversion = \"0.11.22\"\nspdx = \"$miniaudioLicence\"\n")
                append("sourceUrl = \"https://raw.githubusercontent.com/mackron/miniaudio/0.11.22/miniaudio.h\"\n")
                append("sha256 = \"${"b".repeat(64)}\"\n")
                append("aboutLibrariesId = \"neutrodyne:miniaudio\"\n")
            },
        )

    @Test
    fun `allow-listed licences pass`() {
        for (spdx in NativeLicencePolicy.ALLOWED_SPDX) {
            val parsed = lock(ffmpegLicence = spdx, miniaudioLicence = spdx)
            assertTrue(
                parsed.components.all { it.spdx == spdx },
                "expected $spdx to be recorded unchanged",
            )
        }
    }

    @Test
    fun `a licence outside the allow-list is refused`() {
        val parsed = lock(ffmpegLicence = "GPL-3.0-or-later")
        val refused = parsed.components.filter { it.spdx !in NativeLicencePolicy.ALLOWED_SPDX }
        assertEquals(listOf("GPL-3.0-or-later"), refused.map { it.spdx })
    }

    @Test
    fun `LGPL components are marked dynamic-only and MPL unmodified-only`() {
        val parsed = lock()

        val ffmpeg = parsed.components.single { it.name == "FFmpeg" }
        val miniaudio = parsed.components.single { it.name == "miniaudio" }

        assertTrue(ffmpeg.dynamicOnly)
        assertTrue(!miniaudio.dynamicOnly)
        assertTrue(!ffmpeg.unmodifiedOnly)
    }

    @Test
    fun `an MPL-2 component is marked unmodified-only`() {
        val parsed = lock(miniaudioLicence = "MPL-2.0")

        assertTrue(parsed.components.single { it.name == "miniaudio" }.unmodifiedOnly)
    }

    @Test
    fun `an unsupported schema is refused`() {
        assertFails { lock(schema = 2) }
    }

    @Test
    fun `a lock without components is refused`() {
        assertFails {
            NativeLicencePolicy.parse("schema = 1\n")
        }
    }

    @Test
    fun `a component without a checksum is refused`() {
        assertFails {
            NativeLicencePolicy.parse(
                "schema = 1\n\n[ffmpeg]\nname = \"FFmpeg\"\nversion = \"9.0.2\"\n" +
                    "spdx = \"LGPL-2.1-or-later\"\nsourceUrl = \"https://ffmpeg.org/\"\n" +
                    "aboutLibrariesId = \"neutrodyne:ffmpeg\"\n",
            )
        }
    }

    @Test
    fun `the configure line and the library list are read from the lock`() {
        val parsed = NativeLicencePolicy.parse(lockText())

        assertTrue(parsed.configureLine().contains("--disable-everything"))
        assertEquals(listOf("avcodec", "avformat", "avutil", "swresample"), parsed.ffmpegLibraries())
    }

    @Test
    fun `a lock without a configure line is refused`() {
        assertFails {
            NativeLicencePolicy.parse(
                "schema = 1\n\n[ffmpeg]\nname = \"FFmpeg\"\nversion = \"9.0.2\"\n" +
                    "spdx = \"LGPL-2.1-or-later\"\nsourceUrl = \"https://ffmpeg.org/\"\n" +
                    "sha256 = \"${"a".repeat(64)}\"\naboutLibrariesId = \"neutrodyne:ffmpeg\"\n",
            )
        }
    }

    @Test
    fun `the repository lock parses and names both components`() {
        val lock =
            java.io.File("../../playback/native/native-components.lock").takeIf { it.isFile }
                ?: error("the repository lock is missing")
        val parsed = NativeLicencePolicy.parse(lock.readText())

        assertEquals(listOf("FFmpeg", "miniaudio"), parsed.components.map { it.name })
        assertEquals("9.0.2", parsed.components.first().version)
        assertTrue(parsed.components.first().dynamicOnly)
    }

    @Test
    fun `the lock's version is read at configuration time`() {
        val lock =
            java.io.File("../../playback/native/native-components.lock").takeIf { it.isFile }
                ?: error("the repository lock is missing")

        assertEquals("9.0.2", ffmpegVersionOf(lock))
        assertEquals("ffmpeg-9.0.2-neutrodyne-src.tar.xz", ffmpegSourceBundleName(ffmpegVersionOf(lock)))
    }

    private fun lockText(): String =
        buildString {
            append("schema = 1\n\n[ffmpeg]\n")
            append("name = \"FFmpeg\"\nversion = \"9.0.2\"\nspdx = \"LGPL-2.1-or-later\"\n")
            append("sourceUrl = \"https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz\"\n")
            append("sha256 = \"${"a".repeat(64)}\"\n")
            append("aboutLibrariesId = \"neutrodyne:ffmpeg\"\n")
            append("libraries = [\"avcodec\", \"avformat\", \"avutil\", \"swresample\"]\n")
            append("configure = \"--disable-everything --enable-shared\"\n")
        }
}
