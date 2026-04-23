package dev.timoa.ankivoice.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.timoa.ankivoice.anki.AnkiCard
import dev.timoa.ankivoice.anki.AnkiDeckSummary
import dev.timoa.ankivoice.anki.AnkiDroidRepository
import dev.timoa.ankivoice.llm.ChatMessage
import dev.timoa.ankivoice.llm.TutorTerminalAction
import dev.timoa.ankivoice.llm.LlmCompletionGateway
import dev.timoa.ankivoice.llm.TOOL_BURY_CARD
import dev.timoa.ankivoice.llm.TutorBrain
import dev.timoa.ankivoice.llm.TutorAction
import dev.timoa.ankivoice.llm.TutorEvaluationRequest
import dev.timoa.ankivoice.llm.TutorEvaluationService
import dev.timoa.ankivoice.llm.TOOL_GRADE_ANSWER
import dev.timoa.ankivoice.llm.TOOL_LIST_DECKS
import dev.timoa.ankivoice.llm.TOOL_REREAD_CARD_FRONT
import dev.timoa.ankivoice.llm.TOOL_SET_CARD_TUTOR_INSTRUCTIONS
import dev.timoa.ankivoice.llm.TOOL_SET_SPEECH_VERBALIZATION
import dev.timoa.ankivoice.llm.TOOL_SUGGEST_NEXT_DECK
import dev.timoa.ankivoice.llm.TOOL_SUSPEND_CARD
import dev.timoa.ankivoice.llm.TOOL_SWITCH_DECK
import dev.timoa.ankivoice.metadata.CardOverlayRepository
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
    val timeline: List<ChatTimelineEntry> = emptyList(),
)

class StudyViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val anki = AnkiDroidRepository(application)
    private val settingsRepo = SecureSettingsRepository(application)
    private val overlayRepo = CardOverlayRepository(application)
    private val llm = LlmCompletionGateway()
    private val tutorEvaluation = TutorEvaluationService(llm)

    private var voice: VoiceCoordinator? = null

    private val _ui = MutableStateFlow(StudyUiState())
    val ui: StateFlow<StudyUiState> = _ui.asStateFlow()

    private val conversation: MutableList<ChatMessage> = mutableListOf()
    private val diagnosticEvents: MutableList<DiagnosticEvent> = mutableListOf()
    private val sessionId: String = UUID.randomUUID().toString()

    private var studyJob: Job? = null
    private var pendingDeckSuggestion: PendingDeckSuggestion? = null

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
                        lastParsedActionJson = result.action.toString(),
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
        v.applyVoiceSettings(cfg.language, cfg.ttsBackend, cfg.ttsRate)
        logEvent(
            "tts_config",
            mapOf(
                "backend" to cfg.ttsBackend.toStorageValue(),
                "rate" to cfg.ttsRate.toString(),
                "language" to cfg.language.toStorageValue(),
            ),
        )
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
            appendTimeline(TimelineRole.APP, "Study session started.")
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

    fun startChatSession(
        hasMicPermission: Boolean,
        hasAnkiPermission: Boolean,
    ) {
        if (_ui.value.sessionRunning) return
        val v = voice ?: return
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
        v.applyVoiceSettings(cfg.language, cfg.ttsBackend, cfg.ttsRate)
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
                    phase = StudyPhase.SpeakingTutor,
                )
            }
            try {
                v.speak(localized(cfg, "chat_mode_intro"))
                chatBootstrapLoop(v, cfg)
            } catch (e: CancellationException) {
                _ui.update { it.copy(sessionRunning = false, phase = StudyPhase.Idle) }
                throw e
            } catch (e: Exception) {
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
        appendTimeline(TimelineRole.APP, "Session stopped.")
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
            val selectedDeckBeforeSync = withContext(Dispatchers.IO) { anki.queryAnkiSelectedDeckId() }
            if (selectedDeckBeforeSync != deckId) {
                withContext(Dispatchers.IO) {
                    anki.setAnkiSelectedDeckId(deckId).getOrElse { throw it }
                }
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
            appendTimeline(
                TimelineRole.APP,
                "Card prompt: ${card.questionSpeech}",
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

            var scheduledThisCard = false
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
                appendTimeline(TimelineRole.USER, heard)
                val result = try {
                    runTutorRound(card, cfg, heard, "voice_session")
                } catch (e: Exception) {
                    logEvent(
                        "tutor_round_error",
                        mapOf(
                            "tag" to classifyErrorTag(e),
                            "message" to (e.message ?: e.toString()),
                        ),
                    )
                    _ui.update {
                        it.copy(
                            phase = StudyPhase.Listening,
                            errorMessage = localized(cfg, "grade_failed_retry"),
                        )
                    }
                    v.speak(localized(cfg, "grade_failed_retry"))
                    turn--
                    continue
                }
                if (!_ui.value.sessionRunning) return

                val action = result.action
                appendTimeline(
                    TimelineRole.MODEL,
                    if (action.assistantSpeech.isBlank()) "(No spoken feedback)" else action.assistantSpeech,
                )
                appendTimeline(TimelineRole.TOOL, "Tools: ${describeActionTools(action)}")
                overlayRepo.applyWrites(
                    noteId = card.noteId,
                    cardOrd = card.cardOrd,
                    writes = action.metadataWrites,
                    learnerAnswerToAppend = heard,
                    adaptiveHistoryEnabled = cfg.adaptiveFeedbackHistoryEnabled,
                )

                _ui.update { it.copy(lastTutorLine = action.assistantSpeech, phase = StudyPhase.SpeakingTutor) }
                val extraSpeech = buildString {
                    if (action.wantsDeckList) {
                        val decks = deckListSpeech(cfg)
                        append(decks)
                        appendTimeline(TimelineRole.TOOL, "list_decks -> $decks")
                    }
                    if (action.wantsDeckSuggestion) {
                        val suggestion = suggestDeck(action.deckSuggestionPreference.orEmpty())
                        if (suggestion != null) {
                            pendingDeckSuggestion = PendingDeckSuggestion(
                                deckId = suggestion.deckId,
                                spokenReason = suggestion.reason,
                                createdAtMs = System.currentTimeMillis(),
                            )
                            if (isNotEmpty()) append(" ")
                            append(suggestion.reason)
                            appendTimeline(TimelineRole.TOOL, "suggest_next_deck -> ${suggestion.reason}")
                        }
                    }
                }.trim()
                if (action.assistantSpeech.isNotBlank()) {
                    v.speak(action.assistantSpeech)
                }
                if (extraSpeech.isNotBlank()) {
                    v.speak(extraSpeech)
                }
                if (action.rereadCardFront) {
                    v.speak(localized(cfg, "here_is_card", card.questionSpeech))
                }
                if (!_ui.value.sessionRunning) return

                when (action.terminalAction) {
                    TutorTerminalAction.GRADE_ANSWER -> {
                        val modelEase = action.ease?.takeIf { it in 1..4 } ?: 1
                        val maxAllowedEase = card.reviewButtonCount.coerceIn(1, 4)
                        val ease = modelEase.coerceIn(1, maxAllowedEase)
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
                        appendTimeline(TimelineRole.TOOL, "grade_answer -> scheduled ease=$ease")
                        scheduledThisCard = true
                        v.playGradeCue(ease)
                        break
                    }
                    TutorTerminalAction.SUSPEND_CARD -> {
                        _ui.update { it.copy(phase = StudyPhase.Scheduling) }
                        withContext(Dispatchers.IO) {
                            anki.suspendCard(card.noteId, card.cardOrd).getOrElse { throw it }
                        }
                        appendTimeline(TimelineRole.TOOL, "suspend_card -> suspended current card")
                        logEvent("suspend_card", mapOf("note_id" to card.noteId.toString(), "ord" to card.cardOrd.toString()))
                        scheduledThisCard = true
                        break
                    }
                    TutorTerminalAction.BURY_CARD -> {
                        _ui.update { it.copy(phase = StudyPhase.Scheduling) }
                        withContext(Dispatchers.IO) {
                            anki.buryCard(card.noteId, card.cardOrd).getOrElse { throw it }
                        }
                        appendTimeline(TimelineRole.TOOL, "bury_card -> buried current card")
                        logEvent("bury_card", mapOf("note_id" to card.noteId.toString(), "ord" to card.cardOrd.toString()))
                        scheduledThisCard = true
                        break
                    }
                    TutorTerminalAction.SWITCH_DECK -> {
                        _ui.update { it.copy(phase = StudyPhase.Scheduling) }
                        val switched = withContext(Dispatchers.IO) {
                            switchDeckFromAction(action, cfg)
                        }
                        if (!switched) {
                            appendTimeline(TimelineRole.TOOL, "switch_deck -> failed to resolve target")
                            _ui.update { it.copy(phase = StudyPhase.SpeakingTutor) }
                            v.speak(localized(cfg, "deck_not_found"))
                        } else {
                            appendTimeline(TimelineRole.TOOL, "switch_deck -> deck changed")
                            v.speak(localized(cfg, "deck_switched"))
                        }
                        scheduledThisCard = true
                        break
                    }
                }
            }

            if (!scheduledThisCard && turn >= TutorBrain.MAX_TURNS_PER_CARD && _ui.value.sessionRunning) {
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

    private suspend fun chatBootstrapLoop(v: VoiceCoordinator, cfg: UserSettings) {
        val bootstrapCard = AnkiCard(
            noteId = -9999L,
            cardOrd = 0,
            questionHtml = "Deck navigation chat",
            answerHtml = "Use tools to list, suggest, and switch deck.",
        )
        while (_ui.value.sessionRunning) {
            _ui.update { it.copy(phase = StudyPhase.Listening) }
            val heard = try {
                v.listen()
            } catch (_: Exception) {
                v.speak(localized(cfg, "did_not_catch"))
                continue
            }
            if (heard.isBlank()) {
                v.speak(localized(cfg, "did_not_hear"))
                continue
            }
            appendTimeline(TimelineRole.USER, heard)
            _ui.update { it.copy(transcript = heard, phase = StudyPhase.Thinking) }
            val result = runTutorRound(bootstrapCard, cfg, heard, "chat_bootstrap")
            val action = result.action
            appendTimeline(
                TimelineRole.MODEL,
                if (action.assistantSpeech.isBlank()) "(No spoken feedback)" else action.assistantSpeech,
            )
            appendTimeline(TimelineRole.TOOL, "Tools: ${describeActionTools(action)}")
            _ui.update { it.copy(lastTutorLine = action.assistantSpeech, phase = StudyPhase.SpeakingTutor) }
            if (action.assistantSpeech.isNotBlank()) v.speak(action.assistantSpeech)
            if (action.wantsDeckList) {
                val decks = deckListSpeech(cfg)
                appendTimeline(TimelineRole.TOOL, "list_decks -> $decks")
                v.speak(decks)
            }
            if (action.wantsDeckSuggestion) {
                val suggestion = suggestDeck(action.deckSuggestionPreference.orEmpty())
                if (suggestion != null) {
                    pendingDeckSuggestion = PendingDeckSuggestion(
                        deckId = suggestion.deckId,
                        spokenReason = suggestion.reason,
                        createdAtMs = System.currentTimeMillis(),
                    )
                    appendTimeline(TimelineRole.TOOL, "suggest_next_deck -> ${suggestion.reason}")
                    v.speak(suggestion.reason)
                }
            }
            if (action.terminalAction == TutorTerminalAction.SWITCH_DECK) {
                val switched = withContext(Dispatchers.IO) { switchDeckFromAction(action, cfg) }
                if (switched) {
                    appendTimeline(TimelineRole.TOOL, "switch_deck -> deck changed, entering study loop")
                    v.speak(localized(cfg, "deck_switched"))
                    val nextCfg = settingsRepo.load()
                    studyLoop(v, nextCfg)
                    return
                }
                appendTimeline(TimelineRole.TOOL, "switch_deck -> failed to resolve target")
                v.speak(localized(cfg, "deck_not_found"))
            }
        }
    }

    private fun appendTimeline(role: TimelineRole, message: String) {
        val text = message.trim()
        if (text.isBlank()) return
        _ui.update {
            it.copy(
                timeline = (it.timeline + ChatTimelineEntry(isoNow(), role, text)).takeLast(300),
            )
        }
    }

    private fun describeActionTools(action: TutorAction): String {
        val parts = mutableListOf<String>()
        if (action.rereadCardFront) parts.add(TOOL_REREAD_CARD_FRONT)
        if (action.wantsDeckList) parts.add(TOOL_LIST_DECKS)
        if (action.wantsDeckSuggestion) parts.add(TOOL_SUGGEST_NEXT_DECK)
        action.metadataWrites.forEach {
            when (it) {
                is dev.timoa.ankivoice.llm.CardMetadataWrite.TutorInstruction -> parts.add(TOOL_SET_CARD_TUTOR_INSTRUCTIONS)
                is dev.timoa.ankivoice.llm.CardMetadataWrite.SpeechVerbalization -> parts.add(TOOL_SET_SPEECH_VERBALIZATION)
            }
        }
        parts.add(
            when (action.terminalAction) {
                TutorTerminalAction.GRADE_ANSWER -> TOOL_GRADE_ANSWER
                TutorTerminalAction.SUSPEND_CARD -> TOOL_SUSPEND_CARD
                TutorTerminalAction.BURY_CARD -> TOOL_BURY_CARD
                TutorTerminalAction.SWITCH_DECK -> TOOL_SWITCH_DECK
            },
        )
        return if (parts.isEmpty()) "(none)" else parts.joinToString(", ")
    }

    private fun resolveDeckIdOrNull(cfg: UserSettings): Long? =
        when (cfg.studyDeckId) {
            AnkiDroidRepository.DECK_ID_FOLLOW_ANKI_SELECTED -> anki.queryAnkiSelectedDeckId()
            else -> cfg.studyDeckId
        }

    private fun deckListSpeech(cfg: UserSettings): String {
        val decks = anki.queryDeckSummaries()
            .filter { it.dueForVoice > 0 }
            .sortedByDescending { it.dueForVoice }
            .take(3)
        if (decks.isEmpty()) {
            return localized(cfg, "no_decks_due")
        }
        return decks.joinToString(
            separator = " ",
            prefix = localized(cfg, "decks_left_prefix"),
        ) { "${it.fullName} (${it.dueForVoice})" }
    }

    private fun suggestDeck(preference: String): DeckSuggestion? {
        val decks = anki.queryDeckSummaries().filter { it.dueForVoice > 0 }
        if (decks.isEmpty()) return null
        val pref = preference.lowercase(Locale.ROOT)
        val filtered = if (pref.contains("not ") || pref.contains("nicht")) {
            decks.filterNot { pref.split(" ").any { token -> token.length > 2 && it.fullName.lowercase(Locale.ROOT).contains(token) } }
        } else {
            decks
        }
        val target = (filtered.ifEmpty { decks }).maxByOrNull { it.dueForVoice } ?: return null
        return DeckSuggestion(target.deckId, "Suggested: ${target.fullName}, ${target.dueForVoice} due.")
    }

    private fun switchDeckFromAction(action: TutorAction, cfg: UserSettings): Boolean {
        val all = anki.queryDeckSummaries()
        val pending = pendingDeckSuggestion?.takeIf { System.currentTimeMillis() - it.createdAtMs <= 120_000 }
        val deckId = action.switchDeckId
            ?: if (action.deckQuery.orEmpty().lowercase(Locale.ROOT) in setOf("yes", "do it", "sounds good") && pending != null) pending.deckId else null
            ?: resolveDeckByQuery(action.deckQuery, all)
            ?: return false
        pendingDeckSuggestion = null
        settingsRepo.save(cfg.copy(studyDeckId = deckId))
        anki.setAnkiSelectedDeckId(deckId)
        return true
    }

    private fun resolveDeckByQuery(query: String?, decks: List<AnkiDeckSummary>): Long? {
        val q = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (q.isBlank()) return null
        val exact = decks.firstOrNull { it.fullName.lowercase(Locale.ROOT) == q }?.deckId
        if (exact != null) return exact
        return decks.firstOrNull { it.fullName.lowercase(Locale.ROOT).contains(q) }?.deckId
    }

    private fun buildDeckSnapshot(): String {
        val rows = anki.queryDeckSummaries()
            .sortedByDescending { it.dueForVoice }
            .take(8)
            .joinToString(" | ") { "${it.deckId}:${it.fullName}:${it.dueForVoice}" }
        return rows
    }

    private suspend fun runTutorRound(
        card: AnkiCard,
        cfg: UserSettings,
        heard: String,
        source: String,
    ): TutorRoundResult {
        logEvent("user_input", mapOf("source" to source, "text" to heard))
        val overlay = overlayRepo.load(card.noteId, card.cardOrd)
        val deckSnapshot = withContext(Dispatchers.IO) { buildDeckSnapshot() }

        val evalResult = withContext(Dispatchers.IO) {
            tutorEvaluation.evaluate(
                TutorEvaluationRequest(
                    cardFront = overlay.speechVerbalizationFront.ifBlank { card.questionSpeech },
                    cardBack = overlay.speechVerbalizationBack.ifBlank { card.answerSpeech },
                    learnerAnswer = heard,
                    settings = cfg,
                    cardTutorInstructions = overlay.tutorInstructions,
                    cardSpeechVerbalization =
                        buildString {
                            if (overlay.speechVerbalizationFront.isNotBlank()) {
                                append("front=")
                                append(overlay.speechVerbalizationFront)
                            }
                            if (overlay.speechVerbalizationBack.isNotBlank()) {
                                if (isNotEmpty()) append(" | ")
                                append("back=")
                                append(overlay.speechVerbalizationBack)
                            }
                        },
                    recentLearnerResponses = if (cfg.adaptiveFeedbackHistoryEnabled) overlay.recentLearnerResponses else emptyList(),
                    deckSnapshot = deckSnapshot,
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
                lastParsedActionJson = action.toString(),
            )
        }
        logEvent(
            "action_validated",
            mapOf(
                "assistant_speech" to action.assistantSpeech,
                "terminal_action" to action.terminalAction.name,
                "ease" to (action.ease ?: -1).toString(),
                "wants_deck_list" to action.wantsDeckList.toString(),
                "wants_deck_suggestion" to action.wantsDeckSuggestion.toString(),
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
            "grade_answer" in msg -> "missing_grade_tool_call"
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
                    "grade_failed_retry" -> "Ich konnte die Bewertung nicht abschliessen. Lass es uns mit derselben Karte nochmal versuchen."
                    "deck_not_found" -> "Ich konnte das Deck nicht zuordnen."
                    "deck_switched" -> "Deck gewechselt."
                    "chat_mode_intro" -> "Chat gestartet. Sag zum Beispiel: Waehle ein Deck fuer mich."
                    "no_decks_due" -> "Es gibt aktuell keine faelligen Decks."
                    "decks_left_prefix" -> "Faellige Decks: "
                    else -> key
                }
            else ->
                when (key) {
                    "here_is_card" -> "Here is your card. $cardText"
                    "did_not_catch" -> "I didn't catch that. Please try again."
                    "did_not_hear" -> "I didn't hear anything. Try again."
                    "fallback_good" -> "I'll mark this Good and move on."
                    "grade_failed_retry" -> "I could not complete grading. Let's retry the same card."
                    "deck_not_found" -> "I could not map that to a deck."
                    "deck_switched" -> "Switched deck."
                    "chat_mode_intro" -> "Chat started. Say for example: choose a deck for me."
                    "no_decks_due" -> "There are no due decks right now."
                    "decks_left_prefix" -> "Decks left: "
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

private data class DeckSuggestion(
    val deckId: Long,
    val reason: String,
)

private data class PendingDeckSuggestion(
    val deckId: Long,
    val spokenReason: String,
    val createdAtMs: Long,
)

enum class TimelineRole {
    USER,
    APP,
    MODEL,
    TOOL,
}

data class ChatTimelineEntry(
    val tsIso: String,
    val role: TimelineRole,
    val text: String,
)
