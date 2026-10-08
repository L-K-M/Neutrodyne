// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `LazyListScope.showNotes` renderer through `runComposeUiTest` (08 Show notes renderer): one
 * test per `ShowNoteBlock` type of 03's model, plus the span semantics — links and in-range
 * timestamps are clickable `LinkAnnotation`s that report back through `onLink`/`onTimestamp`, a
 * timestamp past the episode duration renders as plain text, TAP_TO_LOAD images gate on the
 * "Image: {alt}" row, and BLOCKED (Wi-Fi-only on a metered link) keeps the row without the tap
 * (03 Images and links). `installFakeImageLoader` keeps SHOWN images off the
 * network (09's deterministic-only rule).
 */
@OptIn(ExperimentalTestApi::class)
class ShowNotesRendererTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        // Compose resources resolve in the JVM default locale — pin English for string asserts.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun paragraphRendersItsText() =
        runComposeUiTest {
            setNotes(notesOf(ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("Hello notes")))))
            onNodeWithText("Hello notes").assertIsDisplayed()
        }

    @Test
    fun headingRendersAtBothLevels() =
        runComposeUiTest {
            setNotes(
                notesOf(
                    ShowNoteBlock.Heading(1, listOf(ShowNoteSpan.Text("Big heading"))),
                    ShowNoteBlock.Heading(5, listOf(ShowNoteSpan.Text("Small heading"))),
                ),
            )
            onNodeWithText("Big heading").assertIsDisplayed()
            onNodeWithText("Small heading").assertIsDisplayed()
        }

    @Test
    fun unorderedListRendersBulletPerItem() =
        runComposeUiTest {
            setNotes(
                notesOf(
                    ShowNoteBlock.ListBlock(
                        ordered = false,
                        items =
                            listOf(
                                listOf(ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("First point")))),
                                listOf(ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("Second point")))),
                            ),
                    ),
                ),
            )
            onNodeWithText("First point").assertIsDisplayed()
            onNodeWithText("Second point").assertIsDisplayed()
            onAllNodesWithText("•").assertCountEquals(2)
        }

    @Test
    fun orderedListNumbersItems() =
        runComposeUiTest {
            setNotes(
                notesOf(
                    ShowNoteBlock.ListBlock(
                        ordered = true,
                        items =
                            listOf(
                                listOf(ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("Alpha")))),
                                listOf(ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("Beta")))),
                            ),
                    ),
                ),
            )
            onNodeWithText("1.").assertIsDisplayed()
            onNodeWithText("2.").assertIsDisplayed()
        }

    @Test
    fun nestedListKeepsRenderingInnerItems() =
        runComposeUiTest {
            setNotes(
                notesOf(
                    ShowNoteBlock.ListBlock(
                        ordered = false,
                        items =
                            listOf(
                                listOf(
                                    ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("Outer"))),
                                    ShowNoteBlock.ListBlock(
                                        ordered = false,
                                        items =
                                            listOf(
                                                listOf(
                                                    ShowNoteBlock.Paragraph(
                                                        listOf(ShowNoteSpan.Text("Inner")),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                    ),
                ),
            )
            onNodeWithText("Outer").assertIsDisplayed()
            onNodeWithText("Inner").assertIsDisplayed()
            onAllNodesWithText("•").assertCountEquals(2)
        }

    @Test
    fun quoteRendersInnerBlocks() =
        runComposeUiTest {
            setNotes(
                notesOf(
                    ShowNoteBlock.Quote(
                        listOf(ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("quoted words")))),
                    ),
                ),
            )
            onNodeWithText("quoted words").assertIsDisplayed()
        }

    @Test
    fun ruleLeavesNeighbouringBlocksIntact() =
        runComposeUiTest {
            // A HorizontalDivider carries no text semantics; both neighbours must still render.
            setNotes(
                notesOf(
                    ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("before the rule"))),
                    ShowNoteBlock.Rule,
                    ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("after the rule"))),
                ),
            )
            onNodeWithText("before the rule").assertIsDisplayed()
            onNodeWithText("after the rule").assertIsDisplayed()
        }

    @Test
    fun tapToLoadImageShowsPlaceholderRow() =
        runComposeUiTest {
            var loads = 0
            setNotes(
                notesOf(ShowNoteBlock.Image(url = "https://example.com/pic.png", alt = "diagram")),
                imageMode = ShowNotesImageMode.TAP_TO_LOAD,
                onLoadImages = { loads++ },
            )
            onNodeWithText("Image: diagram").assertIsDisplayed().performClick()
            assertEquals(1, loads)
        }

    @Test
    fun blockedImageShowsPlaceholderRowWithoutATap() =
        runComposeUiTest {
            var loads = 0
            setNotes(
                notesOf(ShowNoteBlock.Image(url = "https://example.com/pic.png", alt = "diagram")),
                imageMode = ShowNotesImageMode.BLOCKED,
                onLoadImages = { loads++ },
            )
            // Wi-Fi-only on a metered link keeps the 48 dp row but drops the click action, so
            // nothing in the episode can switch the images back on (03 Images and links).
            onNodeWithText("Image: diagram").assertIsDisplayed()
            onAllNodes(hasText("Image: diagram") and hasClickAction()).assertCountEquals(0)
            onNodeWithContentDescription("diagram").assertDoesNotExist()
            assertEquals(0, loads)
        }

    @Test
    fun shownImageRendersWithAltAsDescription() =
        runComposeUiTest {
            setNotes(
                notesOf(ShowNoteBlock.Image(url = "https://example.com/pic.png", alt = "diagram")),
                imageMode = ShowNotesImageMode.SHOWN,
            )
            onNodeWithContentDescription("diagram").assertIsDisplayed()
        }

    @Test
    fun linkSpanIsClickableAndReportsTheUrl() =
        runComposeUiTest {
            val opened = mutableListOf<String>()
            setNotes(
                notesOf(
                    ShowNoteBlock.Paragraph(
                        listOf(ShowNoteSpan.Link("the episode page", "https://example.com/ep")),
                    ),
                ),
                onLink = { opened += it },
            )
            val text = semanticsText("the episode page")
            val link = assertIs<LinkAnnotation.Clickable>(text.getLinkAnnotations(0, text.length).single().item)
            assertEquals("https://example.com/ep", link.tag)

            onNodeWithText("the episode page").performClick()
            assertEquals(listOf("https://example.com/ep"), opened)
        }

    @Test
    fun timestampWithinDurationIsASeekLink() =
        runComposeUiTest {
            val seeks = mutableListOf<Long>()
            setNotes(
                notesOf(
                    ShowNoteBlock.Paragraph(
                        listOf(ShowNoteSpan.Timestamp("12:34", positionMs = 754_000)),
                    ),
                ),
                durationMs = 3_600_000,
                onTimestamp = { seeks += it },
            )
            onNodeWithText("12:34").performClick()
            assertEquals(listOf(754_000L), seeks)
        }

    @Test
    fun timestampBeyondDurationRendersAsPlainText() =
        runComposeUiTest {
            val seeks = mutableListOf<Long>()
            setNotes(
                notesOf(
                    ShowNoteBlock.Paragraph(
                        listOf(ShowNoteSpan.Timestamp("59:00", positionMs = 3_540_000)),
                    ),
                ),
                durationMs = 60_000,
                onTimestamp = { seeks += it },
            )
            val text = semanticsText("59:00")
            assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())

            onNodeWithText("59:00").performClick()
            assertTrue(seeks.isEmpty())
        }

    @Test
    fun lineBreakJoinsSpansWithNewline() =
        runComposeUiTest {
            setNotes(
                notesOf(
                    ShowNoteBlock.Paragraph(
                        listOf(
                            ShowNoteSpan.Text("line one"),
                            ShowNoteSpan.LineBreak,
                            ShowNoteSpan.Text("line two"),
                        ),
                    ),
                ),
            )
            onNodeWithText("line one\nline two").assertIsDisplayed()
        }

    @Test
    fun styleBitsBecomeSpanStyles() =
        runComposeUiTest {
            setNotes(
                notesOf(
                    ShowNoteBlock.Paragraph(
                        listOf(
                            ShowNoteSpan.Text("bold", style = STYLE_BOLD),
                            ShowNoteSpan.Text(" "),
                            ShowNoteSpan.Text("italic", style = STYLE_ITALIC),
                            ShowNoteSpan.Text(" "),
                            ShowNoteSpan.Text("under", style = STYLE_UNDERLINE),
                            ShowNoteSpan.Text(" "),
                            ShowNoteSpan.Text("code", style = STYLE_CODE),
                        ),
                    ),
                ),
            )
            val text = semanticsText("bold italic under code")
            assertEquals(
                FontWeight.Bold,
                text.spanStyles
                    .single { it.covers(text.text, "bold") }
                    .item.fontWeight,
            )
            assertEquals(
                FontStyle.Italic,
                text.spanStyles
                    .single { it.covers(text.text, "italic") }
                    .item.fontStyle,
            )
            assertEquals(
                TextDecoration.Underline,
                text.spanStyles
                    .single { it.covers(text.text, "under") }
                    .item.textDecoration,
            )
            assertEquals(
                FontFamily.Monospace,
                text.spanStyles
                    .single { it.covers(text.text, "code") }
                    .item.fontFamily,
            )
        }

    private fun ComposeUiTest.setNotes(
        notes: ShowNotes,
        imageMode: ShowNotesImageMode = ShowNotesImageMode.SHOWN,
        durationMs: Long? = null,
        onLink: (String) -> Unit = {},
        onTimestamp: (Long) -> Unit = {},
        onLoadImages: () -> Unit = {},
    ) {
        setContent {
            NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                LazyColumn {
                    showNotes(
                        notes = notes,
                        imageMode = imageMode,
                        durationMs = durationMs,
                        onLink = onLink,
                        onTimestamp = onTimestamp,
                        onLoadImages = onLoadImages,
                    )
                }
            }
        }
        waitForIdle()
    }

    /** The first `SemanticsProperties.Text` of the node showing [content] — the AnnotatedString. */
    private fun ComposeUiTest.semanticsText(content: String): AnnotatedString =
        onNodeWithText(content).fetchSemanticsNode().config[SemanticsProperties.Text].first()

    private fun notesOf(vararg blocks: ShowNoteBlock) = ShowNotes(blocks.toList())

    /** True when this style range covers [needle]'s position in [haystack]. */
    private fun AnnotatedString.Range<SpanStyle>.covers(
        haystack: String,
        needle: String,
    ): Boolean {
        val index = haystack.indexOf(needle)
        return index >= start && index + needle.length <= end
    }

    private companion object {
        const val STYLE_BOLD = 1
        const val STYLE_ITALIC = 2
        const val STYLE_UNDERLINE = 4
        const val STYLE_CODE = 8
    }
}
