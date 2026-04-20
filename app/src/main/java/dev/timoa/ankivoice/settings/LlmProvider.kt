package dev.timoa.ankivoice.settings

enum class LlmProvider {
    /** Anthropic Messages API (`/v1/messages`). */
    ANTHROPIC_CLAUDE,

    /** OpenAI or any server with `/v1/chat/completions`. */
    OPENAI_COMPATIBLE,
    ;

    fun toStorageValue(): String =
        when (this) {
            ANTHROPIC_CLAUDE -> "anthropic"
            OPENAI_COMPATIBLE -> "openai_compatible"
        }

    companion object {
        fun fromStorageValue(raw: String?): LlmProvider =
            when (raw) {
                "anthropic", "ANTHROPIC_CLAUDE" -> ANTHROPIC_CLAUDE
                "openai_compatible", "OPENAI_COMPATIBLE" -> OPENAI_COMPATIBLE
                else -> OPENAI_COMPATIBLE
            }
    }
}
