// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.credentials.CredentialCommitCoordinatorImpl
import ch.lkmc.neutrodyne.core.data.importing.ImportCommitter
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkPolicy
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.RestoreEnqueue
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeSubscriptionWriterImpl
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore

internal fun testImportCommitter(
    db: NeutrodyneDatabase,
    clock: Clock,
): ImportCommitter =
    ImportCommitter(
        db,
        FakeSecretStore(),
        CredentialCommitCoordinatorImpl(),
        object : ImportWorkScheduler {
            override suspend fun schedule(
                sessionId: Long,
                policy: ImportWorkPolicy,
            ) {}

            override suspend fun cancel(sessionId: Long) {}

            override suspend fun hasRestoreWork() = false

            override suspend fun scheduleRestore(
                sessionId: Long,
                auto: Boolean,
            ) = RestoreEnqueue.ENQUEUED
        },
        FakeRefreshController(),
        FakeGroupChannelSync(),
        ImportOrderKeys,
        clock,
        YouTubeSubscriptionWriterImpl(db, clock, ImportOrderKeys),
    )

private object ImportOrderKeys : OrderKeys {
    override fun after(last: String?) = (last.orEmpty() + "a")

    override fun between(
        previous: String?,
        next: String?,
    ) = error("unused")

    override fun rewrite(count: Int) = error("unused")
}
