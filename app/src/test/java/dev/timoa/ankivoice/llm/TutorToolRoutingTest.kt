package dev.timoa.ankivoice.llm

import dev.timoa.ankivoice.settings.AppLanguage
import dev.timoa.ankivoice.settings.LlmProvider
import dev.timoa.ankivoice.settings.TtsBackend
import dev.timoa.ankivoice.settings.UserSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorToolRoutingTest {
    @Test
    fun gradeAnswerBuildsGradeTerminalAction() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"ok","ease":3}"""),
        )
        val out = service.evaluate(request())
        assertEquals(TutorTerminalAction.GRADE_ANSWER, out.action.terminalAction)
        assertEquals(3, out.action.ease)
    }

    @Test
    fun suspendBuildsSuspendTerminalAction() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_SUSPEND_CARD, """{"assistant_speech":"done"}"""),
        )
        val out = service.evaluate(request())
        assertEquals(TutorTerminalAction.SUSPEND_CARD, out.action.terminalAction)
    }

    @Test
    fun buryBuildsBuryTerminalAction() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_BURY_CARD, """{"assistant_speech":"bury"}"""),
        )
        val out = service.evaluate(request())
        assertEquals(TutorTerminalAction.BURY_CARD, out.action.terminalAction)
    }

    @Test
    fun switchDeckAcceptsDeckId() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_SWITCH_DECK, """{"deck_id":42,"assistant_speech":"switching"}"""),
        )
        val out = service.evaluate(request())
        assertEquals(TutorTerminalAction.SWITCH_DECK, out.action.terminalAction)
        assertEquals(42L, out.action.switchDeckId)
    }

    @Test
    fun switchDeckAcceptsDeckQuery() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_SWITCH_DECK, """{"deck_name_query":"biology","assistant_speech":"switching"}"""),
        )
        val out = service.evaluate(request())
        assertEquals(TutorTerminalAction.SWITCH_DECK, out.action.terminalAction)
        assertEquals("biology", out.action.deckQuery)
    }

    @Test
    fun switchDeckWithNoTargetFailsValidation() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_SWITCH_DECK, """{"assistant_speech":"switching"}"""),
        )
        val result = runCatching { service.evaluate(request()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun deckUtilityFlagsAreCaptured() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_LIST_DECKS, """{}"""),
            ToolCall(TOOL_SUGGEST_NEXT_DECK, """{"preference":"not math"}"""),
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"","ease":3}"""),
        )
        val out = service.evaluate(request())
        assertTrue(out.action.wantsDeckList)
        assertTrue(out.action.wantsDeckSuggestion)
        assertEquals("not math", out.action.deckSuggestionPreference)
    }

    @Test
    fun metadataWritesAreCaptured() = runBlocking {
        val service = fakeService(
            ToolCall(
                TOOL_SET_CARD_TUTOR_INSTRUCTIONS,
                """{"mode":"append","instruction_text":"Ignore order."}""",
            ),
            ToolCall(
                TOOL_SET_SPEECH_VERBALIZATION,
                """{"target":"front","verbalization_text":"Read sigma as sum."}""",
            ),
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"ok","ease":2}"""),
        )
        val out = service.evaluate(request())
        assertEquals(2, out.action.metadataWrites.size)
    }

    @Test
    fun tutorInstructionReplaceModeIsCaptured() = runBlocking {
        val service = fakeService(
            ToolCall(
                TOOL_SET_CARD_TUTOR_INSTRUCTIONS,
                """{"mode":"replace","instruction_text":"Accept synonyms."}""",
            ),
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"","ease":3}"""),
        )
        val out = service.evaluate(request())
        assertEquals(1, out.action.metadataWrites.size)
        val write = out.action.metadataWrites.first() as CardMetadataWrite.TutorInstruction
        assertEquals(CardInstructionMode.REPLACE, write.mode)
    }

    @Test
    fun speechVerbalizationDefaultTargetFallsBackToBoth() = runBlocking {
        val service = fakeService(
            ToolCall(
                TOOL_SET_SPEECH_VERBALIZATION,
                """{"verbalization_text":"Read alpha as alpha."}""",
            ),
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"","ease":3}"""),
        )
        val out = service.evaluate(request())
        val write = out.action.metadataWrites.first() as CardMetadataWrite.SpeechVerbalization
        assertEquals(SpeechTarget.BOTH, write.target)
    }

    @Test
    fun emptyMetadataWritesAreIgnored() = runBlocking {
        val service = fakeService(
            ToolCall(
                TOOL_SET_CARD_TUTOR_INSTRUCTIONS,
                """{"mode":"append","instruction_text":"   "}""",
            ),
            ToolCall(
                TOOL_SET_SPEECH_VERBALIZATION,
                """{"target":"front","verbalization_text":"   "}""",
            ),
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"","ease":3}"""),
        )
        val out = service.evaluate(request())
        assertTrue(out.action.metadataWrites.isEmpty())
    }

    @Test
    fun rereadFlagIsCaptured() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_REREAD_CARD_FRONT, """{"reason":"again"}"""),
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"","ease":3}"""),
        )
        val out = service.evaluate(request())
        assertTrue(out.action.rereadCardFront)
    }

    @Test
    fun chatFirstSuggestThenSwitchFlowIsRepresentable() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_SUGGEST_NEXT_DECK, """{"preference":"not math"}"""),
            ToolCall(TOOL_SWITCH_DECK, """{"deck_name_query":"biology","assistant_speech":"Let's switch."}"""),
        )
        val out = service.evaluate(request(learner = "choose a deck for me"))
        assertTrue(out.action.wantsDeckSuggestion)
        assertEquals("not math", out.action.deckSuggestionPreference)
        assertEquals(TutorTerminalAction.SWITCH_DECK, out.action.terminalAction)
    }

    @Test
    fun chatFirstListThenSwitchFlowIsRepresentable() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_LIST_DECKS, """{}"""),
            ToolCall(TOOL_SWITCH_DECK, """{"deck_name_query":"chemistry"}"""),
        )
        val out = service.evaluate(request(learner = "what decks are left and pick chemistry"))
        assertTrue(out.action.wantsDeckList)
        assertEquals(TutorTerminalAction.SWITCH_DECK, out.action.terminalAction)
        assertEquals("chemistry", out.action.deckQuery)
    }

    @Test
    fun multipleTerminalToolsFailValidation() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_BURY_CARD, """{"assistant_speech":"bury"}"""),
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"","ease":3}"""),
        )
        val result = runCatching { service.evaluate(request()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun invalidGradeEaseFailsValidation() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"bad","ease":7}"""),
        )
        val result = runCatching { service.evaluate(request()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun suggestNextDeckWithoutPreferenceDefaultsToEmpty() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_SUGGEST_NEXT_DECK, """{}"""),
            ToolCall(TOOL_GRADE_ANSWER, """{"assistant_speech":"","ease":3}"""),
        )
        val out = service.evaluate(request())
        assertTrue(out.action.wantsDeckSuggestion)
        assertEquals("", out.action.deckSuggestionPreference)
    }

    @Test
    fun assistantSpeechDefaultsEmptyForSuspendWhenMissing() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_SUSPEND_CARD, """{}"""),
        )
        val out = service.evaluate(request())
        assertEquals(TutorTerminalAction.SUSPEND_CARD, out.action.terminalAction)
        assertEquals("", out.action.assistantSpeech)
    }

    @Test
    fun switchDeckIdPreservesNoDeckQuery() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_SWITCH_DECK, """{"deck_id":99}"""),
        )
        val out = service.evaluate(request())
        assertEquals(99L, out.action.switchDeckId)
        assertNull(out.action.deckQuery)
    }

    @Test
    fun chatDeckCommandWithoutTerminalIsAcceptedInChatMode() = runBlocking {
        val service = fakeService(
            ToolCall(TOOL_LIST_DECKS, """{}"""),
        )
        val out = service.evaluate(request(requireTerminalTool = false))
        assertTrue(out.action.wantsDeckList)
        assertEquals(TutorTerminalAction.NONE, out.action.terminalAction)
    }

    private fun fakeService(vararg calls: ToolCall): TutorEvaluationService =
        TutorEvaluationService { _, _ ->
            Result.success(
                StructuredLlmTurn(
                    assistantText = "",
                    toolCalls = calls.toList(),
                    rawOutput = "fake",
                ),
            )
        }

    private fun request(
        learner: String = "A",
        requireTerminalTool: Boolean = true,
    ): TutorEvaluationRequest =
        TutorEvaluationRequest(
            cardFront = "Q",
            cardBack = "A",
            learnerAnswer = learner,
            requireTerminalTool = requireTerminalTool,
            settings = UserSettings(
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
            ),
        )
}
