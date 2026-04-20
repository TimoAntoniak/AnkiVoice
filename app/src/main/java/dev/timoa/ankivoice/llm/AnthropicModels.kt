package dev.timoa.ankivoice.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    /** Must always be sent; do not give a Kotlin default or kotlinx.serialization may omit it from JSON. */
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<AnthropicTurn>,
    val tools: List<AnthropicTool> = emptyList(),
    @SerialName("tool_choice") val toolChoice: AnthropicToolChoice? = null,
)

@Serializable
data class AnthropicTurn(
    val role: String,
    val content: List<AnthropicContentInput>,
)

@Serializable
data class AnthropicMessagesResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
)

@Serializable
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
)

@Serializable
data class AnthropicContentInput(
    val type: String = "text",
    val text: String,
)

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

@Serializable
data class AnthropicToolChoice(
    val type: String = "any",
)
