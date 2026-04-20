package dev.timoa.ankivoice.llm

import dev.timoa.ankivoice.settings.LlmProvider
import dev.timoa.ankivoice.settings.UserSettings

/**
 * Dispatches to OpenAI-compatible or Anthropic APIs based on [UserSettings.provider].
 */
open class LlmCompletionGateway(
    private val openAi: OpenAiCompatibleClient = OpenAiCompatibleClient(),
    private val anthropic: AnthropicClient = AnthropicClient(),
) {
    fun complete(settings: UserSettings, messages: List<ChatMessage>): Result<String> =
        when (settings.provider) {
            LlmProvider.OPENAI_COMPATIBLE ->
                openAi.completeChat(settings.baseUrl, settings.apiKey, settings.model, messages)
            LlmProvider.ANTHROPIC_CLAUDE ->
                anthropic.completeChat(settings.baseUrl, settings.apiKey, settings.model, messages)
        }

    open suspend fun completeSuspend(
        settings: UserSettings,
        messages: List<ChatMessage>,
    ): Result<String> = complete(settings, messages)
}
