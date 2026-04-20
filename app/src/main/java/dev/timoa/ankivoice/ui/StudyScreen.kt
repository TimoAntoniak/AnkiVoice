package dev.timoa.ankivoice.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    LaunchedEffect(hasAnkiPermission) {
        if (hasAnkiPermission) {
            viewModel.refreshDeckStatus(true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Study") },
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
            Text(
                "Voice session uses your API key, Android speech recognition, and AnkiDroid due cards. Avoid use while driving.",
                style = MaterialTheme.typography.bodyMedium,
            )

            state.deckStatusLine?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyLarge)
            }
            if (hasAnkiPermission) {
                Button(onClick = { viewModel.refreshDeckStatus(true) }) {
                    Text("Refresh deck info from AnkiDroid")
                }
            }

            when (state.phase) {
                StudyPhase.NeedMicPermission -> {
                    Text("Microphone permission is required.", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onRequestMic) { Text("Grant microphone") }
                }
                StudyPhase.NeedAnkiPermission -> {
                    Text("Allow access to AnkiDroid’s database when prompted.", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onRequestAnki) { Text("Request AnkiDroid access") }
                }
                StudyPhase.MissingApiKey -> {
                    Text("Add an API key in Settings.", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                }
                StudyPhase.NoAnkiDroid -> {
                    Text("AnkiDroid not found.", style = MaterialTheme.typography.titleMedium)
                }
                StudyPhase.NoDueCards -> {
                    Text("No due cards in the current deck.", style = MaterialTheme.typography.titleMedium)
                }
                else -> {}
            }

            state.cardSummary?.let {
                Text("Card: $it", style = MaterialTheme.typography.bodyLarge)
            }
            Text("Phase: ${state.phase.name}", style = MaterialTheme.typography.labelLarge)
            Text("Diagnostics events: ${state.diagnosticsCount}", style = MaterialTheme.typography.labelLarge)
            state.transcript?.let { Text("You said: $it") }
            state.lastTutorLine?.let { Text("Tutor: $it") }
            if (state.turnOnCard > 0) {
                Text("Turn on this card: ${state.turnOnCard}")
            }
            state.lastRawModelOutput?.let {
                Text("Raw model output:", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            state.lastParsedActionJson?.let {
                Text("Parsed tutor action:", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            state.errorMessage?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error)
                Button(onClick = { viewModel.clearError() }) { Text("Dismiss") }
            }

            state.troubleshooting?.let { tip ->
                if (state.phase != StudyPhase.Idle || state.errorMessage != null) {
                    Text(tip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }

            Button(
                onClick = { viewModel.startSession(hasMicPermission, hasAnkiPermission) },
                enabled = !state.sessionRunning,
            ) {
                Text("Start voice session")
            }
            Button(
                onClick = { viewModel.stopSession() },
                enabled = state.sessionRunning,
            ) {
                Text("Stop")
            }
            Button(
                onClick = {
                    val share = viewModel.buildShareLogsIntent()
                    context.startActivity(Intent.createChooser(share, "Share debug logs"))
                },
            ) {
                Text("Share debug logs")
            }

            Text("Simulation (no microphone)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use this to test the LLM grading path with typed recognized speech.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = state.simulationHeardText,
                onValueChange = viewModel::updateSimulationHeardText,
                label = { Text("Simulated recognized speech") },
            )
            Button(
                onClick = { viewModel.runSimulation() },
                enabled = !state.sessionRunning,
            ) {
                Text("Run simulation turn")
            }
        }
    }
}
