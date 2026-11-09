// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.youtube.engine.IpFamilyHints

/** The desktop family hint, mirroring `AndroidIpFamilyHints`: whatever the shared OkHttp DNS
 * probes last resolved for googlevideo.com. `set` is host-only, never called on desktop. */
class DesktopIpFamilyHints(
    private val hints: DnsFamilyHints,
) : IpFamilyHints {
    override fun current(): IpFamily? = hints.familyFor("googlevideo.com")

    override fun set(family: IpFamily?) {
        hints.set("googlevideo.com", family)
    }
}
