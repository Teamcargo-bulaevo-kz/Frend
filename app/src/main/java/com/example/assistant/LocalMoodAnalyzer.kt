package com.example.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.*

data class MoodAnalysis(val scores: Map<String, Int>, val energy: Int, val bpm: Int, val confidence: Int, val description: String, val valence: Int = 50, val arousal: Int = 50, val tension: Int = 50, val danceability: Int = 0, val segments: List<MoodSegment> = emptyList())
data class RepeatedFragment(val firstStartSeconds: Int, val secondStartSeconds: Int, val similarity: Int)
data class AudioFeatures(val rms: Double, val centroid: Double, val low: Double, val high: Double, val flatness: Double, val flux: Double, val beat: Double, val bpm: Int, val dynamics: Double, val count: Int, val durationSeconds: Double = 0.0, val variation: Double = 0.0, val beatAccent: Double = 0.0, val modeMajor: Double = 0.5, val tonalClarity: Double = 0.0, val attack: Double = -1.0, val onsetRate: Double = -1.0, val highAttack: Double = -1.0)

/** Локальный анализ PCM: сеть, Groq и внешние модели не используются. */
object LocalMoodAnalyzer {
    fun analyze(context: Context, uri: Uri): MoodAnalysis {
        val targetRate = 11025
        val pcm = decode(context, uri, targetRate)
        require(pcm.size >= targetRate * 3) { "Недостаточно аудио для анализа (нужно не менее 3 секунд)" }
        val wholeTrack = classify(extractFeatures(pcm, targetRate))
        val segments = analyzeSegments(pcm, targetRate)
        if (segments.isEmpty()) return wholeTrack
        val aggregate = aggregateSegmentScores(targetRate, wholeTrack, segments)
        val changes = segments.map { it.mood }.distinct()
        val trend = if (changes.size > 1) " · смена настроения: " + changes.joinToString(" → ") { MusicTags.moods[it]?.lowercase() ?: it } else ""
        return aggregate.copy(description = wholeTrack.description + " · фрагментов: " + segments.size + trend, segments = segments)
    }

    internal fun analyzeSegments(pcm: FloatArray, sampleRate: Int): List<MoodSegment> {
        val window = sampleRate * 20
        val hop = sampleRate * 10
        val minimum = sampleRate * 8
        if (pcm.size < minimum) return emptyList()
        val result = ArrayList<MoodSegment>()
        var start = 0
        while (start < pcm.size) {
            val end = min(pcm.size, start + window)
            if (end - start >= minimum) {
                val local = classify(extractFeatures(pcm.copyOfRange(start, end), sampleRate))
                result += MoodSegment(start / sampleRate, end / sampleRate, local.scores.maxByOrNull { it.value }?.key ?: "calm", local.valence, local.arousal, local.tension, local.scores)
            }
            if (end == pcm.size) break
            start += hop
        }
        return result
    }

    /** Approximate dynamic transitions between adjacent analysis windows. */
    internal fun transitionLabels(segments: List<MoodSegment>): List<String> {
        if (segments.size < 2) return emptyList()
        var lastDirection = 0
        return segments.zipWithNext().mapNotNull { (previous, current) ->
            val delta = current.arousal - previous.arousal
            val direction = when {
                delta >= 15 -> 1
                delta <= -15 -> -1
                else -> 0
            }
            if (direction == 0 || direction == lastDirection) {
                null
            } else {
                lastDirection = direction
                val at = current.startSeconds
                val time = "%d:%02d".format(at / 60, at % 60)
                if (direction > 0) "$time — нарастание энергии (+$delta)"
                else "$time — спад энергии ($delta)"
            }
        }
    }

