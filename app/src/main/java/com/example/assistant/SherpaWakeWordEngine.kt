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

    /** Наушники (проводные или Bluetooth): эха из динамика почти нет, перебивать можно любой фразой. */
    private fun headsetConnected(): Boolean = try {
        val am = appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
    } catch (_: Exception) { false }

    /** Слушать поверх речи ассистента, чтобы его можно было перебить голосом (режим диалога). */
    @Volatile var bargeInEnabled = false

    fun start(onText: (String) -> Unit, onError: (String) -> Unit, onStatus: (String) -> Unit = {}, onPartial: (String) -> Unit = {}, onAudioLevel: (Int) -> Unit = {}, onBargeIn: (String) -> Unit = {}) {
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
                        // Конец фразы: после слов — пауза из настроек (по умолчанию 1 с вместо 1,4 с); тишина без слов — 2 с; не дольше 20 с
                        endpointConfig = com.k2fsa.sherpa.onnx.EndpointConfig(
                            rule1 = com.k2fsa.sherpa.onnx.EndpointRule(false, 2.0f, 0.0f),
                            rule2 = com.k2fsa.sherpa.onnx.EndpointRule(true, AppSettings.int(appContext, AppSettings.END_SILENCE_MS) / 1000f, 0.0f),
                            rule3 = com.k2fsa.sherpa.onnx.EndpointRule(false, 0.0f, 20.0f)
                        ),
                        enableEndpoint = true,
                        decodingMethod = "greedy_search"
                    )
                )
                recognizer = r
                val s = r.createStream()
                stream = s

                // Телефоны по-разному открывают микрофон: пробуем источники и частоты по очереди и берём первую
                // комбинацию, которая реально пишет звук. Если частота не 8 кГц, звук понижается до 8 кГц.
                val failures = mutableListOf<String>()
                var audio: AudioRecord? = null
                var captureRate = SAMPLE_RATE
                var chosenSource = -1
                // MIC первым: на телефоне пользователя (Redmi) источник VOICE_RECOGNITION открывается, но звук не распознаётся
                // (это подтвердила ветка fix/mic-only-preserve-settings). Остальные источники — запасные.
                val sources = listOf(
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT
                ).distinct()
                search@ for (source in sources) {
                    for (rate in AudioResampler.RATES) {
                        val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        if (minBytes <= 0) {
                            failures += "${rate}Гц/источник $source: размер буфера $minBytes"
                            continue
                        }
                        val frame = AudioResampler.frameSamples(rate)
                        var candidate: AudioRecord? = null
                        try {
                            candidate = AudioRecord(
                                source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                                maxOf(minBytes * 2, frame * 2 * 2)
                            )
                            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                                failures += "${rate}Гц/источник $source: не открылся"
                                candidate.release()
                                continue
                            }
                            candidate.startRecording()
                            // Проверяем, что звук действительно идёт: первое чтение должно вернуть данные
                            val probe = ShortArray(frame)
                            val got = if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                candidate.read(probe, 0, probe.size, AudioRecord.READ_BLOCKING)
                            } else -1
                            if (got <= 0) {
                                failures += "${rate}Гц/источник $source: нет данных ($got)"
                                try { candidate.stop() } catch (_: Exception) {}
                                candidate.release()
                                continue
                            }
                            audio = candidate
                            captureRate = rate
                            chosenSource = source
                            Log.i(TAG, "Микрофон открыт: ${rate} Гц, источник $source")
                            break@search
                        } catch (e: Exception) {
                            failures += "${rate}Гц/источник $source: ${e.javaClass.simpleName}"
                            try { candidate?.release() } catch (_: Exception) {}
                        }
                    }
                }
                val mic = audio ?: error(
                    "Не удалось запустить микрофон. Проверь разрешение на микрофон и закрой приложения, которые пишут звук. " +
                        "Что пробовал: " + failures.takeLast(6).joinToString("; ")
                )
                recorder = mic
                // Reduce speaker/music bleed where the phone exposes these effects.
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(mic.audioSessionId)?.apply { enabled = true }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Echo cancellation is unavailable", e)
                }
                try {
                    // Шумоподавитель телефона вырезает шёпот, поэтому в режиме шёпота он выключен
                    if (NoiseSuppressor.isAvailable() && !AppSettings.bool(appContext, AppSettings.WHISPER, true)) {
                        noiseSuppressor = NoiseSuppressor.create(mic.audioSessionId)?.apply { enabled = true }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Noise suppression is unavailable", e)
                }
                val sourceName = when (chosenSource) {
                    MediaRecorder.AudioSource.MIC -> "MIC"
                    MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                    else -> "DEFAULT"
                }
                // Источник и частота видны в уведомлении: по ним ясно, как именно открылся микрофон на этом телефоне
                onStatus("Микрофон активен ($sourceName, $captureRate Гц). Ожидаю слово «${WakeWordService.phraseTitle(appContext)}»…")
                var wakeWordPartialSent = false
                var lastPartial = ""
                var mutedBySpeech = false
                var bargeActive = false
                var bargeSpoken = ""
                var echoListening = false
                var speakerOnly = true
                val gain = MicGain(
                    AppSettings.int(appContext, AppSettings.MIC_GAIN).toFloat(),
                    AppSettings.bool(appContext, AppSettings.WHISPER, true)
                )
                var framesSinceLevel = 0
                val pcm = ShortArray(AudioResampler.frameSamples(captureRate))

                while (running.get()) {
                    val n = mic.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    framesSinceLevel++
                    if (framesSinceLevel >= 10) {
                        var sum = 0L
                        for (i in 0 until n) sum += kotlin.math.abs(pcm[i].toInt())
                        val level = if (n > 0) ((sum.toDouble() / n / 32768.0) * 100.0).toInt().coerceIn(0, 100) else 0
                        onAudioLevel(level)
                        framesSinceLevel = 0
                    }
                    // Пока говорит сам ассистент, его голос не должен попасть в распознавание.
                    // В режиме перебивания слушаем и во время речи, но реагируем только на чужие слова (см. BargeIn).
                    if (Speaker.isBusy() && !bargeActive) {
                        if (bargeInEnabled && Speaker.isSpeaking()) {
                            if (!echoListening) { echoListening = true; speakerOnly = !headsetConnected() }
                            val echo = AudioResampler.toModel(pcm, n, captureRate)
                            gain.process(echo)
                            if (echo.isNotEmpty()) s.acceptWaveform(echo, SAMPLE_RATE)
                            while (r.isReady(s)) r.decode(s)
                            val heard = r.getResult(s).text.trim()
                            if (heard.isNotBlank() && BargeIn.isUserSpeech(heard, Speaker.currentText, WakeWord.target, onlyStopWords = speakerOnly)) {
                                bargeActive = true
                                bargeSpoken = Speaker.currentText
                                mutedBySpeech = false
                                lastPartial = heard
                                wakeWordPartialSent = true // слово активации в перебивании не запускает новую команду отдельно
                                onBargeIn(heard)
                            } else if (r.isEndpoint(s)) {
                                r.reset(s) // это было эхо ассистента
                            }
                        }
                        if (!bargeActive) {
                            mutedBySpeech = true
                            if (!Speaker.isSpeaking()) echoListening = false
                            continue
                        }
                        continue
                    }
                    if (mutedBySpeech) {
                        mutedBySpeech = false
                        echoListening = false
                        r.reset(s)
                        lastPartial = ""
                        wakeWordPartialSent = false
                    }
                    val floats = AudioResampler.toModel(pcm, n, captureRate)
                    gain.process(floats)
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
                    if (r.isEndpoint(s) && bargeActive) {
                        // Конец фразы, которой пользователь перебил ассистента: убираем эхо и стоп-слова в начале
                        val text = BargeIn.stripEcho(r.getResult(s).text.trim(), bargeSpoken, WakeWord.target)
                        bargeActive = false
                        echoListening = false
                        wakeWordPartialSent = false
                        lastPartial = ""
                        r.reset(s)
                        if (text.isNotBlank()) onText(text)
                        continue
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
