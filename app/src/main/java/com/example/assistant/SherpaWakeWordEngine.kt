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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

                // Проверяем комбинации частоты и источника. На некоторых прошивках AudioRecord
                // может создаться, но startRecording() всё равно завершается ошибкой.
                val failures = mutableListOf<String>()
                var selected: Pair<Int, AudioRecord>? = null
                val sources = listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
                ).distinct()
                val rates = listOf(SAMPLE_RATE, 16000, 22050, 44100, 48000).distinct()
                search@ for (source in sources) {
                    for (rate in rates) {
                        val minBytes = AudioRecord.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBytes <= 0) {
                            failures += "${rate}Hz/source=$source: minBuffer=$minBytes"
                            Log.w(TAG, failures.last())
                            continue
                        }
                        val frameSamples = rate / 10
                        var candidate: AudioRecord? = null
                        try {
                            candidate = AudioRecord(
                                source,
                                rate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                maxOf(minBytes * 2, frameSamples * 2)
                            )
                            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                                failures += "${rate}Hz/source=$source: not initialized"
                                candidate.release()
                                continue
                            }
                            candidate.startRecording()
                            if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                failures += "${rate}Hz/source=$source: not recording after start"
                                try { candidate.stop() } catch (_: Exception) {}
                                candidate.release()
                                continue
                            }
                            selected = rate to candidate
                            Log.i(TAG, "Microphone opened: rate=$rate, source=$source")
                            break@search
                        } catch (e: Exception) {
                            failures += "${rate}Hz/source=$source: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
                            Log.w(TAG, "AudioRecord start failed: rate=$rate source=$source", e)
                            try { candidate?.release() } catch (_: Exception) {}
                        }
                    }
                }
                val audioConfig = selected ?: error(
                    "Не удалось запустить микрофон. Проверь разрешение на микрофон и закрой другие приложения, использующие запись. " +
                        "Диагностика: " + failures.takeLast(8).joinToString("; ")
                )
                val captureRate = audioConfig.first
                val audio = audioConfig.second
                val captureFrameSamples = captureRate / 10
                onStatus(if (captureRate == SAMPLE_RATE) {
                    "Микрофон открыт на 8 кГц"
                } else {
                    "Микрофон открыт на $captureRate Гц; преобразую звук в 8 кГц"
                })
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
                onStatus("Микрофон активен. Ожидаю слово «${WakeWordService.phraseTitle(appContext)}»…")
                var wakeWordPartialSent = false
                var lastPartial = ""
                var framesSinceLevel = 0
                val pcm = ShortArray(captureFrameSamples)
                
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
                    val floats = resampleToModelRate(pcm, n, captureRate)
                    if (floats.isNotEmpty()) s.acceptWaveform(floats, SAMPLE_RATE)
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

    /**
     * Преобразует PCM-захват в частоту, ожидаемую моделью (8 кГц).
     * Входной буфер — короткий блок примерно 100 мс, поэтому линейной интерполяции достаточно.
     */
    private fun resampleToModelRate(pcm: ShortArray, length: Int, inputRate: Int): FloatArray {
        if (length <= 0) return FloatArray(0)
        if (inputRate == SAMPLE_RATE) {
            return FloatArray(length) { i -> pcm[i] / 32768.0f }
        }
        val outputLength = (length.toDouble() * SAMPLE_RATE / inputRate).toInt().coerceAtLeast(1)
        return FloatArray(outputLength) { i ->
            val sourcePosition = i.toDouble() * inputRate / SAMPLE_RATE
            val left = sourcePosition.toInt().coerceIn(0, length - 1)
            val right = (left + 1).coerceAtMost(length - 1)
            val fraction = (sourcePosition - left).toFloat()
            val a = pcm[left].toFloat()
            val b = pcm[right].toFloat()
            (a + (b - a) * fraction) / 32768.0f
        }
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
        if (File(modelDir, "model.onnx").isFile && File(modelDir, "tokens.txt").isFile) return
        if (copyBundledModel(onStatus)) return
        val archive = File(appContext.cacheDir, "russian-t-one-model.tar.bz2")
        val tempDir = File(appContext.filesDir, "$MODEL_DIR.tmp")
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        onStatus("Загрузка модели распознавания (около 140 МБ)…")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Не удалось скачать русскую модель (HTTP ${connection.responseCode})" }
            connection.inputStream.use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }

        onStatus("Распаковка модели…")
        try {
            BufferedInputStream(archive.inputStream()).use { buffered ->
                BZip2CompressorInputStream(buffered).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val name = File(entry.name).name
                            if (!entry.isDirectory && (name == "model.onnx" || name == "tokens.txt" || name == "LICENSE")) {
                                val out = File(tempDir, name)
                                out.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = tar.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
            check(File(tempDir, "model.onnx").length() > 10_000_000L) { "Файл модели скачан не полностью" }
            check(File(tempDir, "tokens.txt").isFile) { "В архиве нет tokens.txt" }
            modelDir.deleteRecursively()
            check(tempDir.renameTo(modelDir)) { "Не удалось сохранить файлы модели" }
        } finally {
            archive.delete()
            tempDir.deleteRecursively()
        }
    }

    companion object {
        private const val TAG = "SherpaWakeWord"
        private const val SAMPLE_RATE = 8000
        private const val FRAME_SAMPLES = 800
        private const val ASSET_DIR = "wake-model"
        private const val MODEL_DIR = "sherpa-onnx-streaming-t-one-russian-2025-09-08"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-t-one-russian-2025-09-08.tar.bz2"
    }
}
