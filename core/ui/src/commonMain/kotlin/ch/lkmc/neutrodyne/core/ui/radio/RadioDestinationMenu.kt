// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Cream
import ch.lkmc.neutrodyne.core.designsystem.radio.EnamelSign
import ch.lkmc.neutrodyne.core.designsystem.radio.Faceplate
import ch.lkmc.neutrodyne.core.designsystem.radio.IvoryKeyCap
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.barlow
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SleepTimerKey
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_failed_badge
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_close
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_go_to
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_playback
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_sleep
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_sleep_key
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_speed
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_speed_key
import ch.lkmc.neutrodyne.core.ui.resources.settings
import ch.lkmc.neutrodyne.core.ui.resources.settings_update_available
import ch.lkmc.neutrodyne.core.ui.root.LocalSettingsBadge
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

public val LocalFailedDownloadCount = compositionLocalOf { 0 }

/**
 * The radio's Menu (PO-51, restyled for PO-55): a metal plate over the radio with an enamel
 * "Menu" sign, the Playback keys (speed and sleep timer sheets) and the Go to keys for the
 * existing destinations, which keep their keys, stacks and external route handling. Every key
 * is an ivory cap with a printed label; Downloads carries a red lamp while downloads failed.
 */
@Composable
public fun RadioDestinationMenu(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalAppNavigator.current
    val open: (NavKey) -> Unit = { key ->
        onDismiss()
        if (key is TopLevelKey) navigator.selectTab(key) else navigator.push(key)
    }
    Dialog(onDismissRequest = onDismiss) {
        Faceplate(
            modifier
                .widthIn(max = PLATE_MAX_WIDTH)
                .shadow(12.dp, PlateShape)
                .testTag("radio_destinations"),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EnamelSign(stringResource(Res.string.radio_menu), Modifier.semantics { heading() })
                Engraving(stringResource(Res.string.radio_menu_playback))
                // The radio is the only full player, so its playback sheets open from here.
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    MenuKey(
                        NdIcons.Speed,
                        stringResource(Res.string.radio_menu_speed_key),
                        "radio_speed",
                        spokenAs = stringResource(Res.string.radio_menu_speed),
                    ) { open(SpeedKey) }
                    MenuKey(
                        NdIcons.Bedtime,
                        stringResource(Res.string.radio_menu_sleep_key),
                        "radio_sleep_timer",
                        spokenAs = stringResource(Res.string.radio_menu_sleep),
                    ) { open(SleepTimerKey) }
                }
                BrassRule()
                Engraving(stringResource(Res.string.radio_menu_go_to))
                Destinations(open)
                CloseKey(onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Destinations(open: (NavKey) -> Unit) {
    val failures = LocalFailedDownloadCount.current
    val failedDescription =
        if (failures > 0) pluralStringResource(Res.plurals.downloads_failed_badge, failures, failures) else null
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = KEYS_PER_ROW,
    ) {
        val library = stringResource(Res.string.nav_library)
        val upNext = stringResource(Res.string.nav_up_next)
        val downloads = stringResource(Res.string.nav_downloads)
        val discover = stringResource(Res.string.nav_discover)
        val settings = stringResource(Res.string.settings)
        MenuKey(NdIcons.GridView, library, "nav_library") { open(LibraryKey) }
        MenuKey(NdIcons.QueueMusic, upNext, "nav_up_next") { open(UpNextKey) }
        MenuKey(
            NdIcons.Download,
            downloads,
            "nav_downloads",
            lamp = failures.takeIf { it > 0 },
            lampDescription = failedDescription,
        ) { open(DownloadsKey) }
        MenuKey(NdIcons.Explore, discover, "nav_discover") { open(DiscoverKey) }
        // With the top bars' gears gone, the Settings key carries D78's update notice.
        val updateNotice = LocalSettingsBadge.current
        MenuKey(
            NdIcons.Settings,
            settings,
            "nav_settings",
            spokenAs = if (updateNotice) stringResource(Res.string.settings_update_available) else null,
            notice = updateNotice,
        ) { open(SettingsHomeKey) }
    }
}

/**
 * One ivory key with its printed label, read as a button named by the label. A short printed
 * [label] gives way to the full [spokenAs] name; [lamp] lights a red count lamp on the cap that
 * reads as [lampDescription], and [notice] an amber dot like the radio's Menu key.
 */
@Composable
private fun MenuKey(
    icon: ImageVector,
    label: String,
    tag: String,
    spokenAs: String? = null,
    lamp: Int? = null,
    lampDescription: String? = null,
    notice: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .testTag(tag)
            .width(KEY_WIDTH)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (spokenAs != null) contentDescription = spokenAs }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box {
            IvoryKeyCap(icon, contentDescription = null, size = KEY_CAP)
            if (lamp != null) CountLamp(lamp, lampDescription, Modifier.align(Alignment.TopEnd))
            if (notice) {
                Box(Modifier.align(Alignment.TopEnd).size(NOTICE_DOT).background(NoticeAmber, CircleShape))
            }
        }
        Text(
            label,
            if (spokenAs != null) Modifier.clearAndSetSemantics {} else Modifier,
            color = Cream,
            fontFamily = barlow(),
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CountLamp(
    count: Int,
    description: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .offset(x = 6.dp, y = (-4).dp)
            .size(20.dp)
            .shadow(4.dp, CircleShape, ambientColor = LampRed, spotColor = LampRed)
            .background(LampRed, CircleShape)
            .border(1.dp, Color(0xFF7A1A12), CircleShape)
            .clearAndSetSemantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_LAMP_COUNT) "$MAX_LAMP_COUNT+" else count.toString(),
            color = Cream,
            fontFamily = barlow(),
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Small spaced capitals engraved into the plate, like the radio's "EPISODES" label. */
@Composable
private fun Engraving(text: String) {
    Text(
        text.uppercase(),
        Modifier.semantics { heading() },
        color = Cream.copy(alpha = ENGRAVING_ALPHA),
        fontFamily = barlow(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 3.sp,
    )
}

@Composable
private fun BrassRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(1.dp)
            .background(Brass.copy(alpha = 0.7f)),
    )
}

/** A small brass plate printed "Close"; Back, Escape and a tap outside close the Menu too. */
@Composable
private fun CloseKey(onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onDismiss)
            .testTag("radio_menu_close"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(Res.string.radio_menu_close),
            Modifier
                .shadow(2.dp, shape)
                .background(Brass, shape)
                .border(1.dp, Color(0xFF6E5530), shape)
                .padding(horizontal = 18.dp, vertical = 6.dp),
            color = Color(0xFF2E2116),
            fontFamily = barlow(),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

private val PlateShape = RoundedCornerShape(10.dp)
private val LampRed = Color(0xFFC62B1F)
private val PLATE_MAX_WIDTH = 360.dp
private val KEY_WIDTH = 88.dp
private val KEY_CAP = 52.dp
private val KEY_GAP = 8.dp
private const val KEYS_PER_ROW = 3
private const val MAX_LAMP_COUNT = 99
private const val ENGRAVING_ALPHA = 0.85f

private val NOTICE_DOT = 8.dp
private val NoticeAmber = Color(0xFFF3881C)
