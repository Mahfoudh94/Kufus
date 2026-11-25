package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.drlacheheb.kufus.disk.model.WindowsUserExperienceOptions
import io.github.drlacheheb.kufus.disk.windows.UnattendXmlBuilder
import io.github.drlacheheb.kufus.ui.components.KufusActionButton
import io.github.drlacheheb.kufus.ui.components.KufusCheckboxIndent
import io.github.drlacheheb.kufus.ui.components.KufusCheckboxRow
import io.github.drlacheheb.kufus.ui.components.KufusCompactTextField
import io.github.drlacheheb.kufus.ui.components.KufusModalDialog
import io.github.drlacheheb.kufus.ui.theme.CaptionXs
import io.github.drlacheheb.kufus.ui.theme.KufusTheme

@Composable
internal fun WindowsUserExperienceDialog(
    state: FlashOperationState.ConfirmingWindows,
    onUpdateOptions: (WindowsUserExperienceOptions) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = state.options
    KufusModalDialog(
        title = "Windows User Experience",
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            KufusActionButton(text = "Cancel", enabled = true, onClick = onDismiss)
            KufusActionButton(text = "OK", enabled = true, emphasized = true, onClick = onConfirm)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KufusCheckboxRow(
                label = "Remove requirement for 4GB+ RAM, Secure Boot and TPM 2.0",
                checked = options.bypassSystemRequirements,
                onCheckedChange = { onUpdateOptions(options.copy(bypassSystemRequirements = it)) },
            )
            KufusCheckboxRow(
                label = "Remove requirement for an online Microsoft account",
                checked = options.bypassMicrosoftAccount,
                onCheckedChange = { onUpdateOptions(options.copy(bypassMicrosoftAccount = it)) },
            )
            KufusCheckboxRow(
                label = "Disable data collection (Skip privacy questions)",
                checked = options.disableDataCollection,
                onCheckedChange = { onUpdateOptions(options.copy(disableDataCollection = it)) },
            )
            WindowsLocalAccountSection(
                options = options,
                defaultUsername = state.defaultUsername,
                onUpdateOptions = onUpdateOptions,
            )
            WindowsRegionalLocaleSection(
                options = options,
                defaultLocaleTag = state.defaultLocaleTag,
                onUpdateOptions = onUpdateOptions,
            )
        }
    }
}

@Composable
private fun WindowsLocalAccountSection(
    options: WindowsUserExperienceOptions,
    defaultUsername: String?,
    onUpdateOptions: (WindowsUserExperienceOptions) -> Unit,
) {
    val palette = KufusTheme.palette
    val enabled = options.localAccountUsername != null
    KufusCheckboxRow(
        label = "Create a local account with username:",
        checked = enabled,
        onCheckedChange = { checked ->
            val seeded = if (checked) defaultUsername?.takeIf { it.isNotBlank() } ?: "User" else null
            onUpdateOptions(options.copy(localAccountUsername = seeded))
        },
    )
    if (enabled) {
        val currentName = options.localAccountUsername.orEmpty()
        Column(
            modifier = Modifier.padding(start = KufusCheckboxIndent, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KufusCompactTextField(
                value = currentName,
                onValueChange = { next -> onUpdateOptions(options.copy(localAccountUsername = next)) },
                enabled = true,
                placeholder = defaultUsername ?: "User",
            )
            val safeUser = UnattendXmlBuilder.sanitizeLocalUsername(currentName) ?: "User"
            if (safeUser != currentName.trim()) {
                Text(
                    text = "Formatted username: $safeUser",
                    style = CaptionXs.copy(color = palette.onSurfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun WindowsRegionalLocaleSection(
    options: WindowsUserExperienceOptions,
    defaultLocaleTag: String?,
    onUpdateOptions: (WindowsUserExperienceOptions) -> Unit,
) {
    val palette = KufusTheme.palette
    val enabled = options.regionalLocaleTag != null
    val fallbackTag = defaultLocaleTag?.takeIf { it.isNotBlank() } ?: "en-US"
    KufusCheckboxRow(
        label = "Set regional options ($fallbackTag)",
        checked = enabled,
        onCheckedChange = { checked ->
            onUpdateOptions(options.copy(regionalLocaleTag = if (checked) fallbackTag else null))
        },
    )
    if (enabled) {
        val currentTag = options.regionalLocaleTag.orEmpty()
        Column(
            modifier = Modifier.padding(start = KufusCheckboxIndent, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KufusCompactTextField(
                value = currentTag,
                onValueChange = { next -> onUpdateOptions(options.copy(regionalLocaleTag = next)) },
                enabled = true,
                placeholder = fallbackTag,
            )
            val safeLocale = UnattendXmlBuilder.sanitizeLocaleTag(currentTag) ?: fallbackTag
            if (safeLocale != currentTag.trim()) {
                Text(
                    text = "Locale tag: $safeLocale",
                    style = CaptionXs.copy(color = palette.onSurfaceVariant),
                )
            }
        }
    }
}
