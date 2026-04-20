package dev.timoa.ankivoice.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.timoa.ankivoice.anki.AnkiCard
import dev.timoa.ankivoice.anki.AnkiDroidRepository
import dev.timoa.ankivoice.llm.ChatMessage
import dev.timoa.ankivoice.llm.LlmCompletionGateway
import dev.timoa.ankivoice.llm.TutorBrain
import dev.timoa.ankivoice.llm.TutorAction
import dev.timoa.ankivoice.llm.TutorEvaluationRequest
import dev.timoa.ankivoice.llm.TutorEvaluationService
import dev.timoa.ankivoice.settings.SecureSettingsRepository
import dev.timoa.ankivoice.settings.UserSettings
import dev.timoa.ankivoice.voice.VoiceCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class StudyPhase {
    Idle,
    NeedAnkiPermission,
    NeedMicPermission,
    MissingApiKey,
    NoAnkiDroid,
    SpeakingPrompt,
    Listening,
    Thinking,
    SpeakingTutor,
    Scheduling,
    LoadingCard,
    NoDueCards,
    Error,
}

data class StudyUiState(
    val phase: StudyPhase = StudyPhase.Idle,
    val cardSummary: String? = null,
    val transcript: String? = null,
    val lastTutorLine: String? = null,
    val turnOnCard: Int = 0,
    val errorMessage: String? = null,
    val troubleshooting: String? = null,
    /** Shown on the study screen so you can confirm AnkiDroid data loads. */
    val deckStatusLine: String? = null,
    val sessionRunning: Boolean = false,
    val simulationHeardText: String = "",
    val lastRawModelOutput: String? = null,
    val lastParsedActionJson: String? = null,
    val diagnosticsCount: Int = 0,
)

class StudyViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val anki = AnkiDroidRepository(application)
    private val settingsRepo = SecureSettingsRepository(application)
    private val llm = LlmCompletionGateway()
    private val tutorEvaluation = TutorEvaluationService(llm)

    private var voice: VoiceCoordinator? = null

    private val _ui = MutableStateFlow(StudyUiState())
    val ui: StateFlow<StudyUiState> = _ui.asStateFlow()

    private val conversation: MutableList<ChatMessage> = mutableListOf()
    private val diagnosticEvents: MutableList<DiagnosticEvent> = mutableListOf()
    private val sessionId: String = UUID.randomUUID().toString()
    private val json = Json { prettyPrint = true }

    private var studyJob: Job? = null

    fun bindVoice(coordinator: VoiceCoordinator) {
        voice = coordinator
    }

    fun clearError() {
        _ui.update { it.copy(errorMessage = null) }
    }

    fun updateSimulationHeardText(value: String) {
        _ui.update { it.copy(simulationHeardText = value) }
    }

    fun buildShareLogsIntent(): Intent {
        val text = exportDiagnosticsText()
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AnkiVoice debug log")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun runSimulation() {
        if (_ui.value.sessionRunning) return
        val cfg = settingsRepo.load()
        val heard = _ui.value.simulationHeardText.trim()
        if (cfg.apiKey.isBlank()) {
            _ui.update {
                it.copy(
                    phase = StudyPhase.MissingApiKey,
                    troubleshooting = TROUBLE_NO_KEY,
                )
            }
            logEvent("simulation_error", mapOf("tag" to "missing_api_key"))
            return
        }
        if (heard.isBlank()) {
            _ui.update {
                it.copy(
                    phase = StudyPhase.Error,
                    errorMessage = "Enter simulated recognized speech first.",
                )
            }
            logEvent("simulation_error", mapOf("tag" to "empty_input"))
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(phase = StudyPhase.Thinking, errorMessage = null) }
            val card = debugSampleCards.first()
            logEvent(
                "simulation_started",
                mapOf(
                    "card_question" to card.questionSpeech,
                    "card_answer" to card.answerSpeech,
                    "heard_text" to heard,
                ),
            )
            runCatching {
                val result = runTutorRound(
                    card = card,
                    cfg = cfg,
                    heard = heard,
                    source = "simulation",
                )
                _ui.update {
                    it.copy(
                        phase = StudyPhase.Idle,
                        transcript = heard,
                        lastTutorLine = result.action.assistantSpeech,
                        lastRawModelOutput = result.rawJson,
                        lastParsedActionJson = json.encodeToString(result.action),
                    )
                }
                logEvent(
                    "simulation_completed",
                    mapOf(
                        "tag" to "ok",
                        "ease" to (result.action.ease ?: -1).toString(),
                    ),
                )
            }.getOrElse { e ->
                logEvent(
                    "simulation_error",
                    mapOf(
                        "tag" to classifyErrorTag(e),
                        "message" to (e.message ?: e.toString()),
                    ),
                )
                _ui.update {
                    it.copy(
                        phase = StudyPhase.Error,
                        errorMessage = e.message ?: e.toString(),
                    )
                }
            }
        }
    }

    /**
     * Loads deck name + due counts from AnkiDroid (no LLM). Call when permissions are granted.
     */
    fun refreshDeckStatus(hasAnkiPermission: Boolean) {
        if (!hasAnkiPermission || !anki.isAnkiDroidInstalled()) {
            _ui.update { it.copy(deckStatusLine = null) }
            return
        }
        viewModelScope.launch {
            val cfg = settingsRepo.load()
            val line = withContext(Dispatchers.IO) {
                runCatching {
                    val deckId = resolveDeckIdOrNull(cfg) ?: return@runCatching "Open AnkiDroid, tap the deck you want on the home screen (or pick a fixed deck in Settings)."
                    val name = anki.queryDeckName(deckId)
                    val summary = anki.queryDeckSummaries().find { it.deckId == deckId }
                    buildString {
                        append("Deck: ")
                        append(name ?: "id $deckId")
                        if (summary != null) {
                            append(" — learn+review due ")
                            append(summary.dueForVoice)
                            append(", new ")
                            append(summary.newCount)
                        }
                    }
                }.getOrElse { e -> "Could not read decks: ${e.message}" }
            }
            _ui.update { it.copy(deckStatusLine = line) }
        }
    }

    fun startSession(
        hasMicPermission: Boolean,
        hasAnkiPermission: Boolean,
    ) {
        if (_ui.value.sessionRunning) return
        logEvent(
            "session_start_attempt",
            mapOf(
                "has_mic_permission" to hasMicPermission.toString(),
                "has_anki_permission" to hasAnkiPermission.toString(),
            ),
        )
        val v = voice ?: run {
            _ui.update {
                it.copy(
                    phase = StudyPhase.Error,
                    errorMessage = "Voice not ready. Re-open the study screen.",
                )
            }
            return
        }
        if (!anki.isAnkiDroidInstalled()) {
            _ui.update {
                it.copy(
                    phase = StudyPhase.NoAnkiDroid,
                    troubleshooting = TROUBLE_NO_ANKI,
                )
            }
            return
        }
        if (!hasMicPermission) {
            _ui.update { it.copy(phase = StudyPhase.NeedMicPermission) }
            return
        }
        if (!hasAnkiPermission) {
            _ui.update { it.copy(phase = StudyPhase.NeedAnkiPermission) }
            return
        }
        val cfg = settingsRepo.load()
        v.setSpeechRate(cfg.ttsRate)
        if (cfg.apiKey.isBlank()) {
            _ui.update {
                it.copy(
                    phase = StudyPhase.MissingApiKey,
                    troubleshooting = TROUBLE_NO_KEY,
                )
            }
            return
        }

        studyJob?.cancel()
        studyJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    sessionRunning = true,
                    errorMessage = null,
                    troubleshooting = TROUBLE_GENERIC,
                    lastRawModelOutput = null,
                    lastParsedActionJson = null,
                )
            }
            try {
                studyLoop(v, cfg)
            } catch (e: CancellationException) {
                _ui.update { it.copy(sessionRunning = false, phase = StudyPhase.Idle) }
                throw e
            } catch (e: Exception) {
                logEvent(
                    "session_error",
                    mapOf(
                        "tag" to classifyErrorTag(e),
                        "message" to (e.message ?: e.toString()),
                    ),
                )
                _ui.update {
                    it.copy(
                        phase = StudyPhase.Error,
                        errorMessage = e.message ?: e.toString(),
                        sessionRunning = false,
                    )
                }
            } finally {
                studyJob = null
            }
        }
    }

    fun stopSession() {
        studyJob?.cancel()
        studyJob = null
        logEvent("session_stopped", mapOf("reason" to "manual_stop"))
        _ui.update { it.copy(sessionRunning = false, phase = StudyPhase.Idle) }
    }

    private suspend fun studyLoop(v: VoiceCoordinator, cfg: UserSettings) {
        while (_ui.value.sessionRunning) {
            _ui.update { it.copy(phase = StudyPhase.LoadingCard, transcript = null, lastTutorLine = null) }
            val deckId = withContext(Dispatchers.IO) {
                resolveDeckIdOrNull(cfg)
            } ?: run {
                _ui.update {
                    it.copy(
                        phase = StudyPhase.Error,
                        sessionRunning = false,
                        errorMessage = "Could not determine which deck to study. Open AnkiDroid, select a deck on the home screen, or choose a deck in Settings.",
                    )
                }
                return
            }
            val deckName = withContext(Dispatchers.IO) { anki.queryDeckName(deckId) }
            val deckSummary = withContext(Dispatchers.IO) { anki.queryDeckSummaries().find { it.deckId == deckId } }
            _ui.update {
                it.copy(
                    deckStatusLine = buildString {
                        append("Deck: ")
                        append(deckName ?: "id $deckId")
                        if (deckSummary != null) {
                            append(" — learn+review due ")
                            append(deckSummary.dueForVoice)
                            append(", new ")
                            append(deckSummary.newCount)
                        }
                    },
                )
            }
            val skipTags = cfg.parsedSkipTags()
            val card = withContext(Dispatchers.IO) {
                anki.nextStudyableCard(deckId, skipTags)
            }
            if (card == null) {
                _ui.update {
                    it.copy(
                        phase = StudyPhase.NoDueCards,
                        sessionRunning = false,
                        cardSummary = null,
                        troubleshooting = "No cards in the queue for this deck, or all due cards matched a skip-tag and were buried. Try another deck in Settings or adjust skip tags.",
                    )
                }
                return
            }
            logEvent(
                "card_loaded",
                mapOf(
                    "question" to card.questionSpeech,
                    "answer" to card.answerSpeech,
                    "deck_id" to deckId.toString(),
                ),
            )
            var turn = 0
            val cardStart = System.currentTimeMillis()
            _ui.update {
                it.copy(
                    cardSummary = card.questionSpeech.take(120).let { s -> if (s.length < card.questionSpeech.length) "$s…" else s },
                    turnOnCard = 0,
                )
            }

            // First pass: read question, then listen.
            _ui.update { it.copy(phase = StudyPhase.SpeakingPrompt) }
            v.speak(localized(cfg, "here_is_card", card.questionSpeech))
            if (!_ui.value.sessionRunning) return

            while (_ui.value.sessionRunning && turn < TutorBrain.MAX_TURNS_PER_CARD) {
                turn++
                _ui.update { it.copy(phase = StudyPhase.Listening, turnOnCard = turn) }
                val heard = try {
                    v.listen()
                } catch (e: Exception) {
                    logEvent(
                        "stt_error",
                        mapOf(
                            "tag" to classifyErrorTag(e),
                            "message" to (e.message ?: e.toString()),
                        ),
                    )
                    _ui.update { it.copy(phase = StudyPhase.SpeakingTutor) }
                    v.speak(localized(cfg, "did_not_catch"))
                    if (!_ui.value.sessionRunning) return
                    continue
                }
                if (!_ui.value.sessionRunning) return
                if (heard.isBlank()) {
                    logEvent("stt_empty", mapOf("tag" to "stt_no_content"))
                    _ui.update { it.copy(phase = StudyPhase.SpeakingTutor) }
                    v.speak(localized(cfg, "did_not_hear"))
                    if (!_ui.value.sessionRunning) return
                    continue
                }
                _ui.update { it.copy(transcript = heard, phase = StudyPhase.Thinking) }
                val result = runTutorRound(card, cfg, heard, "voice_session")
                if (!_ui.value.sessionRunning) return

                val action = result.action

                _ui.update { it.copy(lastTutorLine = action.assistantSpeech, phase = StudyPhase.SpeakingTutor) }
                if (action.assistantSpeech.isNotBlank()) {
                    v.speak(action.assistantSpeech)
                }
                if (!_ui.value.sessionRunning) return

                val ease = action.ease?.takeIf { it in 1..4 } ?: 1
                _ui.update { it.copy(phase = StudyPhase.Scheduling) }
                val elapsed = System.currentTimeMillis() - cardStart
                withContext(Dispatchers.IO) {
                    anki.scheduleAnswer(card.noteId, card.cardOrd, ease, elapsed)
                        .getOrElse { throw it }
                }
                logEvent(
                    "schedule_review",
                    mapOf(
                        "ease" to ease.toString(),
                        "elapsed_ms" to elapsed.toString(),
                    ),
                )
                v.playGradeCue(ease)
                break
            }

            if (turn >= TutorBrain.MAX_TURNS_PER_CARD && _ui.value.sessionRunning) {
                _ui.update { it.copy(phase = StudyPhase.Scheduling) }
                val elapsed = System.currentTimeMillis() - cardStart
                withContext(Dispatchers.IO) {
                    anki.scheduleAnswer(card.noteId, card.cardOrd, 3, elapsed).getOrElse { throw it }
                }
                _ui.update { it.copy(phase = StudyPhase.SpeakingTutor) }
                v.speak(localized(cfg, "fallback_good"))
                if (!_ui.value.sessionRunning) return
            }
        }
    }

    private fun resolveDeckIdOrNull(cfg: UserSettings): Long? =
        when (cfg.studyDeckId) {
            AnkiDroidRepository.DECK_ID_FOLLOW_ANKI_SELECTED -> anki.queryAnkiSelectedDeckId()
            else -> cfg.studyDeckId
        }

    private suspend fun runTutorRound(
        card: AnkiCard,
        cfg: UserSettings,
        heard: String,
        source: String,
    ): TutorRoundResult {
        logEvent("user_input", mapOf("source" to source, "text" to heard))

        val evalResult = withContext(Dispatchers.IO) {
            tutorEvaluation.evaluate(
                TutorEvaluationRequest(
                    cardFront = card.questionSpeech,
                    cardBack = card.answerSpeech,
                    learnerAnswer = heard,
                    settings = cfg,
                ),
            )
        }
        val rawJson = evalResult.rawJson
        val action = evalResult.action
        logEvent(
            "llm_response_raw",
            mapOf(
                "source" to source,
                "raw_output" to rawJson,
                "provider" to cfg.provider.name,
                "model" to cfg.model,
            ),
        )

        _ui.update {
            it.copy(
                lastRawModelOutput = rawJson,
                lastParsedActionJson = json.encodeToString(action),
            )
        }
        logEvent(
            "action_validated",
            mapOf(
                "assistant_speech" to action.assistantSpeech,
                "ease" to (action.ease ?: -1).toString(),
                "continue_conversation" to action.continueConversation.toString(),
                "schedule_review" to action.scheduleReview.toString(),
            ),
        )
        return TutorRoundResult(rawJson = rawJson, action = action)
    }

    private fun logEvent(type: String, payload: Map<String, String>) {
        val event = DiagnosticEvent(
            tsIso = isoNow(),
            type = type,
            payload = payload,
        )
        diagnosticEvents.add(event)
        _ui.update { it.copy(diagnosticsCount = diagnosticEvents.size) }
    }

    private fun exportDiagnosticsText(): String {
        val header = buildString {
            append("session_id=")
            append(sessionId)
            append('\n')
            append("event_count=")
            append(diagnosticEvents.size)
            append('\n')
            append("generated_at=")
            append(isoNow())
            append('\n')
        }
        val body = diagnosticEvents.joinToString("\n") { event ->
            val kv = event.payload.entries.joinToString(" | ") { (k, v) -> "$k=$v" }
            "${event.tsIso} [${event.type}] $kv"
        }
        return "$header\n$body"
    }

    private fun classifyErrorTag(e: Throwable): String {
        val msg = (e.message ?: "").lowercase(Locale.ROOT)
        return when {
            "json" in msg -> "invalid_json"
            "http" in msg -> "llm_http_error"
            "recognition" in msg || "speech" in msg || "audio" in msg -> "stt_error"
            "ease" in msg -> "invalid_ease"
            else -> "unknown_error"
        }
    }

    private fun isoNow(): String = isoFormatter.format(Date())

    private fun localized(cfg: UserSettings, key: String, cardText: String = ""): String =
        when (cfg.language.toStorageValue()) {
            "de" ->
                when (key) {
                    "here_is_card" -> "Hier ist deine Karte. $cardText"
                    "did_not_catch" -> "Ich habe das nicht verstanden. Bitte nochmal."
                    "did_not_hear" -> "Ich habe nichts gehoert. Bitte nochmal."
                    "fallback_good" -> "Ich bewerte das als Gut und gehe weiter."
                    else -> key
                }
            else ->
                when (key) {
                    "here_is_card" -> "Here is your card. $cardText"
                    "did_not_catch" -> "I didn't catch that. Please try again."
                    "did_not_hear" -> "I didn't hear anything. Try again."
                    "fallback_good" -> "I'll mark this Good and move on."
                    else -> key
                }
        }

    companion object {
        private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        private val debugSampleCards = listOf(
            AnkiCard(
                noteId = -1L,
                cardOrd = 0,
                questionHtml = "What is the capital of France?",
                answerHtml = "Paris",
            ),
            AnkiCard(
                noteId = -2L,
                cardOrd = 0,
                questionHtml = "Translate to German: apple",
                answerHtml = "Apfel",
            ),
        )
        const val TROUBLE_GENERIC =
            "Tips: Open AnkiDroid once, enable Advanced → AnkiDroid API, grant access, tap the deck you want on Anki’s home (or pick a deck in Settings), then Refresh deck info."
        const val TROUBLE_NO_ANKI =
            "Install AnkiDroid from F-Droid or Play Store, add a deck with due cards, then try again."
        const val TROUBLE_NO_KEY =
            "Open Settings: choose Claude (Anthropic) or OpenAI-compatible, then paste your API key. Keys stay encrypted on device."
    }
}

private data class DiagnosticEvent(
    val tsIso: String,
    val type: String,
    val payload: Map<String, String>,
)

private data class TutorRoundResult(
    val rawJson: String,
    val action: TutorAction,
)
