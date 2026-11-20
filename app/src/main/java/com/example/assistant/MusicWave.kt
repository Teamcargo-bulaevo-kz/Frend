package com.example.assistant

import android.content.Context
import java.util.Calendar

/** Запуск волн и подборок из рекомендателя: из экранов музыки и из ассистента. Блокирующий: вызывать с IO-потока. */
object MusicWave {

    const val FIRST_BATCH = 8

    /** Удалось ли последнему [start] запустить музыку. */
    @Volatile var lastStarted = false
        private set

    /**
     * Запускает волну. Возвращает текст для пользователя. [afterSpeech] — подождать, пока ассистент договорит
     * (голосовые команды), иначе музыка стартует почти сразу.
     */
    fun start(context: Context, spec: WaveSpec): String {
        lastStarted = false
        val app = context.applicationContext
        val items = ListenStats.items(app)
        if (items.isEmpty()) return "Не вижу музыку на телефоне. Разреши доступ к аудио."
        val tagged = items.count { MusicTags.isTagged(it.tags) }
        val session = MusicRecommender.Session()
        val first = MusicRecommender.nextForWave(spec, items, session, FIRST_BATCH)
        if (first.isEmpty()) {
            if (tagged == 0) {
                val started = MusicTagger.start(app)
                return "Музыка ещё не разобрана по настроению. $started"
            }
            return when (spec.kind) {
                WaveSpec.Kind.MOOD -> "Не нашёл песен с настроением «${MusicTags.moods[spec.mood]?.lowercase()}»." + taggingTail()
                WaveSpec.Kind.SIMILAR -> "Не нашёл достаточно похожих песен." + taggingTail()
                else -> "Не получилось собрать подборку." + taggingTail()
            }
        }
        MusicActions.startPlayback(app, first.map { it.track }, spec, session)
        lastStarted = true
        val name = SpeechText.shortTitle(first.first().track.title)
        val note = if (tagged < items.size / 2 && MusicTagger.running) " Разбор музыки ещё идёт, волна будет точнее." else ""
        return "${spec.title()}: «$name»$note"
    }

    private fun taggingTail() =
        if (MusicTagger.running) " Разбор музыки ещё идёт (${MusicTagger.done} из ${MusicTagger.total}), попробуй позже." else ""

    /** «Похожие» на песню (или на ту, что играет). */
    fun similarTo(context: Context, track: MusicTrack?): String {
        val t = track ?: PlayerState.track ?: return "Сейчас ничего не играет. Скажи, на какую песню подобрать похожие."
        return start(context, WaveSpec(WaveSpec.Kind.SIMILAR, seedKey = MusicTags.trackKey(t)))
    }

    /** Подборка под текущее время суток. */
    fun forNow(context: Context): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return start(context, WaveSpec(WaveSpec.Kind.PRESET, preset = MusicRecommender.presetForHour(hour)))
    }

    /** Ключ подборки по словам пользователя: «для бега», «для сна», «в дорогу». */
    fun presetFromWords(text: String): String? {
        val t = TextMatch.normalize(text)
        val rules = listOf(
            "run" to listOf("бег", "пробежк", "бегать"),
            "workout" to listOf("трениров", "спорт", "зал", "качалк", "зарядк"),
            "sleep" to listOf("сон", "сна", "засып", "уснуть"),
            "drive" to listOf("дорог", "машин", "поездк", "за рулем", "за рулём"),
            "morning" to listOf("утр", "проснут"),
            "evening" to listOf("вечер"),
            "focus" to listOf("работ", "учеб", "учёб", "концентрац", "фокус"),
            "dance" to listOf("танц", "вечеринк", "тусов"),
            "romance" to listOf("романт", "свидан", "любов"),
            "sad" to listOf("погрустить", "грустн"),
            "power" to listOf("мощн", "эпичн", "тяжел", "тяжёл")
        )
        return rules.firstOrNull { (_, stems) -> stems.any { t.contains(TextMatch.normalize(it)) } }?.first
    }
}
