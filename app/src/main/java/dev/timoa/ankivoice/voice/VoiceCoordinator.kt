package dev.timoa.ankivoice.voice

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VoiceCoordinator(
    private val activity: Activity,
) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRate: Float = 1.0f
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    private val speechRecognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            SpeechRecognizer.createSpeechRecognizer(activity)
        } else {
            null
        }

    init {
        tts = TextToSpeech(activity, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(speechRate)
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.6f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    fun shutdown() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        tone.release()
        tts = null
    }

    fun playGradeCue(ease: Int) {
        when (ease) {
            1 -> tone.startTone(ToneGenerator.TONE_PROP_NACK, 220)
            2 -> tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            3 -> tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
            4 -> tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
        }
    }

    suspend fun speak(text: String) {
        val engine = tts ?: error("TTS not initialized")
        if (!ttsReady) error("TTS not ready")
        val utteranceId = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("TTS error"))
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("TTS error code $errorCode"))
                    }
                },
            )
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    suspend fun listen(): String {
        val recognizer = speechRecognizer ?: error("Speech recognition not available on this device")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        return suspendCancellableCoroutine { cont ->
            recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        if (!cont.isActive) return
                        val msg = speechErrorToMessage(error)
                        cont.resumeWithException(IllegalStateException(msg))
                    }

                    override fun onResults(results: Bundle?) {
                        if (!cont.isActive) return
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull().orEmpty()
                        cont.resume(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                },
            )
            recognizer.startListening(intent)
            cont.invokeOnCancellation { recognizer.stopListening() }
        }
    }

    private fun speechErrorToMessage(code: Int): String =
        when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Recognition client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "Network error (recognition)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout (recognition)"
            SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand speech — try again"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard — try again"
            else -> "Speech recognition error ($code)"
        }
}
