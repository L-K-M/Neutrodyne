// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

internal val LocalPaneFocusOwner = compositionLocalOf<PaneFocusOwner?> { null }

/** Passive ancestor of navigation, chrome and player focus. It can never receive focus itself. */
@Composable
internal fun NavigationFocusRoot(
    modifier: Modifier = Modifier,
    owner: PaneFocusOwner = remember { PaneFocusOwner() },
    content: @Composable BoxScope.() -> Unit,
) {
    val parent = LocalFocusManager.current
    val manager = remember(owner, parent) { PaneFocusManager(owner, parent) }
    CompositionLocalProvider(LocalPaneFocusOwner provides owner, LocalFocusManager provides manager) {
        Box(modifier.then(PaneRootElement(owner)), content = content)
    }
}

private class PaneFocusManager(
    private val owner: PaneFocusOwner,
    private val parent: FocusManager,
) : FocusManager {
    override fun clearFocus(force: Boolean) {
        // Empty clear is still an explicit command. Revoke before any delegated callbacks.
        owner.revoke()
        parent.clearFocus(force)
    }

    override fun moveFocus(focusDirection: FocusDirection): Boolean {
        owner.revoke()
        return parent.moveFocus(focusDirection)
    }
}

/** The proposal is read before movable children; only the stable host node may install it. */
@Composable
internal fun PaneFocusTransactionHost(
    owner: PaneFocusOwner,
    modifier: Modifier = Modifier,
    installation: PaneFocusInstallation = owner.capture(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().paneFocusTransaction(owner, installation), content = content)
}

internal fun Modifier.paneFocusTransaction(
    owner: PaneFocusOwner,
    installation: PaneFocusInstallation,
): Modifier = then(PaneHostElement(owner, installation))

internal class PaneFocusOwner {
    private var root: PaneRootNode? = null
    private val entries = mutableSetOf<PaneFocusEntry>()
    private var intentRevision = 0L
    private var bindingRevision = 0L
    private var current: PaneFocusInstallation? = null
    internal val hasFocus: Boolean get() = root?.let { it.isAttached && it.target.focusState.hasFocus } == true

    internal fun capture(hostIsCurrent: () -> Boolean = { true }): PaneFocusInstallation {
        // No ledger, registration, save request or other side effect during speculative composition.
        val entry = if (hostIsCurrent() && hasFocus) entries.singleOrNull { it.hasFocus && it.authorized() } else null
        val proposal = entry?.proposal(intentRevision)
        return PaneFocusInstallation(
            proposal,
            bindingRevision,
            hostIsCurrent,
        )
    }

    internal fun install(installation: PaneFocusInstallation) {
        // Retained outgoing/preview hosts must not supersede the current scene's installation.
        if (!installation.hostIsCurrent()) {
            installation.closed = true
            return
        }
        val fresh = installation.capturedBindingRevision == bindingRevision
        bindingRevision++
        current?.closed = true
        current = installation
        if (installation.installed || !fresh) {
            installation.closed = true
            return
        }
        installation.installed = true
        installation.boundRevision = bindingRevision
        if (installation.proposal?.let(::valid) != true) installation.closed = true
    }

    internal fun positioned(
        installation: PaneFocusInstallation,
        attached: Boolean,
    ) {
        if (current !== installation || installation.closed || installation.boundRevision != bindingRevision) return
        // The first boundary closes permission, including no loss, failed readiness and redirect.
        installation.closed = true
        val proposal = installation.proposal ?: return
        if (!installation.hostIsCurrent() || !attached || !valid(proposal) || hasFocus || !proposal.entry.placed) return
        proposal.entry.restore()
    }

    private fun valid(proposal: PaneFocusProposal): Boolean =
        proposal.intentRevision == intentRevision && root?.isAttached == true &&
            proposal.entry in entries && proposal.entry.matches(proposal)

    internal fun revoke() {
        intentRevision++
        current?.closed = true
    }

    internal fun cancel(installation: PaneFocusInstallation) {
        if (current === installation) bindingRevision++
        installation.closed = true
    }

    internal fun attachRoot(node: PaneRootNode) {
        revoke()
        root = node
    }

    internal fun detachRoot(node: PaneRootNode) {
        if (root !== node) return
        revoke()
        root = null
    }

    internal fun add(entry: PaneFocusEntry) {
        entries.add(entry)
    }

    internal fun remove(entry: PaneFocusEntry) {
        entry.depart()
        entries.remove(entry)
    }
}

internal data class PaneFocusProposal(
    val entry: PaneFocusEntry,
    val node: PaneEntryNode,
    val intentRevision: Long,
    val visitRevision: Long,
    val targetRevision: Long,
    val record: Map<String, List<Any?>>,
)

internal class PaneFocusInstallation(
    val proposal: PaneFocusProposal?,
    val capturedBindingRevision: Long,
    val hostIsCurrent: () -> Boolean = { true },
) {
    internal var boundRevision: Long? = null
    internal var installed = false
    internal var closed = false
}

