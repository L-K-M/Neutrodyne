// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdLoading
import ch.lkmc.neutrodyne.core.designsystem.components.NdOutlinedButton
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.manage_storage
import ch.lkmc.neutrodyne.core.ui.resources.send_report
import ch.lkmc.neutrodyne.core.ui.resources.startup_failed_disk_full
import ch.lkmc.neutrodyne.core.ui.resources.startup_failed_generic
import ch.lkmc.neutrodyne.core.ui.resources.startup_updating
import ch.lkmc.neutrodyne.core.ui.resources.try_again
import org.jetbrains.compose.resources.stringResource

/**
 * The start-up gate (08 Banners and the startup gate): `Pending` shows the in-app mark on navy
 * with "Updating your library…" and an indeterminate loader; `Failed` keeps the gate with the
 * reason's wording and "Try again" (which re-runs `DatabaseOpener.awaitOpen()` — it does not
 * restart the window). The gate uses only design-system pieces and string resources; no
 * repository or ViewModel exists while it shows.
 */
@Composable
public fun StartupGate(
    state: StartupGateState,
    actions: RootActions,
    modifier: Modifier = Modifier,
) {
    // The gate surface is navy in both themes (08 Brand assets: the mark sits on navy).
    Surface(modifier = modifier.fillMaxSize(), color = GATE_NAVY) {
        Column(
            modifier = Modifier.fillMaxSize().padding(GATE_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                GATE_BRAND,
                style = MaterialTheme.typography.displayLarge,
                color = GATE_AMBER,
            )
            Spacer(Modifier.height(GATE_GAP))
            when (state) {
                StartupGateState.Pending, is StartupGateState.Recovered, StartupGateState.Ready -> {
                    Text(
                        stringResource(Res.string.startup_updating),
                        style = MaterialTheme.typography.titleMedium,
                        color = GATE_TEXT,
                    )
                    Spacer(Modifier.height(GATE_GAP))
                    NdLoading()
                }
                is StartupGateState.Failed -> GateFailed(state.reason, actions)
            }
        }
    }
}

@Composable
private fun GateFailed(reason: StartupFailure, actions: RootActions) {
    Text(
        when (reason) {
            StartupFailure.DISK_FULL -> stringResource(Res.string.startup_failed_disk_full)
            StartupFailure.IO, StartupFailure.UNKNOWN -> stringResource(Res.string.startup_failed_generic)
        },
        style = MaterialTheme.typography.titleMedium,
        color = GATE_TEXT,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(GATE_GAP))
    Row(horizontalArrangement = Arrangement.spacedBy(GATE_BUTTON_GAP)) {
        when (reason) {
            StartupFailure.DISK_FULL -> actions.manageStorage?.let {
                NdOutlinedButton(label = stringResource(Res.string.manage_storage), onClick = it)
            }
            else -> actions.reportStartupFailure?.let {
                NdOutlinedButton(label = stringResource(Res.string.send_report), onClick = it)
            }
        }
        NdButton(label = stringResource(Res.string.try_again), onClick = actions.retryStartup)
    }
}

// Brand colours straight from 08 Brand assets: amber seed on navy, independent of the scheme.
private val GATE_NAVY = androidx.compose.ui.graphics.Color(0xFF00192E)
private val GATE_AMBER = androidx.compose.ui.graphics.Color(0xFFF3881C)
private val GATE_TEXT = androidx.compose.ui.graphics.Color(0xFFE4F1FF)
private const val GATE_BRAND = "N"
private val GATE_PADDING = 32.dp
private val GATE_GAP = 24.dp
private val GATE_BUTTON_GAP = 12.dp
