// SPDX-License-Identifier: Unlicense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** [01 Testing]: allowed and rejected SPDX expressions, the OR/`elected` rule and the pbs-patches case. */
class PythonLicencePolicyTest {
    private fun lock(
        licence: String,
        elected: String? = null,
        kind: String = "native",
        aboutLibrariesId: String = "x",
        extraTopLevel: String = "",
    ): PythonLicencePolicy.Lock =
        PythonLicencePolicy.parse(
            buildString {
                append("schema = 1\n")
                append(extraTopLevel)
                append("\n[[component]]\n")
                append("name = \"comp\"\nversion = \"1.0\"\norigin = \"maven:x:y\"\n")
                append("licence = \"$licence\"\n")
                if (elected != null) append("elected = \"$elected\"\n")
                append("kind = \"$kind\"\naboutLibrariesId = \"$aboutLibrariesId\"\n")
            },
        )

    private fun violations(
        l: PythonLicencePolicy.Lock,
        desktop: Boolean = false,
        ids: Set<String> = setOf("x"),
    ) = PythonLicencePolicy.violations(l, desktop, null, null, null, null, null, ids)

    @Test
    fun `allow-listed licences pass`() {
        for (licence in PythonLicencePolicy.ALLOWED_LICENCES) {
            assertEquals(emptyList(), violations(lock(licence)), "expected $licence to pass")
        }
    }

    @Test
    fun `restricted licences fail`() {
        for (licence in listOf("GPL-2.0-only", "GPL-3.0-or-later", "LGPL-2.1-or-later", "AGPL-3.0-only", "Sleepycat")) {
            assertTrue(violations(lock(licence)).isNotEmpty(), "expected $licence to fail")
        }
    }

    @Test
    fun `mpl only for data kind`() {
        assertEquals(emptyList(), violations(lock("MPL-2.0", kind = "data")))
        assertTrue(violations(lock("MPL-2.0", kind = "native")).isNotEmpty())
        assertTrue(violations(lock("MPL-2.0", kind = "runtime")).isNotEmpty())
    }

    @Test
    fun `or expression requires elected allowed alternative`() {
        assertEquals(
            emptyList(),
            violations(lock("BSD-3-Clause OR GPL-2.0-only", elected = "BSD-3-Clause")),
        )
        assertTrue(violations(lock("BSD-3-Clause OR GPL-2.0-only")).isNotEmpty()) // no elected
        assertTrue(violations(lock("BSD-3-Clause OR GPL-2.0-only", elected = "GPL-2.0-only")).isNotEmpty())
        assertTrue(violations(lock("BSD-3-Clause OR GPL-2.0-only", elected = "MIT")).isNotEmpty()) // not an alternative
    }

    @Test
    fun `pbs-patches is the desktop lock's only mpl code case`() {
        val topLevel =
            "pbs = \"20250918\"\n[pbsSource]\ntag = \"20250918\"\n" +
                "name = \"python-build-standalone-20250918-src.tar.gz\"\nurl = \"https://x\"\n" +
                "sha256 = \"${"0".repeat(64)}\"\n"
        val good = lock("MPL-2.0", kind = "pbs-patches", extraTopLevel = topLevel)
        assertEquals(emptyList(), violations(good, desktop = true))
        assertTrue(violations(good, desktop = false).isNotEmpty()) // Android lock never has pbs-patches

        val wrongTag =
            lock(
                "MPL-2.0",
                kind = "pbs-patches",
                extraTopLevel = "pbs = \"20250918\"\n[pbsSource]\ntag = \"other\"\nsha256 = \"${"0".repeat(64)}\"\n",
            )
        assertTrue(violations(wrongTag, desktop = true).isNotEmpty())

        val noSource = lock("MPL-2.0", kind = "pbs-patches", extraTopLevel = "pbs = \"20250918\"\n")
        assertTrue(violations(noSource, desktop = true).isNotEmpty())

        val asData = lock("MPL-2.0", kind = "data", extraTopLevel = topLevel)
        // patches recorded as kind=data pass the licence check (MPL data is legal), but pbs-patches
        // entries must never be recorded as data — enforced by the kind mechanism on real entries
        assertEquals(emptyList(), violations(asData, desktop = true))
    }

    @Test
    fun `missing fields and unknown kinds fail`() {
        // aboutLibrariesId is mandatory — parse throws before violations run
        assertFails {
            PythonLicencePolicy.parse(
                "schema = 1\n[[component]]\nname = \"c\"\nversion = \"1\"\norigin = \"o\"\n" +
                    "licence = \"MIT\"\nkind = \"native\"\n",
            )
        }
        assertTrue(violations(lock("MIT", kind = "bogus")).isNotEmpty())
        assertTrue(violations(lock("MIT"), ids = emptySet()).isNotEmpty()) // no AboutLibraries definition
    }

    @Test
    fun `schema must be 1`() {
        assertFails { PythonLicencePolicy.parse("schema = 2\n") }
    }

