package dev.timoa.ankivoice.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    viewModel: StudyViewModel,
    hasMicPermission: Boolean,
    hasAnkiPermission: Boolean,
    onOpenSettings: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestAnki: () -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var showAdvanced by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    LaunchedEffect(hasAnkiPermission) {
        if (hasAnkiPermission) {
            viewModel.refreshDeckStatus(true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AnkiVoice") },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Voice study", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.sessionRunning) "Session is active" else "Ready to start",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.deckStatusLine?.let { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { viewModel.startSession(hasMicPermission, hasAnkiPermission) },
                    enabled = !state.sessionRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start")
                }
                OutlinedButton(
                    onClick = { viewModel.stopSession() },
                    enabled = state.sessionRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stop")
                }
            }

            when (state.phase) {
                StudyPhase.NeedMicPermission -> {
                    InlineNotice("Microphone permission is required.")
                    Button(onClick = onRequestMic, modifier = Modifier.fillMaxWidth()) { Text("Grant microphone") }
                }
                StudyPhase.NeedAnkiPermission -> {
                    InlineNotice("Allow access to AnkiDroid's database.")
                    Button(onClick = onRequestAnki, modifier = Modifier.fillMaxWidth()) { Text("Request AnkiDroid access") }
                }
                StudyPhase.MissingApiKey -> {
                    InlineNotice("Add an API key in Settings.")
                    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open settings") }
                }
                StudyPhase.NoAnkiDroid -> {
                    InlineNotice("AnkiDroid not found.")
                }
                StudyPhase.NoDueCards -> {
                    InlineNotice("No due cards in the current deck.")
                }
                else -> {}
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.cardSummary?.let {
                        Text("Card", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                    state.transcript?.let { Text("You: $it", style = MaterialTheme.typography.bodyMedium) }
                    state.lastTutorLine?.let { Text("Tutor: $it", style = MaterialTheme.typography.bodyMedium) }
                    if (state.turnOnCard > 0) {
                        Text("Turn ${state.turnOnCard}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (hasAnkiPermission) {
                TextButton(onClick = { viewModel.refreshDeckStatus(true) }) {
                    Text("Refresh deck status")
                }
            }
            state.errorMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Error", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        TextButton(
                            onClick = { viewModel.clearError() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = MaterialTheme.shapes.medium,
                    )
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Advanced")
                    Text(if (showAdvanced) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
                }

                if (showAdvanced) {
                    OutlinedButton(
                        onClick = {
                            val share = viewModel.buildShareLogsIntent()
                            context.startActivity(Intent.createChooser(share, "Share debug logs"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Share debug logs")
                    }
                    Text("Simulation (no microphone)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = state.simulationHeardText,
                        onValueChange = viewModel::updateSimulationHeardText,
                        label = { Text("Recognized speech") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.runSimulation() },
                        enabled = !state.sessionRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Run simulation")
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.medium,
                    )
                    .clickable { showDiagnostics = !showDiagnostics }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Diagnostics")
                    Text(if (showDiagnostics) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
                }
                if (showDiagnostics) {
                    Text("Phase: ${state.phase.name}", style = MaterialTheme.typography.labelLarge)
                    Text("Events: ${state.diagnosticsCount}", style = MaterialTheme.typography.labelLarge)
                    state.lastRawModelOutput?.let {
                        Text("Raw model output", style = MaterialTheme.typography.labelMedium)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    state.lastParsedActionJson?.let {
                        Text("Parsed tutor action", style = MaterialTheme.typography.labelMedium)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    state.troubleshooting?.let { tip ->
                        if (state.phase != StudyPhase.Idle || state.errorMessage != null) {
                            Text(tip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