    /** Ищет неперекрывающиеся пары окон с похожим музыкальным профилем. */
    internal fun findRepeatedFragments(segments: List<MoodSegment>, threshold: Double = 0.90): List<RepeatedFragment> {
        if (segments.size < 3 || threshold !in 0.0..1.0) return emptyList()
        val matches = ArrayList<RepeatedFragment>()
        val usedEarlier = HashSet<Int>()
        for (currentIndex in 2 until segments.size) {
            val current = segments[currentIndex]
            var bestIndex = -1
            var bestSimilarity = 0.0
            for (previousIndex in 0 until currentIndex - 1) {
                if (previousIndex in usedEarlier) continue
                val previous = segments[previousIndex]
                if (previous.endSeconds > current.startSeconds) continue
                val keys = (previous.scores.keys + current.scores.keys).toSet()
                val dot = keys.sumOf { (previous.scores[it] ?: 0).toDouble() * (current.scores[it] ?: 0).toDouble() }
                val leftNorm = sqrt(keys.sumOf { (previous.scores[it] ?: 0).toDouble().pow(2) })
                val rightNorm = sqrt(keys.sumOf { (current.scores[it] ?: 0).toDouble().pow(2) })
                val moodSimilarity = if (leftNorm > 0.0 && rightNorm > 0.0) dot / (leftNorm * rightNorm) else 0.0
                val scalarSimilarity = listOf(abs(previous.valence - current.valence), abs(previous.arousal - current.arousal), abs(previous.tension - current.tension)).map { 1.0 - it / 100.0 }.average()
                val similarity = (0.75 * moodSimilarity + 0.25 * scalarSimilarity).coerceIn(0.0, 1.0)
                if (similarity > bestSimilarity) { bestSimilarity = similarity; bestIndex = previousIndex }
            }
            if (bestIndex >= 0 && bestSimilarity >= threshold) {
                matches += RepeatedFragment(segments[bestIndex].startSeconds, current.startSeconds, (bestSimilarity * 100).roundToInt())
                usedEarlier += bestIndex
            }
        }
        return matches
    }

    /** Вес сегмента — только уникальная часть временной шкалы, без повторного учёта перекрытий. */
    internal fun segmentWeights(segments: List<MoodSegment>, durationSeconds: Int): List<Double> {
        if (segments.isEmpty()) return emptyList()
        val centers = segments.map { (it.startSeconds + it.endSeconds) / 2.0 }
        return segments.indices.map { index ->
            val left = if (index == 0) 0.0 else (centers[index - 1] + centers[index]) / 2.0
            val right = if (index == segments.lastIndex) durationSeconds.toDouble()
                else (centers[index] + centers[index + 1]) / 2.0
            (right.coerceAtMost(durationSeconds.toDouble()) - left.coerceAtLeast(0.0)).coerceAtLeast(0.0)
        }
    }

