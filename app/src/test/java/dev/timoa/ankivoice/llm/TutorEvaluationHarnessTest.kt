package dev.timoa.ankivoice.llm

import dev.timoa.ankivoice.settings.AppLanguage
import dev.timoa.ankivoice.settings.LlmProvider
import dev.timoa.ankivoice.settings.TtsBackend
import dev.timoa.ankivoice.settings.UserSettings
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
            ),
            HarnessCase(
                id = "partial_missing_key_concept",
                cardFront = "Welche Eigenschaften muss ein Monoid erfüllen?",
                cardBack = "Halbgruppe mit Abgeschlossenheit, Assoziativität und neutralem Element.",
                learnerAnswer = "Monoid ist wie Halbgruppe mit Assoziativitaet.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
            ),
            HarnessCase(
                id = "wrong_confused",
                cardFront = "What is photosynthesis?",
                cardBack = "Plants convert light energy into chemical energy.",
                learnerAnswer = "It is when cells divide in two.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
            ),
            HarnessCase(
                id = "hint_request",
                cardFront = "Nenne die drei Newtonschen Gesetze in Kurzform.",
                cardBack = "Traegheit, F = m*a, Actio gleich Reactio.",
                learnerAnswer = "was war das nochmal? remind me",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
            ),
            HarnessCase(
                id = "language_control_german",
                cardFront = "Was ist ein Monoid?",
                cardBack = "Eine Halbgruppe mit neutralem Element.",
                learnerAnswer = "Halbgruppe plus neutrales Element.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
            ),
            HarnessCase(
                id = "chat_list_decks",
                cardFront = "Deck navigation chat",
                cardBack = "Use tools to list decks and switch decks.",
                learnerAnswer = "What decks are left?",
                expectedTerminal = null,
            ),
            HarnessCase(
                id = "chat_suggest_not_math",
                cardFront = "Deck navigation chat",
                cardBack = "Use tools to suggest and switch decks.",
                learnerAnswer = "Give me the next deck that's not math.",
                expectedTerminal = null,
            ),
            HarnessCase(
                id = "chat_suggest_similar",
                cardFront = "Deck navigation chat",
                cardBack = "Use tools to suggest and switch decks.",
                learnerAnswer = "Give me something similar.",
                expectedTerminal = null,
            ),
            HarnessCase(
                id = "chat_choose_deck",
                cardFront = "Deck navigation chat",
                cardBack = "Use tools to list decks and switch decks.",
                learnerAnswer = "Choose a deck for me.",
                expectedTerminal = null,
            ),
            HarnessCase(
                id = "chat_switch_by_name",
                cardFront = "Deck navigation chat",
                cardBack = "Use tools to switch deck.",
                learnerAnswer = "Switch to biology deck.",
                expectedTerminal = TutorTerminalAction.SWITCH_DECK,
            ),
            HarnessCase(
                id = "metadata_ignore_order",
                cardFront = "Name three causes of inflation.",
                cardBack = "Demand pull, cost push, and monetary expansion.",
                learnerAnswer = "Ignore the order for this card.",
                expectedTerminal = null,
            ),
            HarnessCase(
                id = "metadata_speech_rewrite",
                cardFront = "Explain sigma notation.",
                cardBack = "Sigma denotes summation over a sequence.",
                learnerAnswer = "Please read formulas in words for this card.",
                expectedTerminal = null,
            ),
        )

        var passed = 0
        val failures = mutableListOf<String>()
        val caseReports = mutableListOf<String>()
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
                        requireTerminalTool = case.expectedTerminal != null,
                        settings = settings,
                    ),
                )
                val action = result.action
                case.expectedTerminal?.let { expected ->
                    assertTrue(
                        "terminal action mismatch for case=${case.id}; expected=$expected actual=${action.terminalAction}",
                        action.terminalAction == expected,
                    )
                }
                if (action.terminalAction == TutorTerminalAction.GRADE_ANSWER) {
                    assertTrue("ease out of range for case=${case.id}", (action.ease ?: -1) in 1..4)
                }
                if (action.terminalAction == TutorTerminalAction.GRADE_ANSWER && (action.ease ?: 0) <= 2) {
                    assertTrue(
                        "assistant_speech should contain correction for case=${case.id}",
                        action.assistantSpeech.isNotBlank(),
                    )
                }
                println(
                    """
                    CASE=${case.id}
                    QUESTION=${case.cardFront}
                    EXPECTED=${case.cardBack}
                    ANSWER=${case.learnerAnswer}
                    RAW=${result.rawJson}
                    PARSED_EASE=${action.ease}
                    PARSED_TERMINAL=${action.terminalAction}
                    PARSED_ASSISTANT=${action.assistantSpeech}
                    ---
                    """.trimIndent(),
                )
                caseReports.add(
                    """
                    ## ${case.id}
                    
                    - Question: ${case.cardFront}
                    - Expected answer: ${case.cardBack}
                    - Learner answer: ${case.learnerAnswer}
                    - Parsed terminal: ${action.terminalAction}
                    - Parsed ease: ${action.ease}
                    - Parsed assistant speech: ${action.assistantSpeech.ifBlank { "(empty)" }}
                    
                    ### Raw model output
                    
                    ${result.rawJson}
                    """.trimIndent(),
                )
                passed++
            }.onFailure { e ->
                failures.add("${case.id}: ${e.message ?: e::class.java.simpleName}")
                println("CASE=${case.id} FAILED: ${e.message ?: e}")
                caseReports.add(
                    """
                    ## ${case.id}
                    
                    - Question: ${case.cardFront}
                    - Expected answer: ${case.cardBack}
                    - Learner answer: ${case.learnerAnswer}
                    - Result: FAILED
                    - Error: ${e.message ?: e}
                    """.trimIndent(),
                )
            }
        }

        println("SUMMARY total=${cases.size} passed=$passed failed=${failures.size}")
        failures.forEach { println("FAILURE $it") }
        writeHarnessReport(settings, caseReports, failures, passed, cases.size)
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
        assertEquals(TutorTerminalAction.GRADE_ANSWER, out.action.terminalAction)
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

    @Test
    fun suspendTerminalToolIsAccepted() = runBlocking {
        val fake = TutorEvaluationService { _, _ ->
            Result.success(
                StructuredLlmTurn(
                    assistantText = "",
                    toolCalls = listOf(ToolCall(TOOL_SUSPEND_CARD, """{"assistant_speech":"Okay, suspended."}""")),
                    rawOutput = "fake",
                ),
            )
        }
        val out = fake.evaluate(
            TutorEvaluationRequest(
                cardFront = "Q",
                cardBack = "A",
                learnerAnswer = "skip this",
                settings = defaultOpenAiLikeSettings(),
            ),
        )
        assertEquals(TutorTerminalAction.SUSPEND_CARD, out.action.terminalAction)
    }

    @Test
    fun multipleTerminalToolsFailValidation() = runBlocking {
        val fake = TutorEvaluationService { _, _ ->
            Result.success(
                StructuredLlmTurn(
                    assistantText = "",
                    toolCalls = listOf(
                        ToolCall(TOOL_BURY_CARD, """{"assistant_speech":"bury"}"""),
                        ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"","ease":3}"""),
                    ),
                    rawOutput = "bad",
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
        assertTrue(result.isFailure)
    }

    private fun loadSettingsFromEnvOrNull(): UserSettings? {
        val dotenv = loadDotEnvLocal()
        val apiKey = envOrDotEnv("ANKIVOICE_API_KEY", dotenv)
        if (apiKey.isEmpty()) return null
        val providerRaw = envOrDotEnv("ANKIVOICE_PROVIDER", dotenv)
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
            baseUrl = envOrDotEnv("ANKIVOICE_BASE_URL", dotenv, defaultBase).trimEnd('/'),
            model = envOrDotEnv("ANKIVOICE_MODEL", dotenv, defaultModel),
            ttsBackend = TtsBackend.SYSTEM,
            studyDeckId = -1L,
            skipTagsCsv = "",
            ttsRate = 1.0f,
            adaptiveFeedbackHistoryEnabled = false,
        )
    }

    private fun envOrDotEnv(
        key: String,
        dotenv: Properties,
        default: String = "",
    ): String {
        val env = System.getenv(key)?.trim().orEmpty()
        if (env.isNotEmpty()) return env
        return dotenv.getProperty(key, default).trim()
    }

    private fun loadDotEnvLocal(): Properties {
        val props = Properties()
        val file = File(".env.local")
        if (!file.exists()) return props
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().trim('"').trim('\'')
            props.setProperty(key, value)
        }
        return props
    }

    private fun defaultOpenAiLikeSettings(): UserSettings =
        UserSettings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            language = AppLanguage.ENGLISH,
            apiKey = "dummy",
            baseUrl = "https://example.com/v1",
            model = "fake",
            ttsBackend = TtsBackend.SYSTEM,
            studyDeckId = -1L,
            skipTagsCsv = "",
            ttsRate = 1.0f,
            adaptiveFeedbackHistoryEnabled = false,
        )

    private fun writeHarnessReport(
        settings: UserSettings,
        caseReports: List<String>,
        failures: List<String>,
        passed: Int,
        total: Int,
    ) {
        val reportsDir = File("build/reports/ankivoice")
        reportsDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val body = buildString {
            appendLine("# AnkiVoice Conversation Harness")
            appendLine()
            appendLine("- Generated at: ${Date()}")
            appendLine("- Provider: ${settings.provider}")
            appendLine("- Model: ${settings.model}")
            appendLine("- Base URL: ${settings.baseUrl}")
            appendLine("- Passed: $passed / $total")
            appendLine("- Failed: ${failures.size}")
            appendLine()
            if (failures.isNotEmpty()) {
                appendLine("## Failures")
                failures.forEach { appendLine("- $it") }
                appendLine()
            }
            append(caseReports.joinToString("\n\n---\n\n"))
            appendLine()
        }
        File(reportsDir, "conversation-harness-$stamp.md").writeText(body)
        File(reportsDir, "conversation-harness-latest.md").writeText(body)
    }

}

private data class HarnessCase(
    val id: String,
    val cardFront: String,
    val cardBack: String,
    val learnerAnswer: String,
    val expectedTerminal: TutorTerminalAction?,
)