    @Test
    fun `build cross-checks`() {
        val l =
            PythonLicencePolicy.parse(
                "schema = 1\nchaquopy = \"17.1.0\"\npython = \"3.14.0\"\npip = [\"yt-dlp\"]\n" +
                    "[[component]]\nname = \"CPython\"\nversion = \"3.14.0\"\n" +
                    "origin = \"maven:com.chaquo.python:target:3.14.0-0\"\n" +
                    "licence = \"Python-2.0\"\nkind = \"runtime\"\naboutLibrariesId = \"x\"\n",
            )

        fun v(
            lock: PythonLicencePolicy.Lock,
            chaquopy: String?,
            target: String?,
            pip: List<String>?,
        ) = PythonLicencePolicy.violations(
            lock,
            false,
            chaquopy,
            target,
            pip,
            emptyList(),
            emptyList(),
            setOf("x"),
        )
        assertEquals(emptyList(), v(l, "17.1.0", "3.14.0-0", listOf("yt-dlp")))
        assertTrue(v(l, "17.0.0", "3.14.0-0", listOf("yt-dlp")).isNotEmpty())
        assertTrue(v(l, "17.1.0", "3.13.9-0", listOf("yt-dlp")).isNotEmpty())
        assertTrue(v(l, "17.1.0", "3.14.0-0", emptyList()).isNotEmpty())
    }

    @Test
    fun `invalid toml and malformed entries are rejected`() {
        // duplicate keys are recoverable errors for Tomlj — previously the first value was kept
        // silently, so `licence = "MIT"` before `licence = "GPL-3.0-only"` passed the gate
        assertFails {
            PythonLicencePolicy.parse(
                "schema = 1\n[[component]]\nname = \"c\"\nversion = \"1\"\norigin = \"o\"\n" +
                    "licence = \"MIT\"\nlicence = \"GPL-3.0-only\"\nkind = \"native\"\n" +
                    "aboutLibrariesId = \"x\"\n",
            )
        }
        // a truncated document is rejected, not half-read
        assertFails { PythonLicencePolicy.parse("schema = 1\nchaquopy = ") }
        // `component` must be an array of tables; non-table entries were silently filtered out
        assertFails { PythonLicencePolicy.parse("schema = 1\ncomponent = \"x\"\n") }
        assertFails { PythonLicencePolicy.parse("schema = 1\ncomponent = [1]\n") }
        assertFails { PythonLicencePolicy.parse("schema = 1\npbsSource = \"x\"\n") }
    }

    @Test
    fun `pip requirement files and file flags are rejected`() {
        val l = PythonLicencePolicy.parse("schema = 1\npip = []\n")

        fun v(
            reqs: List<String>?,
            options: List<String>?,
        ) = PythonLicencePolicy.violations(
            l,
            false,
            null,
            null,
            emptyList(),
            reqs,
            options,
            emptySet(),
        )
        assertEquals(emptyList(), v(emptyList(), emptyList()))
        // install("-r", f) lands in Chaquopy's reqFiles, not reqs — the pip = [] compare alone passed it
        assertTrue(v(listOf("requirements.txt"), emptyList()).isNotEmpty())
        // the same bypass through options() is rejected as well
        assertTrue(v(emptyList(), listOf("-r", "requirements.txt")).isNotEmpty())
        assertTrue(v(emptyList(), listOf("--requirement=requirements.txt")).isNotEmpty())
        assertTrue(v(emptyList(), listOf("-e", "./local-pkg")).isNotEmpty())
        // attached short-option arguments (-rFILE, -ePATH) install unlisted packages the same
        // way; whole-string matching used to let them through
        assertTrue(v(emptyList(), listOf("-rrequirements.txt")).isNotEmpty())
        assertTrue(v(emptyList(), listOf("-e./local-pkg")).isNotEmpty())
        assertTrue(v(emptyList(), listOf("--editable=./local-pkg")).isNotEmpty())
        // benign flags pass, including look-alikes of the banned spellings
        assertEquals(emptyList(), v(emptyList(), listOf("--no-cache-dir")))
        assertEquals(
            emptyList(),
            v(emptyList(), listOf("--require-hashes", "--resume-retries", "--no-deps", "-i")),
        )
    }

    @Test
    fun `the runtime patch version is verified against the resolved target`() {
        fun parseLock(
            python: String,
            cpythonVersion: String = python,
            cpythonOrigin: String = "maven:com.chaquo.python:target:$python-0",
            components: Boolean = true,
        ) = PythonLicencePolicy.parse(
            "schema = 1\npython = \"$python\"\npip = []\n" +
                if (components) {
                    "[[component]]\nname = \"CPython\"\nversion = \"$cpythonVersion\"\n" +
                        "origin = \"$cpythonOrigin\"\nlicence = \"Python-2.0\"\n" +
                        "kind = \"runtime\"\naboutLibrariesId = \"x\"\n"
                } else {
                    ""
                },
        )

        fun v(lock: PythonLicencePolicy.Lock) =
            PythonLicencePolicy.violations(
                lock,
                false,
                null,
                "3.14.0-0",
                emptyList(),
                emptyList(),
                emptyList(),
                setOf("x"),
            )
        assertEquals(emptyList(), v(parseLock("3.14.0")))
        // previously any 3.14.* passed because only the DSL minor version was compared
        assertTrue(v(parseLock("3.14.999", cpythonOrigin = "maven:com.chaquo.python:target:3.14.999-0")).isNotEmpty())
        // and the CPython component entry is checked the same way
        assertTrue(v(parseLock("3.14.0", cpythonVersion = "3.14.1")).isNotEmpty())
        assertTrue(v(parseLock("3.14.0", cpythonOrigin = "maven:com.chaquo.python:target:3.14.0-9")).isNotEmpty())
        assertTrue(v(parseLock("3.14.0", components = false)).isNotEmpty())
    }
}