    private fun aggregateSegmentScores(sampleRate: Int, whole: MoodAnalysis, segments: List<MoodSegment>): MoodAnalysis {
        val keys = whole.scores.keys.toList()
        val weighted = DoubleArray(keys.size)
        var totalWeight = 0.0
        val durationSeconds = segments.maxOfOrNull { it.endSeconds } ?: 0
        val weights = segmentWeights(segments, durationSeconds)
        for ((index, segment) in segments.withIndex()) {
            val weight = weights[index]
            for (i in keys.indices) weighted[i] += (segment.scores[keys[i]] ?: 0) * weight
            totalWeight += weight
        }
        val blended = keys.mapIndexed { i, key -> key to ((whole.scores[key] ?: 0) * .60 + weighted[i] / totalWeight.coerceAtLeast(1.0) * .40) }
        val sum = blended.sumOf { it.second }.coerceAtLeast(1e-9)
        val exact = blended.associate { it.first to it.second / sum * 100.0 }
        val scores = exact.mapValues { floor(it.value).toInt() }.toMutableMap()
        exact.entries.sortedByDescending { it.value - floor(it.value) }.take(100 - scores.values.sum()).forEach { scores[it.key] = scores.getValue(it.key) + 1 }
        // Используем ту же уникальную временную шкалу, что и для moodScores:
        // перекрывающиеся окна не должны повторно усиливать середину композиции.
        val duration = weights.sum().coerceAtLeast(1.0)
        val valence = segments.indices.sumOf { segments[it].valence.toDouble() * weights[it] } / duration
        val arousal = segments.indices.sumOf { segments[it].arousal.toDouble() * weights[it] } / duration
        val tension = segments.indices.sumOf { segments[it].tension.toDouble() * weights[it] } / duration
        return whole.copy(scores = scores, valence = valence.roundToInt(), arousal = arousal.roundToInt(), tension = tension.roundToInt(), segments = segments)
    }
    private fun decode(context: Context, uri: Uri, targetRate: Int): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        // Ограничиваем память: до четырёх минут моно PCM 11,025 кГц.
        val out = FloatArray(targetRate * 240)
        var outCount = 0
        var sourceFrames = 0L
        var nextSampleFrame = 0.0
        try {
            extractor.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    extractor.selectTrack(i); format = f; break
                }
            }
            val input = format ?: error("Аудиодорожка не найдена")
            codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(input, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            while (!outputDone && outCount < out.size) {
                if (!inputDone) {
                    val ix = codec.dequeueInputBuffer(10_000)
                    if (ix >= 0) {
                        val b = codec.getInputBuffer(ix)!!; b.clear()
                        if (extractor.sampleTime < 0) {
                            codec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true
                        } else {
                            val size=extractor.readSampleData(b,0)
                            if(size<0){codec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                            else {codec.queueInputBuffer(ix,0,size,extractor.sampleTime,extractor.sampleFlags);extractor.advance()}
                        }
                    }
                }
                when(val ix=codec.dequeueOutputBuffer(info,10_000)){
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{channels=codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1,8);rate=codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)}
                    MediaCodec.INFO_TRY_AGAIN_LATER->Unit
                    else->if(ix>=0){
                        val b=codec.getOutputBuffer(ix)
                        if(b!=null&&info.size>0){
                            b.position(info.offset);b.limit(info.offset+info.size)
                            val enc=if(codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val isFloat=enc==AudioFormat.ENCODING_PCM_FLOAT
                            val v=b.slice().order(ByteOrder.nativeOrder())
                            val bytes=if(isFloat)4 else 2
                            val frames=info.size/bytes/channels
                            for(frame in 0 until frames){
                                var sample=0.0
                                repeat(channels){sample+=if(isFloat)v.getFloat().toDouble() else v.getShort().toDouble()/32768.0}
                                val mono=(sample/channels).toFloat().coerceIn(-1f,1f)
                                val absoluteFrame=sourceFrames+frame
                                if(absoluteFrame+1e-9>=nextSampleFrame && outCount<out.size){
                                    out[outCount++]=mono
                                    nextSampleFrame += rate.toDouble()/targetRate
                                }
                                if(outCount>=out.size)break
                            }
                            sourceFrames += frames
                        }
                        outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        codec.releaseOutputBuffer(ix,false)
                    }
                }
            }
        } finally {
            try{codec?.stop()}catch(_:Exception){}
            try{codec?.release()}catch(_:Exception){}
            extractor.release()
        }
        return out.copyOf(outCount)
    }

    fun extractFeatures(x: FloatArray, sampleRate: Int): AudioFeatures {
        val n=1024;val hop=512
        val env=ArrayList<Double>();val centers=ArrayList<Double>();val lows=ArrayList<Double>();val highs=ArrayList<Double>();val flats=ArrayList<Double>();val fluxes=ArrayList<Double>();val attacks=ArrayList<Double>();val hiAttacks=ArrayList<Double>()
        var sumSq=0.0;var prev=DoubleArray(n/2+1)
        for(v in x)sumSq+=v.toDouble()*v
        var pos=0
        while(pos+n<=x.size){
            val re=DoubleArray(n){j->x[pos+j]*(.5-.5*cos(2*PI*j/(n-1)))};val im=DoubleArray(n)
            env.add(sqrt(re.sumOf{it*it}/n))
            var j=0
            for(i in 1 until n){var bit=n shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val t=re[i];re[i]=re[j];re[j]=t}}
            var len=2
            while(len<=n){val a=-2*PI/len;val wr0=cos(a);val wi0=sin(a);var base=0
                while(base<n){var wr=1.0;var wi=0.0
                    for(k in 0 until len/2){val e=base+k;val o=e+len/2;val tr=wr*re[o]-wi*im[o];val ti=wr*im[o]+wi*re[o];re[o]=re[e]-tr;im[o]=im[e]-ti;re[e]+=tr;im[e]+=ti;val nw=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=nw}
                    base+=len
                };len*=2
            }
            var pwr=0.0;var weighted=0.0;var low=0.0;var high=0.0;var log=0.0;var count=0;var flux=0.0;var magSum=0.0;var hiFlux=0.0
            for(bin in 1..n/2){val p=re[bin]*re[bin]+im[bin]*im[bin];val hz=bin.toDouble()*sampleRate/n;pwr+=p;weighted+=hz*p;if(hz<250)low+=p;if(hz>4000)high+=p;if(p>1e-15){log+=ln(p);count++};val mag=sqrt(p);magSum+=mag;val d=max(0.0,mag-prev[bin]);flux+=d;if(hz>=2000)hiFlux+=d;prev[bin]=mag}
            centers.add(if(pwr>0)weighted/pwr else 0.0);lows.add(if(pwr>0)low/pwr else 0.0);highs.add(if(pwr>0)high/pwr else 0.0)
            val ar=pwr/(n/2);flats.add(if(count>0&&ar>0)exp(log/count)/ar else 0.0);fluxes.add(flux);if(pos>0&&magSum>1e-9){attacks.add(flux/magSum);hiAttacks.add(hiFlux/magSum)};pos+=hop
        }
        val sorted=env.sorted();val median=if(sorted.isEmpty())0.0 else sorted[sorted.size/2]
        val dynamics=if(median>1e-8)((env.maxOrNull()?:median)/median).coerceAtMost(12.0)else 1.0
        // Спектральный flux реагирует на атаки и удары точнее, чем общая громкость RMS.
        val fps = sampleRate.toDouble() / hop
        val beat=detectBeat(fluxes,fps)
        val accent = estimateBeatAccent(fluxes, fps, beat.second)
        val tonal = estimateMode(x, sampleRate)
        val meanEnv=env.average().coerceAtLeast(1e-9)
        val variation=if(env.isNotEmpty())sqrt(env.map{(it-meanEnv)*(it-meanEnv)}.average())/meanEnv else 0.0
        return AudioFeatures(sqrt(sumSq/max(1,x.size)),centers.average().finiteOrZero(),lows.average().finiteOrZero(),highs.average().finiteOrZero(),flats.average().finiteOrZero(),fluxes.average().finiteOrZero(),beat.first,beat.second,dynamics,x.size,x.size.toDouble()/sampleRate,variation.finiteOrZero(),accent,tonal.first,tonal.second,if(attacks.isEmpty())-1.0 else attacks.average().finiteOrZero(),estimateOnsetRate(fluxes,fps),if(hiAttacks.isEmpty())-1.0 else hiAttacks.average().finiteOrZero())
    }
    private fun Double.finiteOrZero()=if(isFinite())this else 0.0

    /**
     * Число атак звука в секунду (плотность нот, в исследованиях это «темп»): пики спектрального роста,
     * заметно превышающие среднее вокруг себя, не чаще чем раз в 0,12 с. У спокойной музыки около 1-2,
     * у бодрой с барабанами 4 и больше.
     */
    internal fun estimateOnsetRate(flux: List<Double>, fps: Double): Double {
        if (flux.size < 12 || fps <= 0) return -1.0
        val half = (fps * 0.5).roundToInt().coerceAtLeast(3)
        val minGap = (fps * 0.12).roundToInt().coerceAtLeast(1)
        val prefix = DoubleArray(flux.size + 1)
        for (i in flux.indices) prefix[i + 1] = prefix[i] + flux[i]
        var count = 0
        var last = -minGap
        for (i in 1 until flux.size - 1) {
            val v = flux[i]
            if (v <= flux[i - 1] || v < flux[i + 1]) continue
            val from = max(0, i - half)
            val to = min(flux.size, i + half + 1)
            val mean = (prefix[to] - prefix[from]) / (to - from)
            if (v > 1e-9 && v > mean * 1.5 && i - last >= minGap) { count++; last = i }
        }
        return count / (flux.size / fps)
    }

    // Профили тональности Крумхансла-Кесслера (интенсивность 12 нот относительно тоники).
    private val KEY_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val KEY_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr0 = cos(angle); val wi0 = sin(angle)
            var base = 0
            while (base < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val e = base + k; val o = e + len / 2
                    val tr = wr * re[o] - wi * im[o]; val ti = wr * im[o] + wi * re[o]
                    re[o] = re[e] - tr; im[o] = im[e] - ti; re[e] += tr; im[e] += ti
                    val nw = wr * wr0 - wi * wi0; wi = wr * wi0 + wi * wr0; wr = nw
                }
                base += len
            }
            len *= 2
        }
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { val da = a[i] - ma; val db = b[i] - mb; ab += da * db; aa += da * da; bb += db * db }
        return if (aa > 1e-12 && bb > 1e-12) ab / sqrt(aa * bb) else 0.0
    }

    /**
     * Мажор или минор по хромаграмме (энергия 12 нот в диапазоне 130-2000 Гц) и профилям тональности.
     * Возвращает (мажорность 0..1, где 0.5 неясно; ясность тональности 0..1). У шума и ударных без мелодии
     * ясность около нуля, и ладовая поправка в classify почти не действует.
     */
    internal fun estimateMode(x: FloatArray, sampleRate: Int): Pair<Double, Double> {
        val n = 4096
        val hop = 8192
        if (x.size < n * 3 || sampleRate <= 0) return 0.5 to 0.0
        val pitchClass = IntArray(n / 2 + 1) { bin ->
            val hz = bin.toDouble() * sampleRate / n
            if (hz < 130.0 || hz > 2000.0) -1 else ((12.0 * ln(hz / 440.0) / ln(2.0)).roundToInt() + 9).mod(12)
        }
        val window = DoubleArray(n) { .5 - .5 * cos(2 * PI * it / (n - 1)) }
        val chroma = DoubleArray(12)
        val re = DoubleArray(n); val im = DoubleArray(n)
        var pos = 0
        while (pos + n <= x.size) {
            for (j in 0 until n) { re[j] = x[pos + j] * window[j]; im[j] = 0.0 }
            fftInPlace(re, im)
            for (bin in 1..n / 2) {
                val pc = pitchClass[bin]
                if (pc >= 0) chroma[pc] += sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            }
            pos += hop
        }
        if (chroma.sum() <= 1e-9) return 0.5 to 0.0
        var bestMajor = -1.0; var bestMinor = -1.0
        for (tonic in 0 until 12) {
            bestMajor = max(bestMajor, pearson(chroma, DoubleArray(12) { KEY_MAJOR[(it - tonic + 12) % 12] }))
            bestMinor = max(bestMinor, pearson(chroma, DoubleArray(12) { KEY_MINOR[(it - tonic + 12) % 12] }))
        }
        val major = (0.5 + (bestMajor - bestMinor) * 1.2).coerceIn(0.0, 1.0)
        val clarity = ((max(bestMajor, bestMinor) - 0.45) / 0.45).coerceIn(0.0, 1.0)
        return major to clarity
    }

    /**
     * Оценивает акцент сильной доли относительно слабой по спектральным атакам.
     * Это относительная выраженность пульса, не распознавание размера такта.
     */
    internal fun estimateBeatAccent(onsets: List<Double>, fps: Double, bpm: Int): Double {
        if (bpm !in 60..180 || onsets.size < 24 || fps <= 0.0) return 0.0
        val lag = (fps * 60.0 / bpm).roundToInt().coerceAtLeast(2)
        val half = (lag / 2).coerceAtLeast(1)
        if (onsets.size < lag * 3) return 0.0
        val mean = onsets.average()
        val centered = onsets.map { (it - mean).coerceAtLeast(0.0) }
        var best = 0.0
        for (phase in 0 until lag) {
            var strong = 0.0
            var weak = 0.0
            var count = 0
            var i = phase
            while (i < centered.size) {
                strong += centered[i]
                val weakIndex = i + half
                if (weakIndex < centered.size) weak += centered[weakIndex]
                count++
                i += lag
            }
            if (count > 0) {
                val contrast = (strong / count - weak / count) /
                    (strong / count + weak / count + 1e-9)
                best = max(best, contrast)
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    /** Ищет максимум корреляции рядом с выбранным темпом (+-8 %) с шагом 0,5 BPM и дробным сдвигом. */
    private fun refineTempo(x: List<Double>, fps: Double, bpm: Int): Int {
        if (bpm <= 0) return bpm
        var bestBpm = bpm.toDouble()
        var bestCorr = -2.0
        var candidate = bpm * 0.92
        val end = bpm * 1.08
        while (candidate <= end) {
            val lag = fps * 60.0 / candidate
            var cross = 0.0; var aa = 0.0; var bb = 0.0
            for (i in ceil(lag).toInt() until x.size) {
                val pos = i - lag
                val i0 = floor(pos).toInt()
                val frac = pos - i0
                val past = x[i0] * (1.0 - frac) + x[min(i0 + 1, x.size - 1)] * frac
                cross += x[i] * past; aa += x[i] * x[i]; bb += past * past
            }
            val corr = if (aa > 0 && bb > 0) cross / sqrt(aa * bb) else 0.0
            if (corr > bestCorr) { bestCorr = corr; bestBpm = candidate }
            candidate += 0.5
        }
        return bestBpm.roundToInt().coerceIn(60, 180)
    }

    private fun detectBeat(onsets:List<Double>,fps:Double):Pair<Double,Int>{
        if(onsets.size<80)return 0.0 to 0
        // Нормируем onset envelope: отдельные громкие удары не должны доминировать над всей записью.
        val mean=onsets.average(); val variance=onsets.map{(it-mean)*(it-mean)}.average()
        if (variance < 1e-12) return 0.0 to 0
        val scale=sqrt(variance); val x=onsets.map{((it-mean)/scale).coerceIn(-4.0,4.0)}
        var best=0.0;var bpm=0
        val correlations = ArrayList<Pair<Int, Double>>()
        for(candidate in 60..180){val lag=(fps*60/candidate).roundToInt();var cross=0.0;var aa=0.0;var bb=0.0
            for(i in lag until x.size){cross+=x[i]*x[i-lag];aa+=x[i]*x[i];bb+=x[i-lag]*x[i-lag]}
            val corr=if(aa>0&&bb>0)cross/sqrt(aa*bb)else 0.0
            correlations += candidate to corr
            if(corr>best){best=corr;bpm=candidate}
        }
        // Убираем гармонические дубли: один и тот же пульс часто даёт пики на половинном
        // и двойном темпе. Сначала выбираем устойчивую музыкальную область, а не максимум BPM.
        val byTempo = correlations.associate { it.first to it.second }
        // Если сильный пик приходится на половинный темп, проверяем удвоенный:
        // у регулярных ударов автокорреляция часто одинаково видна на каждом втором бите.
        val octaveCorrected = when {
            bpm <= 90 && bpm * 2 in 80..180 && (byTempo[bpm * 2] ?: 0.0) >= best * 0.28 -> bpm * 2
            bpm >= 120 && bpm / 2 in 60..90 && (byTempo[bpm / 2] ?: 0.0) >= best * 0.92 -> bpm / 2
            else -> bpm
        }
        // Не притягиваем результат к 120 BPM: выбираем фактический максимум
        // корреляции. Иначе широкий набор близких пиков систематически смещал
        // медленные и быстрые композиции к условно «среднему» темпу.
        // Автокорреляция по целым кадрам даёт шаг 2-5 % (120 BPM выходило как 113): уточняем темп дробным сдвигом.
        val selected = refineTempo(x, fps, octaveCorrected)
        // Слабая корреляция — это отсутствие надёжного ритмического пульса, а не случайный BPM.
        val reliableTempo = if (best >= 0.12) selected else 0
        return best.coerceIn(0.0,1.0) to reliableTempo
    }

    /** Процентное распределение оценок: сумма ровно 100. Это не медицинская и не объективная вероятность. */
    fun classify(f:AudioFeatures):MoodAnalysis{
        fun u(v:Double)=if(v.isFinite())v.coerceIn(0.0,1.0)else 0.0
        // Резкость ударов: доля нового звука в спектре кадра. Не зависит от громкости записи и отличает мягкий бит
        // (около 0.02-0.08) от жёсткого (около 0.2 и выше). Без неё ровный бит выглядел бодрым: beat измеряет только регулярность.
        // Резкость: общая (доля нового звука) и в верхах 2 кГц и выше (тарелки, снейр, атаки), где у спокойной музыки почти пусто.
        val sharpAll=if(f.attack>=0)u(f.attack/.22)else u(f.flux*3)
        val sharp=if(f.highAttack>=0).5*sharpAll+.5*u(f.highAttack/.12)else sharpAll
        // Плотность атак (аналог темпа в исследованиях восприятия): 1 в секунду это спокойно, 5 и больше бодро.
        val onsetN=if(f.onsetRate>=0)u((f.onsetRate-1.0)/4.0)else sharp
        val energy=.40*u((f.rms-.02)/.18)+.20*u((f.bpm-60.0)/120)+.22*sharp+.18*u(f.centroid/6500)
        val bright=u(f.centroid/6500);val rhythm=u((f.beat-.07)/.55) * (.65 + .35 * u(f.beatAccent * 2.0))
        // Регулярный бит делает песню бодрой или весёлой только если он ещё и сильный.
        val rhythmE=rhythm*(.30+.70*u(.5*energy+.5*sharp))
        // Бодрость: мощь, плотность атак и резкость. Весёлой песня бывает только при ней: мажор и яркий тембр есть и у спокойных.
        val drive=u(.40*energy+.30*onsetN+.30*sharp);val smooth=1-u(f.flatness*2.2);val stable=1-u(f.dynamics/8)
        val dance=if(f.bpm in 100..140)1.0 else 1-min(1.0,abs(f.bpm-120)/75.0)
        // Двухосевая модель: валентность и возбуждение дополняют, но не подменяют теги настроения.
        // Лад: положительное значение это мажор, отрицательное минор; без ясной тональности остаётся около нуля.
        val modeShift=(u(f.modeMajor)-.5)*2*u(f.tonalClarity)
        val valence=u(.32*bright+.24*smooth+.18*energy+.12*(1-u(f.low*1.8))+.14*(.5+.5*modeShift))
        val arousal=u(.55*energy+.25*rhythmE+.20*u(f.variation/2.5))
        val tension=u(.35*f.flatness+.25*sharp+.22*u(f.dynamics/8)+.18*(1-smooth))
        val danceability=u(.42*dance+.30*rhythm+.18*energy+.10*u(f.beatAccent*2.0))
        val raw=linkedMapOf(
            "energetic" to (.12+.45*energy+.22*rhythmE+.21*sharp),
            "calm" to (.12+.34*(1-energy)+.14*stable+.20*(1-bright)+.12*(1-sharp)+.08*(1-onsetN)),
            "happy" to (.12+(.30*bright+.28*energy+.22*rhythmE+.08*smooth+.14*max(0.0,modeShift))*(.40+.60*drive)),
            "sad" to (.10+.38*(1-energy)+.22*(1-bright)+.18*(1-smooth)+.18*max(0.0,-modeShift)),
            "romantic" to (.12+.30*smooth+.25*(1-energy)+.18*stable+.15*(1-f.high)),
            "dance" to (.10+.38*dance+.34*rhythm*(.6+.4*sharp)+.18*energy),
            "focus" to (.12+.34*stable+.28*(1-energy)+.18*smooth),
            "epic" to (.10+.38*energy+.24*f.dynamics.coerceAtMost(4.0)/4+.18*(1-f.low)),
            "dark" to (.10+.28*(1-bright)+.24*f.low+.22*energy+.16*f.flatness+.08*max(0.0,-modeShift))
        )
        val top=raw.values.maxOrNull()?:1.0;val ex=raw.mapValues{kotlin.math.exp((it.value-top)/.24)};val total=ex.values.sum()
        val exact=ex.mapValues{it.value/total*100};val scores=exact.mapValues{floor(it.value).toInt()}.toMutableMap()
        exact.entries.sortedByDescending{it.value-floor(it.value)}.take(100-scores.values.sum()).forEach{scores[it.key]=scores.getValue(it.key)+1}
        // Это качество исходного сигнала и устойчивость измерений, а не вероятность
        // того, что эмоциональная метка объективно верна.
        val durationQuality = u(f.durationSeconds / 90.0)
        val signalQuality = u(f.rms / .035)
        val spectralQuality = 1.0 - u(f.flatness * .65)
        val rhythmQuality = u(f.beat * 1.8)
        val confidence = (
            15 + 20 * durationQuality + 20 * signalQuality +
                15 * spectralQuality + 30 * rhythmQuality
            ).roundToInt().coerceIn(15, 85)
        val level=(1+(energy*4).roundToInt()).coerceIn(1,5)
        val desc=(if(f.bpm>0)f.bpm.toString()+" BPM · " else "")+
            (if(f.tonalClarity>=.4)(if(f.modeMajor>=.5)"мажор · " else "минор · ") else "")+"позитивность "+(valence*100).roundToInt()+"% · возбуждение "+(arousal*100).roundToInt()+"% · напряжение "+(tension*100).roundToInt()+"% · танцевальность "+(danceability*100).roundToInt()+"%"
        return MoodAnalysis(scores,level,f.bpm,confidence,desc,(valence*100).roundToInt(),(arousal*100).roundToInt(),(tension*100).roundToInt(),(danceability*100).roundToInt())
    }
}
