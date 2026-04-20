package dev.timoa.ankivoice.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.FlashCardsContract.Card
import com.ichi2.anki.FlashCardsContract.Deck
import com.ichi2.anki.FlashCardsContract.Note
import com.ichi2.anki.FlashCardsContract.ReviewInfo
import org.json.JSONArray

class AnkiDroidRepository(
    private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun isAnkiDroidInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(ANKI_PACKAGE) != null

    fun hasReadWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            FlashCardsContract.READ_WRITE_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Deck currently highlighted on AnkiDroid’s home screen (not necessarily the same as “default” for old queries).
     */
    fun queryAnkiSelectedDeckId(): Long? {
        resolver.query(
            Deck.CONTENT_SELECTED_URI,
            arrayOf(Deck.DECK_ID),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                return c.getLong(c.getColumnIndexOrThrow(Deck.DECK_ID))
            }
        }
        return null
    }

    fun setAnkiSelectedDeckId(deckId: Long): Result<Unit> =
        runCatching {
            val values = ContentValues().apply {
                put(Deck.DECK_ID, deckId)
            }
            val updated = resolver.update(Deck.CONTENT_SELECTED_URI, values, null, null)
            if (updated <= 0) error("Could not set selected deck in AnkiDroid (updated=$updated).")
        }

    fun queryDeckSummaries(): List<AnkiDeckSummary> {
        val out = mutableListOf<AnkiDeckSummary>()
        resolver.query(
            Deck.CONTENT_ALL_URI,
            Deck.DEFAULT_PROJECTION,
            null,
            null,
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Deck.DECK_ID)
            val nameCol = c.getColumnIndexOrThrow(Deck.DECK_NAME)
            val countsCol = c.getColumnIndexOrThrow(Deck.DECK_COUNTS)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val countsJson = c.getString(countsCol) ?: "[]"
                val (learn, review, new) = parseDeckCounts(countsJson)
                out.add(AnkiDeckSummary(id, name, learn, review, new))
            }
        }
        return out.sortedBy { it.fullName.lowercase() }
    }

    fun queryDeckName(deckId: Long): String? {
        val uri = Uri.withAppendedPath(Deck.CONTENT_ALL_URI, deckId.toString())
        resolver.query(uri, arrayOf(Deck.DECK_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(c.getColumnIndexOrThrow(Deck.DECK_NAME))
            }
        }
        return null
    }

    /**
     * @param deckId concrete deck id from [queryAnkiSelectedDeckId] or settings; never use a guessed id.
     */
    fun queryNextScheduledCard(deckId: Long, limit: Int = 1): AnkiCard? {
        val selection = "limit=?,deckID=?"
        val args = arrayOf(limit.toString(), deckId.toString())
        resolver.query(
            ReviewInfo.CONTENT_URI,
            ReviewInfo.DEFAULT_PROJECTION,
            selection,
            args,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val noteId = c.getLong(c.getColumnIndexOrThrow(ReviewInfo.NOTE_ID))
            val ord = c.getInt(c.getColumnIndexOrThrow(ReviewInfo.CARD_ORD))
            val buttonCount = c.getColumnIndex(ReviewInfo.BUTTON_COUNT).takeIf { it >= 0 }?.let(c::getInt) ?: -1
            return loadCard(noteId, ord)?.copy(reviewButtonCount = buttonCount.takeIf { it in 1..4 } ?: 4)
        }
        return null
    }

    fun loadCard(noteId: Long, cardOrd: Int): AnkiCard? {
        val noteUri = Note.CONTENT_URI.buildUpon().appendPath(noteId.toString()).build()
        val cardsUri = noteUri.buildUpon().appendPath("cards").build()
        val cardUri = cardsUri.buildUpon().appendPath(cardOrd.toString()).build()
        resolver.query(
            cardUri,
            arrayOf(
                Card.NOTE_ID,
                Card.CARD_ORD,
                Card.QUESTION,
                Card.ANSWER,
            ),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            return AnkiCard(
                noteId = c.getLong(c.getColumnIndexOrThrow(Card.NOTE_ID)),
                cardOrd = c.getInt(c.getColumnIndexOrThrow(Card.CARD_ORD)),
                questionHtml = c.getString(c.getColumnIndexOrThrow(Card.QUESTION)) ?: "",
                answerHtml = c.getString(c.getColumnIndexOrThrow(Card.ANSWER)) ?: "",
            )
        }
        return null
    }

    fun queryNoteTags(noteId: Long): Set<String> {
        val noteUri = Uri.withAppendedPath(Note.CONTENT_URI, noteId.toString())
        resolver.query(noteUri, arrayOf(Note.TAGS), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return emptySet()
            val raw = c.getString(c.getColumnIndexOrThrow(Note.TAGS)) ?: return emptySet()
            return raw.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        return emptySet()
    }

    /** True if the note contains any of the tags (exact match, Anki’s space-separated tags). */
    fun noteMatchesSkipTags(noteId: Long, skipTags: List<String>): Boolean {
        if (skipTags.isEmpty()) return false
        val noteTags = queryNoteTags(noteId)
        return skipTags.any { wanted -> noteTags.contains(wanted) }
    }

    fun buryCard(noteId: Long, cardOrd: Int): Result<Unit> =
        runCatching {
            val values = ContentValues().apply {
                put(ReviewInfo.NOTE_ID, noteId)
                put(ReviewInfo.CARD_ORD, cardOrd)
                put(ReviewInfo.BURY, 1)
            }
            val updated = resolver.update(ReviewInfo.CONTENT_URI, values, null, null)
            if (updated <= 0) error("AnkiDroid did not bury the card (updated=$updated).")
        }

    /**
     * Next card in the deck queue, burying cards whose notes match [skipTags] (voice-unfriendly cards).
     */
    fun nextStudyableCard(deckId: Long, skipTags: List<String>, maxBurySkips: Int = 60): AnkiCard? {
        var buried = 0
        while (buried <= maxBurySkips) {
            val card = queryNextScheduledCard(deckId, 1) ?: return null
            if (skipTags.isEmpty() || !noteMatchesSkipTags(card.noteId, skipTags)) {
                return card
            }
            buryCard(card.noteId, card.cardOrd).getOrElse { throw it }
            buried++
        }
        return null
    }

    fun scheduleAnswer(
        noteId: Long,
        cardOrd: Int,
        ease: Int,
        timeTakenMs: Long,
    ): Result<Unit> =
        runCatching {
            require(ease in 1..4) { "ease must be 1..4" }
            val values = ContentValues().apply {
                put(ReviewInfo.NOTE_ID, noteId)
                put(ReviewInfo.CARD_ORD, cardOrd)
                put(ReviewInfo.EASE, ease)
                put(ReviewInfo.TIME_TAKEN, timeTakenMs)
            }
            val updated = resolver.update(ReviewInfo.CONTENT_URI, values, null, null)
            if (updated <= 0) error("AnkiDroid did not accept the review (updated=$updated). Is the API enabled in AnkiDroid settings?")
        }

    private fun parseDeckCounts(json: String): Triple<Int, Int, Int> =
        try {
            val arr = JSONArray(json)
            Triple(
                arr.optInt(0, 0),
                arr.optInt(1, 0),
                arr.optInt(2, 0),
            )
        } catch (_: Exception) {
            Triple(0, 0, 0)
        }

    private fun Cursor.getColumnIndexOrThrow(name: String): Int {
        val idx = getColumnIndex(name)
        if (idx < 0) error("Missing column $name")
        return idx
    }

    companion object {
        const val ANKI_PACKAGE = "com.ichi2.anki"

        /** Settings value: follow [queryAnkiSelectedDeckId] each time. */
        const val DECK_ID_FOLLOW_ANKI_SELECTED: Long = -1L
    }
}
