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
    fun completeStructured(settings: UserSettings, messages: List<ChatMessage>): Result<StructuredLlmTurn> =
        when (settings.provider) {
            LlmProvider.OPENAI_COMPATIBLE ->
                openAi.completeStructuredTurn(settings.baseUrl, settings.apiKey, settings.model, messages)
            LlmProvider.ANTHROPIC_CLAUDE ->
                anthropic.completeStructuredTurn(settings.baseUrl, settings.apiKey, settings.model, messages)
        }

    open suspend fun completeStructuredSuspend(
        settings: UserSettings,
        messages: List<ChatMessage>,
    ): Result<StructuredLlmTurn> = completeStructured(settings, messages)
}
