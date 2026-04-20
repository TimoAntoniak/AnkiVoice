package dev.timoa.ankivoice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.timoa.ankivoice.anki.AnkiDeckSummary
import dev.timoa.ankivoice.anki.AnkiDroidRepository
import dev.timoa.ankivoice.settings.AppLanguage
import dev.timoa.ankivoice.settings.LlmProvider
import dev.timoa.ankivoice.settings.SecureSettingsRepository
import dev.timoa.ankivoice.settings.TtsBackend
import dev.timoa.ankivoice.settings.UserSettings
import dev.timoa.ankivoice.voice.VoiceCoordinator
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    hasAnkiPermission: Boolean,
    voice: VoiceCoordinator,
) {
    val context = LocalContext.current
    val repo = remember { SecureSettingsRepository(context) }
    val anki = remember { AnkiDroidRepository(context) }

    var provider by remember { mutableStateOf(LlmProvider.ANTHROPIC_CLAUDE) }
    var language by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var ttsBackend by remember { mutableStateOf(TtsBackend.SYSTEM) }
    var studyDeckId by remember { mutableStateOf(AnkiDroidRepository.DECK_ID_FOLLOW_ANKI_SELECTED) }
    var skipTagsCsv by remember { mutableStateOf("") }
    var ttsRate by remember { mutableStateOf(SecureSettingsRepository.DEFAULT_TTS_RATE) }
    var decks by remember { mutableStateOf<List<AnkiDeckSummary>>(emptyList()) }
    var saved by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var ttsTestError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val s = repo.load()
        provider = s.provider
        language = s.language
        apiKey = s.apiKey
        baseUrl = s.baseUrl
        model = s.model
        ttsBackend = s.ttsBackend
        studyDeckId = s.studyDeckId
        skipTagsCsv = s.skipTagsCsv
        ttsRate = s.ttsRate
    }

    LaunchedEffect(hasAnkiPermission) {
        if (hasAnkiPermission) {
            decks = anki.queryDeckSummaries()
        } else {
            decks = emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            item {
                SettingsSection(title = "Language") {
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = language == AppLanguage.ENGLISH,
                            onClick = { language = AppLanguage.ENGLISH; saved = false },
                            label = { Text("English") },
                        )
                        FilterChip(
                            selected = language == AppLanguage.GERMAN,
                            onClick = { language = AppLanguage.GERMAN; saved = false },
                            label = { Text("Deutsch") },
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Model") {
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = provider == LlmProvider.ANTHROPIC_CLAUDE,
                            onClick = {
                                if (provider != LlmProvider.ANTHROPIC_CLAUDE) {
                                    provider = LlmProvider.ANTHROPIC_CLAUDE
                                    baseUrl = SecureSettingsRepository.defaultBaseFor(LlmProvider.ANTHROPIC_CLAUDE)
                                    model = SecureSettingsRepository.defaultModelFor(LlmProvider.ANTHROPIC_CLAUDE)
                                    saved = false
                                }
                            },
                            label = { Text("Claude") },
                        )
                        FilterChip(
                            selected = provider == LlmProvider.OPENAI_COMPATIBLE,
                            onClick = {
                                if (provider != LlmProvider.OPENAI_COMPATIBLE) {
                                    provider = LlmProvider.OPENAI_COMPATIBLE
                                    baseUrl = SecureSettingsRepository.defaultBaseFor(LlmProvider.OPENAI_COMPATIBLE)
                                    model = SecureSettingsRepository.defaultModelFor(LlmProvider.OPENAI_COMPATIBLE)
                                    saved = false
                                }
                            },
                            label = { Text("OpenAI-compatible") },
                        )
                    }
                    val hint = when (provider) {
                        LlmProvider.ANTHROPIC_CLAUDE -> "Use your Claude API key"
                        LlmProvider.OPENAI_COMPATIBLE -> "Requires /v1/chat/completions support"
                    }
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; saved = false },
                        label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
            }

            item {
                SettingsSection(title = "Voice") {
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = ttsBackend == TtsBackend.SYSTEM,
                            onClick = { ttsBackend = TtsBackend.SYSTEM; saved = false },
                            label = { Text("System TTS") },
                        )
                        FilterChip(
                            selected = ttsBackend == TtsBackend.LOCAL_PIPER_EXPERIMENTAL,
                            onClick = { ttsBackend = TtsBackend.LOCAL_PIPER_EXPERIMENTAL; saved = false },
                            label = { Text("Local Piper (exp)") },
                        )
                    }
                    Text(
                        when (ttsBackend) {
                            TtsBackend.SYSTEM ->
                                "Uses the Android TTS engine and voices you have installed (e.g. Google / Samsung)."
                            TtsBackend.LOCAL_PIPER_EXPERIMENTAL ->
                                "Offline Piper (Sherpa-ONNX): neural voice bundled with the app. " +
                                    "First play may take a few seconds while the model loads."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    OutlinedButton(
                        onClick = {
                            ttsTestError = null
                            scope.launch {
                                if (!voice.isTtsReady()) {
                                    ttsTestError = "Voice engine is still loading. Try again in a moment."
                                    return@launch
                                }
                                runCatching {
                                    voice.applyVoiceSettings(language, ttsBackend, ttsRate)
                                    voice.speak(ttsTestPhrase(language))
                                }.onFailure { e ->
                                    ttsTestError = e.message ?: e.toString()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    ) {
                        Text("Test voice")
                    }
                    ttsTestError?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("TTS speed", style = MaterialTheme.typography.titleSmall)
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("${(ttsRate * 100).roundToInt()}%") },
                            shape = CircleShape,
                        )
                    }
                    Slider(
                        value = ttsRate,
                        onValueChange = { ttsRate = it; saved = false },
                        valueRange = 0.6f..2.0f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                SettingsSection(title = "Study deck") {
                    if (!hasAnkiPermission) {
                        Text(
                            "Grant AnkiDroid access in Study to load decks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                    }
                    val followSelected = studyDeckId == AnkiDroidRepository.DECK_ID_FOLLOW_ANKI_SELECTED
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = followSelected,
                                onClick = {
                                    studyDeckId = AnkiDroidRepository.DECK_ID_FOLLOW_ANKI_SELECTED
                                    saved = false
                                },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = followSelected, onClick = null)
                        Text(
                            "Same as AnkiDroid home",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            items(decks, key = { it.deckId }) { deck ->
                val selected = studyDeckId == deck.deckId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = (12 * deck.indentLevel).dp)
                        .selectable(
                            selected = selected,
                            onClick = {
                                studyDeckId = deck.deckId
                                saved = false
                            },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(deck.fullName)
                        Text(
                            "due=${deck.dueForVoice}, new=${deck.newCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Advanced", topPadding = 6.dp) {
                    OutlinedButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "Hide advanced options" else "Show advanced options")
                    }

                    if (showAdvanced) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it; saved = false },
                            label = { Text("Base URL") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it; saved = false },
                            label = { Text("Model id") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                        OutlinedTextField(
                            value = skipTagsCsv,
                            onValueChange = { skipTagsCsv = it; saved = false },
                            label = { Text("Skip tags") },
                            placeholder = { Text("code, no_voice") },
                            singleLine = false,
                            minLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        repo.save(
                            UserSettings(
                                provider = provider,
                                language = language,
                                apiKey = apiKey.trim(),
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                model = model.trim(),
                                ttsBackend = ttsBackend,
                                studyDeckId = studyDeckId,
                                skipTagsCsv = skipTagsCsv.trim(),
                                ttsRate = ttsRate,
                            ),
                        )
                        saved = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 24.dp),
                ) {
                    Text(if (saved) "Saved" else "Save settings")
                }
            }
        }
    }
}

private fun ttsTestPhrase(language: AppLanguage): String =
    when (language) {
        AppLanguage.ENGLISH ->
            "This is AnkiVoice. You are hearing the app language and speech speed for study sessions."
        AppLanguage.GERMAN ->
            "Das ist AnkiVoice. Du hoerst die App-Sprache und die Sprechgeschwindigkeit fuer das Kartenlernen."
    }

@Composable
private fun SettingsSection(
    title: String,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
