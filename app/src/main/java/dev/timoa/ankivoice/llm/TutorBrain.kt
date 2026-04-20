package dev.timoa.ankivoice.llm

import dev.timoa.ankivoice.anki.AnkiCard
import dev.timoa.ankivoice.settings.AppLanguage

object TutorBrain {
    const val MAX_TURNS_PER_CARD = 1

    fun systemPrompt(card: AnkiCard, language: AppLanguage): String =
        systemPrompt(card.questionSpeech, card.answerSpeech, language)

    fun systemPrompt(questionSpeech: String, answerSpeech: String, language: AppLanguage): String =
        """
        You are grading one spoken Anki answer.

        Card front (question): $questionSpeech
        Card back (expected answer): $answerSpeech

        Return exactly one JSON object with these keys:
        {
          "assistant_speech": string,
          "reread_card_front": boolean,
          "continue_conversation": boolean,
          "schedule_review": boolean,
          "ease": number
        }

        Hard rules:
        - This is NOT a conversation.
        - Always set schedule_review=true.
        - Always set continue_conversation=false.
        - Always set reread_card_front=false.
        - Always set ease to an integer 1..4 (1=Again, 2=Hard, 3=Good, 4=Easy).
        - Keep assistant_speech concise by default.
        - IMPORTANT: if user asks for a reminder/hint (e.g. "remind me", "was war das", "help"), you may give a longer, explicit reminder of the expected answer content.
        - If answer is wrong/insufficient, assistant_speech must be corrective and concise, then choose ease=1.
        - If partially correct, choose ease=2.
        - If correct, choose ease=3 or 4 and avoid praise.
        - No small talk, no follow-up questions, no extra explanations.
        - Use this language for assistant_speech: ${if (language == AppLanguage.GERMAN) "German" else "English"}.

        Output JSON only. No markdown.
        """.trimIndent()
}
