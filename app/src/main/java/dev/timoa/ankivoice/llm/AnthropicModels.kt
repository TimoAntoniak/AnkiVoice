package dev.timoa.ankivoice.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    /** Must always be sent; do not give a Kotlin default or kotlinx.serialization may omit it from JSON. */
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<AnthropicTurn>,
)

@Serializable
data class AnthropicTurn(
    val role: String,
    val content: String,
)

@Serializable
data class AnthropicMessagesResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
)

@Serializable
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
)
