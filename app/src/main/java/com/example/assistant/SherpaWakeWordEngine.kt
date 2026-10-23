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

    fun start(onText: (String) -> Unit, onError: (String) -> Unit, onStatus: (String) -> Unit = {}, onPartial: (String) -> Unit = {}, onAudioLevel: (Int) -> Unit = {}, onUtterance: ((String, ShortArray) -> Unit)? = null, soundGateEnabled: Boolean = true) {
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
                    // VOICE_RECOGNITION asks Android for the speech-oriented input path when available.
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord не начал запись" }
                onStatus("Микрофон активен. Ожидаю слово «" + WakeWordService.phraseTitle(appContext) + "»…")
                var wakeWordPartialSent = false
                var lastPartial = ""
                var framesSinceLevel = 0
                var speechActive = false
                var hangoverFrames = 0
                var noiseFloor = 120.0
                val preRoll = ArrayDeque<ShortArray>()
                val utteranceSamples = ArrayList<Short>(SAMPLE_RATE * 20)
                val pcm = ShortArray(FRAME_SAMPLES)
                val floats = FloatArray(FRAME_SAMPLES)
                val targetPhrase = WakeWordService.phrase(appContext)

                while (running.get()) {
                    val n = audio.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    framesSinceLevel++
                    if (framesSinceLevel >= 10) {
                        val level = ((WavEncoder.rms(pcm, n) / 32768.0) * 100.0).toInt().coerceIn(0, 100)
                        onAudioLevel(level)
                        framesSinceLevel = 0
                    }
                    val rms = WavEncoder.rms(pcm, n)
                    val voice = !soundGateEnabled || rms > maxOf(280.0, noiseFloor * 2.4)
                    if (voice) {
                        if (!speechActive) {
                            while (preRoll.isNotEmpty()) {
                                val previous = preRoll.removeFirst()
                                previous.forEach { utteranceSamples.add(it) }
                                val previousFloats = FloatArray(previous.size) { previous[it] / 32768.0f }
                                s.acceptWaveform(previousFloats, SAMPLE_RATE)
                                while (r.isReady(s)) r.decode(s)
                            }
                            speechActive = true
                        }
                        hangoverFrames = 8
                    } else if (speechActive && hangoverFrames > 0) {
                        hangoverFrames--
                    } else {
                        preRoll.addLast(pcm.copyOf(n))
                        while (preRoll.size > 3) preRoll.removeFirst()
                        noiseFloor = noiseFloor * 0.97 + rms * 0.03
                        continue
                    }
                    for (i in 0 until n) {
                        floats[i] = pcm[i] / 32768.0f
                        utteranceSamples.add(pcm[i])
                    }
                    if (utteranceSamples.size > SAMPLE_RATE * 25) {
                        utteranceSamples.subList(0, utteranceSamples.size - SAMPLE_RATE * 25).clear()
                    }
                    s.acceptWaveform(if (n == floats.size) floats else floats.copyOf(n), SAMPLE_RATE)
                    while (r.isReady(s)) r.decode(s)
                    val partial = r.getResult(s).text.trim()
                    if (partial.isNotBlank() && partial != lastPartial) { lastPartial = partial; onPartial(partial) }
                    if (!wakeWordPartialSent && WakeWord.commandAfter(partial, targetPhrase) == "" && partial.isNotBlank()) {
                        wakeWordPartialSent = true
                        onText(partial)
                    }
                    val endpoint = r.isEndpoint(s) || (soundGateEnabled && speechActive && hangoverFrames == 0)
                    if (endpoint) {
                        val text = r.getResult(s).text.trim()
                        val samples = ShortArray(utteranceSamples.size) { utteranceSamples[it] }
                        if (text.isNotEmpty()) {
                            if (onUtterance != null) onUtterance(text, samples)
                            else if (!(wakeWordPartialSent && WakeWord.commandAfter(text, targetPhrase) == "")) onText(text)
                        }
                        wakeWordPartialSent = false
                        lastPartial = ""
                        utteranceSamples.clear()
                        preRoll.clear()
                        speechActive = false
                        hangoverFrames = 0
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

    private fun ensureModel(onStatus: (String) -> Unit) {
        if (File(modelDir, "model.onnx").isFile && File(modelDir, "tokens.txt").isFile) return
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
        private const val MODEL_DIR = "sherpa-onnx-streaming-t-one-russian-2025-09-08"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-t-one-russian-2025-09-08.tar.bz2"
    }
}
