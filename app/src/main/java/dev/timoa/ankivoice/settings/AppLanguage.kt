package dev.timoa.ankivoice.settings

enum class AppLanguage {
    ENGLISH,
    GERMAN,
    ;

    fun toStorageValue(): String =
        when (this) {
            ENGLISH -> "en"
            GERMAN -> "de"
        }

    companion object {
        fun fromStorageValue(raw: String?): AppLanguage =
            when (raw) {
                "de", "GERMAN" -> GERMAN
                else -> ENGLISH
            }
    }
}
