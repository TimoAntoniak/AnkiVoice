package dev.timoa.ankivoice

import androidx.core.text.HtmlCompat

fun String.stripHtmlForSpeech(): String =
    HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("\\s+"), " ")
        .trim()
