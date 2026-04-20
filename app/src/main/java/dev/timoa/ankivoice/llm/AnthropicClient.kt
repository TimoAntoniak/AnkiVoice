package dev.timoa.ankivoice.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * [Anthropic Messages API](https://docs.anthropic.com/en/api/messages)
 */
class AnthropicClient(
    private val okHttpClient: OkHttpClient = defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        // Anthropic requires max_tokens; ensure optional fields we set explicitly still encode when needed.
        encodeDefaults = true
    }

    fun completeChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
    ): Result<String> =
        runCatching {
            val systemAndTurns = splitSystemAndTurns(messages)
            val turns = mergeConsecutiveRoles(systemAndTurns.turns)
            require(turns.isNotEmpty()) { "No user/assistant messages for Claude" }
            require(turns.first().role == "user") { "Claude expects the first message to be from the user" }

            val url = "${baseUrl.trimEnd('/')}/messages"
            val body = AnthropicMessagesRequest(
                model = model,
                maxTokens = 2048,
                system = systemAndTurns.system,
                messages = turns,
            )
            val payload = json.encodeToString(body)
            val req = Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: $respBody")
                }
                val parsed = json.decodeFromString<AnthropicMessagesResponse>(respBody)
                val text = parsed.content
                    .filter { it.type == "text" }
                    .mapNotNull { it.text?.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                    .trim()
                if (text.isEmpty()) error("No text content in Claude response")
                text
            }
        }

    private data class SystemAndTurns(
        val system: String?,
        val turns: List<AnthropicTurn>,
    )

    private fun splitSystemAndTurns(messages: List<ChatMessage>): SystemAndTurns {
        val systemParts = mutableListOf<String>()
        val turns = mutableListOf<AnthropicTurn>()
        for (m in messages) {
            when (m.role) {
                "system" -> systemParts.add(m.content)
                "user", "assistant" -> turns.add(AnthropicTurn(m.role, m.content))
                else -> turns.add(AnthropicTurn(m.role, m.content))
            }
        }
        val system = systemParts.joinToString("\n\n").takeIf { it.isNotBlank() }
        return SystemAndTurns(system, turns)
    }

    /** Anthropic requires alternating user / assistant roles. */
    private fun mergeConsecutiveRoles(turns: List<AnthropicTurn>): List<AnthropicTurn> {
        if (turns.isEmpty()) return turns
        val out = mutableListOf<AnthropicTurn>()
        for (t in turns) {
            val last = out.lastOrNull()
            if (last != null && last.role == t.role) {
                out[out.lastIndex] = AnthropicTurn(
                    t.role,
                    last.content + "\n\n" + t.content,
                )
            } else {
                out.add(t)
            }
        }
        return out
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val ANTHROPIC_VERSION = "2023-06-01"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
