// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImportFileControllerTest {
    @Test fun chosenFileCreatesSessionWithDisplayName() =
        runTest {
            val imports = FakeImportRepository()
            val result = ImportFileController(imports).open(picker("file:///tmp/My%20feeds.json"))
            assertEquals(FileImportResult.Opened(1), result)
            assertEquals("My feeds.json", imports.sessions.value[1]?.sourceName)
            assertEquals(listOf("create(file:///tmp/My%20feeds.json)"), imports.calls)
        }

    @Test fun cancelCreatesNothing() =
        runTest {
            val imports = FakeImportRepository()
            assertEquals(FileImportResult.Cancelled, ImportFileController(imports).open(picker(null)))
            assertEquals(emptyList(), imports.calls)
        }

    @Test fun dialogFailureReturnsUnreadable() =
        runTest {
            assertEquals(
                FileImportResult.Failed(ImportError.Unreadable),
                ImportFileController(FakeImportRepository()).open(picker { error("Dialog unavailable") }),
            )
        }

    @Test fun repositoryFailureIsShownWithoutNavigation() =
        runTest {
            val imports = FakeImportRepository().apply { createOutcome = Outcome.Failure(ImportError.Storage) }
            assertEquals(
                FileImportResult.Failed(ImportError.Storage),
                ImportFileController(imports).open(picker("file:///tmp/list.opml")),
            )
        }

    @Test fun coroutineCancellationIsNotADialogError() =
        runTest {
            assertFailsWith<CancellationException> {
                ImportFileController(FakeImportRepository()).open(picker { throw CancellationException() })
            }
        }

    private fun picker(uri: String?): FilePicker = picker { uri }

    private fun picker(choose: () -> String?): FilePicker =
        object : FilePicker {
            override suspend fun pickFile(
                mimeTypes: List<String>,
                extensions: List<String>,
            ): String? {
                assertEquals(listOf("*/*"), mimeTypes)
                assertEquals(emptyList(), extensions)
                return choose()
            }

            override suspend fun pickFolder(title: String): String? = null
        }
}
