package dev.timoa.ankivoice.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<OpenAiTool> = emptyList(),
    @SerialName("tool_choice") val toolChoice: OpenAiToolChoice? = null,
)

@Serializable
data class OpenAiToolChoice(val type: String = "required")

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
)

@Serializable
data class Choice(
    val message: ChatCompletionMessage? = null,
)

@Serializable
data class ChatCompletionMessage(
    val role: String = "assistant",
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall> = emptyList(),
)

@Serializable
data class OpenAiToolCall(
    val id: String? = null,
    val type: String = "function",
    val function: OpenAiFunctionCall,
)

@Serializable
data class OpenAiFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunctionDef,
)

@Serializable
data class OpenAiFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class StructuredLlmTurn(
    val assistantText: String,
    val toolCalls: List<ToolCall>,
    val rawOutput: String,
)

data class ToolCall(
    val name: String,
    val arguments: String,
)

/**
 * Rich tutor response: conversational help, optional re-read, then schedule when the learner is done.
 */
@Serializable
data class TutorAction(
    /**
     * Spoken to the user (TTS). Can be feedback, answers to their questions, or prompts to try again.
     */
    @SerialName("assistant_speech") val assistantSpeech: String,
    /**
     * If true, after [assistantSpeech] the app will read the card front again (question only).
     */
    @SerialName("reread_card_front") val rereadCardFront: Boolean = false,
    /**
     * If true, keep the mic open for follow-up on the same card (questions, another attempt, etc.).
     */
    @SerialName("continue_conversation") val continueConversation: Boolean = false,
    /**
     * When true, apply [ease] in Anki and move to the next card. Usually false while clarifying or re-reading.
     */
    @SerialName("schedule_review") val scheduleReview: Boolean = false,
    /**
     * 1=Again, 2=Hard, 3=Good, 4=Easy. Required when schedule_review is true.
     */
    @SerialName("ease") val ease: Int? = null,
)

private val tutorJson = Json { ignoreUnknownKeys = true }

const val TOOL_GRADE_ANSWER = "grade_answer"
const val TOOL_REREAD_CARD_FRONT = "reread_card_front"

val tutorToolSpecs: List<OpenAiTool> = listOf(
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_GRADE_ANSWER,
            description = "Finalize grading for current card with ease (1..4) and concise spoken feedback.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("assistant_speech") { put("type", "string") }
                    putJsonObject("ease") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 4)
                    }
                }
                put("required", buildJsonArray {
                    add(JsonPrimitive("assistant_speech"))
                    add(JsonPrimitive("ease"))
                })
                put("additionalProperties", false)
            },
        ),
    ),
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_REREAD_CARD_FRONT,
            description = "Request app to reread current card front.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("reason") { put("type", "string") }
                }
                put("required", buildJsonArray {})
                put("additionalProperties", false)
            },
        ),
    ),
)

@Serializable
data class GradeAnswerArgs(
    @SerialName("assistant_speech") val assistantSpeech: String,
    @SerialName("ease") val ease: Int,
)

fun parseTutorAction(raw: String): TutorAction {
    var s = raw.trim()
    if (s.startsWith("```")) {
        s = s.removePrefix("```json").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
    }
    return tutorJson.decodeFromString<TutorAction>(s)
}

fun parseGradeAnswerArgs(raw: String): GradeAnswerArgs {
    val s = raw.trim()
    return tutorJson.decodeFromString<GradeAnswerArgs>(s)
}

fun validateTutorAction(action: TutorAction) {
    require(action.scheduleReview) { "Model must finalize with schedule_review=true" }
    require(action.ease != null && action.ease in 1..4) { "Model must return ease 1-4" }
}
