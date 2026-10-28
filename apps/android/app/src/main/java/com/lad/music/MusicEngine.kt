// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*

class LadApplication : Application() {
    val updates by lazy { AppUpdates(this) }
    lateinit var engine: MusicEngine

    override fun onCreate() {
        super.onCreate()
        engine = MusicEngine(this)
    }
}

data class AuthState(
    val loading: Boolean = false,
    val code: String = "",
    val url: String = "",
    val error: String = "",
)

class MusicEngine(
    val context: Context,
    private val streamLoader: (suspend (String) -> String)? = null,
    private val serviceStarter: (() -> Unit)? = null,
) {
    internal var mediaSessionToken: android.media.session.MediaSession.Token? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storage = Storage(context)
    private var accessToken = ""
    val api = YandexApi({ accessToken })
    val player =
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
    private val mutable = MutableStateFlow(MusicState(settings = storage.settings()))
    val state = mutable.asStateFlow()
    val uiState =
        state
            .map { it.copy(positionMs = 0) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), state.value.copy(positionMs = 0))
    val position =
        state
            .map { it.positionMs }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), state.value.positionMs)
    private val mutableAuth = MutableStateFlow(AuthState())
    val auth = mutableAuth.asStateFlow()
    private val mutex = Mutex()
    private val volumeMutex = Mutex()
    private var station: StationClient? = null
    private var phoneVolumeBeforeCast: Float? = null
    private var authJob: Job? = null
    private var stationBase = 0L
    private var stationAt = 0L
    private var stationPlaying = false
    private var stationDuration = 0L
    private var demoAt = 0L
    private var radio: WaveSession? = null
    private val stationContinuity = StationContinuity()
    private var expected = ""
    private var advancing = false
    private var originalQueue: List<Track>? = null
    private val metadata = mutableMapOf<String, Track>()
    private var lastHydrated = ""
    private val audioManager = context.getSystemService(android.media.AudioManager::class.java)

    private fun deviceVolume(): Float =
        audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
            audioManager
                .getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                .coerceAtLeast(1)

    private var operationEpoch = 0L

    private suspend fun <T> playbackLock(block: suspend () -> T): T {
        val epoch = operationEpoch
        return mutex.withLock {
            requireOperation(epoch)
            block()
        }
    }

    private fun requireOperation(epoch: Long) {
        if (epoch != operationEpoch) throw CancellationException("Действие отменено")
    }

    private fun restorePlayback(old: MusicState, epoch: Long, message: String? = null) {
        if (epoch != operationEpoch) return
        update { current ->
            old.copy(
                settings = current.settings,
                volume = current.volume,
                repeat = current.repeat,
                loading = false,
                message = message ?: current.message,
            )
        }
    }

    private fun update(block: (MusicState) -> MusicState) {
        mutable.update(block)
    }

    fun notice(message: String) {
        update { it.copy(message = message) }
    }

    fun clearNotice() {
        update { it.copy(message = null) }
    }

    fun task(action: suspend () -> Unit) =
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(e.message ?: "Не удалось выполнить действие")
            }
        }

    init {
        player.addListener(
            object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) {
                    if (state.value.output == "phone" && !state.value.demo)
                        update {
                            it.copy(
                                playing = p.isPlaying,
                                loading = p.playbackState == Player.STATE_BUFFERING,
                                durationMs =
                                    p.duration.takeIf { v -> v > 0 } ?: it.track?.durationMs ?: 0,
                                positionMs = p.currentPosition,
                            )
                        }
                }

                override fun onPlaybackStateChanged(value: Int) {
                    if (
                        value == Player.STATE_ENDED &&
                            state.value.output == "phone" &&
                            !state.value.demo
                    )
                        task { advance(1, true) }
                }

                override fun onPlayerError(error: PlaybackException) {
                    update { it.copy(playing = false, loading = false) }
                    notice("Не удалось воспроизвести аудио. Попробуй включить трек снова.")
                }
            }
        )
        task {
            accessToken =
                runCatching { storage.token() }
                    .getOrElse {
                        notice("Не удалось открыть сохранённую сессию. Войди снова.")
                        ""
                    }
            if (accessToken.isNotBlank()) {
                val epoch = operationEpoch
                try {
                    val p = api.profile()
                    requireOperation(epoch)
                    val saved = storage.queue(p.uid)
                    update {
                        it.copy(
                            profile = p,
                            queue = saved.first,
                            index = saved.second,
                            owned = false,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice(e.message ?: "Не удалось проверить сессию")
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(200)
                val s = state.value
                if (s.settings.syncVolume && !s.demo && s.output == "phone") {
                    val v = deviceVolume()
                    if (kotlin.math.abs(s.volume - v) > .001f) update { it.copy(volume = v) }
                }
                if (s.demo && s.playing) {
                    val now = SystemClock.elapsedRealtime()
                    val delta = now - demoAt
                    demoAt = now
                    update {
                        it.copy(positionMs = (it.positionMs + delta).coerceAtMost(it.durationMs))
                    }
                    if (state.value.positionMs >= s.durationMs) task { advance(1, true) }
                } else if (s.output == "phone" && !s.demo && s.track != null) {
                    update {
                        it.copy(
                            positionMs = player.currentPosition.coerceAtLeast(0),
                            playing = player.isPlaying,
                        )
                    }
                } else if (s.output == "station" && !s.demo) {
                    val age = SystemClock.elapsedRealtime() - stationAt
                    val position =
                        (stationBase + if (stationPlaying) age.coerceIn(0, 4000) else 0).coerceIn(
                            0,
                            stationDuration.coerceAtLeast(0),
                        )
                    update { it.copy(positionMs = position) }
                    if (
                        age < 2500 &&
                            s.owned &&
                            !s.loading &&
                            s.playing &&
                            s.durationMs > 0 &&
                            position >= s.durationMs - 100 &&
                            !advancing
                    )
                        task { advance(1, true) }
                }
            }
        }
    }

    suspend fun login(token: String) {
        val epoch = ++operationEpoch
        val old = accessToken
        accessToken = token.trim()
        try {
            val profile = api.profile()
            requireOperation(epoch)
            storage.token(accessToken)
            player.pause()
            station?.close()
            station = null
            val saved = storage.queue(profile.uid)
            radio = null
            stationContinuity.reset()
            update {
                MusicState(
                    profile = profile,
                    queue = saved.first,
                    index = saved.second,
                    settings = it.settings,
                )
            }
            task { refreshDevices() }
        } catch (e: Exception) {
            if (epoch == operationEpoch) accessToken = old
            throw e
        }
    }

    fun beginLogin() {
        authJob?.cancel()
        authJob =
            scope.launch {
                try {
                    mutableAuth.value = AuthState(loading = true)
                    val code = api.deviceCode()
                    val deadline =
                        SystemClock.elapsedRealtime() + code.optLong("expires_in", 300) * 1000
                    var interval = code.optLong("interval", 5).coerceAtLeast(5)
                    mutableAuth.value =
                        AuthState(
                            code = code.text("user_code"),
                            url =
                                code.text("verification_url").ifBlank {
                                    code.text("verification_uri")
                                },
                        )
                    while (isActive && SystemClock.elapsedRealtime() < deadline) {
                        delay(interval * 1000)
                        try {
                            val result = api.pollCode(code.text("device_code"))
                            val token = result.text("access_token")
                            if (token.isNotBlank()) {
                                login(token)
                                mutableAuth.value = AuthState()
                                return@launch
                            }
                        } catch (e: ApiError) {
                            if (e.message == "slow_down") interval += 5
                            else if (e.message != "authorization_pending") throw e
                        }
                    }
                    mutableAuth.value = mutableAuth.value.copy(error = "Код истёк. Запроси новый.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableAuth.value = AuthState(error = e.message ?: "Не удалось начать вход")
                }
            }
    }

    fun cancelLogin() {
        authJob?.cancel()
        mutableAuth.value = AuthState()
    }

    fun logout() {
        operationEpoch++
        authJob?.cancel()
        station?.close()
        station = null
        player.stop()
        accessToken = ""
        storage.token("")
        radio = null
        stationContinuity.reset()
        update { MusicState(settings = it.settings) }
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    fun demo() {
        operationEpoch++
        player.stop()
        station?.close()
        station = null
        accessToken = ""
        radio = null
        stationContinuity.reset()
        update {
            MusicState(
                profile = Profile("demo", "Режим знакомства", true),
                demo = true,
                queue = demoTracks,
                index = 0,
                source = "Лад Session · пример",
                owned = true,
                durationMs = demoTracks[0].durationMs,
                settings = it.settings,
            )
        }
        demoAt = SystemClock.elapsedRealtime()
    }

    fun settings(value: Settings) {
        if (value.blockExplicit != state.value.settings.blockExplicit) {
            operationEpoch++
            update { it.copy(loading = false) }
        }
        val wasSync = state.value.settings.syncVolume
        storage.settings(value)
        if (value.syncVolume && !wasSync && state.value.output == "phone" && !state.value.demo)
            update { it.copy(volume = deviceVolume()) }
        player.volume = if (value.syncVolume && !state.value.demo) 1f else state.value.volume
        update { it.copy(settings = value) }
        if (value.blockExplicit && state.value.track?.explicit == true && state.value.owned)
            task { pause() }
    }

    fun persist() {
        state.value.let {
            if (!it.demo && it.profile != null) storage.queue(it.profile.uid, it.queue, it.index)
        }
    }

    private fun foreground() {
        serviceStarter?.let {
            it()
            return
        }
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }

    private suspend fun checked(track: Track): Track {
        val s = state.value
        val fresh =
            if (!s.demo && s.settings.blockExplicit)
                api.tracks(listOf(track.id)).firstOrNull()?.copy(liked = track.liked)
                    ?: throw IllegalStateException("Не удалось проверить маркировку 18+")
            else track
        check(fresh.available) { "Трек недоступен" }
        check(!(s.settings.blockExplicit && fresh.explicit)) {
            "Трек 18+ пропущен: включён фильтр контента"
        }
        return fresh
    }

    private suspend fun start(index: Int, position: Long = 0, paused: Boolean = false) {
        val epoch = operationEpoch
        val old = state.value
        val t = checked(old.queue.getOrNull(index) ?: throw IllegalArgumentException("Выбери трек"))
        requireOperation(epoch)
        if (old.queue.map { it.id } != state.value.queue.map { it.id })
            throw CancellationException("Очередь изменилась")
        metadata[t.id] = t
        foreground()
        expected = t.id.substringBefore(':')
        stationContinuity.reset()
        update {
            it.copy(
                index = index,
                queue = it.queue.mapIndexed { i, item -> if (i == index) t else item },
                loading = true,
                externalTrack = null,
                owned = true,
                playing = false,
            )
        }
        try {
            when {
                old.demo -> {
                    demoAt = SystemClock.elapsedRealtime()
                    update {
                        it.copy(
                            playing = !paused,
                            positionMs = position,
                            durationMs = t.durationMs,
                            loading = false,
                        )
                    }
                }
                old.output == "phone" -> {
                    val url = streamLoader?.invoke(t.id) ?: api.stream(t.id)
                    requireOperation(epoch)
                    player.setMediaItem(
                        MediaItem.Builder()
                            .setUri(url)
                            .setMediaId(t.id)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(t.title)
                                    .setArtist(t.artist)
                                    .setAlbumTitle(t.album)
                                    .setArtworkUri(t.cover?.let(android.net.Uri::parse))
                                    .build()
                            )
                            .build()
                    )
                    player.volume = if (state.value.settings.syncVolume) 1f else state.value.volume
                    player.prepare()
                    player.seekTo(position)
                    player.playWhenReady = !paused
                    update { it.copy(positionMs = position, durationMs = t.durationMs) }
                }
                else -> {
                    val connected = station ?: throw IllegalStateException("Подключи Станцию")
                    val confirmed = connected.play(t, position, paused)
                    requireOperation(epoch)
                    stationState(confirmed)
                    update { it.copy(loading = false) }
                }
            }
            persist()
            val startedBatch = radio?.batch
            if (startedBatch != null && !old.demo)
                task { runCatching { api.radioFeedback("trackStarted", t, startedBatch) } }
        } catch (e: Exception) {
            restorePlayback(old, epoch, e.message)
            throw e
        }
    }

    suspend fun play(tracks: List<Track>, index: Int, source: String) = playbackLock {
        val epoch = operationEpoch
        val old = state.value
        val t = tracks.getOrNull(index) ?: throw IllegalArgumentException("В подборке нет треков")
        checked(t)
        requireOperation(epoch)
        check(tracks.size <= 5000) { "Слишком большая очередь" }
        radio = null
        stationContinuity.reset()
        originalQueue = null
        update {
            it.copy(queue = tracks, index = index, source = source, owned = true, shuffle = false)
        }
        try {
            start(index)
        } catch (e: Exception) {
            restorePlayback(old, epoch, e.message)
            throw e
        }
    }

    suspend fun wave() = playbackLock {
        val epoch = operationEpoch
        if (state.value.demo) {
            update {
                it.copy(queue = demoTracks, index = 0, source = "Моя волна · пример", owned = true)
            }
            start(0)
            return@playbackLock
        }
        val context = WaveSession(api::wave, api::radioFeedback)
        val tracks = context.replenish(emptyList(), "", state.value.settings.blockExplicit)
        requireOperation(epoch)
        check(tracks.isNotEmpty()) {
            "Моя волна пока не вернула подходящие треки. Попробуй ещё раз."
        }
        val old = state.value
        val oldRadio = radio
        radio = context
        originalQueue = null
        update {
            it.copy(queue = tracks, index = 0, source = "Моя волна", owned = true, shuffle = false)
        }
        try {
            start(0)
            runCatching { context.report("radioStarted", null) }
        } catch (e: Exception) {
            if (epoch == operationEpoch) radio = oldRadio
            restorePlayback(old, epoch)
            throw e
        }
    }

    private suspend fun replenishWave(context: WaveSession) {
        val s = state.value
        val additions =
            context.replenish(
                s.queue.drop(s.index.coerceAtLeast(0)),
                s.track?.id.orEmpty(),
                s.settings.blockExplicit,
                maxBatches = 1,
                isCurrent = { radio === context && state.value.owned },
            )
        if (radio !== context || !state.value.owned) return
        update {
            val trim =
                (it.queue.size + additions.size - 5000)
                    .coerceAtLeast(0)
                    .coerceAtMost(it.index.coerceAtLeast(0))
            it.copy(queue = (it.queue.drop(trim) + additions).take(5000), index = it.index - trim)
        }
        persist()
    }

    suspend fun advance(
        direction: Int,
        automatic: Boolean = false,
        coverSelection: Pair<CoverWindow, Int>? = null,
    ) = playbackLock {
        if (advancing) return@playbackLock
        val epoch = operationEpoch
        advancing = true
        try {
            val s = state.value
            if (
                coverSelection != null &&
                    !acceptsCoverSelection(s, coverSelection.first, coverSelection.second)
            )
                return@playbackLock
            if (s.output == "station" && !s.owned) {
                check(!s.settings.blockExplicit) { "Фильтр 18+ включён. Выбери трек через Лад." }
                station?.command(JSONObject().put("command", if (direction > 0) "next" else "prev"))
                return@playbackLock
            }
            val context = radio
            if (context != null && !s.demo && direction > 0)
                runCatching {
                    context.report(
                        if (automatic) "trackFinished" else "skip",
                        s.track,
                        s.positionMs / 1000,
                    )
                }
            requireOperation(epoch)
            var refills = 0
            if (context != null && !s.demo && direction > 0 && s.queue.size - s.index < 3) {
                replenishWave(context)
                refills++
                if (radio !== context || !state.value.owned) return@playbackLock
            }
            val tried = mutableSetOf<String>()
            while (true) {
                val current = state.value
                var candidate =
                    if (automatic && current.repeat == 1) current.index
                    else
                        nextPlayable(
                            current.queue,
                            current.index,
                            direction,
                            current.repeat,
                            current.settings.blockExplicit,
                        )
                val walked = mutableSetOf<Int>()
                while (candidate != null && walked.add(candidate)) {
                    val key = "${candidate}:${state.value.queue[candidate].id}"
                    if (tried.add(key)) {
                        try {
                            start(candidate)
                            return@playbackLock
                        } catch (e: Exception) {
                            if (
                                !state.value.settings.blockExplicit ||
                                    !e.message.orEmpty().contains("18+")
                            )
                                throw e
                        }
                    }
                    candidate =
                        nextPlayable(
                            state.value.queue,
                            candidate,
                            direction,
                            current.repeat,
                            current.settings.blockExplicit,
                        )
                }
                if (context != null && !s.demo && direction > 0 && refills < 4) {
                    replenishWave(context)
                    refills++
                    if (radio !== context || !state.value.owned) return@playbackLock
                    continue
                }
                pause()
                update { it.copy(loading = false) }
                if (context != null && direction > 0)
                    notice(
                        "Моя волна пока не вернула подходящие треки. Нажми «Следующий», чтобы продолжить поиск."
                    )
                return@playbackLock
            }
        } finally {
            advancing = false
        }
    }

    suspend fun jump(index: Int) = playbackLock {
        val epoch = operationEpoch
        val old = state.value
        val context = radio
        if (context != null && !old.demo && old.index != index)
            runCatching { context.report("skip", old.track, old.positionMs / 1000) }
        requireOperation(epoch)
        start(index)
        if (context != null && !old.demo && state.value.queue.size - index < 3) {
            runCatching {
                    val additions =
                        context.replenish(
                            state.value.queue.drop(index),
                            state.value.track?.id.orEmpty(),
                            state.value.settings.blockExplicit,
                            isCurrent = { radio === context && state.value.owned },
                        )
                    if (radio === context && state.value.owned) {
                        update { it.copy(queue = it.queue + additions) }
                        persist()
                    }
                }
                .onFailure {
                    notice(
                        "Трек играет. Не удалось обновить хвост волны; «Следующий» повторит запрос."
                    )
                }
        }
    }

    suspend fun toggle() {
        if (state.value.playing) pause() else resume()
    }

    suspend fun pause() {
        operationEpoch++
        update { it.copy(loading = false) }
        val s = state.value
        if (s.output == "station" && !s.demo) station?.command(JSONObject().put("command", "stop"))
        else player.pause()
        update { it.copy(playing = false) }
        stationPlaying = false
    }

    suspend fun resume() {
        val epoch = operationEpoch
        val s = state.value
        val t = s.track ?: return
        checked(t)
        requireOperation(epoch)
        foreground()
        if (s.demo) {
            demoAt = SystemClock.elapsedRealtime()
            update { it.copy(playing = true) }
        } else if (s.output == "station") {
            if (stationContinuity.pending != null) {
                if (radio != null) advance(1) else playbackLock { start(s.index, s.positionMs) }
            } else station?.command(JSONObject().put("command", "play"))
        } else if (player.currentMediaItem?.mediaId != t.id) {
            playbackLock { start(s.index, s.positionMs) }
        } else player.play()
    }

    suspend fun seek(position: Long) {
        val s = state.value
        val p = position.coerceIn(0, s.durationMs.coerceAtLeast(0))
        if (s.demo) {
            demoAt = SystemClock.elapsedRealtime()
        } else if (s.output == "station") {
            station?.command(JSONObject().put("command", "rewind").put("position", p / 1000.0))
            stationBase = p
            stationAt = SystemClock.elapsedRealtime()
        } else player.seekTo(p)
        update { it.copy(positionMs = p) }
    }

    suspend fun volume(value: Float) = volumeMutex.withLock { setVolume(value) }

    suspend fun adjustVolume(delta: Float) =
        volumeMutex.withLock { setVolume(state.value.volume + delta) }

    private suspend fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (state.value.output == "station" && !state.value.demo)
            station?.command(JSONObject().put("command", "setVolume").put("volume", v))
        else if (state.value.settings.syncVolume && !state.value.demo) {
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                (v * max).toInt().coerceIn(0, max),
                0,
            )
            player.volume = 1f
        } else player.volume = v
        update {
            it.copy(
                volume =
                    if (it.settings.syncVolume && !it.demo && it.output == "phone") deviceVolume()
                    else v
            )
        }
    }

    fun add(tracks: List<Track>, next: Boolean = false) {
        val s = state.value
        val eligible = tracks.filter { it.available && !(s.settings.blockExplicit && it.explicit) }
        check(eligible.isNotEmpty()) { "Нет доступных треков с учётом фильтра" }
        check(s.queue.size + eligible.size <= 5000) { "Очередь вмещает до 5000 треков" }
        val position = if (next) (s.index + 1).coerceAtLeast(0) else s.queue.size
        val q = s.queue.toMutableList().apply { addAll(position, eligible) }
        originalQueue = null
        update { it.copy(queue = q, shuffle = false) }
        persist()
        notice(if (next) "Будет играть следующим" else "Добавлено в очередь")
    }

    fun move(from: Int, to: Int) {
        val s = state.value
        val moved = moveQueue(s.queue, s.index, from, to)
        originalQueue = null
        update { it.copy(queue = moved.first, index = moved.second, shuffle = false) }
        persist()
    }

    fun remove(index: Int) {
        val s = state.value
        check(index in s.queue.indices)
        check(!(s.owned && s.index == index)) { "Сначала переключи текущий трек" }
        originalQueue = null
        update {
            it.copy(
                queue = it.queue.filterIndexed { i, _ -> i != index },
                index = if (index < s.index) s.index - 1 else if (index == s.index) -1 else s.index,
                shuffle = false,
            )
        }
        persist()
    }

    fun clearQueue() {
        val s = state.value
        val t = s.queue.getOrNull(s.index)
        radio = null
        stationContinuity.reset()
        originalQueue = null
        update {
            it.copy(
                queue = t?.let { listOf(it) } ?: emptyList(),
                index = if (t != null) 0 else -1,
                shuffle = false,
            )
        }
        persist()
    }

    fun repeat() {
        update { it.copy(repeat = (it.repeat + 1) % 3) }
    }

    fun shuffle() {
        val s = state.value
        if (s.shuffle && originalQueue != null) {
            val q = restoreUnshuffledQueue(s.queue, s.index, originalQueue!!)
            update { it.copy(queue = q, shuffle = false) }
            originalQueue = null
        } else {
            originalQueue = s.queue
            val prefix = s.queue.take((s.index + 1).coerceAtLeast(0))
            update {
                it.copy(queue = prefix + s.queue.drop(prefix.size).shuffled(), shuffle = true)
            }
        }
        persist()
    }

    suspend fun like(track: Track) {
        val s = state.value
        val liked = !track.liked
        if (!s.demo) api.like(s.profile?.uid ?: error("Войди в аккаунт"), track.id, "track", liked)
        update {
            it.copy(
                queue = it.queue.map { t -> if (t.id == track.id) t.copy(liked = liked) else t },
                externalTrack =
                    it.externalTrack?.let { t ->
                        if (t.id == track.id) t.copy(liked = liked) else t
                    },
            )
        }
        persist()
    }

    suspend fun refreshDevices() {
        val epoch = operationEpoch
        val s = state.value
        if (s.demo) {
            update {
                it.copy(
                    devices = listOf(Device("demo-midi", "Станция Миди · пример", "cucumber", ""))
                )
            }
            return
        }
        val owned = api.devices()
        val found = StationDiscovery(context).discover()
        requireOperation(epoch)
        val uid = s.profile?.uid.orEmpty()
        update { current ->
            current.copy(
                devices =
                    owned.map { d ->
                        val lan = found[d.id]
                        d.copy(
                            host = lan?.first ?: storage.host(uid, d.id),
                            port = lan?.second ?: d.port,
                            platform = lan?.third?.ifBlank { d.platform } ?: d.platform,
                        )
                    }
            )
        }
    }

    suspend fun transferPhone() = playbackLock {
        val epoch = operationEpoch
        val s = state.value
        if (s.output == "phone") return@playbackLock
        val localVolume =
            if (s.settings.syncVolume && !s.demo) deviceVolume()
            else phoneVolumeBeforeCast ?: s.volume
        val t = s.track
        val position = s.positionMs
        val wasPlaying = s.playing
        if (t == null) {
            station?.close()
            station = null
            update { it.copy(output = "phone", connection = "disconnected", volume = localVolume) }
            return@playbackLock
        }
        checked(t)
        requireOperation(epoch)
        if (!s.demo) station?.command(JSONObject().put("command", "stop"))
        val q = if (s.owned) s.queue else listOf(t)
        val index = if (s.owned) s.index else 0
        update {
            it.copy(output = "phone", queue = q, index = index, owned = true, volume = localVolume)
        }
        try {
            start(index, position, !wasPlaying)
            station?.close()
            station = null
            update { it.copy(connection = "disconnected") }
        } catch (e: Exception) {
            requireOperation(epoch)
            restorePlayback(s, epoch)
            if (wasPlaying) runCatching { station?.command(JSONObject().put("command", "play")) }
            throw e
        }
    }

    suspend fun transfer(device: Device, manualHost: String = "") = playbackLock {
        val epoch = operationEpoch
        val s = state.value
        if (s.output == "phone") phoneVolumeBeforeCast = s.volume
        if (s.demo) {
            update { it.copy(output = "station", deviceId = device.id, connection = "connected") }
            return@playbackLock
        }
        val t = s.track
        val position =
            if (s.output == "phone") player.currentPosition.coerceAtLeast(0) else s.positionMs
        val wasPlaying = s.playing
        if (t != null) checked(t)
        requireOperation(epoch)
        val host = manualHost.ifBlank { device.host }
        check(host.isNotBlank()) { "Укажи локальный IP этой Станции" }
        val selected = device.copy(host = localIp(host))
        player.pause()
        val oldStation = station
        lateinit var candidate: StationClient
        candidate =
            StationClient(
                api,
                selected,
                scope,
                { if (station === candidate) stationState(it) },
                { status, message ->
                    if (station === oldStation || station?.device?.id == selected.id)
                        update { it.copy(connection = status, message = message ?: it.message) }
                },
            )
        update { it.copy(loading = true, playing = false) }
        try {
            candidate.connect()
            requireOperation(epoch)
            station = candidate
            update {
                it.copy(
                    output = "station",
                    deviceId = selected.id,
                    devices = it.devices.map { d -> if (d.id == selected.id) selected else d },
                    connection = "connected",
                    loading = true,
                )
            }
            if (t != null) {
                expected = t.id.substringBefore(':')
                stationContinuity.reset()
                val confirmed = candidate.play(t, position, !wasPlaying)
                requireOperation(epoch)
                stationState(confirmed)
            }
            oldStation?.close()
            update { it.copy(loading = false) }
            storage.host(s.profile?.uid.orEmpty(), selected.id, selected.host)
        } catch (e: Exception) {
            runCatching { candidate.command(JSONObject().put("command", "stop")) }
            candidate.close()
            requireOperation(epoch)
            station = oldStation
            restorePlayback(s, epoch)
            if (s.output == "phone" && wasPlaying) player.play()
            throw IllegalStateException("Не удалось передать музыку: ${e.message}")
        }
    }

    private fun stationState(json: JSONObject) {
        val p = json.optJSONObject("playerState") ?: JSONObject()
        val id = p.text("id").substringBefore(':')
        val old = state.value
        if (old.output != "station") return
        val incomingVolume =
            json.optDouble("volume", old.volume.toDouble()).toFloat().coerceIn(0f, 1f)
        val nearEnd =
            old.playing &&
                old.durationMs > 0 &&
                old.positionMs >= old.durationMs - 1500 &&
                SystemClock.elapsedRealtime() - stationAt < 2500
        val report = stationContinuity.classify(id, expected, old.owned, old.loading, nearEnd)
        if (report == StationReport.STARTING) return
        if (
            report == StationReport.CONTINUATION_START || report == StationReport.CONTINUATION_WAIT
        ) {
            update {
                it.copy(
                    volume = incomingVolume,
                    connection = "connected",
                    loading =
                        if (report == StationReport.CONTINUATION_START) true
                        else advancing || it.loading,
                    playing =
                        if (!advancing && !it.loading) json.optBoolean("playing") else it.playing,
                )
            }
            if (report == StationReport.CONTINUATION_START)
                task {
                    // Stop unknown native autoplay while a filtered radio batch is loading.
                    if (state.value.settings.blockExplicit) pause()
                    advance(1, true)
                }
            return
        }
        stationBase = (p.optDouble("progress") * 1000).toLong()
        stationDuration = (p.optDouble("duration") * 1000).toLong()
        stationAt = SystemClock.elapsedRealtime()
        stationPlaying = json.optBoolean("playing")
        val own = old.queue.getOrNull(old.index)?.takeIf { it.id.substringBefore(':') == id }
        val cached = metadata[id] ?: old.queue.find { it.id.substringBefore(':') == id }
        val t =
            own
                ?: cached
                ?: id.takeIf { it.isNotBlank() }
                    ?.let {
                        Track(
                            it,
                            p.text("title", "Музыка на Станции"),
                            listOf(ArtistRef("", p.text("subtitle"))),
                            durationMs = stationDuration,
                            cover = coverUrl(p.optJSONObject("extra")?.text("coverURI").orEmpty()),
                        )
                    }
        val changed = report == StationReport.EXTERNAL
        update {
            it.copy(
                playing = stationPlaying,
                positionMs = stationBase,
                durationMs = stationDuration,
                volume = json.optDouble("volume", it.volume.toDouble()).toFloat().coerceIn(0f, 1f),
                owned = if (changed) false else it.owned,
                externalTrack = if (own == null) t else null,
                connection = "connected",
            )
        }
        if (changed) {
            radio = null
            stationContinuity.reset()
            notice("Музыку переключили вне Лада. Прежняя очередь сохранена.")
        }
        if (t != null && cached == null && lastHydrated != id) {
            lastHydrated = id
            task {
                val full = api.tracks(listOf(id)).firstOrNull() ?: return@task
                metadata[id] = full
                if (state.value.externalTrack?.id == id) update { it.copy(externalTrack = full) }
            }
        }
    }

    companion object {
        val demoTracks =
            listOf(
                    "Первый свет",
                    "Тихий город",
                    "Между станциями",
                    "После полуночи",
                    "Ближе к свету",
                    "До следующего утра",
                )
                .mapIndexed { i, title ->
                    Track(
                        "demo-$i",
                        title,
                        listOf(ArtistRef("demo-artist", "Лад Session")),
                        "demo-album",
                        "Тихий город",
                        216000L + i * 9000L,
                        liked = i % 2 == 0,
                    )
                }
        val demoCards =
            listOf("Первый свет", "Тихий город", "После полуночи", "Ближе к свету").mapIndexed {
                i,
                title ->
                Card("demo-$i", "playlist", title, "Лад Session · пример")
            }
    }
}
