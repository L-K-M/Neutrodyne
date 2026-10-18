// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import com.google.common.truth.Truth.assertThat
import okio.Path.Companion.toPath
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Verify ZIP timestamp compatibility and typed path errors on the real device implementation. */
@RunWith(AndroidJUnit4::class)
class AndroidArchiveRoundTripTest {
    @Test
    fun archiveRoundTripsWithoutDependingOnTheDefaultTimeZone() {
        val previous = TimeZone.getDefault()
        val utc = tempFile()
        val apia = tempFile()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            write(utc)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia"))
            write(apia)

            assertArrayEquals(utc.readBytes(), apia.readBytes())
            ZipGuard().open(utc.absolutePath.toPath(), ArchiveCaps.BACKUP).use { reader ->
                assertThat(reader.entryNames).containsExactly(ENTRY)
                reader.open(ENTRY).buffer().use { assertThat(it.readUtf8()).isEqualTo(PAYLOAD) }
            }
        } finally {
            TimeZone.setDefault(previous)
            utc.delete()
            apia.delete()
        }
    }

    @Test
    fun unsafeEntryNameKeepsItsTypedErrorOnTheDevice() {
        val file = tempFile()
        try {
            ZipOutputStream(FileOutputStream(file)).use { writer ->
                writer.putNextEntry(ZipEntry("../x"))
                writer.write(PAYLOAD.toByteArray())
                writer.closeEntry()
            }

            val error =
                assertThrows(ArchiveException::class.java) {
                    ZipGuard().open(file.absolutePath.toPath(), ArchiveCaps.BACKUP).close()
                }
            assertThat(error.kind).isEqualTo(ArchiveException.Kind.HOSTILE)
            assertThat(error.reason).isEqualTo("zip_path")
        } finally {
            file.delete()
        }
    }

    private fun tempFile(): File =
        File.createTempFile(
            "archive-device-",
            ".zip",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        )

    private fun write(file: File) {
        ZipArchiveWriter().create(file.absolutePath.toPath()).use { writer ->
            writer.entry(ENTRY).buffer().use { it.writeUtf8(PAYLOAD) }
        }
    }

    private companion object {
        const val ENTRY = "library.json"
        const val PAYLOAD = "{\"title\":\"Podcasts\"}"
    }
}
