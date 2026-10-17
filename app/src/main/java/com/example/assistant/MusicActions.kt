package com.example.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Музыка: воспроизведение, управление плеером, громкость, плейлисты. Блокирующие: вызывать с IO-потока. */
object MusicActions {

    private const val NO_ACCESS =
        "Не вижу музыку на телефоне. Разреши доступ к аудио в настройках приложения Assistant"

    private fun startPlayback(context: Context, tracks: List<MusicTrack>) {
        MusicQueue.tracks = tracks
        val intent = Intent(context, MusicService::class.java).setAction(MusicService.ACTION_PLAY)
        ContextCompat.startForegroundService(context, intent)
    }

    // ---------------------------------------------------------------- воспроизведение

    fun playMusic(context: Context, query: String, shuffle: Boolean, mood: String? = null, genre: String? = null): String {
        val index = MusicLibrary.index(context)
        if (mood != null || genre != null) return playByTags(context, index.allMusic(), mood, genre)
        val blank = query.isBlank()
        val found = if (blank) index.allMusic() else index.search(query, 100)
        if (found.isEmpty()) {
            return if (blank) NO_ACCESS else "Не нашёл на телефоне ничего похожего на «$query»"
        }
        // «Включи музыку» без уточнений — вся музыка вперемешку (не больше 300 треков в очереди)
        val tracks = if (shuffle || blank) found.shuffled().take(300) else found
        startPlayback(context, tracks)
        val first = tracks.first()
        val name = if (first.artist.isBlank()) first.title else "${first.artist} — ${first.title}"
        return if (tracks.size > 1) "Включаю «$name», дальше ещё ${tracks.size - 1}" else "Включаю «$name»"
    }

    private fun playByTags(context: Context, music: List<MusicTrack>, mood: String?, genre: String?): String {
        if (music.isEmpty()) return NO_ACCESS
        val label = listOfNotNull(mood?.let { MusicTags.moods[it]?.lowercase() }, genre?.let { MusicTags.genres[it]?.lowercase() })
            .joinToString(", ")
        if (MusicTags.count(context) == 0) {
            val started = MusicTagger.start(context)
            return "Музыка ещё не размечена по настроению и жанру. $started Когда будет готово, повтори просьбу."
        }
        val found = MusicTags.filter(context, music, mood, genre)
        if (found.isEmpty()) {
            val tail = if (MusicTagger.running) " Разметка ещё идёт (${MusicTagger.done} из ${MusicTagger.total}), попробуй позже." else ""
            return "Не нашёл у тебя музыки с настроением/жанром «$label».$tail"
        }
        val tracks = found.shuffled().take(300)
        startPlayback(context, tracks)
        val first = tracks.first()
        val name = if (first.artist.isBlank()) first.title else "${first.artist} — ${first.title}"
        return "Включаю подборку ($label): «$name», всего ${tracks.size}"
    }

    /** Разметка музыки и выгрузка полного списка песен. */
    fun tags(context: Context, action: String): String {
        val music = MusicLibrary.index(context).allMusic()
        if (music.isEmpty()) return NO_ACCESS
        return when (action.trim().lowercase()) {
            "analyze" -> MusicTagger.start(context)
            "status" -> MusicTags.summary(context, music) + if (MusicTagger.running) "\n" + MusicTagger.progressText() else ""
            "export" -> exportList(context, music)
            else -> "Неизвестное действие: $action"
        }
    }

