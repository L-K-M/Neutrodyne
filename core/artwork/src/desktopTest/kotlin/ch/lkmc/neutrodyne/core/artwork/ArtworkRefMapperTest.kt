// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import coil3.PlatformContext
import coil3.request.Options
import com.google.common.truth.Truth.assertThat
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Test

/** 08 `ArtworkRefMapperTest` (M1 row): pinned first, fallbacks skipped, `m-` keys → null. */
class ArtworkRefMapperTest {
    private val options = Options(PlatformContext.INSTANCE)

    @Test
    fun `pinned file wins over the url`() {
        val store = FakeArtworkStore(mapOf(KEY to "/art/$KEY.jpg"))
        val mapped = ArtworkRefMapper(store).map(ref(KEY), options)
        assertThat(mapped.toString()).isEqualTo("/art/$KEY.jpg")
    }

    @Test
    fun `fallback index entry is skipped and the url is used`() {
        val store = FakeArtworkStore(mapOf(KEY to "/art/$KEY.fallback.png"))
        val mapped = ArtworkRefMapper(store).map(ref(KEY), options)
        assertThat(mapped).isEqualTo("https://img.example/$KEY.jpg")
    }

    @Test
    fun `missing pin maps to the url`() {
        val mapped = ArtworkRefMapper(FakeArtworkStore(emptyMap())).map(ref(KEY), options)
        assertThat(mapped).isEqualTo("https://img.example/$KEY.jpg")
    }

    @Test
    fun `monogram keys map to null even when pinned`() {
        val key = MONOGRAM_KEY
        val store = FakeArtworkStore(mapOf(key to "/art/$key.png"))
        assertThat(ArtworkRefMapper(store).map(ref(key), options)).isNull()
    }

    private fun ref(key: String) = ArtworkRef(key = key, url = "https://img.example/$key.jpg", version = 2)

    private class FakeArtworkStore(
        private val pinned: Map<String, String>,
    ) : ArtworkStore {
        override fun pinnedFile(key: String): Path? = pinned[key]?.toPath()

        override fun pin(
            ref: ArtworkRef,
            reason: PinReason,
            ownerId: Long,
        ) {}

        override fun unpin(
            key: String,
            reason: PinReason,
            ownerId: Long,
        ) {}

        override fun contentUri(
            key: String,
            version: Int,
        ): String = ""

        override fun isPinned(key: String): Boolean = pinned.containsKey(key)

        override fun pinnedPath(key: String): Path? = pinned[key]?.toPath()

        override suspend fun collectGarbage(): Int = 0
    }

    private companion object {
        val KEY = "u-" + "b".repeat(40)
        val MONOGRAM_KEY = "m-" + "a".repeat(40)
    }
}
