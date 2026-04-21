package dev.timoa.ankivoice.settings

data class UserSettings(
    val provider: LlmProvider,
    val language: AppLanguage,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val ttsBackend: TtsBackend,
    /** Deck id from AnkiDroid, or `-1` to use whichever deck is selected on AnkiDroid’s home screen. */
    val studyDeckId: Long,
    /** Comma-separated note tags; cards with any of these tags are buried and skipped for voice. */
    val skipTagsCsv: String,
    /** Text-to-speech speed multiplier. 1.0=normal, >1 faster, <1 slower. */
    val ttsRate: Float,
    /** If enabled, include recent per-card learner attempts in tutor context. */
    val adaptiveFeedbackHistoryEnabled: Boolean,
) {
    fun parsedSkipTags(): List<String> =
        skipTagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
