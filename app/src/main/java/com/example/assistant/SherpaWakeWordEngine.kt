package com.example.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline Russian speech recognition. The model is bundled in the APK and copied
 * to private app storage on first start. Microphone capture remains on the known-working MIC/8 kHz path.
 */
class SherpaWakeWordEngine(context: Context) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    private val modelDir = File(appContext.filesDir, MODEL_DIR)

    fun start(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                ensureModel()
                if (!running.get()) return@Thread
                val tokens = File(modelDir, "tokens.txt")
                val model = File(modelDir, "model.onnx")
                check(model.isFile && tokens.isFile) { "Русская модель Sherpa-ONNX не найдена" }

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
                audio.startRecording()
                val pcm = ShortArray(FRAME_SAMPLES)
                val floats = FloatArray(FRAME_SAMPLES)

                while (running.get()) {
                    val n = audio.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    for (i in 0 until n) floats[i] = pcm[i] / 32768.0f
                    s.acceptWaveform(if (n == floats.size) floats else floats.copyOf(n), SAMPLE_RATE)
                    while (r.isReady(s)) r.decode(s)
                    if (r.isEndpoint(s)) {
                        val text = r.getResult(s).text.trim()
                        if (text.isNotEmpty()) onText(text)
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

    /** Copies the model bundled in APK assets. No runtime model download is attempted. */
    private fun ensureModel() {
        val model = File(modelDir, "model.onnx")
        val tokens = File(modelDir, "tokens.txt")
        if (model.isFile && model.length() > 10_000_000L && tokens.isFile) return

        val assetNames = appContext.assets.list(ASSET_DIR)?.toSet().orEmpty()
        check("model.onnx" in assetNames && "tokens.txt" in assetNames) {
            "В APK нет встроенной русской модели распознавания (assets/wake-model)"
        }

        val tempDir = File(appContext.filesDir, "$MODEL_DIR.tmp")
        tempDir.deleteRecursively()
        check(tempDir.mkdirs() || tempDir.isDirectory) { "Не удалось создать каталог модели" }
        try {
            for (name in listOf("model.onnx", "tokens.txt")) {
                appContext.assets.open("$ASSET_DIR/$name").use { input ->
                    File(tempDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            check(File(tempDir, "model.onnx").length() > 10_000_000L) {
                "Встроенная модель повреждена или скопирована не полностью"
            }
            check(File(tempDir, "tokens.txt").isFile) { "Во встроенной модели отсутствует tokens.txt" }
            modelDir.deleteRecursively()
            check(tempDir.renameTo(modelDir)) { "Не удалось сохранить файлы встроенной модели" }
        } finally {
            tempDir.deleteRecursively()
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
