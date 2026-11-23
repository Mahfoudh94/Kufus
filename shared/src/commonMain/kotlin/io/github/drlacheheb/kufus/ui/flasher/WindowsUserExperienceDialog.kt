package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drlacheheb.kufus.disk.model.WindowsUserExperienceOptions
import io.github.drlacheheb.kufus.disk.windows.UnattendXmlBuilder
import io.github.drlacheheb.kufus.ui.components.KufusActionButton
import io.github.drlacheheb.kufus.ui.components.KufusCompactTextField
import io.github.drlacheheb.kufus.ui.components.KufusModalDialog
import io.github.drlacheheb.kufus.ui.theme.KufusTheme

@Composable
internal fun WindowsUserExperienceDialog(
    state: FlashOperationState.ConfirmingWindows,
    imageName: String,
    onUpdateOptions: (WindowsUserExperienceOptions) -> Unit,
    onToggleEject: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    val options = state.options
    KufusModalDialog(
        title = "Windows User Experience",
        subtitle = "Customize $imageName via \\autounattend.xml",
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            KufusActionButton(text = "Cancel", enabled = true, onClick = onDismiss)
            KufusActionButton(text = "OK", enabled = true, emphasized = true, onClick = onConfirm)
        },
    ) {
        Text(
            text = "Optional Windows Setup automation written to the bootable drive:",
            fontSize = 12.sp,
            color = palette.onSurfaceVariant,
        )
        DialogCheckboxRow(
            label = "Remove requirement for 4GB+ RAM, Secure Boot and TPM 2.0",
            checked = options.bypassSystemRequirements,
            onCheckedChange = { onUpdateOptions(options.copy(bypassSystemRequirements = it)) },
        )
        DialogCheckboxRow(
            label = "Remove requirement for an online Microsoft account",
            checked = options.bypassMicrosoftAccount,
            onCheckedChange = { onUpdateOptions(options.copy(bypassMicrosoftAccount = it)) },
        )
        DialogCheckboxRow(
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
        HorizontalDivider(color = palette.outlineVariant)
        DialogCheckboxRow(
            label = "Safe-eject USB drive after flashing",
            checked = state.ejectAfterWrite,
            onCheckedChange = onToggleEject,
        )
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
    DialogCheckboxRow(
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
            modifier = Modifier.padding(start = 34.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KufusCompactTextField(
                value = currentName,
                onValueChange = { next -> onUpdateOptions(options.copy(localAccountUsername = next)) },
                enabled = true,
                placeholder = defaultUsername ?: "User",
            )
            val safeUser = UnattendXmlBuilder.sanitizeLocalUsername(currentName)
            Text(
                text = "Will configure local administrator '${safeUser ?: "User"}'",
                fontSize = 11.sp,
                color = palette.onSurfaceVariant,
            )
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
    DialogCheckboxRow(
        label = "Set regional options ($fallbackTag):",
        checked = enabled,
        onCheckedChange = { checked ->
            onUpdateOptions(options.copy(regionalLocaleTag = if (checked) fallbackTag else null))
        },
    )
    if (enabled) {
        Column(
            modifier = Modifier.padding(start = 34.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KufusCompactTextField(
                value = options.regionalLocaleTag.orEmpty(),
                onValueChange = { next -> onUpdateOptions(options.copy(regionalLocaleTag = next)) },
                enabled = true,
                placeholder = fallbackTag,
            )
            val safeLocale = UnattendXmlBuilder.sanitizeLocaleTag(options.regionalLocaleTag) ?: fallbackTag
            Text(
                text = "Locale tag: $safeLocale",
                fontSize = 11.sp,
                color = palette.onSurfaceVariant,
            )
        }
    }
}
