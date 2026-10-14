// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_hide
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_password
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_show
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_username
import org.jetbrains.compose.resources.stringResource

/**
 * The credential dialogs' username/password pair (08's dialogs table: "password (masked, show
 * toggle)"). The password renders through `PasswordVisualTransformation` and the password
 * keyboard; the trailing toggle reveals it and flips to the hide icon while shown. The reveal
 * flag lives in plain `remember` so a recreated dialog never comes back exposing the secret.
 */
@Composable
internal fun CredentialFields(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(Res.string.add_auth_username)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(Res.string.add_auth_password)) },
            singleLine = true,
            visualTransformation =
                if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                NdIconButton(
                    onClick = { showPassword = !showPassword },
                    icon =
                        if (showPassword) {
                            NdIcons.VisibilityOff
                        } else {
                            NdIcons.Visibility
                        },
                    contentDescription =
                        stringResource(
                            if (showPassword) {
                                Res.string.add_auth_hide
                            } else {
                                Res.string.add_auth_show
                            },
                        ),
                )
            },
        )
    }
}

private val FIELD_GAP = 12.dp
