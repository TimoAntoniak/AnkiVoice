package dev.timoa.ankivoice.llm

import dev.timoa.ankivoice.settings.AppLanguage
import dev.timoa.ankivoice.settings.LlmProvider
import dev.timoa.ankivoice.settings.UserSettings
import kotlinx.coroutines.runBlocking
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
                assertTrue("assistant_speech empty for case=${case.id}", action.assistantSpeech.isNotBlank())
                assertTrue("ease out of range for case=${case.id}", (action.ease ?: -1) in 1..4)
                println(
                    """
                    CASE=${case.id}
                    QUESTION=${case.cardFront}
                    EXPECTED=${case.cardBack}
                    ANSWER=${case.learnerAnswer}
                    RAW=${result.rawJson}
                    PARSED_EASE=${action.ease}
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
    fun malformedJsonIsReported() = runBlocking {
        val fake = TutorEvaluationService { _, _ ->
            Result.success("not-json")
        }
        val settings = defaultOpenAiLikeSettings()
        val request = TutorEvaluationRequest(
            cardFront = "Q",
            cardBack = "A",
            learnerAnswer = "A",
            settings = settings,
        )
        val result = runCatching { fake.evaluate(request) }
        assertTrue("Malformed JSON should fail", result.isFailure)
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
