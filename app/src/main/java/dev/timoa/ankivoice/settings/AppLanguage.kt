package dev.timoa.ankivoice.settings

import java.util.Locale

enum class AppLanguage {
    ENGLISH,
    GERMAN,
    ;

    fun toStorageValue(): String =
        when (this) {
            ENGLISH -> "en"
            GERMAN -> "de"
        }

    /** Locale used for Android TTS until a bundled on-device model is active. */
    fun toSpeechLocale(): Locale =
        when (this) {
            ENGLISH -> Locale.US
            GERMAN -> Locale.GERMAN
        }

    companion object {
        fun fromStorageValue(raw: String?): AppLanguage =
            when (raw) {
                "de", "GERMAN" -> GERMAN
                else -> ENGLISH
            }
    }
}
