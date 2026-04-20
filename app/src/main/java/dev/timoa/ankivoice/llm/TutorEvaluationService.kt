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
        completeFn: suspend (UserSettings, List<ChatMessage>) -> Result<String>,
    ) : this(
        llmGateway = object : LlmCompletionGateway() {
            override suspend fun completeSuspend(
                settings: UserSettings,
                messages: List<ChatMessage>,
            ): Result<String> = completeFn(settings, messages)
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

        val rawJson = llmGateway.completeSuspend(request.settings, conversation).getOrElse { throw it }
        val action = try {
            parseTutorAction(rawJson)
        } catch (e: Exception) {
            throw IllegalStateException("Model returned invalid JSON. Try another model.\n${e.message}", e)
        }

        require(action.ease != null && action.ease in 1..4) {
            "Model must return ease 1-4"
        }

        conversation.add(ChatMessage("assistant", action.assistantSpeech))
        return TutorEvaluationResult(
            conversation = conversation,
            rawJson = rawJson,
            action = action,
        )
    }
}
