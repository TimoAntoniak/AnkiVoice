package dev.timoa.ankivoice.settings

enum class TtsBackend(
    private val storageValue: String,
) {
    SYSTEM("system"),
    LOCAL_PIPER_EXPERIMENTAL("local_piper_experimental"),
    ;

    fun toStorageValue(): String = storageValue

    companion object {
        fun fromStorageValue(raw: String?): TtsBackend =
            entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}
