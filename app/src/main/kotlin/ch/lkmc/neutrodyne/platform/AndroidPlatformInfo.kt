// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.platform

import android.os.Build
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import java.util.Locale

/** [PlatformInfo] from `Build` and the default locale, e.g. User-Agent segment `Android 17`. */
internal class AndroidPlatformInfo : PlatformInfo {
    override val kind: PlatformKind = PlatformKind.ANDROID

    override val userAgentPlatform: String = "Android ${Build.VERSION.RELEASE}"

    override val androidSdkInt: Int = Build.VERSION.SDK_INT

    override val regionCode: String
        get() = Locale.getDefault().country.uppercase(Locale.ROOT)
}
