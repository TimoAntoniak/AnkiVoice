package dev.timoa.ankivoice.voice

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.timoa.ankivoice.settings.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
/**
 * Sherpa-ONNX + Piper (VITS) offline synthesis. Models live under [ASSET_ROOT] after
 * `./gradlew :app:ensureSherpaPiperAssets` (runs automatically before build).
 */
class LocalPiperTts(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var en: OfflineTts? = null
    private var de: OfflineTts? = null

    suspend fun speak(
        text: String,
        language: AppLanguage,
        speed: Float,
        diagnostics: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val tts = ttsForLanguage(language)
        diagnostics("piper_generate_start lang=${language.toStorageValue()} chars=${text.length}")
        val audio = tts.generateWithConfig(
            text,
            GenerationConfig(
                sid = 0,
                speed = speed.coerceIn(0.5f, 2.5f),
                silenceScale = 0.2f,
            ),
        )
        diagnostics(
            "piper_generate_done ms=${System.currentTimeMillis() - t0} " +
                "samples=${audio.samples.size} rate=${audio.sampleRate}",
        )
        if (audio.samples.isEmpty()) {
            error("Piper returned no audio samples (check model assets and text).")
        }
        if (audio.sampleRate <= 0) {
            error("Piper returned invalid sample rate: ${audio.sampleRate}")
        }
        playPcm(audio.samples, audio.sampleRate, diagnostics)
    }

    fun release() {
        en?.release()
        de?.release()
        en = null
        de = null
    }

    private fun ttsForLanguage(language: AppLanguage): OfflineTts {
        when (language) {
            AppLanguage.ENGLISH -> {
                if (en == null) {
                    en = OfflineTts(
                        appContext.assets,
                        buildPiperConfig(
                            assetModelDir = "$ASSET_ROOT/$MODEL_EN_DIR",
                            onnxName = "en_US-amy-low.onnx",
                        ),
                    )
                }
                return en!!
            }
            AppLanguage.GERMAN -> {
                if (de == null) {
                    de = OfflineTts(
                        appContext.assets,
                        buildPiperConfig(
                            assetModelDir = "$ASSET_ROOT/$MODEL_DE_DIR",
                            onnxName = "de_DE-thorsten-medium.onnx",
                        ),
                    )
                }
                return de!!
            }
        }
    }

    private fun buildPiperConfig(assetModelDir: String, onnxName: String): OfflineTtsConfig {
        val onnxAsset = "$assetModelDir/$onnxName"
        try {
            appContext.assets.open(onnxAsset).close()
        } catch (e: Exception) {
            error(
                "Missing Piper model in APK: $onnxAsset. " +
                    "Run a full online build so :app:ensureSherpaPiperAssets can download assets.",
            )
        }
        val dataDirAbs = ensureEspeakDataOnDisk(assetModelDir)
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$assetModelDir/$onnxName",
                    tokens = "$assetModelDir/tokens.txt",
                    dataDir = dataDirAbs,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
    }

    private fun ensureEspeakDataOnDisk(assetModelDir: String): String {
        val relAfterRoot = assetModelDir.removePrefix("$ASSET_ROOT/").trimStart('/')
        val workBase = File(appContext.filesDir, WORK_DIR)
        val marker = File(workBase, "$relAfterRoot/.espeak_ok")
        val espeakDst = File(workBase, "$relAfterRoot/espeak-ng-data")
        if (marker.exists()) {
            return espeakDst.absolutePath
        }
        copyAssetDirRecursive(appContext.assets, "$assetModelDir/espeak-ng-data", espeakDst)
        marker.parentFile?.mkdirs()
        marker.writeText("ok\n")
        return espeakDst.absolutePath
    }

    /**
     * STREAM mode: [AudioTrack.write] only queues samples; playback finishes later.
     * We must wait ~audio duration before [stop]/[release], otherwise audio is cut off and
     * [speak] returns immediately (study flow jumps straight to the microphone).
     */
    private suspend fun playPcm(
        samples: FloatArray,
        sampleRate: Int,
        diagnostics: (String) -> Unit,
    ) {
        val minBufFloat = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBufFloat > 0) {
            try {
                playPcmFloatDrain(samples, sampleRate, minBufFloat, diagnostics)
            } catch (e: Exception) {
                diagnostics("piper_play_float_failed ${e.message ?: e}")
                playPcm16Drain(floatToPcm16(samples), sampleRate, diagnostics)
            }
        } else {
            diagnostics("piper_play_using_pcm16 (float_output_not_supported)")
            playPcm16Drain(floatToPcm16(samples), sampleRate, diagnostics)
        }
    }

    private suspend fun playPcmFloatDrain(
        samples: FloatArray,
        sampleRate: Int,
        minBufFromApi: Int,
        diagnostics: (String) -> Unit,
    ) {
        val bytesPerFrame = 4
        val minBuf = minBufFromApi.coerceAtLeast(samples.size * bytesPerFrame)
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        val track = AudioTrack(
            attr,
            format,
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.play()
        val tPlay = System.currentTimeMillis()
        var offset = 0
        val chunk = 2048
        while (offset < samples.size) {
            val len = (samples.size - offset).coerceAtMost(chunk)
            val written = track.write(samples, offset, len, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                track.release()
                error("AudioTrack.write(float) failed: $written")
            }
            offset += written
        }
        val playMs = (samples.size * 1000L) / sampleRate + 250L
        delay(playMs.coerceAtLeast(50L))
        track.stop()
        track.release()
        diagnostics("piper_play_done float ms=${System.currentTimeMillis() - tPlay} waited_ms=$playMs")
    }

    private suspend fun playPcm16Drain(
        samples: ShortArray,
        sampleRate: Int,
        diagnostics: (String) -> Unit,
    ) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(samples.size * 2)
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        val track = AudioTrack(
            attr,
            format,
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.play()
        val tPlay = System.currentTimeMillis()
        var offset = 0
        val chunk = 4096
        while (offset < samples.size) {
            val len = (samples.size - offset).coerceAtMost(chunk)
            val written = track.write(samples, offset, len)
            if (written < 0) {
                track.release()
                error("AudioTrack.write(short) failed: $written")
            }
            offset += written
        }
        val playMs = (samples.size * 1000L) / sampleRate + 250L
        delay(playMs.coerceAtLeast(50L))
        track.stop()
        track.release()
        diagnostics("piper_play_done pcm16 ms=${System.currentTimeMillis() - tPlay} waited_ms=$playMs")
    }

    private fun floatToPcm16(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }

    private companion object {
        const val ASSET_ROOT = "tts/piper"
        const val MODEL_EN_DIR = "vits-piper-en_US-amy-low"
        const val MODEL_DE_DIR = "vits-piper-de_DE-thorsten-medium"
        const val WORK_DIR = "tts_piper_work"

        private fun copyAssetDirRecursive(am: AssetManager, assetPath: String, dest: File) {
            val list = am.list(assetPath)
            if (list != null && list.isNotEmpty()) {
                dest.mkdirs()
                for (name in list) {
                    copyAssetDirRecursive(am, "$assetPath/$name", File(dest, name))
                }
            } else {
                dest.parentFile?.mkdirs()
                am.open(assetPath).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
