package dev.timoa.ankivoice.llm

import dev.timoa.ankivoice.settings.UserSettings

data class TutorEvaluationRequest(
    val cardFront: String,
    val cardBack: String,
    val learnerAnswer: String,
    val settings: UserSettings,
    val cardTutorInstructions: String = "",
    val cardSpeechVerbalization: String = "",
    val recentLearnerResponses: List<String> = emptyList(),
    val deckSnapshot: String = "",
    val requireTerminalTool: Boolean = true,
)

data class TutorEvaluationResult(
    val conversation: List<ChatMessage>,
    val rawJson: String,
    val action: TutorAction,
)

class TutorEvaluationService(
    private val llmGateway: LlmCompletionGateway = LlmCompletionGateway(),
) {
    constructor(
        completeFn: suspend (UserSettings, List<ChatMessage>) -> Result<StructuredLlmTurn>,
    ) : this(
        llmGateway = object : LlmCompletionGateway() {
            override suspend fun completeStructuredSuspend(
                settings: UserSettings,
                messages: List<ChatMessage>,
            ): Result<StructuredLlmTurn> = completeFn(settings, messages)
        },
    )

    suspend fun evaluate(request: TutorEvaluationRequest): TutorEvaluationResult {
        val conversation = mutableListOf(
            ChatMessage(
                "system",
                TutorBrain.systemPrompt(
                    questionSpeech = request.cardFront,
                    answerSpeech = request.cardBack,
                    language = request.settings.language,
                    cardTutorInstructions = request.cardTutorInstructions,
                    cardSpeechVerbalization = request.cardSpeechVerbalization,
                    recentLearnerResponses = request.recentLearnerResponses,
                    deckSnapshot = request.deckSnapshot,
                    requireTerminalTool = request.requireTerminalTool,
                ),
            ),
            ChatMessage("user", request.learnerAnswer),
        )

        val rawOutputs = mutableListOf<String>()
        var retry = 0
        while (true) {
            val turn = llmGateway.completeStructuredSuspend(request.settings, conversation).getOrElse { throw it }
            rawOutputs.add(turn.rawOutput)

            if (turn.assistantText.isNotBlank()) {
                conversation.add(ChatMessage("assistant", turn.assistantText))
            }

            val metadataWrites = mutableListOf<CardMetadataWrite>()
            var wantsDeckList = false
            var wantsDeckSuggestion = false
            var deckSuggestionPreference: String? = null
            var terminalAction: TutorTerminalAction? = null
            var assistantSpeech = ""
            var ease: Int? = null
            var switchDeckId: Long? = null
            var deckQuery: String? = null

            turn.toolCalls.forEach { call ->
                when (call.name) {
                    TOOL_REREAD_CARD_FRONT -> Unit
                    TOOL_SET_CARD_TUTOR_INSTRUCTIONS -> {
                        val args = parseSetTutorInstructionsArgs(call.arguments)
                        val mode = if (args.mode.equals("replace", ignoreCase = true)) {
                            CardInstructionMode.REPLACE
                        } else {
                            CardInstructionMode.APPEND
                        }
                        metadataWrites.add(
                            CardMetadataWrite.TutorInstruction(
                                mode = mode,
                                text = args.instructionText.trim(),
                            ),
                        )
                    }
                    TOOL_SET_SPEECH_VERBALIZATION -> {
                        val args = parseSetSpeechVerbalizationArgs(call.arguments)
                        val target = when (args.target.lowercase()) {
                            "front" -> SpeechTarget.FRONT
                            "back" -> SpeechTarget.BACK
                            else -> SpeechTarget.BOTH
                        }
                        metadataWrites.add(
                            CardMetadataWrite.SpeechVerbalization(
                                target = target,
                                text = args.verbalizationText.trim(),
                            ),
                        )
                    }
                    TOOL_LIST_DECKS -> wantsDeckList = true
                    TOOL_SUGGEST_NEXT_DECK -> {
                        wantsDeckSuggestion = true
                        deckSuggestionPreference = runCatching { parseSuggestNextDeckArgs(call.arguments).preference.trim() }
                            .getOrDefault("")
                    }
                    TOOL_GRADE_ANSWER -> {
                        val args = parseGradeAnswerArgs(call.arguments)
                        ensureSingleTerminal(terminalAction, TOOL_GRADE_ANSWER)
                        terminalAction = TutorTerminalAction.GRADE_ANSWER
                        assistantSpeech = args.assistantSpeech
                        ease = args.ease
                    }
                    TOOL_SUSPEND_CARD -> {
                        val args = parseSuspendOrBuryArgs(call.arguments)
                        ensureSingleTerminal(terminalAction, TOOL_SUSPEND_CARD)
                        terminalAction = TutorTerminalAction.SUSPEND_CARD
                        assistantSpeech = args.assistantSpeech
                    }
                    TOOL_BURY_CARD -> {
                        val args = parseSuspendOrBuryArgs(call.arguments)
                        ensureSingleTerminal(terminalAction, TOOL_BURY_CARD)
                        terminalAction = TutorTerminalAction.BURY_CARD
                        assistantSpeech = args.assistantSpeech
                    }
                    TOOL_SWITCH_DECK -> {
                        val args = parseSwitchDeckArgs(call.arguments)
                        ensureSingleTerminal(terminalAction, TOOL_SWITCH_DECK)
                        terminalAction = TutorTerminalAction.SWITCH_DECK
                        assistantSpeech = args.assistantSpeech
                        switchDeckId = args.deckId
                        deckQuery = args.deckNameQuery?.trim().takeIf { !it.isNullOrBlank() }
                    }
                }
            }

            if (terminalAction != null) {
                val action = TutorAction(
                    assistantSpeech = assistantSpeech,
                    rereadCardFront = turn.toolCalls.any { it.name == TOOL_REREAD_CARD_FRONT },
                    terminalAction = terminalAction ?: TutorTerminalAction.GRADE_ANSWER,
                    ease = ease,
                    switchDeckId = switchDeckId,
                    deckQuery = deckQuery,
                    wantsDeckList = wantsDeckList,
                    wantsDeckSuggestion = wantsDeckSuggestion,
                    deckSuggestionPreference = deckSuggestionPreference,
                    metadataWrites = metadataWrites.filter {
                        when (it) {
                            is CardMetadataWrite.TutorInstruction -> it.text.isNotBlank()
                            is CardMetadataWrite.SpeechVerbalization -> it.text.isNotBlank()
                        }
                    },
                )
                validateTutorAction(action)
                conversation.add(ChatMessage("assistant", action.assistantSpeech))
                return TutorEvaluationResult(
                    conversation = conversation,
                    rawJson = rawOutputs.joinToString("\n---\n"),
                    action = action,
                )
            }

            if (!request.requireTerminalTool) {
                val action = TutorAction(
                    assistantSpeech = turn.assistantText,
                    rereadCardFront = turn.toolCalls.any { it.name == TOOL_REREAD_CARD_FRONT },
                    terminalAction = TutorTerminalAction.NONE,
                    wantsDeckList = wantsDeckList,
                    wantsDeckSuggestion = wantsDeckSuggestion,
                    deckSuggestionPreference = deckSuggestionPreference,
                    metadataWrites = metadataWrites.filter {
                        when (it) {
                            is CardMetadataWrite.TutorInstruction -> it.text.isNotBlank()
                            is CardMetadataWrite.SpeechVerbalization -> it.text.isNotBlank()
                        }
                    },
                )
                validateTutorAction(action)
                if (action.assistantSpeech.isNotBlank()) {
                    conversation.add(ChatMessage("assistant", action.assistantSpeech))
                }
                return TutorEvaluationResult(
                    conversation = conversation,
                    rawJson = rawOutputs.joinToString("\n---\n"),
                    action = action,
                )
            }

            if (retry >= 1) {
                throw IllegalStateException("Model did not call a terminal tool after retry")
            }
            retry++
            conversation.add(
                ChatMessage(
                    "user",
                    "STOP. REQUIRED: return exactly one terminal tool call now. " +
                        "Allowed terminals: grade_answer, suspend_card, bury_card, switch_deck. " +
                        "Do NOT ask follow-up questions. If grading, include assistant_speech and ease (1..4).",
                ),
            )
        }
    }

    private fun ensureSingleTerminal(current: TutorTerminalAction?, nextTool: String) {
        require(current == null) { "Model returned multiple terminal actions; saw duplicate via $nextTool" }
    }
}
