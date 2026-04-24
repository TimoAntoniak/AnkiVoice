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
                expectedSpeechLanguage = "en",
            ),
            HarnessCase(
                id = "partial_missing_key_concept",
                cardFront = "Welche Eigenschaften muss ein Monoid erfüllen?",
                cardBack = "Halbgruppe mit Abgeschlossenheit, Assoziativität und neutralem Element.",
                learnerAnswer = "Monoid ist wie Halbgruppe mit Assoziativitaet.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
                expectedSpeechLanguage = "de",
            ),
            HarnessCase(
                id = "wrong_confused",
                cardFront = "What is photosynthesis?",
                cardBack = "Plants convert light energy into chemical energy.",
                learnerAnswer = "It is when cells divide in two.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
                expectedSpeechLanguage = "de",
            ),
            HarnessCase(
                id = "hint_request",
                cardFront = "Nenne die drei Newtonschen Gesetze in Kurzform.",
                cardBack = "Traegheit, F = m*a, Actio gleich Reactio.",
                learnerAnswer = "was war das nochmal? remind me",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
                expectedSpeechLanguage = "de",
            ),
            HarnessCase(
                id = "language_control_german",
                cardFront = "Was ist ein Monoid?",
                cardBack = "Eine Halbgruppe mit neutralem Element.",
                learnerAnswer = "Halbgruppe plus neutrales Element.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
                expectedSpeechLanguage = "de",
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
                expectedSpeechLanguage = "en",
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
            HarnessCase(
                id = "matrix_en_card_en_app",
                cardFront = "What is entropy in one sentence?",
                cardBack = "A measure of uncertainty in a system.",
                learnerAnswer = "It measures uncertainty.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
                expectedSpeechLanguage = "en",
                settingsLanguage = AppLanguage.ENGLISH,
                assistantSpeechLanguagePolicy = AssistantSpeechLanguagePolicy.APP_LANGUAGE,
            ),
            HarnessCase(
                id = "matrix_de_card_en_app",
                cardFront = "Was ist die Hauptstadt von Frankreich?",
                cardBack = "Paris.",
                learnerAnswer = "London.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
                expectedSpeechLanguage = "en",
                settingsLanguage = AppLanguage.ENGLISH,
                assistantSpeechLanguagePolicy = AssistantSpeechLanguagePolicy.APP_LANGUAGE,
            ),
            HarnessCase(
                id = "matrix_en_card_de_app",
                cardFront = "What is photosynthesis?",
                cardBack = "Plants convert light energy into chemical energy.",
                learnerAnswer = "Cell division.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
                expectedSpeechLanguage = "de",
                settingsLanguage = AppLanguage.GERMAN,
                assistantSpeechLanguagePolicy = AssistantSpeechLanguagePolicy.APP_LANGUAGE,
            ),
            HarnessCase(
                id = "matrix_en_card_card_policy",
                cardFront = "What is diffusion?",
                cardBack = "Particles spread from higher to lower concentration.",
                learnerAnswer = "It's when particles move from higher to lower concentration.",
                expectedTerminal = TutorTerminalAction.GRADE_ANSWER,
                expectedSpeechLanguage = "en",
                settingsLanguage = AppLanguage.GERMAN,
                assistantSpeechLanguagePolicy = AssistantSpeechLanguagePolicy.CARD_LANGUAGE,
            ),
        )

        var passed = 0
        val failures = mutableListOf<String>()
        val caseReports = mutableListOf<String>()
        val caseResults = mutableListOf<HarnessCaseResult>()
        println("=== AnkiVoice Local LLM Harness ===")
        println("provider=${settings.provider} model=${settings.model} base=${settings.baseUrl}")
        println("cases=${cases.size}")

        for (case in cases) {
            runCatching {
                val caseSettings = settings.copy(language = case.settingsLanguage ?: settings.language)
                val result = service.evaluate(
                    TutorEvaluationRequest(
                        cardFront = case.cardFront,
                        cardBack = case.cardBack,
                        learnerAnswer = case.learnerAnswer,
                        requireTerminalTool = case.expectedTerminal != null,
                        assistantSpeechLanguagePolicy = case.assistantSpeechLanguagePolicy,
                        settings = caseSettings,
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
                case.expectedSpeechLanguage?.let { expectedLang ->
                    val speech = action.assistantSpeech.trim()
                    if (speech.isNotBlank()) {
                        assertTrue(
                            "assistant_speech language mismatch for case=${case.id}; expected=$expectedLang speech=$speech",
                            isLikelyLanguage(speech, expectedLang),
                        )
                    }
                }
                if (case.id == "metadata_speech_rewrite") {
                    assertTrue(
                        "metadata_speech_rewrite should call set_speech_verbalization",
                        result.rawJson.contains("\"name\":\"set_speech_verbalization\""),
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
                caseResults.add(
                    HarnessCaseResult(
                        id = case.id,
                        question = case.cardFront,
                        expectedAnswer = case.cardBack,
                        learnerAnswer = case.learnerAnswer,
                        expectedTerminal = case.expectedTerminal?.name,
                        parsedTerminal = action.terminalAction.name,
                        parsedEase = action.ease,
                        parsedAssistantSpeech = action.assistantSpeech,
                        passed = true,
                        failure = null,
                        rawModelOutput = result.rawJson,
                        settingsLanguage = caseSettings.language.name,
                        assistantSpeechLanguagePolicy = case.assistantSpeechLanguagePolicy.name,
                    ),
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
                caseResults.add(
                    HarnessCaseResult(
                        id = case.id,
                        question = case.cardFront,
                        expectedAnswer = case.cardBack,
                        learnerAnswer = case.learnerAnswer,
                        expectedTerminal = case.expectedTerminal?.name,
                        parsedTerminal = null,
                        parsedEase = null,
                        parsedAssistantSpeech = null,
                        passed = false,
                        failure = e.message ?: e::class.java.simpleName,
                        rawModelOutput = "",
                        settingsLanguage = (case.settingsLanguage ?: settings.language).name,
                        assistantSpeechLanguagePolicy = case.assistantSpeechLanguagePolicy.name,
                    ),
                )
            }
        }

        println("SUMMARY total=${cases.size} passed=$passed failed=${failures.size}")
        failures.forEach { println("FAILURE $it") }
        writeHarnessReport(
            settings = settings,
            caseReports = caseReports,
            failures = failures,
            passed = passed,
            total = cases.size,
            caseResults = caseResults,
        )
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

    private fun isLikelyLanguage(text: String, expected: String): Boolean {
        val s = " ${text.lowercase(Locale.ROOT)} "
        val germanScore = listOf(" der ", " die ", " das ", " und ", " ist ", " nicht ", " ein ", " eine ")
            .count { s.contains(it) } + if (Regex("[äöüß]").containsMatchIn(s)) 2 else 0
        val englishScore = listOf(" the ", " and ", " is ", " not ", " a ", " an ", " of ", " to ")
            .count { s.contains(it) }
        return when (expected) {
            "de" -> germanScore >= englishScore
            "en" -> englishScore >= germanScore
            else -> true
        }
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
        caseResults: List<HarnessCaseResult>,
    ) {
        val reportsDir = File("build/reports/ankivoice")
        reportsDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val now = Date()
        val gitBranch = gitValue("rev-parse --abbrev-ref HEAD") ?: "(unknown)"
        val gitCommit = gitValue("rev-parse --short HEAD") ?: "(unknown)"
        val failuresCount = failures.size
        val body = buildString {
            appendLine("# AnkiVoice Conversation Harness")
            appendLine()
            appendLine("- Generated at: $now")
            appendLine("- Provider: ${settings.provider}")
            appendLine("- Model: ${settings.model}")
            appendLine("- Base URL: ${settings.baseUrl}")
            appendLine("- Branch: $gitBranch")
            appendLine("- Commit: $gitCommit")
            appendLine("- Passed: $passed / $total")
            appendLine("- Failed: $failuresCount")
            appendLine()
            if (failures.isNotEmpty()) {
                appendLine("## Failures")
                failures.forEach { appendLine("- $it") }
                appendLine()
            }
            append(caseReports.joinToString("\n\n---\n\n"))
            appendLine()
        }
        val markdownTimestamped = File(reportsDir, "conversation-harness-$stamp.md")
        val markdownLatest = File(reportsDir, "conversation-harness-latest.md")
        markdownTimestamped.writeText(body)
        markdownLatest.writeText(body)

        val jsonBody = buildHarnessJson(
            generatedAt = now,
            provider = settings.provider.name,
            model = settings.model,
            baseUrl = settings.baseUrl,
            branch = gitBranch,
            commit = gitCommit,
            passed = passed,
            total = total,
            failures = failures,
            cases = caseResults,
        )
        val jsonTimestamped = File(reportsDir, "conversation-harness-$stamp.json")
        val jsonLatest = File(reportsDir, "conversation-harness-latest.json")
        jsonTimestamped.writeText(jsonBody)
        jsonLatest.writeText(jsonBody)

        val htmlBody = buildHarnessHtml(
            generatedAt = now,
            provider = settings.provider.name,
            model = settings.model,
            baseUrl = settings.baseUrl,
            branch = gitBranch,
            commit = gitCommit,
            passed = passed,
            total = total,
            failures = failures,
            cases = caseResults,
        )
        val htmlTimestamped = File(reportsDir, "conversation-harness-$stamp.html")
        val htmlLatest = File(reportsDir, "conversation-harness-latest.html")
        htmlTimestamped.writeText(htmlBody)
        htmlLatest.writeText(htmlBody)
    }

    private fun buildHarnessJson(
        generatedAt: Date,
        provider: String,
        model: String,
        baseUrl: String,
        branch: String,
        commit: String,
        passed: Int,
        total: Int,
        failures: List<String>,
        cases: List<HarnessCaseResult>,
    ): String =
        buildString {
            append("{\n")
            append("  \"generated_at\": \"${jsonEscape(generatedAt.toString())}\",\n")
            append("  \"provider\": \"${jsonEscape(provider)}\",\n")
            append("  \"model\": \"${jsonEscape(model)}\",\n")
            append("  \"base_url\": \"${jsonEscape(baseUrl)}\",\n")
            append("  \"branch\": \"${jsonEscape(branch)}\",\n")
            append("  \"commit\": \"${jsonEscape(commit)}\",\n")
            append("  \"summary\": {\n")
            append("    \"passed\": $passed,\n")
            append("    \"total\": $total,\n")
            append("    \"failed\": ${failures.size}\n")
            append("  },\n")
            append("  \"failures\": [\n")
            failures.forEachIndexed { idx, failure ->
                append("    \"${jsonEscape(failure)}\"")
                if (idx < failures.lastIndex) append(",")
                append("\n")
            }
            append("  ],\n")
            append("  \"cases\": [\n")
            cases.forEachIndexed { idx, case ->
                append("    {\n")
                append("      \"id\": \"${jsonEscape(case.id)}\",\n")
                append("      \"question\": \"${jsonEscape(case.question)}\",\n")
                append("      \"expected_answer\": \"${jsonEscape(case.expectedAnswer)}\",\n")
                append("      \"learner_answer\": \"${jsonEscape(case.learnerAnswer)}\",\n")
                append("      \"expected_terminal\": ${jsonOrNull(case.expectedTerminal)},\n")
                append("      \"parsed_terminal\": ${jsonOrNull(case.parsedTerminal)},\n")
                append("      \"parsed_ease\": ${case.parsedEase?.toString() ?: "null"},\n")
                append("      \"parsed_assistant_speech\": ${jsonOrNull(case.parsedAssistantSpeech)},\n")
                append("      \"settings_language\": \"${jsonEscape(case.settingsLanguage)}\",\n")
                append("      \"assistant_speech_language_policy\": \"${jsonEscape(case.assistantSpeechLanguagePolicy)}\",\n")
                append("      \"passed\": ${case.passed},\n")
                append("      \"failure\": ${jsonOrNull(case.failure)},\n")
                append("      \"raw_model_output\": ${jsonOrNull(case.rawModelOutput)}\n")
                append("    }")
                if (idx < cases.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }

    private fun buildHarnessHtml(
        generatedAt: Date,
        provider: String,
        model: String,
        baseUrl: String,
        branch: String,
        commit: String,
        passed: Int,
        total: Int,
        failures: List<String>,
        cases: List<HarnessCaseResult>,
    ): String =
        buildString {
            appendLine("<!doctype html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"utf-8\" />")
            appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />")
            appendLine("  <title>AnkiVoice Conversation Harness</title>")
            appendLine("  <style>")
            appendLine("    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 24px; color: #222; }")
            appendLine("    h1 { margin-bottom: 8px; }")
            appendLine("    .meta { display: grid; grid-template-columns: 180px 1fr; row-gap: 6px; column-gap: 10px; margin-bottom: 20px; }")
            appendLine("    .meta dt { font-weight: 600; color: #555; }")
            appendLine("    .meta dd { margin: 0; }")
            appendLine("    .ok { color: #0a7a2f; font-weight: 700; }")
            appendLine("    .fail { color: #b42318; font-weight: 700; }")
            appendLine("    table { width: 100%; border-collapse: collapse; margin-top: 12px; }")
            appendLine("    th, td { border: 1px solid #ddd; padding: 8px; vertical-align: top; text-align: left; font-size: 13px; }")
            appendLine("    th { background: #f6f6f6; position: sticky; top: 0; }")
            appendLine("    tr:nth-child(even) { background: #fcfcfc; }")
            appendLine("    details { margin-top: 6px; }")
            appendLine("    pre { white-space: pre-wrap; font-size: 12px; margin: 6px 0 0; }")
            appendLine("    .fail-list li { margin-bottom: 4px; }")
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <h1>AnkiVoice Conversation Harness</h1>")
            appendLine("  <dl class=\"meta\">")
            appendLine("    <dt>Generated at</dt><dd>${htmlEscape(generatedAt.toString())}</dd>")
            appendLine("    <dt>Provider</dt><dd>${htmlEscape(provider)}</dd>")
            appendLine("    <dt>Model</dt><dd>${htmlEscape(model)}</dd>")
            appendLine("    <dt>Base URL</dt><dd>${htmlEscape(baseUrl)}</dd>")
            appendLine("    <dt>Branch</dt><dd>${htmlEscape(branch)}</dd>")
            appendLine("    <dt>Commit</dt><dd>${htmlEscape(commit)}</dd>")
            appendLine("    <dt>Passed</dt><dd class=\"ok\">$passed / $total</dd>")
            appendLine("    <dt>Failed</dt><dd class=\"${if (failures.isEmpty()) "ok" else "fail"}\">${failures.size}</dd>")
            appendLine("  </dl>")
            if (failures.isNotEmpty()) {
                appendLine("  <h2>Failures</h2>")
                appendLine("  <ul class=\"fail-list\">")
                failures.forEach { failure ->
                    appendLine("    <li>${htmlEscape(failure)}</li>")
                }
                appendLine("  </ul>")
            }
            appendLine("  <h2>Cases</h2>")
            appendLine("  <table>")
            appendLine("    <thead>")
            appendLine("      <tr>")
            appendLine("        <th>ID</th><th>Result</th><th>Expected terminal</th><th>Parsed terminal</th><th>Ease</th><th>Question</th><th>Learner answer</th><th>Assistant speech</th><th>Lang/Policy</th><th>Raw output</th>")
            appendLine("      </tr>")
            appendLine("    </thead>")
            appendLine("    <tbody>")
            cases.forEach { case ->
                appendLine("      <tr>")
                appendLine("        <td>${htmlEscape(case.id)}</td>")
                appendLine("        <td class=\"${if (case.passed) "ok" else "fail"}\">${if (case.passed) "PASS" else "FAIL"}</td>")
                appendLine("        <td>${htmlEscape(case.expectedTerminal ?: "-")}</td>")
                appendLine("        <td>${htmlEscape(case.parsedTerminal ?: "-")}</td>")
                appendLine("        <td>${case.parsedEase?.toString() ?: "-"}</td>")
                appendLine("        <td>${htmlEscape(case.question)}</td>")
                appendLine("        <td>${htmlEscape(case.learnerAnswer)}</td>")
                appendLine("        <td>${htmlEscape(case.parsedAssistantSpeech ?: "-")}</td>")
                appendLine("        <td>${htmlEscape(case.settingsLanguage)} / ${htmlEscape(case.assistantSpeechLanguagePolicy)}</td>")
                appendLine("        <td>")
                if (!case.rawModelOutput.isNullOrBlank()) {
                    appendLine("          <details><summary>Show raw</summary><pre>${htmlEscape(case.rawModelOutput)}</pre></details>")
                } else {
                    appendLine("          -")
                }
                if (!case.failure.isNullOrBlank()) {
                    appendLine("          <div class=\"fail\">${htmlEscape(case.failure)}</div>")
                }
                appendLine("        </td>")
                appendLine("      </tr>")
            }
            appendLine("    </tbody>")
            appendLine("  </table>")
            appendLine("</body>")
            appendLine("</html>")
        }

    private fun gitValue(args: String): String? =
        runCatching {
            val parts = args.split(" ")
            val process = ProcessBuilder(listOf("git") + parts)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            if (exit == 0 && output.isNotBlank()) output else null
        }.getOrNull()

    private fun jsonEscape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun jsonOrNull(value: String?): String = value?.let { "\"${jsonEscape(it)}\"" } ?: "null"

    private fun htmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

}

private data class HarnessCaseResult(
    val id: String,
    val question: String,
    val expectedAnswer: String,
    val learnerAnswer: String,
    val expectedTerminal: String?,
    val parsedTerminal: String?,
    val parsedEase: Int?,
    val parsedAssistantSpeech: String?,
    val settingsLanguage: String,
    val assistantSpeechLanguagePolicy: String,
    val passed: Boolean,
    val failure: String?,
    val rawModelOutput: String?,
)

private data class HarnessCase(
    val id: String,
    val cardFront: String,
    val cardBack: String,
    val learnerAnswer: String,
    val expectedTerminal: TutorTerminalAction?,
    val expectedSpeechLanguage: String? = null,
    val settingsLanguage: AppLanguage? = null,
    val assistantSpeechLanguagePolicy: AssistantSpeechLanguagePolicy = AssistantSpeechLanguagePolicy.APP_LANGUAGE,
)
