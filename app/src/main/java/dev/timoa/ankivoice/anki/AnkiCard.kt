package dev.timoa.ankivoice.anki

import dev.timoa.ankivoice.stripHtmlForSpeech

data class AnkiCard(
    val noteId: Long,
    val cardOrd: Int,
    val questionHtml: String,
    val answerHtml: String,
    val reviewButtonCount: Int = 4,
) {
    val questionSpeech: String get() = questionHtml.stripHtmlForSpeech()
    val answerSpeech: String get() = answerHtml.stripHtmlForSpeech()
}
