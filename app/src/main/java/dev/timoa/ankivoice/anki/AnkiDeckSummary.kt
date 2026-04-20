package dev.timoa.ankivoice.anki

data class AnkiDeckSummary(
    val deckId: Long,
    /** Full name with `::` for sub-decks, e.g. `Language::French`. */
    val fullName: String,
    val learnCount: Int,
    val reviewCount: Int,
    val newCount: Int,
) {
    val indentLevel: Int get() = (fullName.split("::").size - 1).coerceAtLeast(0)

    val dueForVoice: Int get() = learnCount + reviewCount
}
