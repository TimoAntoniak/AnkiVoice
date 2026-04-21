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

enum class TutorTerminalAction {
    GRADE_ANSWER,
    SUSPEND_CARD,
    BURY_CARD,
    SWITCH_DECK,
}

data class TutorAction(
    val assistantSpeech: String = "",
    val rereadCardFront: Boolean = false,
    val terminalAction: TutorTerminalAction,
    val ease: Int? = null,
    val switchDeckId: Long? = null,
    val deckQuery: String? = null,
    val wantsDeckList: Boolean = false,
    val wantsDeckSuggestion: Boolean = false,
    val deckSuggestionPreference: String? = null,
    val metadataWrites: List<CardMetadataWrite> = emptyList(),
)

enum class CardInstructionMode { REPLACE, APPEND }

sealed class CardMetadataWrite {
    data class TutorInstruction(
        val mode: CardInstructionMode,
        val text: String,
    ) : CardMetadataWrite()

    data class SpeechVerbalization(
        val target: SpeechTarget,
        val text: String,
    ) : CardMetadataWrite()
}

enum class SpeechTarget { FRONT, BACK, BOTH }

private val tutorJson = Json { ignoreUnknownKeys = true }

const val TOOL_GRADE_ANSWER = "grade_answer"
const val TOOL_REREAD_CARD_FRONT = "reread_card_front"
const val TOOL_SUSPEND_CARD = "suspend_card"
const val TOOL_BURY_CARD = "bury_card"
const val TOOL_LIST_DECKS = "list_decks"
const val TOOL_SWITCH_DECK = "switch_deck"
const val TOOL_SUGGEST_NEXT_DECK = "suggest_next_deck"
const val TOOL_SET_CARD_TUTOR_INSTRUCTIONS = "set_card_tutor_instructions"
const val TOOL_SET_SPEECH_VERBALIZATION = "set_speech_verbalization"

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
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_SUSPEND_CARD,
            description = "Suspend this card in Anki (Aussetzen). Use when user wants to stop seeing it.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("assistant_speech") { put("type", "string") }
                }
                put("additionalProperties", false)
            },
        ),
    ),
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_BURY_CARD,
            description = "Bury this card temporarily.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("assistant_speech") { put("type", "string") }
                }
                put("additionalProperties", false)
            },
        ),
    ),
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_LIST_DECKS,
            description = "Ask app to list available decks with due counts.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 10)
                    }
                }
                put("additionalProperties", false)
            },
        ),
    ),
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_SWITCH_DECK,
            description = "Switch to a different deck.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("deck_id") { put("type", "integer") }
                    putJsonObject("deck_name_query") { put("type", "string") }
                    putJsonObject("assistant_speech") { put("type", "string") }
                }
                put("additionalProperties", false)
            },
        ),
    ),
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_SUGGEST_NEXT_DECK,
            description = "Suggest a next deck based on user preference.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("preference") { put("type", "string") }
                }
                put("additionalProperties", false)
            },
        ),
    ),
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_SET_CARD_TUTOR_INSTRUCTIONS,
            description = "Persist tutor instructions for this card, for example 'ignore order'.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("mode") { put("type", "string") }
                    putJsonObject("instruction_text") { put("type", "string") }
                }
                put("required", buildJsonArray { add(JsonPrimitive("instruction_text")) })
                put("additionalProperties", false)
            },
        ),
    ),
    OpenAiTool(
        function = OpenAiFunctionDef(
            name = TOOL_SET_SPEECH_VERBALIZATION,
            description = "Persist a speech-friendly verbalization for this card.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("target") { put("type", "string") }
                    putJsonObject("verbalization_text") { put("type", "string") }
                }
                put("required", buildJsonArray { add(JsonPrimitive("verbalization_text")) })
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

fun parseGradeAnswerArgs(raw: String): GradeAnswerArgs {
    val s = raw.trim()
    return tutorJson.decodeFromString<GradeAnswerArgs>(s)
}

@Serializable
data class SuspendOrBuryArgs(@SerialName("assistant_speech") val assistantSpeech: String = "")

@Serializable
data class SwitchDeckArgs(
    @SerialName("deck_id") val deckId: Long? = null,
    @SerialName("deck_name_query") val deckNameQuery: String? = null,
    @SerialName("assistant_speech") val assistantSpeech: String = "",
)

@Serializable
data class SuggestNextDeckArgs(
    @SerialName("preference") val preference: String = "",
)

@Serializable
data class SetTutorInstructionsArgs(
    @SerialName("mode") val mode: String = "append",
    @SerialName("instruction_text") val instructionText: String,
)

@Serializable
data class SetSpeechVerbalizationArgs(
    @SerialName("target") val target: String = "both",
    @SerialName("verbalization_text") val verbalizationText: String,
)

fun parseSuspendOrBuryArgs(raw: String): SuspendOrBuryArgs = tutorJson.decodeFromString(raw.trim())

fun parseSwitchDeckArgs(raw: String): SwitchDeckArgs = tutorJson.decodeFromString(raw.trim())

fun parseSuggestNextDeckArgs(raw: String): SuggestNextDeckArgs = tutorJson.decodeFromString(raw.trim())

fun parseSetTutorInstructionsArgs(raw: String): SetTutorInstructionsArgs = tutorJson.decodeFromString(raw.trim())

fun parseSetSpeechVerbalizationArgs(raw: String): SetSpeechVerbalizationArgs = tutorJson.decodeFromString(raw.trim())

fun validateTutorAction(action: TutorAction) {
    when (action.terminalAction) {
        TutorTerminalAction.GRADE_ANSWER -> require(action.ease != null && action.ease in 1..4) {
            "grade_answer must return ease 1..4"
        }
        TutorTerminalAction.SWITCH_DECK -> require(action.switchDeckId != null || !action.deckQuery.isNullOrBlank()) {
            "switch_deck requires deck_id or deck_name_query"
        }
        TutorTerminalAction.SUSPEND_CARD, TutorTerminalAction.BURY_CARD -> Unit
    }
}
