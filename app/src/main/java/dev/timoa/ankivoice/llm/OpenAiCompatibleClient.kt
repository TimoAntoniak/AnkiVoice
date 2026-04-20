package dev.timoa.ankivoice.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiCompatibleClient(
    private val okHttpClient: OkHttpClient = defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun completeChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
    ): Result<String> =
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/chat/completions"
            val body = ChatCompletionRequest(model = model, messages = messages)
            val payload = json.encodeToString(body)
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: $respBody")
                }
                val parsed = json.decodeFromString<ChatCompletionResponse>(respBody)
                val content = parsed.choices.firstOrNull()?.message?.content
                    ?: error("No message in completion response")
                content
            }
        }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
