package com.example.assistant

/** Что сейчас играет: MusicService обновляет, экраны музыки читают (один процесс). */
object PlayerState {
    const val REPEAT_OFF = 0
    const val REPEAT_ALL = 1
    const val REPEAT_ONE = 2

    @Volatile var track: MusicTrack? = null
    @Volatile var playing = false
    @Volatile var index = 0
    @Volatile var queue: List<MusicTrack> = emptyList()
    @Volatile var shuffle = false
    @Volatile var repeat = REPEAT_OFF
    @Volatile var durationMs = 0
    @Volatile var sleepAtMs = 0L
    @Volatile var positionProvider: (() -> Int)? = null

    fun position(): Int = positionProvider?.invoke() ?: 0

    fun clear() {
        track = null
        playing = false
        queue = emptyList()
        durationMs = 0
        sleepAtMs = 0L
        positionProvider = null
    }

    fun formatTime(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