    private fun exportList(context: Context, music: List<MusicTrack>): String {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Исполнитель", "Название", "Альбом", "Жанр", "Настроение", "Энергичность (1-5)"))
        for (t in music.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))) {
            val tg = MusicTags.get(context, t)
            rows.add(listOf(t.artist, t.title, t.album, tg?.let { MusicTags.genres[it.genre] }.orEmpty(),
                tg?.let { MusicTags.moods[it.mood] }.orEmpty(), tg?.energy?.toString().orEmpty()))
        }
        val result = SharedFolder.write("songs.csv", "\uFEFF" + FileConvert.toCsv(rows), append = false, overwrite = true)
        return "Список из ${music.size} песен сохранён в Исходящие как songs.csv. $result"
    }

    /** Что есть в музыкальной библиотеке: сводка или результаты поиска по запросу. */
    fun library(context: Context, query: String?): String {
        val index = MusicLibrary.index(context)
        val music = index.allMusic()
        if (music.isEmpty()) return NO_ACCESS
        val text = query?.trim().orEmpty()
        if (text.isEmpty()) {
            val artists = index.artists()
            val top = artists.take(15).joinToString(", ") { "${it.first} (${it.second})" }
            return "На телефоне ${music.size} треков, ${artists.size} исполнителей. Больше всего: $top"
        }
        val found = index.search(text, 200)
        if (found.isEmpty()) return "Не нашёл на телефоне «$text»"
        val byArtist = found.groupBy { it.artist.ifBlank { "Без исполнителя" } }
        return byArtist.entries.take(5).joinToString("\n") { (artist, list) ->
            val titles = list.take(8).joinToString(", ") { it.title }
            val more = if (list.size > 8) " и ещё ${list.size - 8}" else ""
            "• $artist — ${list.size} треков: $titles$more"
        }
    }

    /** Управление плеером: наш плеер, а если он не играет — медиакнопки для любого другого. */
    fun control(context: Context, action: String): String {
        val a = action.trim().lowercase()
        val serviceAction = when (a) {
            "pause" -> MusicService.ACTION_PAUSE
            "play", "resume" -> MusicService.ACTION_RESUME
            "next" -> MusicService.ACTION_NEXT
            "previous", "prev" -> MusicService.ACTION_PREV
            "stop" -> MusicService.ACTION_STOP
            else -> return "Неизвестная команда плеера: $action"
        }
        val done = when (serviceAction) {
            MusicService.ACTION_PAUSE -> "Поставил на паузу"
            MusicService.ACTION_RESUME -> "Продолжаю"
            MusicService.ACTION_NEXT -> "Следующий трек"
            MusicService.ACTION_PREV -> "Предыдущий трек"
            else -> "Остановил музыку"
        }

        if (MusicService.isRunning) {
            context.startService(Intent(context, MusicService::class.java).setAction(serviceAction))
            return done
        }

        val keyCode = when (serviceAction) {
            MusicService.ACTION_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MusicService.ACTION_RESUME -> KeyEvent.KEYCODE_MEDIA_PLAY
            MusicService.ACTION_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MusicService.ACTION_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return "$done (отправил команду плееру, который сейчас открыт на телефоне)"
    }

    // ---------------------------------------------------------------- громкость

    fun setVolume(context: Context, action: String, percent: Int?, stream: String?): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val (type, title) = when (stream?.trim()?.lowercase()) {
            "ring" -> AudioManager.STREAM_RING to "звонка"
            "alarm" -> AudioManager.STREAM_ALARM to "будильника"
            "notification" -> AudioManager.STREAM_NOTIFICATION to "уведомлений"
            else -> AudioManager.STREAM_MUSIC to "музыки"
        }
        val max = audio.getStreamMaxVolume(type)
        val current = audio.getStreamVolume(type)
        val step = if (percent != null) {
            ceil(max * percent.coerceIn(1, 100) / 100.0).toInt().coerceAtLeast(1)
        } else {
            ceil(max * 0.1).toInt().coerceAtLeast(1)
        }
        val target = when (action.trim().lowercase()) {
            "set" -> {
                val value = percent ?: return "Не указан уровень громкости"
                (value.coerceIn(0, 100) * max / 100.0).roundToInt()
            }
            "up" -> current + step
            "down" -> current - step
            "mute" -> 0
            "max" -> max
            else -> return "Неизвестное действие с громкостью: $action"
        }.coerceIn(0, max)

        return try {
            audio.setStreamVolume(type, target, AudioManager.FLAG_SHOW_UI)
            "Громкость $title: ${(target * 100.0 / max).roundToInt()}%"
        } catch (e: SecurityException) {
            "Android не разрешает менять эту громкость без доступа к режиму «Не беспокоить»"
        }
    }

    // ---------------------------------------------------------------- плейлисты

    fun playlist(
        context: Context,
        action: String,
        name: String?,
        queries: List<String>,
        shuffle: Boolean,
        confirmed: Boolean = false
    ): String {
        val playlists = PlaylistStore.load(context)
        val title = name?.trim().orEmpty()
        val existing = if (title.isEmpty()) null else PlaylistStore.findName(playlists, title)

        return when (action.trim().lowercase()) {
            "list" -> {
                if (playlists.isEmpty()) {
                    "Плейлистов пока нет"
                } else {
                    playlists.entries.joinToString("\n") { "• ${it.key} — ${it.value.size} треков" }
                }
            }

            "create" -> {
                if (title.isEmpty()) return "Не указано название плейлиста"
                if (existing != null) {
                    return "Плейлист «$existing» уже есть. Могу добавить в него треки или удалить его"
                }
                if (queries.isEmpty()) return "Не указано, какие треки или исполнителей добавить"
                val (tracks, missing) = collect(context, queries)
                if (tracks.isEmpty()) return "Не нашёл на телефоне ничего для плейлиста" + missingNote(missing)
                playlists[title] = tracks.toMutableList()
                PlaylistStore.save(context, playlists)
                "Создал плейлист «$title»: ${tracks.size} треков" + missingNote(missing)
            }

            "add" -> {
                if (existing == null) return notFound(title, playlists)
                if (queries.isEmpty()) return "Не указано, что добавить"
                val (tracks, missing) = collect(context, queries)
                val list = playlists.getValue(existing)
                val known = list.map { it.id }.toSet()
                val added = tracks.filter { it.id !in known }
                list += added
                PlaylistStore.save(context, playlists)
                "В плейлист «$existing» добавил ${added.size} треков, всего ${list.size}" + missingNote(missing)
            }

            "play" -> {
                if (existing == null) return notFound(title, playlists)
                val list = playlists.getValue(existing)
                if (list.isEmpty()) return "Плейлист «$existing» пустой"
                startPlayback(context, if (shuffle) list.shuffled() else list.toList())
                "Включаю плейлист «$existing»: ${list.size} треков" + if (shuffle) ", вперемешку" else ""
            }

            "show" -> {
                if (existing == null) return notFound(title, playlists)
                val list = playlists.getValue(existing)
                if (list.isEmpty()) return "Плейлист «$existing» пустой"
                val lines = list.take(30).joinToString("\n") { t ->
                    if (t.artist.isBlank()) "• ${t.title}" else "• ${t.artist} — ${t.title}"
                }
                val more = if (list.size > 30) "\n…и ещё ${list.size - 30}" else ""
                "«$existing», ${list.size} треков:\n$lines$more"
            }

            "delete" -> {
                if (existing == null) return notFound(title, playlists)
                if (!confirmed) return Confirm.ask("Удалить плейлист «$existing»? Сами песни останутся на телефоне.")
                playlists.remove(existing)
                PlaylistStore.save(context, playlists)
                "Удалил плейлист «$existing»"
            }

            else -> "Неизвестное действие с плейлистом: $action"
        }
    }

    /** Собирает треки по списку запросов. Второй результат — запросы, по которым ничего не нашлось. */
    private fun collect(context: Context, queries: List<String>): Pair<List<MusicTrack>, List<String>> {
        val seen = LinkedHashMap<Long, MusicTrack>()
        val missing = mutableListOf<String>()
        for (query in queries.map { it.trim() }.filter { it.isNotEmpty() }) {
            val found = MusicLibrary.search(context, query, limit = 25)
            if (found.isEmpty()) missing += query
            for (track in found) seen.putIfAbsent(track.id, track)
        }
        return seen.values.toList() to missing
    }

    private fun missingNote(missing: List<String>): String =
        if (missing.isEmpty()) "" else ". Не нашёл: " + missing.joinToString(", ") { "«$it»" }

    private fun notFound(title: String, playlists: Map<String, *>): String = when {
        title.isEmpty() -> "Не указано название плейлиста"
        playlists.isEmpty() -> "Плейлистов пока нет"
        else -> "Нет плейлиста «$title». Есть: " + playlists.keys.joinToString(", ") { "«$it»" }
    }
}