/** Entry identity survives expected detach. Target and navigation visits do not repeat. */
internal class PaneFocusEntry(
    private val owner: PaneFocusOwner,
    private val navigation: NavigationState,
    val authorized: () -> Boolean,
    private val departed: (NavigationDeparture) -> Boolean,
    val restore: () -> Boolean,
) {
    private var node: PaneEntryNode? = null
    private var visitRevision = 0L
    private var targetRevision = 0L
    private var lastAuthorized: Boolean? = null
    private var removeNavigationListener: (() -> Unit)? = null
    private var lastBounds: LayoutCoordinates? = null
    private var record: Map<String, List<Any?>>? = null
    internal val hasFocus: Boolean get() = node?.let { it.isAttached && it.target.focusState.hasFocus } == true
    internal val placed: Boolean get() = node?.let { it.isAttached && it.placed } == true

    internal fun attach(node: PaneEntryNode) {
        if (this.node != null && this.node !== node) invalidateTarget()
        this.node = node
        owner.add(this)
        if (removeNavigationListener == null) {
            removeNavigationListener =
                navigation.observeMutations { departure ->
                    if (departure != null && departed(departure)) depart()
                    authorityChanged()
                }
        }
        authorityChanged()
    }

    internal fun dispose() {
        removeNavigationListener?.invoke()
        removeNavigationListener = null
        owner.remove(this)
    }

    private fun authorityChanged() {
        val current = authorized()
        if (lastAuthorized != null && current != lastAuthorized) depart()
        lastAuthorized = current
    }

    internal fun depart() {
        visitRevision++
        invalidateTarget()
    }

    private fun invalidateTarget() {
        targetRevision++
        record = null
    }

    internal fun focusChanged(
        previous: FocusState,
        current: FocusState,
    ) {
        if (!current.hasFocus && owner.hasFocus) owner.revoke()
        if (current.hasFocus && !previous.hasFocus) invalidateTarget()
    }

    internal fun sampled(
        bounds: LayoutCoordinates?,
        save: () -> Map<String, List<Any?>>?,
    ) {
        if (!hasFocus) return
        // Foundation loss is synchronous even if the gaining leaf has no coordinates yet.
        // This is a bounded provider adapter, not a universal descendant focus journal.
        if (bounds == null) {
            invalidateTarget()
            return
        }
        if (bounds !== lastBounds) {
            invalidateTarget()
            lastBounds = bounds
        }
        record = save()?.mapValues { (_, values) -> values.toPersistentList() }?.toPersistentMap()
    }

    internal fun proposal(intentRevision: Long): PaneFocusProposal? {
        val currentNode = node ?: return null
        val saved = record ?: return null
        if (!hasFocus || !authorized()) return null
        return PaneFocusProposal(this, currentNode, intentRevision, visitRevision, targetRevision, saved)
    }

    internal fun matches(proposal: PaneFocusProposal): Boolean =
        node === proposal.node && proposal.visitRevision == visitRevision &&
            proposal.targetRevision == targetRevision && proposal.record == record && authorized()
}

internal data class PaneEntryElement(
    val entry: PaneFocusEntry,
) : ModifierNodeElement<PaneEntryNode>() {
    override fun create() = PaneEntryNode(entry)

    override fun update(node: PaneEntryNode) {
        node.update(entry)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "paneEntryFocus"
    }
}

internal class PaneEntryNode(
    internal var entry: PaneFocusEntry,
) : DelegatingNode(),
    LayoutAwareModifierNode,
    UnplacedAwareModifierNode {
    internal var placed = false
        private set
    internal val target =
        delegate(
            FocusTargetModifierNode(Focusability.Never) { previous, current -> entry.focusChanged(previous, current) },
        )

    override fun onAttach() {
        entry.attach(this)
    }

    override fun onDetach() {
        placed = false
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = coordinates.isAttached
    }

    override fun onUnplaced() {
        placed = false
    }

    internal fun update(next: PaneFocusEntry) {
        if (entry === next) return
        entry.dispose()
        entry = next
        if (isAttached) next.attach(this)
    }
}

private data class PaneRootElement(
    val owner: PaneFocusOwner,
) : ModifierNodeElement<PaneRootNode>() {
    override fun create() = PaneRootNode(owner)

    override fun update(node: PaneRootNode) {
        check(node.owner === owner)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "paneFocusRoot"
    }
}

internal class PaneRootNode(
    val owner: PaneFocusOwner,
) : DelegatingNode() {
    internal val target =
        delegate(
            FocusTargetModifierNode(Focusability.Never) { previous, current ->
                if (!previous.hasFocus && current.hasFocus) owner.revoke()
            },
        )

    override fun onAttach() {
        owner.attachRoot(this)
    }

    override fun onDetach() {
        owner.detachRoot(this)
    }
}

private data class PaneHostElement(
    val owner: PaneFocusOwner,
    val installation: PaneFocusInstallation,
) : ModifierNodeElement<PaneHostNode>() {
    override fun create() = PaneHostNode(owner, installation)

    override fun update(node: PaneHostNode) {
        node.install(installation)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "paneFocusTransaction"
    }
}

private class PaneHostNode(
    private val owner: PaneFocusOwner,
    private var installation: PaneFocusInstallation,
) : Modifier.Node(),
    GlobalPositionAwareModifierNode {
    override fun onAttach() {
        owner.install(installation)
    }

    internal fun install(next: PaneFocusInstallation) {
        installation = next
        if (isAttached) owner.install(next)
    }

    override fun onDetach() {
        owner.cancel(installation)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        owner.positioned(installation, coordinates.isAttached)
    }
}
