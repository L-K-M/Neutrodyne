// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.ui.CredentialFields
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.import_edit_url
import ch.lkmc.neutrodyne.core.ui.resources.import_enter_password
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal enum class ImportFixUp { URL, PASSWORD }

/** Draft URLs may contain credentials too: neither form uses saved state. */
@Composable
internal fun ImportFixUpDialog(
    kind: ImportFixUp,
    onUrl: suspend (String) -> UiText?,
    onPassword: suspend (BasicCredentials) -> UiText?,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title =
        stringResource(
            if (kind == ImportFixUp.URL) Res.string.import_edit_url else Res.string.import_enter_password,
        )
    NdDialog(
        title = title,
        onDismissRequest = { if (!submitting) onDismiss() },
        confirm =
            NdDialogAction(stringResource(Res.string.action_save)) {
                if (submitting) return@NdDialogAction
                submitting = true
                scope.launch {
                    error =
                        if (kind == ImportFixUp.URL) {
                            onUrl(url)
                        } else {
                            onPassword(BasicCredentials(username, password))
                        }
                    submitting = false
                    if (error == null) {
                        password = ""
                        url = ""
                        onDismiss()
                    }
                }
            },
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel)) { if (!submitting) onDismiss() },
    ) {
        Column {
            if (kind == ImportFixUp.URL) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text(title) },
                    enabled = !submitting,
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.testTag("import-url-input"),
                )
            } else {
                CredentialFields(
                    username = username,
                    onUsernameChange = {
                        if (!submitting) {
                            username = it
                            error = null
                        }
                    },
                    password = password,
                    onPasswordChange = {
                        if (!submitting) {
                            password = it
                            error = null
                        }
                    },
                )
            }
            error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        }
    }
}
