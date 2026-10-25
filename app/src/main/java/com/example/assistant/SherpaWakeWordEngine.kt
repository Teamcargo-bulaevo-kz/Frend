package com.example.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fully local microphone recognition. The model is downloaded once (about 140 MB)
 * and then reused offline. The Russian T-One model is streaming and runs at 8 kHz.
 */
class SherpaWakeWordEngine(context: Context) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    private val modelDir = File(appContext.filesDir, MODEL_DIR)

    fun start(onText: (String) -> Unit, onError: (String) -> Unit, onStatus: (String) -> Unit = {}, onPartial: (String) -> Unit = {}, onAudioLevel: (Int) -> Unit = {}) {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                onStatus("Подготовка русской модели…")
                ensureModel(onStatus)
                if (!running.get()) return@Thread
                val tokens = File(modelDir, "tokens.txt")
                val model = File(modelDir, "model.onnx")
                check(model.isFile && tokens.isFile) { "Русская модель Sherpa-ONNX не найдена" }

                onStatus("Модель загружена. Инициализация распознавания…")
                val r = OnlineRecognizer(
                    config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            toneCtc = OnlineToneCtcModelConfig(model = model.absolutePath),
                            tokens = tokens.absolutePath,
                            numThreads = 2,
                            provider = "cpu"
                        ),
                        enableEndpoint = true,
                        decodingMethod = "greedy_search"
                    )
                )
                recognizer = r
                val s = r.createStream()
                stream = s

                val minBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                check(minBytes > 0) { "Телефон не поддерживает запись аудио 8 кГц" }
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBytes * 2, FRAME_SAMPLES * 2)
                )
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон" }
                recorder = audio
                // Reduce speaker/music bleed where the phone exposes these effects.
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Echo cancellation is unavailable", e)
                }
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Noise suppression is unavailable", e)
                }
                audio.startRecording()
                onStatus("Микрофон активен. Ожидаю слово «${WakeWordService.phraseTitle(appContext)}»…")
                var wakeWordPartialSent = false
                var lastPartial = ""
                var framesSinceLevel = 0
                val pcm = ShortArray(FRAME_SAMPLES)
                val floats = FloatArray(FRAME_SAMPLES)

                while (running.get()) {
                    val n = audio.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    framesSinceLevel++
                    if (framesSinceLevel >= 10) {
                        var sum = 0L
                        for (i in 0 until n) sum += kotlin.math.abs(pcm[i].toInt())
                        val level = if (n > 0) ((sum.toDouble() / n / 32768.0) * 100.0).toInt().coerceIn(0, 100) else 0
                        onAudioLevel(level)
                        framesSinceLevel = 0
                    }
                    for (i in 0 until n) floats[i] = pcm[i] / 32768.0f
                    s.acceptWaveform(if (n == floats.size) floats else floats.copyOf(n), SAMPLE_RATE)
                    while (r.isReady(s)) r.decode(s)
                    // Streaming recognizers can identify the wake word before an endpoint.
                    // Emit a bare wake word immediately; commands are emitted only at endpoint.
                    val partial = r.getResult(s).text.trim()
                    if (partial.isNotBlank() && partial != lastPartial) {
                        lastPartial = partial
                        onPartial(partial)
                    }
                    if (!wakeWordPartialSent && WakeWord.commandAfter(partial) == "" && partial.isNotBlank()) {
                        wakeWordPartialSent = true
                        onText(partial)
                    }
                    if (r.isEndpoint(s)) {
                        val text = r.getResult(s).text.trim()
                        val command = WakeWord.commandAfter(text)
                        if (text.isNotEmpty() && !(wakeWordPartialSent && command == "")) onText(text)
                        wakeWordPartialSent = false
                        lastPartial = ""
                        r.reset(s)
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    Log.e(TAG, "Offline wake-word engine failed", t)
                    onError(t.message ?: t.javaClass.simpleName)
                }
            } finally {
                running.set(false)
                try { recorder?.stop() } catch (_: Exception) {}
                recorder?.release()
                recorder = null
                try { echoCanceler?.release() } catch (_: Exception) {}
                echoCanceler = null
                try { noiseSuppressor?.release() } catch (_: Exception) {}
                noiseSuppressor = null
                stream?.release()
                stream = null
                recognizer?.release()
                recognizer = null
            }
        }, "sherpa-wake-word").start()
    }

    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
    }

    /** Модель, вшитая в APK при сборке (assets/wake-model): копируем во внутреннюю память, интернет не нужен. */
    private fun copyBundledModel(onStatus: (String) -> Unit): Boolean {
        val names = try { appContext.assets.list(ASSET_DIR)?.toSet().orEmpty() } catch (e: Exception) { emptySet() }
        if ("model.onnx" !in names || "tokens.txt" !in names) return false
        onStatus("Подготовка встроенной модели…")
        val tempDir = File(appContext.filesDir, "$MODEL_DIR.tmp")
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        try {
            for (name in listOf("model.onnx", "tokens.txt")) {
                appContext.assets.open("$ASSET_DIR/$name").use { input ->
                    File(tempDir, name).outputStream().use { input.copyTo(it) }
                }
            }
            check(File(tempDir, "model.onnx").length() > 10_000_000L) { "Встроенная модель повреждена" }
            modelDir.deleteRecursively()
            check(tempDir.renameTo(modelDir)) { "Не удалось сохранить файлы модели" }
        } finally {
            tempDir.deleteRecursively()
        }
        return true
    }

    private fun ensureModel(onStatus: (String) -> Unit) {
        val model = File(modelDir, "model.onnx")
        val tokens = File(modelDir, "tokens.txt")
        if (model.isFile && model.length() > 10_000_000L && tokens.isFile) return
        check(copyBundledModel(onStatus)) {
            "В APK нет встроенной русской модели распознавания. Скачивание модели отключено."
        }
        check(model.isFile && model.length() > 10_000_000L && tokens.isFile) {
            "Не удалось подготовить встроенную русскую модель"
        }
    }

    companion object {
        private const val TAG = "SherpaWakeWord"
        private const val SAMPLE_RATE = 8000
        private const val FRAME_SAMPLES = 800
        private const val ASSET_DIR = "wake-model"
        private const val MODEL_DIR = "sherpa-onnx-streaming-t-one-russian-2025-09-08"
    }
}
