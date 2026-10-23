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

    private fun ensureModel() {
        if (File(modelDir, "model.onnx").isFile && File(modelDir, "tokens.txt").isFile) return
        val archive = File(appContext.cacheDir, "russian-t-one-model.tar.bz2")
        val tempDir = File(appContext.filesDir, "$MODEL_DIR.tmp")
        tempDir.deleteRecursively()
        tempDir.mkdirs()
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
