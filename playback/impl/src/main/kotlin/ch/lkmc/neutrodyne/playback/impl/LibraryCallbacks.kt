// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only browse is available to untrusted controllers; starts remain in SessionCallback.
 * Inheritance forwards Media3's Java default callbacks, which Kotlin interface delegation skips.
 */
internal open class LibraryCallbacks(
    private val tree: MediaLibraryTree,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : MediaLibrarySession.Callback {
    private val rootLimits = java.util.concurrent.ConcurrentHashMap<ControllerInfo, Int>()
    private val subscriptions = mutableMapOf<ControllerInfo, MutableSet<String>>()
    private var observing: Job? = null

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        rootLimits[browser] =
            (params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 4) ?: 4).coerceIn(0, 4)
        return Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val limit = rootLimits[browser] ?: 4
        return scope.future(io) {
            try {
                LibraryResult.ofItemList(tree.children(parentId, page, pageSize, limit), params)
            } catch (_: IllegalArgumentException) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(io) {
            try {
                tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } catch (_: IllegalArgumentException) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        scope.future {
            try {
                val count = withContext(io) { tree.search(query).size }
                session.notifySearchResultChanged(browser, query, count, params)
                LibraryResult.ofVoid(params)
            } catch (_: IllegalArgumentException) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(io) {
            try {
                MediaLibraryTree.validatePage(page, pageSize)
                LibraryResult.ofItemList(MediaLibraryTree.page(tree.search(query), page, pageSize), params)
            } catch (_: IllegalArgumentException) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        scope.future {
            try {
                val count = withContext(io) { tree.children(parentId, 0, 100, rootLimits[browser] ?: 4).size }
                subscriptions.getOrPut(browser) { mutableSetOf() }.add(parentId)
                session.notifyChildrenChanged(browser, parentId, count, params)
                if (observing == null) {
                    observing =
                        scope.launch {
                            tree.changes().flowOn(io).collect {
                                for ((controller, parents) in subscriptions.map { it.key to it.value.toList() }) {
                                    for (parent in parents) {
                                        val size =
                                            withContext(io) {
                                                try {
                                                    tree.children(parent, 0, 100, rootLimits[controller] ?: 4).size
                                                } catch (
                                                    _: IllegalArgumentException,
                                                ) {
                                                    0
                                                }
                                            }
                                        session.notifyChildrenChanged(controller, parent, size, null)
                                    }
                                }
                            }
                        }
                }
                LibraryResult.ofVoid(params)
            } catch (_: IllegalArgumentException) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

    override fun onUnsubscribe(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
    ): ListenableFuture<LibraryResult<Void>> {
        subscriptions[browser]?.remove(parentId)
        stopIfIdle()
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onDisconnected(
        session: MediaSession,
        controller: ControllerInfo,
    ) {
        rootLimits.remove(controller)
        subscriptions.remove(controller)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (subscriptions.values.any { it.isNotEmpty() }) return
        observing?.cancel()
        observing = null
    }
}
