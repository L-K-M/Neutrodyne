// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.update.UpdateManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError as CheckError

class UpdateManifestParserTest {
    private val parser = UpdateManifestParser(updateBuild())

    private fun parsed(text: String = updateJson()): UpdateManifest =
        assertIs<Outcome.Success<UpdateManifest>>(parser.parse(text.encodeToByteArray())).value

    private fun invalid(text: String) {
        assertEquals(Outcome.Failure(CheckError.MANIFEST_INVALID), parser.parse(text.encodeToByteArray()), text)
    }

    @Test fun versionExamplesMatchReleaseScripts() {
        val examples = mapOf("0.1.0" to 10095L, "1.0.0" to 1000095L, "1.2.3" to 1020395L, "0.99.99" to 999995L)
        examples.forEach { (name, code) ->
            assertEquals(code, VersionScheme.versionCode(name))
            assertEquals(code, parsed(updateJson(name)).versionCode)
        }
    }

    @Test fun versionRejectsSuffixesLeadingZeroesOverflowAndInvalidComponents() {
        listOf(
            "1.2",
            "v1.2.3",
            "1.2.3-beta",
            "01.2.3",
            "1.02.3",
            "1.2.03",
            "-1.0.0",
            "1.100.0",
            "1.0.100",
            "9223372036854775807.1.1",
            "9223372036855.0.0",
            "1.2.3 ",
            "1.2.3\n",
        ).forEach {
            assertNull(VersionScheme.versionCode(it), it)
        }
        assertEquals(9_223_372_036_854_000_095L, VersionScheme.versionCode("9223372036854.0.0"))
    }

    @Test fun acceptsAndroidOnlyAndUnknownAdditiveFields() {
        val result = parsed(updateJson(extra = ",\"future\":{\"something\":[1,true,null]}"))
        assertEquals(1, result.apks.size)
        assertEquals(emptyList(), result.desktop)
        assertNull(result.server)
        assertEquals("Fixes and improvements.", result.notes)
    }

    @Test fun acceptsDesktopAndServerStages() {
        val desktop = desktopJson("windows", "x64", "msi", "10.0")
        val result = parsed(updateJson(extra = ",\"desktop\":[$desktop]${serverJson()}"))
        assertEquals("10.0", result.desktop.single().minOs)
        assertEquals("ghcr.io/l-k-m/neutrodyne-server@sha256:$UPDATE_HASH", result.server?.imageDigestRef)
        assertEquals(21, result.server?.minJava)
    }

    @Test fun rejectsMissingRequiredFields() {
        val root = Json.parseToJsonElement(updateJson()).jsonObject
        root.keys.forEach { missing -> invalid(JsonObject(root - missing).toString()) }
    }

    @Test fun schemaAndAllNumericFieldsRequireStrictIntegers() {
        listOf("schema", "versionCode", "minSdk").forEach { key ->
            listOf(
                JsonPrimitive("1"),
                JsonPrimitive(1.0),
                JsonPrimitive(true),
                JsonNull,
                JsonArray(emptyList()),
                Json.parseToJsonElement("1e0"),
                Json.parseToJsonElement("9223372036854775808"),
            ).forEach { value ->
                invalid(changedJson(updateJson(), key, value))
            }
        }
    }

    @Test fun rejectsWrongSchemaAndVersionFormula() {
        listOf(0L, 2L, Long.MAX_VALUE).forEach { invalid(changedJson(updateJson(), "schema", JsonPrimitive(it))) }
        listOf(0L, 10095L, 1000096L).forEach {
            invalid(changedJson(updateJson(), "versionCode", JsonPrimitive(it)))
        }
    }

