package dev.timoa.ankivoice.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("response_format") val responseFormat: ResponseFormat? = ResponseFormat(type = "json_object"),
)

@Serializable
data class ResponseFormat(
    val type: String,
)

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
    val message: ChatMessage? = null,
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

fun parseTutorAction(raw: String): TutorAction {
    var s = raw.trim()
    if (s.startsWith("```")) {
        s = s.removePrefix("```json").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
    }
    return tutorJson.decodeFromString(TutorAction.serializer(), s)
}
