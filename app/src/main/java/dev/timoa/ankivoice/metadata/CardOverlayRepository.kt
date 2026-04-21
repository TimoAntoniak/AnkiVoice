package dev.timoa.ankivoice.metadata

import android.content.Context
import android.content.SharedPreferences
import dev.timoa.ankivoice.llm.CardInstructionMode
import dev.timoa.ankivoice.llm.CardMetadataWrite
import dev.timoa.ankivoice.llm.SpeechTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CardOverlayRepository(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(noteId: Long, cardOrd: Int): CardOverlay {
        val raw = prefs.getString(key(noteId, cardOrd), null) ?: return CardOverlay()
        return runCatching { json.decodeFromString<CardOverlay>(raw) }.getOrElse { CardOverlay() }
    }

    fun save(noteId: Long, cardOrd: Int, overlay: CardOverlay) {
        prefs.edit().putString(key(noteId, cardOrd), json.encodeToString(overlay)).apply()
    }

    fun applyWrites(
        noteId: Long,
        cardOrd: Int,
        writes: List<CardMetadataWrite>,
        learnerAnswerToAppend: String? = null,
        adaptiveHistoryEnabled: Boolean = false,
    ): CardOverlay {
        var current = load(noteId, cardOrd)
        writes.forEach { write ->
            when (write) {
                is CardMetadataWrite.TutorInstruction -> {
                    val trimmed = write.text.trim().take(MAX_FIELD_CHARS)
                    if (trimmed.isNotBlank()) {
                        current = current.copy(
                            tutorInstructions = when (write.mode) {
                                CardInstructionMode.REPLACE -> trimmed
                                CardInstructionMode.APPEND -> {
                                    val base = current.tutorInstructions.trim()
                                    if (base.isBlank()) trimmed else "$base\n$trimmed"
                                }
                            },
                        )
                    }
                }
                is CardMetadataWrite.SpeechVerbalization -> {
                    val trimmed = write.text.trim().take(MAX_FIELD_CHARS)
                    if (trimmed.isNotBlank()) {
                        current = when (write.target) {
                            SpeechTarget.FRONT -> current.copy(speechVerbalizationFront = trimmed)
                            SpeechTarget.BACK -> current.copy(speechVerbalizationBack = trimmed)
                            SpeechTarget.BOTH -> current.copy(
                                speechVerbalizationFront = trimmed,
                                speechVerbalizationBack = trimmed,
                            )
                        }
                    }
                }
            }
        }

        if (adaptiveHistoryEnabled) {
            val response = learnerAnswerToAppend?.trim().orEmpty()
            if (response.isNotBlank()) {
                current = current.copy(
                    recentLearnerResponses = (current.recentLearnerResponses + response).takeLast(MAX_RECENT_RESPONSES),
                )
            }
        }

        save(noteId, cardOrd, current)
        return current
    }

    private fun key(noteId: Long, cardOrd: Int): String = "overlay_${noteId}_$cardOrd"

    companion object {
        private const val PREFS_NAME = "ankivoice_card_overlay"
        private const val MAX_RECENT_RESPONSES = 3
        private const val MAX_FIELD_CHARS = 300
    }
}

@Serializable
data class CardOverlay(
    val tutorInstructions: String = "",
    val speechVerbalizationFront: String = "",
    val speechVerbalizationBack: String = "",
    val recentLearnerResponses: List<String> = emptyList(),
)
