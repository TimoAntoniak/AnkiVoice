package dev.timoa.ankivoice.llm

import dev.timoa.ankivoice.settings.AppLanguage
import dev.timoa.ankivoice.settings.LlmProvider
import dev.timoa.ankivoice.settings.UserSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TutorEvaluationHarnessTest {
    @Test
    fun runBatchCasesAndPrintStructuredLogs() = runBlocking {
        val settings = loadSettingsFromEnvOrNull()
        assumeTrue(
            "Set ANKIVOICE_API_KEY, ANKIVOICE_BASE_URL, ANKIVOICE_MODEL to run live harness.",
            settings != null,
        )
        settings ?: return@runBlocking

        val service = TutorEvaluationService()
        val cases = listOf(
            HarnessCase(
                id = "correct_concise",
                cardFront = "What is the capital of France?",
                cardBack = "Paris",
                learnerAnswer = "Paris.",
            ),
            HarnessCase(
                id = "partial_missing_key_concept",
                cardFront = "Welche Eigenschaften muss ein Monoid erfüllen?",
                cardBack = "Halbgruppe mit Abgeschlossenheit, Assoziativität und neutralem Element.",
                learnerAnswer = "Monoid ist wie Halbgruppe mit Assoziativitaet.",
            ),
            HarnessCase(
                id = "wrong_confused",
                cardFront = "What is photosynthesis?",
                cardBack = "Plants convert light energy into chemical energy.",
                learnerAnswer = "It is when cells divide in two.",
            ),
            HarnessCase(
                id = "hint_request",
                cardFront = "Nenne die drei Newtonschen Gesetze in Kurzform.",
                cardBack = "Traegheit, F = m*a, Actio gleich Reactio.",
                learnerAnswer = "was war das nochmal? remind me",
            ),
            HarnessCase(
                id = "language_control_german",
                cardFront = "Was ist ein Monoid?",
                cardBack = "Eine Halbgruppe mit neutralem Element.",
                learnerAnswer = "Halbgruppe plus neutrales Element.",
            ),
        )

        var passed = 0
        val failures = mutableListOf<String>()
        println("=== AnkiVoice Local LLM Harness ===")
        println("provider=${settings.provider} model=${settings.model} base=${settings.baseUrl}")
        println("cases=${cases.size}")

        for (case in cases) {
            runCatching {
                val result = service.evaluate(
                    TutorEvaluationRequest(
                        cardFront = case.cardFront,
                        cardBack = case.cardBack,
                        learnerAnswer = case.learnerAnswer,
                        settings = settings,
                    ),
                )
                val action = result.action
                assertTrue("ease out of range for case=${case.id}", (action.ease ?: -1) in 1..4)
                assertTrue("schedule_review must be true for case=${case.id}", action.scheduleReview)
                if ((action.ease ?: 0) <= 2) {
                    assertTrue("assistant_speech should contain correction for case=${case.id}", action.assistantSpeech.isNotBlank())
                }
                println(
                    """
                    CASE=${case.id}
                    QUESTION=${case.cardFront}
                    EXPECTED=${case.cardBack}
                    ANSWER=${case.learnerAnswer}
                    RAW=${result.rawJson}
                    PARSED_EASE=${action.ease}
                    PARSED_SCHEDULE=${action.scheduleReview}
                    PARSED_ASSISTANT=${action.assistantSpeech}
                    ---
                    """.trimIndent(),
                )
                passed++
            }.onFailure { e ->
                failures.add("${case.id}: ${e.message ?: e::class.java.simpleName}")
                println("CASE=${case.id} FAILED: ${e.message ?: e}")
            }
        }

        println("SUMMARY total=${cases.size} passed=$passed failed=${failures.size}")
        failures.forEach { println("FAILURE $it") }
        assertTrue("One or more harness cases failed: $failures", failures.isEmpty())
    }

    @Test
    fun invalidToolArgsIsReported() = runBlocking {
        val fake = TutorEvaluationService { _, _ ->
            Result.success(
                StructuredLlmTurn(
                    assistantText = "",
                    toolCalls = listOf(ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"ok","ease":9}""")),
                    rawOutput = "fake",
                ),
            )
        }
        val settings = defaultOpenAiLikeSettings()
        val request = TutorEvaluationRequest(
            cardFront = "Q",
            cardBack = "A",
            learnerAnswer = "A",
            settings = settings,
        )
        val result = runCatching { fake.evaluate(request) }
        assertTrue("Invalid tool args should fail", result.isFailure)
    }

    @Test
    fun missingFinalToolCallRetriesThenSucceeds() = runBlocking {
        var calls = 0
        val fake = TutorEvaluationService { _, _ ->
            calls++
            if (calls == 1) {
                Result.success(
                    StructuredLlmTurn(
                        assistantText = "Thinking...",
                        toolCalls = listOf(ToolCall(TOOL_REREAD_CARD_FRONT, """{"reason":"asked again"}""")),
                        rawOutput = "first",
                    ),
                )
            } else {
                Result.success(
                    StructuredLlmTurn(
                        assistantText = "",
                        toolCalls = listOf(ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"Correct.","ease":3}""")),
                        rawOutput = "second",
                    ),
                )
            }
        }
        val out = fake.evaluate(
            TutorEvaluationRequest(
                cardFront = "What is 2+2?",
                cardBack = "4",
                learnerAnswer = "4",
                settings = defaultOpenAiLikeSettings(),
            ),
        )
        assertEquals(2, calls)
        assertEquals(3, out.action.ease)
        assertTrue(out.action.scheduleReview)
    }

    @Test
    fun missingFinalToolCallAfterRetryFails() = runBlocking {
        val fake = TutorEvaluationService { _, _ ->
            Result.success(
                StructuredLlmTurn(
                    assistantText = "No grade call",
                    toolCalls = emptyList(),
                    rawOutput = "none",
                ),
            )
        }
        val result = runCatching {
            fake.evaluate(
                TutorEvaluationRequest(
                    cardFront = "Q",
                    cardBack = "A",
                    learnerAnswer = "A",
                    settings = defaultOpenAiLikeSettings(),
                ),
            )
        }
        assertTrue("Must fail when grade_answer never arrives", result.isFailure)
    }

    private fun loadSettingsFromEnvOrNull(): UserSettings? {
        val apiKey = System.getenv("ANKIVOICE_API_KEY")?.trim().orEmpty()
        if (apiKey.isEmpty()) return null
        val providerRaw = System.getenv("ANKIVOICE_PROVIDER")?.trim().orEmpty()
        val provider = if (providerRaw.equals("anthropic", ignoreCase = true)) {
            LlmProvider.ANTHROPIC_CLAUDE
        } else {
            LlmProvider.OPENAI_COMPATIBLE
        }
        val defaultBase = if (provider == LlmProvider.ANTHROPIC_CLAUDE) {
            "https://api.anthropic.com/v1"
        } else {
            "https://api.openai.com/v1"
        }
        val defaultModel = if (provider == LlmProvider.ANTHROPIC_CLAUDE) {
            "claude-3-5-haiku-20241022"
        } else {
            "gpt-4o-mini"
        }
        return UserSettings(
            provider = provider,
            language = AppLanguage.GERMAN,
            apiKey = apiKey,
            baseUrl = (System.getenv("ANKIVOICE_BASE_URL") ?: defaultBase).trimEnd('/'),
            model = System.getenv("ANKIVOICE_MODEL") ?: defaultModel,
            studyDeckId = -1L,
            skipTagsCsv = "",
            ttsRate = 1.0f,
        )
    }

    private fun defaultOpenAiLikeSettings(): UserSettings =
        UserSettings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            language = AppLanguage.ENGLISH,
            apiKey = "dummy",
            baseUrl = "https://example.com/v1",
            model = "fake",
            studyDeckId = -1L,
            skipTagsCsv = "",
            ttsRate = 1.0f,
        )

}

private data class HarnessCase(
    val id: String,
    val cardFront: String,
    val cardBack: String,
    val learnerAnswer: String,
)
