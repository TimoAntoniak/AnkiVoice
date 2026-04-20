package dev.timoa.ankivoice.llm

import dev.timoa.ankivoice.settings.UserSettings

data class TutorEvaluationRequest(
    val cardFront: String,
    val cardBack: String,
    val learnerAnswer: String,
    val settings: UserSettings,
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

            turn.toolCalls.forEach { call ->
                when (call.name) {
                    TOOL_REREAD_CARD_FRONT -> {
                        // Local app action; no special state needed here for v1.
                    }
                }
            }

            val gradeCall = turn.toolCalls.lastOrNull { it.name == TOOL_GRADE_ANSWER }
            if (gradeCall != null) {
                val args = try {
                    parseGradeAnswerArgs(gradeCall.arguments)
                } catch (e: Exception) {
                    throw IllegalStateException("Model returned invalid grade_answer arguments. ${e.message}", e)
                }
                val action = TutorAction(
                    assistantSpeech = args.assistantSpeech,
                    rereadCardFront = turn.toolCalls.any { it.name == TOOL_REREAD_CARD_FRONT },
                    continueConversation = false,
                    scheduleReview = true,
                    ease = args.ease,
                )
                validateTutorAction(action)
                conversation.add(ChatMessage("assistant", action.assistantSpeech))
                return TutorEvaluationResult(
                    conversation = conversation,
                    rawJson = rawOutputs.joinToString("\n---\n"),
                    action = action,
                )
            }

            if (retry >= 1) {
                throw IllegalStateException("Model did not call grade_answer after retry")
            }
            retry++
            conversation.add(
                ChatMessage(
                    "user",
                    "STOP. CALL grade_answer NOW. REQUIRED IN THIS RESPONSE. " +
                        "Do NOT ask follow-up questions. Do NOT continue conversation. " +
                        "Return exactly one grade_answer call with assistant_speech and ease (1..4). " +
                        "If this was a hint/reminder request, put the hint into assistant_speech and still finalize now.",
                ),
            )
        }
    }
}
