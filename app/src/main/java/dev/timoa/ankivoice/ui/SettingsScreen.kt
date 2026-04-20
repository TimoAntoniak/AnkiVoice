package dev.timoa.ankivoice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.timoa.ankivoice.settings.UserSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    hasAnkiPermission: Boolean,
) {
    val context = LocalContext.current
    val repo = remember { SecureSettingsRepository(context) }
    val anki = remember { AnkiDroidRepository(context) }

    var provider by remember { mutableStateOf(LlmProvider.ANTHROPIC_CLAUDE) }
    var language by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var studyDeckId by remember { mutableStateOf(AnkiDroidRepository.DECK_ID_FOLLOW_ANKI_SELECTED) }
    var skipTagsCsv by remember { mutableStateOf("") }
    var ttsRate by remember { mutableStateOf(SecureSettingsRepository.DEFAULT_TTS_RATE) }
    var decks by remember { mutableStateOf<List<AnkiDeckSummary>>(emptyList()) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = repo.load()
        provider = s.provider
        language = s.language
        apiKey = s.apiKey
        baseUrl = s.baseUrl
        model = s.model
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
                Text("Language")
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = language == AppLanguage.ENGLISH,
                        onClick = { language = AppLanguage.ENGLISH; saved = false },
                        label = { Text("English") },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    FilterChip(
                        selected = language == AppLanguage.GERMAN,
                        onClick = { language = AppLanguage.GERMAN; saved = false },
                        label = { Text("Deutsch") },
                    )
                }
            }

            item {
                Text("Provider")
                Row(
                    Modifier.padding(vertical = 8.dp),
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
                        modifier = Modifier.padding(end = 8.dp),
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
                    LlmProvider.ANTHROPIC_CLAUDE ->
                        "Anthropic: API key from the Claude Console. Base URL is usually https://api.anthropic.com/v1"
                    LlmProvider.OPENAI_COMPATIBLE ->
                        "OpenAI or any server with POST …/v1/chat/completions."
                }
                Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; saved = false },
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; saved = false },
                    label = { Text("Base URL (no trailing slash)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; saved = false },
                    label = { Text("Model id") },
                    singleLine = true,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item {
                Text("TTS speed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text("${(ttsRate * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = ttsRate,
                    onValueChange = { ttsRate = it; saved = false },
                    valueRange = 0.6f..2.0f,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item {
                Text("Study deck", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "AnkiVoice always passes an explicit deck to AnkiDroid’s scheduler (fixes “no due cards” when another deck is selected). Sub-decks use :: in the name.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!hasAnkiPermission) {
                    Text(
                        "Grant AnkiDroid access from the Study tab to load the deck list here. You can still use “Same as AnkiDroid” if that deck is selected on Anki’s home screen.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            item {
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
                        "Same as AnkiDroid home (whichever deck is selected there)",
                        modifier = Modifier.padding(start = 8.dp),
                    )
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
                            "due (learn+rev)=${deck.dueForVoice}, new=${deck.newCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = skipTagsCsv,
                    onValueChange = { skipTagsCsv = it; saved = false },
                    label = { Text("Skip voice for notes with these tags") },
                    placeholder = { Text("e.g. code, no_voice") },
                    supportingText = {
                        Text("Comma-separated. Matching due cards are buried so AnkiDroid skips them for now (good for code-heavy cards).")
                    },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Button(
                    onClick = {
                        repo.save(
                            UserSettings(
                                provider = provider,
                                language = language,
                                apiKey = apiKey.trim(),
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                model = model.trim(),
                                studyDeckId = studyDeckId,
                                skipTagsCsv = skipTagsCsv.trim(),
                                ttsRate = ttsRate,
                            ),
                        )
                        saved = true
                    },
                ) {
                    Text(if (saved) "Saved" else "Save")
                }
            }
        }
    }
}
