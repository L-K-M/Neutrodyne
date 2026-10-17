// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.GroupChannelSync

/**
 * In-memory [GroupChannelSync]: records every call in order. The sweep's live-undo set comes from
 * the repository under test, so the fake records nothing extra for it.
 */
class FakeGroupChannelSync : GroupChannelSync {
    val calls = mutableListOf<String>()
    var onSync: suspend () -> Unit = {}

    override suspend fun sync() {
        calls += "sync()"
        onSync()
    }

    override suspend fun onUuidChanged(
        oldUuid: String,
        newUuid: String,
    ) {
        calls += "onUuidChanged($oldUuid, $newUuid)"
    }

    override suspend fun onDeleted(uuid: String) {
        calls += "onDeleted($uuid)"
    }
}