    @Test fun sdkAndJavaLevelsArePositiveIntRanges() {
        listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1).forEach { value ->
            invalid(changedJson(updateJson(), "minSdk", JsonPrimitive(value)))
            invalid(updateJson(extra = serverJson()).replace("\"minJava\":21", "\"minJava\":$value"))
        }
        assertEquals(Int.MAX_VALUE, parsed(changedJson(updateJson(), "minSdk", JsonPrimitive(Int.MAX_VALUE))).minSdk)
    }

    @Test fun notesArePlainTextAndBoundedByUnicodeCodePoints() {
        val notes = "<b>Not HTML</b>\nhttps://example.org"
        assertEquals(notes, parsed(changedJson(updateJson(), "notes", JsonPrimitive(notes))).notes)
        assertEquals(
            "😀".repeat(500),
            parsed(changedJson(updateJson(), "notes", JsonPrimitive("😀".repeat(500)))).notes,
        )
        listOf("", "  \n", "a".repeat(501), "😀".repeat(501)).forEach {
            invalid(changedJson(updateJson(), "notes", JsonPrimitive(it)))
        }
    }

    @Test fun escapedUnpairedSurrogatesCannotBypassTheNotesLimit() {
        listOf("\\ud800", "\\udfff").forEach { escaped ->
            invalid(updateJson().replace("Fixes and improvements.", escaped))
        }
    }

    @Test fun publicationRequiresRealUtcDateTime() {
        listOf(
            "",
            "2026-10-19",
            "2026-02-30T12:00:00Z",
            "2026-13-01T00:00:00Z",
            "2026-01-01T25:00:00Z",
            "2026-01-01T00:00:00+01:00",
            "2026-01-01T00:00:00z",
        ).forEach {
            invalid(changedJson(updateJson(), "published", JsonPrimitive(it)))
        }
        parsed(changedJson(updateJson(), "published", JsonPrimitive("2026-01-01T00:00:00.123456789Z")))
    }

    @Test fun allKnownAssetsRequirePositiveSizeAndHexHash() {
        val fixtures =
            listOf(
                updateJson(),
                updateJson(extra = ",\"desktop\":[${desktopJson("linux", "arm64", "tar.gz")}]"),
                updateJson(extra = serverJson()),
            )
        fixtures.forEach { input ->
            listOf("0", "-1", "1.5", "1e2", "\"123\"", "9223372036854775808", "null").forEach { value ->
                invalid(input.replace(Regex("\"size\":(?:123|345|999)"), "\"size\":$value"))
            }
            listOf("g".repeat(64), "a".repeat(63), "a".repeat(65), "").forEach {
                invalid(input.replace(UPDATE_HASH, it))
            }
        }
        assertEquals(
            UPDATE_HASH,
            parsed(updateJson().replace(UPDATE_HASH, UPDATE_HASH.uppercase())).apks.single().sha256,
        )
    }

    @Test fun duplicateKnownTargetsRejectTheManifest() {
        val root = Json.parseToJsonElement(updateJson()).jsonObject
        val apk = root.getValue("apks").jsonArray.single()
        invalid(changedJson(updateJson(), "apks", JsonArray(listOf(apk, apk))))
        val desktop = desktopJson("linux", "x64", "deb")
        invalid(updateJson(extra = ",\"desktop\":[$desktop,$desktop]"))
    }

    @Test fun ignoresUnknownEnumTargetsEvenWithFutureAssetLayouts() {
        val root = Json.parseToJsonElement(updateJson()).jsonObject
        val unknown = Json.parseToJsonElement("""{"abi":"riscv64","futureAsset":true}""")
        assertEquals(
            1,
            parsed(changedJson(updateJson(), "apks", JsonArray(root.getValue("apks").jsonArray + unknown))).apks.size,
        )
        listOf(
            Triple("future", "x64", "deb"),
            Triple("linux", "future", "deb"),
            Triple("linux", "x64", "future"),
        ).forEach { (os, arch, kind) ->
            assertEquals(
                emptyList(),
                parsed(
                    updateJson(extra = ",\"desktop\":[{\"os\":\"$os\",\"arch\":\"$arch\",\"kind\":\"$kind\"}]"),
                ).desktop,
            )
        }
    }

    @Test fun unknownOperatingSystemMayUseAFutureLayout() {
        val parsed = parsed(updateJson(extra = ",\"desktop\":[{\"os\":\"future\"}]"))
        assertEquals(emptyList(), parsed.desktop)
    }

    @Test fun malformedKnownOptionalSectionsFailOnAndroidToo() {
        listOf(
            ",\"desktop\":null",
            ",\"desktop\":{}",
            ",\"server\":null",
            ",\"server\":[]",
            ",\"desktop\":[${desktopJson("linux", "x64", "deb", "13.x")}]",
            serverJson().replace("ghcr.io/l-k-m", "ghcr.io/attacker"),
        ).forEach { invalid(updateJson(extra = it)) }
    }

    @Test fun serverImageMustBeThisOwnersUntaggedDigest() {
        val image = "ghcr.io/l-k-m/neutrodyne-server@sha256:$UPDATE_HASH"
        listOf(
            "ghcr.io/other/neutrodyne-server@sha256:$UPDATE_HASH",
            "ghcr.io/l-k-m/other@sha256:$UPDATE_HASH",
            "ghcr.io/l-k-m/neutrodyne-server:latest",
            "$image:latest",
            "https://$image",
            image.dropLast(1),
        ).forEach { bad ->
            invalid(updateJson(extra = serverJson()).replace(image, bad))
        }
    }

    @Test fun hostileReleaseUrlsRejectBeforeNormalization() {
        val valid = "$UPDATE_REPO/releases/tag/v1.0.0"
        val bad =
            listOf(
                valid.replace("https:", "http:"),
                valid.replace("github.com", "github.com.evil.org"),
                valid.replace("github.com", "gıthub.com"),
                valid.replace("github.com", "github.com@evil.org"),
                valid.replace("github.com", "user:pass@github.com"),
                valid.replace("github.com", "github.com:444"),
                "$valid?x=1",
                "$valid#fragment",
                "$valid?",
                "$valid#",
                valid.replace("L-K-M", "other"),
                valid.replace("Neutrodyne", "neutrodyne"),
                valid.replace("v1.0.0", "v1.0.1"),
                valid.replace("/releases/", "/releases//"),
                valid.replace("/releases/", "/x/../releases/"),
                valid.replace("/releases/", "/%2e%2e/releases/"),
                valid.replace("/releases/", "/%2E/releases/"),
                valid.replace("/tag/", "/tag%2f/"),
                valid.replace("/tag/", "/tag%5c/"),
                valid.replace("/tag/", "\\tag/"),
                valid.replace("/tag/", "/tag%/"),
                "$valid/",
                " $valid",
                "$valid\n",
                valid.replace("/tag/", "/tag%252f/"),
            )
        bad.forEach { invalid(changedJson(updateJson(), "releaseUrl", JsonPrimitive(it))) }
    }

    @Test fun permitsExplicit443AndRebuildsPercentEncodedComponents() {
        val authority = "$UPDATE_REPO/releases/tag/v1.0.0".replace("github.com", "GITHUB.COM:443")
        val raw = authority.replace("Neutrodyne", "%4Eeutrodyne")
        assertEquals(
            "$UPDATE_REPO/releases/tag/v1.0.0",
            parsed(changedJson(updateJson(), "releaseUrl", JsonPrimitive(raw))).releaseUrl,
        )
    }

    @Test fun hostileAssetUrlsAndNamesFailInAllRecognizedSections() {
        val fixtures =
            listOf(
                updateJson(),
                updateJson(extra = ",\"desktop\":[${desktopJson("linux", "x64", "deb")}]"),
                updateJson(extra = serverJson()),
            )
        fixtures.forEach { input ->
            listOf(
                "/releases/download/v1.0.1/",
                "/releases/../../../attacker/x/releases/download/v1.0.0/",
                "/releases/%2e%2e/download/v1.0.0/",
                "/releases/download/v1.0.0%2F/",
                "/releases/download/v1.0.0%5C/",
                "/releases/download//v1.0.0/",
            ).forEach { path ->
                invalid(input.replace("/releases/download/v1.0.0/", path))
            }
            invalid(input.replace("\"url\":\"$UPDATE_REPO", "\"url\":\"https://evil.org/L-K-M/Neutrodyne"))
            invalid(input.replace("\"file\":\"neutrodyne-", "\"file\":\"different-"))
            invalid(input.replace("\"file\":\"neutrodyne-", "\"file\":\"../neutrodyne-"))
        }
    }

    @Test fun boundedBytesInvalidUtf8TruncationAndWrongShapesAreRecoverable() {
        assertIs<Outcome.Failure<CheckError>>(parser.parse(ByteArray(MAX_UPDATE_MANIFEST_BYTES + 1)))
        assertIs<Outcome.Failure<CheckError>>(parser.parse(byteArrayOf(0xff.toByte())))
        listOf("", "{", "[]", "null", "true", "42", updateJson().dropLast(3), "{\"schema\":1}").forEach(::invalid)
        invalid(changedJson(updateJson(), "apks", JsonNull))
    }
}
