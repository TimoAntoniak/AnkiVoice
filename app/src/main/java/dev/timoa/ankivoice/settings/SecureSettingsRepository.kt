package dev.timoa.ankivoice.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.timoa.ankivoice.anki.AnkiDroidRepository

class SecureSettingsRepository(
    context: Context,
) {
    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): UserSettings {
        val hasSavedKeys = prefs.contains(KEY_API) ||
            prefs.contains(KEY_BASE) ||
            prefs.contains(KEY_MODEL)
        val storedProvider = prefs.getString(KEY_PROVIDER, null)
        val provider = when {
            storedProvider != null -> LlmProvider.fromStorageValue(storedProvider)
            !hasSavedKeys -> LlmProvider.ANTHROPIC_CLAUDE
            else -> LlmProvider.OPENAI_COMPATIBLE
        }
        val defaultBase = defaultBaseFor(provider)
        val defaultModel = defaultModelFor(provider)
        return UserSettings(
            provider = provider,
            language = AppLanguage.fromStorageValue(prefs.getString(KEY_LANGUAGE, null)),
            apiKey = prefs.getString(KEY_API, "") ?: "",
            baseUrl = (prefs.getString(KEY_BASE, null) ?: defaultBase).trimEnd('/'),
            model = prefs.getString(KEY_MODEL, null) ?: defaultModel,
            ttsBackend = TtsBackend.fromStorageValue(prefs.getString(KEY_TTS_BACKEND, null)),
            studyDeckId = prefs.getLong(KEY_STUDY_DECK, AnkiDroidRepository.DECK_ID_FOLLOW_ANKI_SELECTED),
            skipTagsCsv = prefs.getString(KEY_SKIP_TAGS, "") ?: "",
            ttsRate = prefs.getFloat(KEY_TTS_RATE, DEFAULT_TTS_RATE),
            adaptiveFeedbackHistoryEnabled = prefs.getBoolean(KEY_ADAPTIVE_FEEDBACK, false),
        )
    }

    fun save(settings: UserSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.toStorageValue())
            .putString(KEY_LANGUAGE, settings.language.toStorageValue())
            .putString(KEY_API, settings.apiKey)
            .putString(KEY_BASE, settings.baseUrl.trimEnd('/'))
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_TTS_BACKEND, settings.ttsBackend.toStorageValue())
            .putLong(KEY_STUDY_DECK, settings.studyDeckId)
            .putString(KEY_SKIP_TAGS, settings.skipTagsCsv)
            .putFloat(KEY_TTS_RATE, settings.ttsRate.coerceIn(0.6f, 2.0f))
            .putBoolean(KEY_ADAPTIVE_FEEDBACK, settings.adaptiveFeedbackHistoryEnabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ankivoice_secure_settings"
        private const val KEY_PROVIDER = "llm_provider"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_API = "openai_api_key"
        private const val KEY_BASE = "api_base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_TTS_BACKEND = "tts_backend"
        private const val KEY_STUDY_DECK = "study_deck_id"
        private const val KEY_SKIP_TAGS = "skip_tags_csv"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_ADAPTIVE_FEEDBACK = "adaptive_feedback_history"

        const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        const val DEFAULT_ANTHROPIC_BASE = "https://api.anthropic.com/v1"
        /** Cost-effective; change in Settings if you prefer Sonnet, etc. */
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-4-5"
        const val DEFAULT_TTS_RATE = 1.0f

        fun defaultBaseFor(provider: LlmProvider): String =
            when (provider) {
                LlmProvider.ANTHROPIC_CLAUDE -> DEFAULT_ANTHROPIC_BASE
                LlmProvider.OPENAI_COMPATIBLE -> DEFAULT_OPENAI_BASE
            }

        fun defaultModelFor(provider: LlmProvider): String =
            when (provider) {
                LlmProvider.ANTHROPIC_CLAUDE -> DEFAULT_CLAUDE_MODEL
                LlmProvider.OPENAI_COMPATIBLE -> DEFAULT_OPENAI_MODEL
            }
    }
}
