package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.formatByteSize

@Composable
fun FlasherScreen(
    component: FlasherComponent,
    modifier: Modifier = Modifier,
) {
    val uiState by component.state.subscribeAsState()

    Column(
        modifier =
            modifier
                .safeContentPadding()
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Kufus",
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (uiState.platformName.isNotEmpty()) {
                    Text(
                        text = "Runtime: ${uiState.platformName}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = component::onRefreshDrives,
                enabled = !uiState.isScanning,
            ) {
                Text(if (uiState.isScanning) "Scanning..." else "Refresh Drives")
            }
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        DriveListSection(
            drives = uiState.drives,
            selectedDriveId = uiState.selectedDriveId,
            onSelectDrive = component::onSelectDrive,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DriveListSection(
    drives: List<UsbDrive>,
    selectedDriveId: String?,
    onSelectDrive: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (drives.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "No USB drives detected.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Plug in a USB drive and click Refresh Drives.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = drives, key = { it.id }) { drive ->
            DriveCard(
                drive = drive,
                selected = drive.id == selectedDriveId,
                onSelect = { onSelectDrive(drive.id) },
            )
        }
    }
}

@Composable
private fun DriveCard(
    drive: UsbDrive,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth(),
        border =
            if (selected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = drive.model,
                    style = MaterialTheme.typography.titleMedium,
                )
                val mountLabel =
                    if (drive.mountPoints.isEmpty()) {
                        "Not mounted"
                    } else {
                        "Mounted: ${drive.mountPoints.joinToString(", ")}"
                    }
                Text(
                    text = "${drive.id} • ${formatByteSize(drive.sizeBytes)} • $mountLabel",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
