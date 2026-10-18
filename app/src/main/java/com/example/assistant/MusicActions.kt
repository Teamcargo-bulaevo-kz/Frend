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

    fun playMusic(
        context: Context, query: String, shuffle: Boolean, mood: String? = null, genre: String? = null, liked: Boolean = false
    ): String {
        val index = MusicLibrary.index(context)
        if (liked) return playLiked(context, index.allMusic(), mood, genre)
        if (mood != null || genre != null) return playByTags(context, index.allMusic(), mood, genre)
        val blank = query.isBlank()
        val found = if (blank) index.allMusic() else index.search(query, 100)
        if (found.isEmpty()) {
            return if (blank) NO_ACCESS else "Не нашёл на телефоне ничего похожего на «$query»"
        }
        // «Включи музыку» без уточнений — вся музыка вперемешку (не больше 300 треков в очереди)
        // Без уточнений дизлайкнутые песни не включаем
        val pool = if (blank) found.filter { MusicTags.likedOf(context, it) >= 0 }.ifEmpty { found } else found
        val tracks = if (shuffle || blank) pool.shuffled().take(300) else pool
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

    /** «Включи любимое»: песни с лайком, при желании ещё и по настроению или жанру. */
    private fun playLiked(context: Context, music: List<MusicTrack>, mood: String?, genre: String?): String {
        if (music.isEmpty()) return NO_ACCESS
        val liked = music.filter { MusicTags.likedOf(context, it) > 0 }.filter { t ->
            val tags = MusicTags.get(context, t)
            (mood == null || (tags != null && mood in tags.moods)) && (genre == null || tags?.genre == genre)
        }
        if (liked.isEmpty()) {
            return "Любимых песен пока нет. Нажми ♥ в плеере или скажи «поставь лайк этой песне»."
        }
        val tracks = liked.shuffled().take(300)
        startPlayback(context, tracks)
        val first = tracks.first()
        return "Включаю любимое: «${if (first.artist.isBlank()) first.title else first.artist + " — " + first.title}», всего ${tracks.size}"
    }

    private fun nameOf(t: MusicTrack) = if (t.artist.isBlank()) t.title else "${t.artist} — ${t.title}"

    /** Песня, о которой речь: названная или та, что играет сейчас. */
    private fun target(context: Context, query: String?): Pair<MusicTrack?, String?> {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) {
            val now = PlayerState.track
            return if (now != null) now to null else null to "Сейчас ничего не играет. Скажи название песни."
        }
        val found = MusicLibrary.index(context).search(q, 1).firstOrNull()
        return if (found != null) found to null else null to "Не нашёл на телефоне «$q»"
    }

    /** Лайк, дизлайк и ручная правка характеристик песни (голосом). */
    fun rate(
        context: Context, action: String, query: String?, moods: List<String>, genre: String?, energy: Int?, tempo: String?
    ): String {
        val (track, error) = target(context, query)
        if (track == null) return error ?: "Не нашёл песню"
        val name = nameOf(track)
        return when (action.trim().lowercase()) {
            "like" -> { MusicTags.setLiked(context, track, 1); "Поставил ♥ песне «$name»" }
            "dislike" -> { MusicTags.setLiked(context, track, -1); "Отметил «$name» как не нравится: в подборках её не будет" }
            "clear" -> { MusicTags.setLiked(context, track, 0); "Снял оценку с «$name»" }
            "info" -> {
                val tg = MusicTags.get(context, track)
                if (!MusicTags.isTagged(tg) || tg == null) "«$name» ещё не размечена"
                else "«$name»: ${tg.genreText.lowercase()}, ${tg.moods.joinToString(", ") { MusicTags.moodShort[it]?.lowercase() ?: it }}, " +
                    "энергия ${tg.energy} из 5" + (if (tg.tempo.isNotBlank()) ", ${tg.tempo}" else "") +
                    (if (tg.liked > 0) ", в любимых" else "") + (if (tg.needsReview) ". Я в этом не уверен" else "")
            }
            "tag" -> {
                val newMoods = moods.mapNotNull { m -> m.trim().lowercase().takeIf { it in MusicTags.moods } ?: MusicTags.moodFromWords(m) }
                    .distinct()
                val newGenre = genre?.trim()?.lowercase()?.let { g -> g.takeIf { it in MusicTags.genres } ?: MusicTags.genreFromWords(g) }
                val tempoKey = tempo?.let { MusicTags.tempoKey(it) }
                if (newMoods.isEmpty() && newGenre == null && energy == null && tempoKey == null) {
                    return "Не понял, что поменять. Назови настроение, жанр, энергичность или темп."
                }
                MusicTags.update(context, track) { old ->
                    old.copy(
                        moods = if (newMoods.isNotEmpty()) newMoods.toSet() else old.moods,
                        mood = if (newMoods.isNotEmpty()) newMoods.first() else old.mood,
                        genre = newGenre ?: old.genre,
                        genreLabel = if (newGenre != null) "" else old.genreLabel,
                        energy = energy?.coerceIn(1, 5) ?: old.energy,
                        tempo = tempoKey?.let { MusicTags.tempos[it] } ?: old.tempo
                    )
                }
                "Запомнил для «$name»: " + listOfNotNull(
                    newMoods.takeIf { it.isNotEmpty() }?.joinToString(", ") { MusicTags.moodShort[it]?.lowercase() ?: it },
                    newGenre?.let { MusicTags.genres[it]?.lowercase() },
                    energy?.let { "энергия $it из 5" },
                    tempoKey?.let { MusicTags.tempos[it] }
                ).joinToString(", ")
            }
            else -> "Неизвестное действие: $action"
        }
    }

    /** Разметка музыки и выгрузка полного списка песен. */
    fun tags(context: Context, action: String): String {
        val music = MusicLibrary.index(context).allMusic()
        if (music.isEmpty()) return NO_ACCESS
        return when (action.trim().lowercase()) {
            "analyze" -> MusicTagger.start(context)
            "status" -> MusicTags.summary(context, music) + if (MusicTagger.running) "\n" + MusicTagger.progressText() else ""
            "export" -> exportList(context)
            else -> "Неизвестное действие: $action"
        }
    }

    private fun exportList(context: Context): String {
        val ok = MusicCatalog.exportNow(context)
        return if (ok) "Таблица со всеми песнями, жанрами и настроениями обновлена: Assistant/${MusicCatalog.FILE_NAME}"
        else "Нет доступа к общей папке Assistant. Разреши доступ ко всем файлам в настройках."
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
        confirmed: Boolean = false,
        current: Boolean = false,
        targetName: String? = null
    ): String {
        val playlists = PlaylistStore.load(context)
        val title = name?.trim().orEmpty()
        val existing = if (title.isEmpty()) null else PlaylistStore.findName(playlists, title)

        /** Песни, о которых просили: та, что играет сейчас, и найденные по запросам. */
        fun requested(): Pair<List<MusicTrack>, List<String>> {
            val (found, missing) = collect(context, queries)
            val now = if (current) listOfNotNull(PlayerState.track) else emptyList()
            return (now + found).distinctBy { it.id } to missing
        }
        val noSong = "Не указано, какие песни. Назови их или скажи «эту песню», когда она играет"

        return when (action.trim().lowercase()) {
            "list" -> {
                if (playlists.isEmpty()) {
                    "Папок и плейлистов пока нет"
                } else {
                    playlists.entries.joinToString("\n") { "• ${it.key} — ${it.value.size} треков" }
                }
            }

            "create" -> {
                if (title.isEmpty()) return "Не указано название"
                if (existing != null) {
                    return "«$existing» уже есть. Могу добавить в неё песни или удалить её"
                }
                val (tracks, missing) = requested()
                if (tracks.isEmpty() && (queries.isNotEmpty() || current)) {
                    return "Не нашёл на телефоне ничего для «$title»" + missingNote(missing)
                }
                playlists[title] = tracks.toMutableList()
                PlaylistStore.save(context, playlists)
                MusicCatalog.scheduleExport(context)
                if (tracks.isEmpty()) "Создал пустую папку «$title». Добавляй в неё песни"
                else "Создал «$title»: ${tracks.size} треков" + missingNote(missing)
            }

            "add" -> {
                if (existing == null) {
                    // Нет такой папки: если просят добавить в новую, создаём её, чтобы не переспрашивать
                    if (title.isEmpty()) return notFound(title, playlists)
                    val (tracks, missing) = requested()
                    if (tracks.isEmpty()) return noSong + missingNote(missing)
                    playlists[title] = tracks.toMutableList()
                    PlaylistStore.save(context, playlists)
                    MusicCatalog.scheduleExport(context)
                    return "Создал «$title» и добавил ${tracks.size} треков" + missingNote(missing)
                }
                val (tracks, missing) = requested()
                if (tracks.isEmpty()) return noSong + missingNote(missing)
                val list = playlists.getValue(existing)
                val known = list.map { it.id }.toSet()
                val added = tracks.filter { it.id !in known }
                list += added
                PlaylistStore.save(context, playlists)
                MusicCatalog.scheduleExport(context)
                "В «$existing» добавил ${added.size} треков, всего ${list.size}" + missingNote(missing)
            }

            "remove" -> {
                if (existing == null) return notFound(title, playlists)
                val (tracks, missing) = requested()
                if (tracks.isEmpty()) return noSong + missingNote(missing)
                val ids = tracks.map { it.id }.toSet()
                val list = playlists.getValue(existing)
                val before = list.size
                list.removeAll { it.id in ids }
                PlaylistStore.save(context, playlists)
                MusicCatalog.scheduleExport(context)
                "Убрал из «$existing» ${before - list.size} треков, осталось ${list.size}" + missingNote(missing)
            }

            "move" -> {
                if (existing == null) return notFound(title, playlists)
                val destTitle = targetName?.trim().orEmpty()
                if (destTitle.isEmpty()) return "Не указано, куда переложить"
                val (tracks, missing) = requested()
                if (tracks.isEmpty()) return noSong + missingNote(missing)
                val ids = tracks.map { it.id }.toSet()
                val source = playlists.getValue(existing)
                val moved = source.filter { it.id in ids }
                if (moved.isEmpty()) return "В «$existing» этих песен нет" + missingNote(missing)
                source.removeAll { it.id in ids }
                val destName = PlaylistStore.findName(playlists, destTitle) ?: destTitle
                val dest = playlists.getOrPut(destName) { mutableListOf() }
                val known = dest.map { it.id }.toSet()
                dest += moved.filter { it.id !in known }
                PlaylistStore.save(context, playlists)
                MusicCatalog.scheduleExport(context)
                "Переложил ${moved.size} треков из «$existing» в «$destName»" + missingNote(missing)
            }

            "rename" -> {
                if (existing == null) return notFound(title, playlists)
                val newTitle = targetName?.trim().orEmpty()
                if (newTitle.isEmpty()) return "Не указано новое название"
                if (PlaylistStore.findName(playlists, newTitle).let { it != null && it != existing }) return "«$newTitle» уже есть"
                val renamed = LinkedHashMap<String, MutableList<MusicTrack>>()
                for ((k, v) in playlists) renamed[if (k == existing) newTitle else k] = v
                PlaylistStore.save(context, renamed)
                MusicCatalog.scheduleExport(context)
                "Переименовал «$existing» в «$newTitle»"
            }

            "play" -> {
                if (existing == null) return notFound(title, playlists)
                val list = playlists.getValue(existing).filter { MusicTags.likedOf(context, it) >= 0 }
                if (list.isEmpty()) return "«$existing» пустая"
                startPlayback(context, if (shuffle) list.shuffled() else list.toList())
                "Включаю «$existing»: ${list.size} треков" + if (shuffle) ", вперемешку" else ""
            }

            "show" -> {
                if (existing == null) return notFound(title, playlists)
                val list = playlists.getValue(existing)
                if (list.isEmpty()) return "«$existing» пустая"
                val lines = list.take(30).joinToString("\n") { t -> "• " + nameOf(t) }
                val more = if (list.size > 30) "\n…и ещё ${list.size - 30}" else ""
                "«$existing», ${list.size} треков:\n$lines$more"
            }

            "delete" -> {
                if (existing == null) return notFound(title, playlists)
                if (!confirmed) return Confirm.ask("Удалить «$existing»? Сами песни останутся на телефоне.")
                playlists.remove(existing)
                PlaylistStore.save(context, playlists)
                MusicCatalog.scheduleExport(context)
                "Удалил «$existing»"
            }

            else -> "Неизвестное действие: $action"
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
        title.isEmpty() -> "Не указано название папки или плейлиста"
        playlists.isEmpty() -> "Папок и плейлистов пока нет"
        else -> "Нет папки «$title». Есть: " + playlists.keys.joinToString(", ") { "«$it»" }
    }
}
