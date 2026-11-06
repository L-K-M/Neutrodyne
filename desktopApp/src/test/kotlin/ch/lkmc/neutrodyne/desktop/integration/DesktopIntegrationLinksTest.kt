// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.integration

import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.model.LoginItemState
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The links half of `DesktopIntegrationImpl` over the seam: the flow reports the OS state
 * (null where the rows do not apply), the actions delegate and re-read, and a failed write
 * returns `false` while the OS truth keeps showing.
 */
class DesktopIntegrationLinksTest {
    @Test
    fun aNonApplicableInstallHidesTheRows() = runTest {
        val integration = DesktopIntegrationImpl(UnsupportedRegistrar, NotApplicableLinks, FakeSettingsRepository())

        assertThat(integration.linkHandlers.value).isNull()
        assertThat(integration.canAddToApplicationsMenu).isFalse()
    }

    @Test
    fun theFlowReportsTheOsStateAndRegisterReReadsIt() = runTest {
        val links = RecordingLinks(handled = false)
        val integration = DesktopIntegrationImpl(UnsupportedRegistrar, links, FakeSettingsRepository())

        assertThat(integration.linkHandlers.value).isFalse()

        links.nextHandled = true
        assertThat(integration.registerLinkHandlers()).isTrue()

        assertThat(integration.linkHandlers.value).isTrue()
        assertThat(links.registerCalls).isEqualTo(1)
    }

    @Test
    fun aFailedWriteReturnsFalseAndKeepsTheOsTruth() = runTest {
        val links = RecordingLinks(handled = false, succeed = false)
        val integration = DesktopIntegrationImpl(UnsupportedRegistrar, links, FakeSettingsRepository())

        assertThat(integration.registerLinkHandlers()).isFalse()
        assertThat(integration.linkHandlers.value).isFalse()
    }

    @Test
    fun theMenuRowDelegatesToTheSeam() = runTest {
        val links = RecordingLinks(handled = false, menu = true)
        val integration = DesktopIntegrationImpl(UnsupportedRegistrar, links, FakeSettingsRepository())

        assertThat(integration.canAddToApplicationsMenu).isTrue()
        assertThat(integration.addToApplicationsMenu()).isTrue()
        assertThat(links.menuCalls).isEqualTo(1)
        assertThat(integration.linkHandlers.value).isTrue()
    }

    private object UnsupportedRegistrar : LoginItemRegistrar {
        override fun status(): LoginItemState = LoginItemState.UNSUPPORTED

        override fun register() = throw LoginItemException("unused")

        override fun unregister() = Unit
    }

    private object NotApplicableLinks : DesktopLinkRegistration {
        override val applicable: Boolean = false
        override val canAddToApplicationsMenu: Boolean = false

        override fun handled(): Boolean = false

        override fun register(): Boolean = false

        override fun addToMenu(): Boolean = false
    }

    private class RecordingLinks(
        handled: Boolean,
        private val succeed: Boolean = true,
        private val menu: Boolean = false,
    ) : DesktopLinkRegistration {
        var nextHandled = handled

        override val applicable: Boolean = true
        override val canAddToApplicationsMenu: Boolean = menu

        var registerCalls = 0
            private set

        var menuCalls = 0
            private set

        override fun handled(): Boolean = nextHandled

        override fun register(): Boolean {
            registerCalls++
            if (succeed) nextHandled = true
            return succeed
        }

        override fun addToMenu(): Boolean {
            menuCalls++
            if (succeed) nextHandled = true
            return succeed
        }
    }
}
