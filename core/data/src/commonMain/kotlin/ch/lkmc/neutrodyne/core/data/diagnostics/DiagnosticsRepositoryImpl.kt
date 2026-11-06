// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.Redactor
import ch.lkmc.neutrodyne.core.common.RingBufferLogSink
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.exporting.ExportDestination
import ch.lkmc.neutrodyne.core.domain.DiagnosticsContributor
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.domain.DiagnosticsRepository
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsReport
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSection
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSeverity
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * 09's `DiagnosticsRepositoryImpl`: fans out to the contributor set (2 s each, a failure
 * degrades to one line), renders [DiagnosticsReport.toPlainText] and runs the database-copy
 * export through [DatabaseCopyExporter] plus the platform [ExportDestination] handoff.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DiagnosticsRepositoryImpl
    @Inject
    constructor(
        private val contributors: Set<DiagnosticsContributor>,
        private val exporter: DatabaseCopyExporter,
        private val destination: ExportDestination,
        private val platformInfo: PlatformInfo,
        private val logSink: RingBufferLogSink,
        private val clock: Clock,
        @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
    ) : DiagnosticsRepository {
        override suspend fun snapshot(): DiagnosticsReport =
            withContext(io) {
                val sections =
                    coroutineScope {
                        contributors
                            .sortedBy { it.section.ordinal }
                            .map { contributor ->
                                async {
                                    val lines =
                                        withTimeoutOrNull(CONTRIBUTOR_BUDGET_MS) {
                                            suspendRunCatching { contributor.collect() }
                                                .getOrElse { throwable ->
                                                    if (throwable is CancellationException) throw throwable
                                                    listOf(
                                                        DiagnosticsLine(
                                                            "unavailable",
                                                            throwable::class.simpleName ?: "error",
                                                            DiagnosticsSeverity.WARNING,
                                                        ),
                                                    )
                                                }
                                        }
                                            ?: listOf(
                                                DiagnosticsLine("unavailable", "timeout", DiagnosticsSeverity.WARNING),
                                            )
                                    // 09: values pass Redactor once more — contributors should already have
                                    // redacted, but stop-reason and parse-warning texts can quote URLs.
                                    val redacted = lines.map { it.copy(value = Redactor.text(it.value)) }
                                    if (redacted.isEmpty()) null else DiagnosticsSection(contributor.section, redacted)
                                }
                            }.awaitAll()
                            .filterNotNull()
                    }
                val logLines = logSink.linesFlow.value
                val withLog =
                    if (logLines.isEmpty()) {
                        sections
                    } else {
                        sections +
                            DiagnosticsSection(DiagnosticsSectionId.LOG, logLines.map { DiagnosticsLine("", it) })
                    }
                DiagnosticsReport(generatedAt = clock.now(), sections = withLog)
            }

        override fun toPlainText(
            report: DiagnosticsReport,
            maxBytes: Int,
        ): String {
            val builder = StringBuilder()
            for (section in report.sections.sortedBy { it.id.ordinal }) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append("== ").append(section.id.name.replace('_', ' ')).append(" ==\n")
                if (section.id == DiagnosticsSectionId.LOG) {
                    // 09: truncate the LOG tail to fit the budget — newest lines win.
                    val lines = section.lines.map { it.value.ifEmpty { it.key } }
                    val kept = mutableListOf<String>()
                    var used = 0
                    for (line in lines.asReversed()) {
                        val cost = line.encodeToByteArray().size + 1
                        if (used + cost > maxBytes - builder.length) break
                        kept.add(0, line)
                        used += cost
                    }
                    kept.forEach { builder.append(it).append('\n') }
                } else {
                    for (line in section.lines) {
                        builder
                            .append(line.key)
                            .append(": ")
                            .append(line.value)
                            .append('\n')
                    }
                }
            }
            return builder.toString()
        }

        override suspend fun exportDatabaseCopy(destination: String?): Outcome<String, DiagnosticsError> {
            val produced =
                when (val outcome = exporter.produce()) {
                    is Outcome.Failure -> return outcome
                    is Outcome.Success -> outcome.value
                }
            return when {
                platformInfo.kind == PlatformKind.ANDROID -> {
                    // Android ignores `destination`: the share sheet hands out the
                    // FileProvider URI of the private cache copy (09).
                    Outcome.Success(this.destination.shareUri(produced.name) ?: produced.toString())
                }

                destination.isNullOrBlank() -> {
                    exporter.removeStale()
                    Outcome.Failure(DiagnosticsError.CANCELLED)
                }

                else -> {
                    when (val saved = this.destination.save(produced, destination)) {
                        is Outcome.Failure -> {
                            exporter.removeStale()
                            Outcome.Failure(DiagnosticsError.FAILED)
                        }

                        is Outcome.Success -> {
                            // The user holds the copied file; drop our cache leaf.
                            exporter.removeStale()
                            Outcome.Success(destination)
                        }
                    }
                }
            }
        }

        override fun observeLogLines(): Flow<List<String>> = logSink.linesFlow
    }

private const val CONTRIBUTOR_BUDGET_MS = 2_000L
