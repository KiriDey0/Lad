// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import coil.load
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: MusicEngine
    private lateinit var root: FrameLayout
    private lateinit var pageLayer: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var font: Typeface
    private val buttons = linkedMapOf<String, View>()
    private val actions = mutableMapOf<String, () -> Unit>()
    private val panels = mutableListOf<TvGlass>()
    private val scrolls = mutableMapOf<String, ScrollView>()
    private val history = TvHistory()
    private var page = "home"
    private var tab = "Плейлисты"
    private var menu = false
    private var modal: String? = null
    private var returnFocus = ""
    private var activeFocus = "wave"
    private var focusAfter = ""
    private var query = ""
    private var searchCatalog = Catalog()
    private var keyboard = "RU"
    private var typingJob: Job? = null
    private var loadJob: Job? = null
    private var searchGeneration = 0
    private var screenError = ""
    private var busy = false
    private var catalog = Catalog()
    private var opened: Card? = null
    private val catalogCache = mutableMapOf<String, Catalog>()
    private var library = Library()
    private var home = Catalog()
    private var libraryBody: FrameLayout? = null
    private var lyrics = Lyrics()
    private var lyricTrack = ""
    private var lyricError = ""
    private var lyricLoading = false
    private var reading = false
    private var lyricIndex = -1
    private var dialogTrack: Track? = null
    private var dialogQueueIndex: Int? = null
    private var moving: QueueMove? = null
    private var seekDraft: Long? = null
    private var ambient = false
    private var lastInput = 0L
    private var stateKey = ""
    private val updater by lazy { (application as LadApplication).updates }
    private var updatePhase = ""
    private var updateProgressLabel: TextView? = null
    private var deviceScanJob: Job? = null
    private var devicesLoading = false
    private var devicesError = ""
    private var pendingOutputId: String? = null
    private var outputReturnFocus = ""
    private var wavePreferences: com.lad.music.wave.WavePreferences? = null
    private var wavePreferenceError = ""
    private var wavePreferenceLoading = false
    private var homeWave: com.lad.music.wave.WaveView? = null
    private var playerTime: TextView? = null
    private var lineText: TextView? = null
    private var previousLine: TextView? = null
    private var nextLine: TextView? = null
    private var miniSubtitle: TextView? = null
    private var miniTitle: TextView? = null
    private var progress: TvProgress? = null
    private val s: MusicState
        get() = engine.state.value

    private val bg = Color.rgb(12, 13, 15)
    private val surface = Color.rgb(23, 25, 29)
    private val raised = Color.rgb(48, 53, 61)
    private val ink = Color.rgb(241, 243, 245)
    private val muted = Color.rgb(170, 175, 185)
    private val scale
        get() = resources.displayMetrics.widthPixels / 1920f

    private fun px(v: Int) = (v * scale).toInt()

    private fun textPixels(size: Int) =
        px(((size * .8f / 2).roundToInt() * 2)).toFloat() * s.settings.scale

    private fun colour() = s.settings.accent.toInt()

    private fun round(color: Int, radius: Int = 24, stroke: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = px(radius).toFloat()
            if (stroke != 0) setStroke(px(4).coerceAtLeast(2), stroke)
        }

    private fun place(parent: FrameLayout, v: View, x: Int, y: Int, w: Int, h: Int) {
        parent.addView(
            v,
            FrameLayout.LayoutParams(if (w < 0) w else px(w), if (h < 0) h else px(h)).apply {
                leftMargin = px(x)
                topMargin = px(y)
            },
        )
    }

    private fun frame(
        parent: FrameLayout,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: Int? = null,
    ): FrameLayout =
        FrameLayout(this).also {
            it.clipChildren = false
            if (color != null) it.background = round(color)
            place(parent, it, x, y, w, h)
        }

    private fun label(
        parent: FrameLayout,
        text: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        size: Int = 32,
        color: Int = ink,
        bold: Boolean = false,
    ): TextView =
        TextView(this).also {
            it.text = text
            it.setTextColor(color)
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPixels(size))
            it.typeface = if (bold) Typeface.create(font, 600, false) else font
            it.gravity = Gravity.CENTER_VERTICAL
            it.maxLines = 1
            it.ellipsize = TextUtils.TruncateAt.END
            it.includeFontPadding = false
            place(parent, it, x, y, w, h)
        }

    private fun paragraph(
        parent: FrameLayout,
        text: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        size: Int = 32,
        color: Int = muted,
    ): TextView =
        label(parent, text, x, y, w, h, size, color).also {
            it.maxLines = 6
            it.gravity = Gravity.TOP
            it.setLineSpacing(px(8).toFloat(), 1f)
        }

    private fun icon(name: String): Int =
        when (name) {
            "home" -> R.drawable.ic_radio
            "search" -> R.drawable.ic_search
            "library" -> R.drawable.ic_library
            "player" -> R.drawable.ic_audio_lines
            "settings" -> R.drawable.ic_settings
            "play" -> R.drawable.ic_play
            "pause" -> R.drawable.ic_pause
            "previous" -> R.drawable.ic_skip_back
            "next" -> R.drawable.ic_skip_forward
            "heart" -> R.drawable.ic_heart
            "chevron" -> R.drawable.ic_chevron_right
            "expand" -> R.drawable.ic_maximize_2
            "text" -> R.drawable.ic_file_text
            "queue" -> R.drawable.ic_list_music
            "more" -> R.drawable.ic_ellipsis
            "shuffle" -> R.drawable.ic_shuffle
            "add-next" -> R.drawable.ic_list_start
            "add-end" -> R.drawable.ic_list_end
            "move" -> R.drawable.ic_arrow_up_down
            "album" -> R.drawable.ic_disc_3
            "artist" -> R.drawable.ic_user_round
            "refresh" -> R.drawable.ic_refresh_cw
            "speaker" -> R.drawable.ic_speaker
            "tv" -> R.drawable.ic_tv
            "check" -> R.drawable.ic_check
            "volume" -> R.drawable.ic_volume_2
            "key" -> R.drawable.ic_key_round
            "delete" -> R.drawable.ic_delete
            "follow" -> R.drawable.ic_locate_fixed
            else -> R.drawable.ic_ellipsis
        }

    private fun symbol(
        parent: FrameLayout,
        name: String,
        x: Int,
        y: Int,
        size: Int,
        tint: Int = ink,
    ): ImageView =
        ImageView(this).also {
            it.setImageResource(icon(name))
            it.setColorFilter(tint)
            place(parent, it, x, y, size, size)
        }

    private fun focusable(
        view: View,
        id: String,
        mode: String = "filled",
        normal: Int = surface,
        onClick: () -> Unit,
    ) {
        view.id = View.generateViewId()
        view.tag = id
        view.isFocusable = true
        view.isClickable = true
        view.isFocusableInTouchMode = false
        if (view !is TvGlass) view.background = round(normal, 20)
        buttons[id] = view
        actions[id] = onClick
        view.setOnClickListener {
            lastInput = SystemClock.elapsedRealtime()
            onClick()
        }
        val baseTextColor = (view as? TextView)?.currentTextColor ?: ink
        view.onFocusChangeListener =
            View.OnFocusChangeListener { _, focused ->
                if (view !is TvGlass)
                    view.background =
                        round(
                            if (focused) {
                                if (mode == "filled") colour() else raised
                            } else if (view.isActivated) raised else normal,
                            20,
                            if (focused) colour() else 0,
                        )
                if (view is TvGlass) {
                    view.foreground = if (focused) round(Color.TRANSPARENT, 24, colour()) else null
                }
                if (view is TextView) {
                    view.setTextColor(
                        if (focused) {
                            if (mode == "filled") bg else ink
                        } else baseTextColor
                    )
                    view.isSelected = focused
                    view.ellipsize =
                        if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
                    view.marqueeRepeatLimit = -1
                }
                if (focused) {
                    activeFocus = id
                    keepFocusVisible(view)
                    lastInput = SystemClock.elapsedRealtime()
                }
            }
    }

    private fun button(
        parent: FrameLayout,
        id: String,
        title: String,
        x: Int,
        y: Int,
        w: Int = 240,
        h: Int = 64,
        onClick: () -> Unit,
    ): TextView {
        val v = label(parent, title, x, y, w, h, 28, ink)
        v.typeface = Typeface.create(font, 500, false)
        v.gravity = Gravity.CENTER
        v.contentDescription = title
        val glyph =
            when (id) {
                "wave",
                "liked-play",
                "collection-play",
                "action-play" -> "play"
                "previous" -> "previous"
                "pause" -> if (s.playing) "pause" else "play"
                "next" -> "next"
                "text",
                "lyrics-open" -> "text"
                "queue" -> "queue"
                "more",
                "collection-actions" -> "more"
                "collection-shuffle",
                "action-shuffle",
                "more-shuffle" -> "shuffle"
                "action-like",
                "more-like" -> "heart"
                "liked-open" -> "chevron"
                "action-next" -> "add-next"
                "action-add" -> "add-end"
                "action-move" -> "move"
                "action-album" -> "album"
                "action-artist" -> "artist"
                "device-tv" -> "tv"
                "devices-refresh",
                "login-code",
                "lyrics-retry" -> if (id == "login-code") "key" else "refresh"
                "devices-done" -> "check"
                "more-output",
                "output-picker" ->
                    if (id == "output-picker" && s.output == "phone") "tv" else "speaker"
                "more-volume" -> "volume"
                "follow" -> "follow"
                "key-delete" -> null
                else ->
                    if (id.startsWith("device-")) "speaker"
                    else if (id.startsWith("nav-")) id.removePrefix("nav-") else null
            }
        if (glyph != null) {
            val text = android.text.SpannableString("\uFFFC  " + title)
            val d = getDrawable(icon(glyph))!!.mutate()
            text.setSpan(TvIconSpan(d, px(26)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.text = text
        }
        focusable(v, id, onClick = onClick)
        return v
    }

    private fun item(
        parent: FrameLayout,
        id: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        normal: Int = Color.TRANSPARENT,
        onClick: () -> Unit,
    ): FrameLayout {
        val view = frame(parent, x, y, w, h)
        focusable(view, id, "tonal", normal, onClick)
        return view
    }

    private fun settingRow(
        parent: FrameLayout,
        id: String,
        title: String,
        value: String,
        y: Int,
        onClick: () -> Unit,
    ) {
        val row = item(parent, id, 0, y, 1576, 72, surface, onClick)
        row.contentDescription = title + " · " + value
        label(row, title, 28, 0, 720, 72, 30)
        val v = label(row, value, 740, 0, 748, 72, 24, muted)
        v.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        symbol(row, "chevron", 1520, 22, 28)
    }

    private fun artwork(
        parent: FrameLayout,
        title: String,
        url: String?,
        x: Int,
        y: Int,
        size: Int,
    ): ImageView {
        val v = ImageView(this)
        v.contentDescription = "Обложка: $title"
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.background =
            GradientDrawable(
                    GradientDrawable.Orientation.BL_TR,
                    intArrayOf(bg, Color.rgb(35, 62, 73), Color.rgb(138, 159, 157)),
                )
                .apply { cornerRadius = px(24).toFloat() }
        val placeholder =
            android.graphics.drawable.LayerDrawable(
                arrayOf(
                    v.background.constantState!!.newDrawable(),
                    getDrawable(icon("player"))!!.mutate(),
                )
            )
        val glyph = (size * .15f).toInt().coerceAtLeast(10)
        val inset = if (size < 100) 8 else 12
        placeholder.setLayerInset(
            1,
            px(inset),
            px(size - inset - glyph),
            px(size - inset - glyph),
            px(inset),
        )
        v.setImageDrawable(placeholder)
        v.clipToOutline = true
        place(parent, v, x, y, size, size)
        if (!url.isNullOrBlank())
            v.load(url) {
                placeholder(placeholder)
                error(placeholder)
                size(px(size).coerceAtMost(640))
                crossfade(false)
                allowHardware(false)
                listener(onSuccess = { _, _ -> refreshGlass() })
            }
        return v
    }

    override fun onResume() {
        super.onResume()
        (application as LadApplication).updates.onResume(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        engine = (application as LadApplication).engine
        font = ResourcesCompat.getFont(this, R.font.golos) ?: Typeface.SANS_SERIF
        lastInput = SystemClock.elapsedRealtime()
        build()
        scope.launch {
            engine.state.collect { state ->
                val key =
                    "${state.profile?.uid}:${state.demo}:${state.queue.map{it.id+it.liked}}:${state.index}:${state.output}:${state.deviceId}:${state.devices.map { listOf(it.id,it.name,it.host,it.port) }}:${state.connection}:${state.playing}:${state.loading}:${state.settings}:${state.message}"
                if (key != stateKey) {
                    val hadProfile = stateKey.isNotBlank()
                    stateKey = key
                    if (state.profile != null && page == "login") {
                        page = "home"
                        history.clear()
                        focusAfter = "wave"
                    }
                    build(activeFocus)
                    if (state.profile != null && (!hadProfile || home.title.isEmpty())) loadHome()
                    if (state.track?.id != lyricTrack) loadLyrics()
                }
                updateClock()
            }
        }
        scope.launch {
            updater.state.collect { update ->
                if (modal == "updates") {
                    if (update.phase != updatePhase) {
                        updatePhase = update.phase
                        build(activeFocus)
                    } else
                        updateProgressLabel?.text =
                            if (update.phase == "downloading")
                                "Загружено ${update.progress}% · можно слушать музыку"
                            else update.message
                }
            }
        }
        scope.launch { engine.auth.collect { if (page == "login") build(activeFocus) } }
        scope.launch {
            while (isActive) {
                delay(1000)
                if (
                    page == "player" &&
                        modal == null &&
                        seekDraft == null &&
                        s.playing &&
                        getSharedPreferences("tv-ui", MODE_PRIVATE).getLong("ambient-seconds", 30) >
                            0 &&
                        SystemClock.elapsedRealtime() - lastInput >
                            getSharedPreferences("tv-ui", MODE_PRIVATE)
                                .getLong("ambient-seconds", 30) * 1000 &&
                        !ambient
                ) {
                    ambient = true
                    build()
                }
            }
        }
    }

    private fun refreshGlass() {
        panels.forEach { it.refresh() }
    }

    private fun capturePlace() =
        TvPlace(
            page,
            activeFocus,
            scrolls.mapValues { it.value.scrollY },
            opened,
            catalog,
            tab,
            query,
        )

    private fun go(target: String, focus: String = "", push: Boolean = true) {
        if (target == page && !menu && modal == null) return
        if (push) history.push(capturePlace())
        if (page == "login") engine.cancelLogin()
        page = target
        if (target == "search") catalog = searchCatalog
        menu = false
        modal = null
        ambient = false
        seekDraft = null
        focusAfter = focus
        screenError = ""
        build(focus)
        if (target == "library") loadLibrary()
        if (target == "home") loadHome()
        if (target == "player" || target == "lyrics") loadLyrics()
    }

    private fun back() {
        if (seekDraft != null) {
            seekDraft = null
            build("seek")
            return
        }
        if (moving != null) {
            moving = null
            modal = null
            build(returnFocus)
            return
        }
        if (modal != null) {
            modal = null
            build(returnFocus)
            return
        }
        if (ambient) {
            ambient = false
            lastInput = SystemClock.elapsedRealtime()
            build("pause")
            return
        }
        if (menu) {
            menu = false
            build(returnFocus)
            return
        }
        if (page == "login") engine.cancelLogin()
        val previous = history.pop()
        if (previous != null) {
            page = previous.page
            opened = previous.card
            catalog = previous.catalog
            tab = previous.tab
            query = previous.query
            build(previous.focus)
            root.post { previous.scroll.forEach { (k, y) -> scrolls[k]?.scrollTo(0, y) } }
            return
        }
        if (page != "home") {
            page = "home"
            build("wave")
            return
        }
        if (activeFocus.startsWith("nav-")) {
            finish()
            return
        }
        returnFocus = activeFocus
        menu = true
        build("nav-home")
    }

    private fun glass(parent: FrameLayout, x: Int, y: Int, w: Int, h: Int): TvGlass =
        TvGlass(this, pageLayer) { s.settings.glass }
            .also {
                place(parent, it, x, y, w, h)
                panels += it
                it.refresh()
            }

    private fun build(focus: String = "") {
        if (!::engine.isInitialized) return
        val preserved = scrolls.mapValues { it.value.scrollY }
        buttons.clear()
        actions.clear()
        panels.clear()
        scrolls.clear()
        playerTime = null
        progress = null
        miniTitle = null
        miniSubtitle = null
        libraryBody = null
        previousLine = null
        nextLine = null
        lyricViews.clear()
        lineText = null
        if (!::root.isInitialized) {
            root = FrameLayout(this).apply { clipChildren = false }
            setContentView(root)
        } else root.removeAllViews()
        root.setBackgroundColor(if (s.settings.oled) Color.BLACK else bg)
        pageLayer = FrameLayout(this)
        overlay = FrameLayout(this)
        root.addView(pageLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val mark = ImageView(this).apply { setImageResource(R.drawable.audio_lines) }
        place(pageLayer, mark, 96, 76, 40, 40)
        label(pageLayer, "лад .", 152, 72, 220, 48, 40, ink, true)
        when (page) {
            "login" -> loginPage()
            "search" -> searchPage()
            "library" -> libraryPage()
            "collection" -> collectionPage()
            "player" -> playerPage()
            "lyrics" -> lyricsPage()
            "queue" -> queuePage()
            "settings" -> settingsPage()
            else -> homePage()
        }
        if (page !in listOf("login", "player", "lyrics")) navRail()
        if (s.profile != null && page !in listOf("login", "player", "lyrics", "search", "settings"))
            miniPlayer()
        if (s.profile != null && page != "login" && !ambient) outputPickerButton()
        if (menu) drawMenu()
        modal?.let { drawModal(it) }
        s.message?.let { msg ->
            val note = glass(overlay, 640, 70, 1184, 112)
            label(note, msg, 24, 12, 1048, 88, 28, ink)
            button(note, "dismiss-notice", "×", 1088, 12, 72, 88) { engine.clearNotice() }
        }
        root.post {
            preserved.forEach { (k, y) -> scrolls[k]?.scrollTo(0, y) }
            val desired = focus.ifBlank { focusAfter }
            val target =
                buttons[desired]
                    ?: buttons[
                        when (page) {
                            "login" -> "login-code"
                            "player",
                            "lyrics" -> "pause"
                            "search" -> "key-0-3"
                            "queue" -> "queue-${s.index.coerceAtLeast(0)}"
                            "settings" -> "setting-filter"
                            "library" -> "liked-play"
                            else -> "wave"
                        }]
                    ?: buttons.values.firstOrNull()
            target?.requestFocus()
            refreshGlass()
        }
        updateClock(true)
    }

    private fun hint(text: String) {
        val box = frame(overlay, 248, 992, 1576, 88, if (s.settings.oled) Color.BLACK else bg)
        label(box, text, 0, 0, 1576, 44, 22, muted)
    }

    private fun navRail() {
        val active = if (page == "collection") "library" else page
        for ((i, name) in listOf("home", "search", "library", "player", "settings").withIndex()) {
            val v =
                item(
                    pageLayer,
                    "nav-$name",
                    96,
                    160 + i * 80,
                    64,
                    64,
                    if (active == name) raised else Color.TRANSPARENT,
                ) {
                    go(name)
                }
            v.contentDescription =
                listOf("Главная", "Поиск", "Коллекция", "Сейчас играет", "Настройки")[i]
            symbol(v, name, 18, 18, 28)
        }
    }

    private fun drawMenu() {
        buttons.values.forEach { it.isFocusable = false }
        buttons.clear()
        actions.clear()
        val dim = frame(overlay, 0, 0, 1920, 1080, Color.argb(130, 0, 0, 0))
        val p = glass(dim, 64, 54, 440, 972)
        val mark = ImageView(this).apply { setImageResource(R.drawable.audio_lines) }
        place(p, mark, 32, 32, 48, 48)
        label(p, "лад .", 96, 32, 312, 80, 50, ink, true)
        for ((i, e) in
            listOf(
                    "home" to "Главная",
                    "search" to "Поиск",
                    "library" to "Коллекция",
                    "player" to "Сейчас играет",
                    "settings" to "Настройки",
                )
                .withIndex()) button(p, "nav-${e.first}", e.second, 32, 120 + i * 116, 376) {
            go(e.first)
        }
        label(p, s.profile?.name ?: "Войти в Яндекс", 32, 700, 376, 32, 26, muted)
        label(p, "→ — вернуться к музыке", 32, 759, 376, 29, 24, muted)
    }

    private fun viewport(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        key: String,
        contentHeight: Int,
    ): FrameLayout {
        val scroll =
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                isFillViewport = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                setOnScrollChangeListener { _, _, _, _, _ -> refreshGlass() }
            }
        val body = FrameLayout(this).apply { clipChildren = false }
        scroll.addView(body, FrameLayout.LayoutParams(-1, px(contentHeight)))
        place(pageLayer, scroll, x, y, w, h)
        scrolls[key] = scroll
        return body
    }

    private fun miniPlayer() {
        val d = glass(overlay, 248, 904, 1576, 88)
        val t = s.track
        artwork(d, t?.title ?: "Лад", t?.cover, 12, 12, 64)
        miniTitle = label(d, t?.title ?: "Твоя музыка ждёт", 92, 12, 1000, 30, 30, ink, true)
        val duration =
            if (t == null) "Выбери, с чего начать"
            else "${t.artist} · ${timeLabel(s.positionMs)} / ${timeLabel(s.durationMs)}"
        miniSubtitle = label(d, duration, 92, 48, 1000, 26, 26, muted)
        label(d, s.outputName, 1130, 22, 356, 44, 26, muted).gravity =
            Gravity.CENTER_VERTICAL or Gravity.END
        symbol(d, "expand", 1528, 28, 32)
        focusable(d, "mini-open", "tonal", Color.TRANSPARENT) { go("player", "pause") }
    }

    private var homeLoading = false
    private var libraryLoading = false
    private var limit = 100

    private fun fetch(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            screenError = ""
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                screenError = e.message ?: "Не удалось загрузить музыку"
            } finally {
                busy = false
                build(activeFocus)
            }
        }
    }

    private fun loadHome() {
        if (homeLoading || home.title.isNotEmpty() || s.profile == null) return
        homeLoading = true
        fetch {
            try {
                home =
                    if (s.demo) Catalog("Для тебя", MusicEngine.demoTracks, MusicEngine.demoCards)
                    else engine.api.home()
            } finally {
                homeLoading = false
            }
        }
    }

    private fun loadLibrary() {
        if (libraryLoading || s.profile == null) return
        libraryLoading = true
        fetch {
            try {
                library =
                    if (s.demo)
                        Library(
                            playlists = MusicEngine.demoCards,
                            albums =
                                listOf(Card("demo-album", "album", "Тихий город", "Лад Session")),
                            artists = listOf(Card("demo-artist", "artist", "Лад Session")),
                        )
                    else engine.api.library(s.profile!!.uid)
            } finally {
                libraryLoading = false
            }
        }
    }

    private fun openCollection(card: Card) {
        history.push(capturePlace())
        opened = card
        limit = 100
        page = "collection"
        menu = false
        modal = null
        screenError = ""
        catalog = catalogCache[card.key] ?: Catalog(card.title)
        build("collection-play")
        if (catalogCache[card.key] == null)
            fetch {
                val value =
                    if (s.demo) Catalog(card.title, MusicEngine.demoTracks)
                    else if (card.type == "liked") engine.api.liked(s.profile!!.uid)
                    else engine.api.collection(card)
                catalogCache[card.key] = value
                if (opened?.key == card.key) {
                    catalog = value
                    focusAfter = "track-0"
                }
            }
    }

    private fun playTracks(tracks: List<Track>, index: Int, source: String) {
        engine.task { engine.play(tracks, index, source) }
    }

    private fun showModal(name: String) {
        returnFocus = activeFocus
        modal = name
        menu = false
        build()
    }

    private fun cards(parent: FrameLayout, items: List<Card>, x: Int, y: Int, prefix: String) {
        for ((i, c) in items.take(7).withIndex()) {
            val view = item(parent, "$prefix-$i", x + i * 224, y, 200, 264) { openCollection(c) }
            view.contentDescription = c.title
            artwork(view, c.title, c.cover, 12, 12, 176)
            label(view, c.title, 12, 200, 176, 26, 26, ink).apply {
                typeface = Typeface.create(font, 500, false)
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
            }
            label(
                view,
                c.subtitle.ifBlank { if (c.type == "album") "Альбом" else "Плейлист" },
                12,
                238,
                176,
                22,
                22,
                muted,
            )
            view.setOnLongClickListener {
                opened = c
                showModal("collection-actions")
                true
            }
        }
    }

    private fun homePage() {
        if (s.profile == null) {
            paragraph(pageLayer, "Твоя музыка.\nНа большом экране.", 248, 220, 1000, 200, 76, ink)
            paragraph(
                pageLayer,
                "Волна, коллекция и слова любимых песен. Управление обычным пультом.",
                248,
                470,
                1000,
                160,
                38,
            )
            button(pageLayer, "wave", "Войти в Яндекс", 248, 676, 464) { go("login", "login-code") }
            button(pageLayer, "demo", "Посмотреть демо", 744, 676, 400) {
                engine.demo()
                home = Catalog()
                library = Library()
                loadHome()
            }
            artwork(pageLayer, "Лад", null, 1300, 260, 440)
            hint("OK — выбрать    Стрелки — перемещение    Назад — меню")
            return
        }
        val body = viewport(248, 160, 1576, 920, "home", 1480)
        val hero = frame(body, 0, 0, 1576, 650, if (s.settings.oled) Color.BLACK else bg)
        val wave =
            (homeWave ?: com.lad.music.wave.WaveView(this).also { homeWave = it }).apply {
                (parent as? android.view.ViewGroup)?.removeView(this)
                audio = engine.waveAudio
                remote = engine.remoteWave
                playing = s.playing
                local = s.output != "station"
                intensity = s.settings.waveIntensity
                smoothing = s.settings.waveSmoothing
                cover = s.track?.cover
            }
        place(hero, wave, 0, 0, 1576, 600)
        label(hero, "Моя волна", 288, 26, 1000, 46, 40, ink, true).gravity = Gravity.CENTER
        artwork(hero, "Моя волна", s.track?.cover, 658, 120, 260)
        label(hero, s.track?.title ?: "В твоём настроении", 288, 402, 1000, 36, 30, ink, true)
            .apply {
                gravity = Gravity.CENTER
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
                isSingleLine = true
            }
        label(hero, s.track?.artist ?: "Знакомое и новое", 288, 444, 1000, 30, 24, muted).gravity =
            Gravity.CENTER
        lineText = label(hero, "", 238, 486, 1100, 32, 26, ink).apply { gravity = Gravity.CENTER }
        nextLine = label(hero, "", 238, 524, 1100, 30, 24, muted).apply { gravity = Gravity.CENTER }
        button(
            hero,
            "wave",
            if (s.source.startsWith("Моя волна") && s.owned)
                if (s.playing) "Пауза" else "Продолжить волну"
            else "Включить Мою волну",
            264,
            578,
            332,
            56,
        ) {
            engine.task {
                if (s.source.startsWith("Моя волна") && s.owned) engine.toggle() else engine.wave()
            }
        }
        button(hero, "home-lyrics", "Полный текст ↗", 620, 578, 332, 56) { go("lyrics", "follow") }
        button(hero, "wave-settings", "Настроить", 976, 578, 332, 56) {
            wavePreferences = null
            wavePreferenceError = ""
            wavePreferenceLoading = true
            showModal("wave-settings")
            scope.launch {
                try {
                    if (s.demo) error("Настройки Яндекс Музыки появятся после входа")
                    wavePreferences = engine.api.waveSettings()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    wavePreferenceError = e.message ?: "Не удалось загрузить настройки"
                } finally {
                    wavePreferenceLoading = false
                    if (modal == "wave-settings") build(activeFocus)
                }
            }
        }
        label(body, "Продолжить из коллекции", 12, 704, 1500, 38, 32, ink, true)
        val entries =
            (listOf(Card("liked", "liked", "Мне нравится", "Любимые треки")) +
                    library.playlists.ifEmpty { home.cards })
                .take(7)
        cards(body, entries, 0, 752, "home-card")
        label(body, "Треки для тебя", 12, 1048, 1500, 56, 38, ink, true)
        home.tracks.take(2).forEachIndexed { i, t ->
            trackRow(body, t, i, 0, 1114 + i * 104, 1576, "home-track-$i") {
                playTracks(home.tracks, i, "Для тебя")
            }
        }
        if (busy) label(body, "Загружаем подборки…", 650, 1350, 510, 56, 28, muted)
        if (screenError.isNotBlank()) label(body, screenError, 0, 1190, 1576, 64, 28, muted)
        hint("OK — включить    ↓ — твоя коллекция    ← — меню")
    }

    private fun libraryPage() {
        label(pageLayer, "Коллекция", 260, 152, 1500, 58, 48, ink, true)
        val fav = frame(pageLayer, 248, 216, 1576, 120, surface)
        val heart = frame(fav, 20, 20, 80, 80, raised)
        symbol(heart, "heart", 16, 16, 48)
        label(fav, "Мне нравится", 124, 23, 800, 34, 36, ink, true)
        label(fav, "Вся любимая музыка", 124, 69, 800, 25, 26, muted)
        button(fav, "liked-play", "Слушать", 1197, 28, 168) {
            fetch {
                val c =
                    if (s.demo) Catalog("Мне нравится", MusicEngine.demoTracks)
                    else engine.api.liked(s.profile!!.uid)
                val first =
                    c.tracks.indexOfFirst {
                        it.available && (!s.settings.blockExplicit || !it.explicit)
                    }
                check(first >= 0) { "Нет треков с учётом фильтра" }
                engine.play(c.tracks, first, c.title)
            }
        }
        val open =
            button(fav, "liked-open", "Открыть", 1389, 28, 167) {
                openCollection(Card("liked", "liked", "Мне нравится"))
            }
        open.background = round(Color.TRANSPARENT)
        val tabs =
            listOf(
                Triple("Плейлисты", 248, 158),
                Triple("Альбомы", 422, 135),
                Triple("Исполнители", 573, 181),
            )
        for ((i, t) in tabs.withIndex()) {
            val v =
                button(pageLayer, "tab-$i", t.first, t.second, 368, t.third, 56) {
                    selectLibraryTab(i, t.first)
                }
            v.isActivated = tab == t.first
            if (v.isActivated) v.background = round(raised, 20)
        }
        libraryBody = viewport(248, 456, 1576, 624, "library", 624)
        renderLibraryCards()
        hint("OK — слушать любимое    ↓ — разделы коллекции    Назад — меню")
    }

    private fun selectLibraryTab(index: Int, value: String) {
        if (tab == value) return
        val previous = listOf("Плейлисты", "Альбомы", "Исполнители").indexOf(tab)
        tab = value
        for (i in 0..2) buttons["tab-$i"]?.let {
            it.isActivated = i == index
            it.background =
                round(
                    if (it.hasFocus()) colour() else if (it.isActivated) raised else surface,
                    20,
                    if (it.hasFocus()) colour() else 0,
                )
        }
        scrolls["library"]?.scrollTo(0, 0)
        renderLibraryCards()
        libraryBody?.let { body ->
            body.animate().cancel()
            body.alpha = 0f
            body.translationX = px(if (index > previous) 24 else -24).toFloat()
            body.post {
                body
                    .animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction { refreshGlass() }
                    .start()
            }
        }
    }

    private fun renderLibraryCards() {
        val body = libraryBody ?: return
        val ids = buttons.keys.filter { it.startsWith("lib-") }
        ids.forEach {
            buttons.remove(it)
            actions.remove(it)
        }
        body.removeAllViews()
        val items =
            when (tab) {
                "Альбомы" -> library.albums
                "Исполнители" -> library.artists
                else -> library.playlists
            }
        val rows = (items.size + 6) / 7
        body.layoutParams =
            body.layoutParams.apply { height = px((rows * 288 + 200).coerceAtLeast(624)) }
        items.chunked(7).forEachIndexed { row, cs -> cards(body, cs, 0, row * 288, "lib-$row") }
        if (items.isEmpty())
            paragraph(
                body,
                if (busy) "Загружаем коллекцию…"
                else
                    screenError.ifBlank {
                        "Здесь пока пусто. Добавляй любимые альбомы и плейлисты в коллекцию."
                    },
                12,
                30,
                1480,
                160,
                30,
            )
    }

    private fun trackRow(
        parent: FrameLayout,
        t: Track,
        index: Int,
        x: Int,
        y: Int,
        w: Int,
        key: String,
        onClick: () -> Unit,
    ) {
        val blocked = !t.available || (s.settings.blockExplicit && t.explicit)
        val row =
            item(parent, key, x, y, w, 92) {
                if (blocked)
                    engine.notice(
                        if (t.explicit) "Трек E пропущен: включён фильтр 18+" else "Трек недоступен"
                    )
                else onClick()
            }
        row.contentDescription = t.title + " · " + t.artist
        row.alpha = if (blocked) .6f else 1f
        artwork(row, t.title, t.cover, 14, 14, 64)
        val available = w - 344
        val title = label(row, t.title, 98, 12, available, 32, 32, ink)
        title.typeface = Typeface.create(font, 500, false)
        if (t.explicit) {
            val textWidth =
                (title.paint.measureText(t.title) / scale).toInt().coerceAtMost(available - 52)
            val chip = frame(row, 98 + textWidth + 12, 14, 26, 26, raised)
            chip.background = round(raised, 6)
            label(chip, "E", 0, 0, 26, 26, 24, ink, true).gravity = Gravity.CENTER
            title.layoutParams = title.layoutParams.apply { width = px(textWidth) }
        }
        label(row, t.artist, 98, 50, available, 28, 28, muted)
        label(row, timeLabel(t.durationMs), w - 192, 24, 96, 44, 26, muted).gravity =
            Gravity.CENTER_VERTICAL or Gravity.END
        val more =
            item(row, "$key-more", w - 72, 18, 56, 56) {
                dialogTrack = t
                dialogQueueIndex = if (page == "queue") index else null
                showModal("track-actions")
            }
        more.contentDescription = "Действия с треком ${t.title}"
        symbol(more, "more", 14, 14, 28)
        row.setOnLongClickListener {
            dialogTrack = t
            dialogQueueIndex = if (page == "queue") index else null
            showModal("track-actions")
            true
        }
    }

    private fun collectionPage() {
        val c = opened ?: return
        artwork(pageLayer, c.title, c.cover, 248, 164, 248)
        label(
            pageLayer,
            when (c.type) {
                "album" -> "АЛЬБОМ"
                "artist" -> "ИСПОЛНИТЕЛЬ"
                else -> "ПЛЕЙЛИСТ"
            },
            536,
            164,
            1288,
            29,
            24,
            muted,
        )
        label(pageLayer, c.title, 536, 209, 1288, 77, 64, ink, true)
        label(pageLayer, "${catalog.total} треков · ${c.subtitle}", 536, 302, 1288, 36, 30, muted)
        button(pageLayer, "collection-play", "Слушать", 536, 354, 239) {
            val i =
                catalog.tracks.indexOfFirst {
                    it.available && (!s.settings.blockExplicit || !it.explicit)
                }
            if (i >= 0) playTracks(catalog.tracks, i, c.title)
            else engine.notice("Нет доступных треков с учётом фильтра")
        }
        button(pageLayer, "collection-shuffle", "Перемешать", 793, 354, 353) {
            val q =
                catalog.tracks
                    .filter { it.available && (!s.settings.blockExplicit || !it.explicit) }
                    .shuffled()
            if (q.isNotEmpty()) playTracks(q, 0, c.title)
        }
        button(pageLayer, "collection-actions", "Действия", 1164, 354, 255) {
            showModal("collection-actions")
        }
        val shown = catalog.tracks.take(limit)
        val body = viewport(248, 462, 1576, 618, "collection-${c.key}", shown.size * 104 + 240)
        shown.forEachIndexed { i, t ->
            trackRow(body, t, i, 0, i * 104, 1576, "track-$i") {
                playTracks(catalog.tracks, i, c.title)
            }
        }
        if (shown.isEmpty()) {
            paragraph(
                body,
                if (busy) "Загрузка треков…"
                else screenError.ifBlank { "В этой подборке пока нет треков" },
                16,
                20,
                1500,
                160,
                36,
            )
            if (screenError.isNotBlank())
                button(body, "collection-retry", "Повторить", 16, 200, 320) {
                    catalogCache.remove(c.key)
                    openCollection(c)
                }
        }
        if (catalog.tracks.size > limit || catalog.more)
            button(body, "load-more", "Показать ещё", 16, shown.size * 104 + 12, 480) {
                if (catalog.tracks.size > limit) {
                    limit += 100
                    build("load-more")
                } else
                    fetch {
                        val next = engine.api.collection(c, catalog.tracks.size / 100)
                        catalog =
                            catalog.copy(
                                tracks = catalog.tracks + next.tracks,
                                more = next.more,
                                total = next.total,
                            )
                        catalogCache[c.key] = catalog
                        limit += 100
                    }
            }
        hint("OK — включить трек    → — действия с треком    Назад — к прежней карточке")
    }

    private fun searchPage() {
        label(pageLayer, "Поиск", 260, 160, 1500, 80, 60, ink, true)
        val field = frame(pageLayer, 248, 260, 1576, 72, surface)
        label(
            field,
            query.ifBlank { "Название, исполнитель или альбом" } + " |",
            24,
            12,
            1528,
            72,
            38,
            ink,
            true,
        )
        val rows = TvKeyboard.rows(keyboard)
        for ((ri, letters) in rows.withIndex()) {
            val inset = ri * 48
            val kw = (1144 - inset * 2 - (letters.size - 1) * 8) / letters.size
            letters.forEachIndexed { ci, key ->
                button(
                    pageLayer,
                    "key-$ri-$ci",
                    key,
                    248 + inset + ci * (kw + 8),
                    356 + ri * 80,
                    kw,
                    64,
                ) {
                    query = TvKeyboard.edit(query, key)
                    build("key-$ri-$ci")
                    scheduleSearch()
                }
            }
        }
        var x = 248
        for ((id, title, width) in
            listOf(
                Triple("lang", if (keyboard == "EN") "RU" else "EN", 144),
                Triple("digits", if (keyboard == "123") "АБВ" else "123", 144),
                Triple("space", "Пробел", 320),
                Triple("delete", "⌫", 144),
                Triple("find", "Найти", 208),
            )) {
            button(pageLayer, "key-$id", title, x, 628, width, 64) {
                when (id) {
                    "lang" -> {
                        keyboard = if (keyboard == "EN") "RU" else "EN"
                        build("key-lang")
                    }
                    "digits" -> {
                        keyboard = if (keyboard == "123") "RU" else "123"
                        build("key-digits")
                    }
                    "find" -> {
                        performSearch(true)
                    }
                    else -> {
                        query = TvKeyboard.edit(query, if (id == "space") "Пробел" else "⌫")
                        build("key-$id")
                        scheduleSearch()
                    }
                }
            }
            x += width + 16
        }
        label(pageLayer, "Результаты", 1460, 364, 364, 52, 34, ink, true)
        val items = catalog.tracks.take(12)
        val result =
            viewport(
                1448,
                424,
                376,
                540,
                "search-results",
                (items.size + catalog.cards.size) * 104 + 180,
            )
        items.forEachIndexed { i, t ->
            val v =
                button(result, "result-$i", t.title + "\n" + t.artist, 0, i * 104, 368, 92) {
                    playTracks(catalog.tracks, i, "Поиск: $query")
                }
            v.maxLines = 2
            v.gravity = Gravity.CENTER_VERTICAL
            v.setPadding(px(20), 0, px(20), 0)
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPixels(27))
            v.setOnLongClickListener {
                dialogTrack = t
                dialogQueueIndex = null
                showModal("track-actions")
                true
            }
        }
        catalog.cards.take(12).forEachIndexed { i, c ->
            button(result, "result-card-$i", c.title, 0, (items.size + i) * 104, 368, 92) {
                openCollection(c)
            }
        }
        if (items.isEmpty() && catalog.cards.isEmpty())
            paragraph(
                result,
                if (busy) "Ищем…"
                else
                    screenError.ifBlank {
                        if (query.isBlank()) "Начни вводить название" else "Ничего не найдено"
                    },
                12,
                12,
                352,
                180,
                30,
            )
        hint("ЙЦУКЕН / QWERTY    EN — язык    123 — цифры    → с края — результаты")
    }

    private fun scheduleSearch() {
        typingJob?.cancel()
        typingJob =
            scope.launch {
                delay(650)
                performSearch(false)
            }
    }

    private fun performSearch(focusResult: Boolean) {
        val q = query.trim()
        val gen = ++searchGeneration
        if (q.isBlank()) {
            catalog = Catalog()
            busy = false
            build(activeFocus)
            return
        }
        scope.launch {
            busy = true
            screenError = ""
            try {
                val found =
                    if (s.demo)
                        Catalog(
                            "Поиск",
                            MusicEngine.demoTracks.filter {
                                it.title.contains(q, true) || it.artist.contains(q, true)
                            },
                            MusicEngine.demoCards,
                        )
                    else engine.api.search(q)
                if (gen == searchGeneration) {
                    catalog = found
                    searchCatalog = found
                    busy = false
                    build(if (focusResult) "result-0" else activeFocus)
                }
            } catch (e: Exception) {
                if (gen == searchGeneration) {
                    busy = false
                    screenError = e.message ?: "Не удалось выполнить поиск"
                    build(activeFocus)
                }
            }
        }
    }

    private fun loginPage() {
        val auth = engine.auth.value
        paragraph(
            pageLayer,
            if (auth.code.isBlank()) "Твоя музыка.\nНа большом экране." else "Остался один шаг",
            200,
            232,
            1000,
            200,
            72,
            ink,
        )
        paragraph(
            pageLayer,
            "Открой страницу входа Яндекса на телефоне. Пароль на телевизоре вводить не нужно.",
            200,
            458,
            960,
            160,
            36,
        )
        if (auth.code.isNotBlank()) {
            label(pageLayer, auth.url, 200, 626, 1000, 64, 30, muted)
            label(pageLayer, auth.code, 200, 706, 960, 120, 92, ink, true)
            button(pageLayer, "login-code", "Получить новый код", 200, 856, 520) {
                engine.beginLogin()
            }
        } else {
            button(
                pageLayer,
                "login-code",
                if (auth.loading) "Получаем код…" else "Получить код входа",
                200,
                676,
                536,
            ) {
                engine.beginLogin()
            }
            button(pageLayer, "login-demo", "Посмотреть демо", 200, 788, 536) {
                engine.cancelLogin()
                engine.demo()
                page = "home"
                history.clear()
                home = Catalog()
                build("wave")
                loadHome()
            }
        }
        val p = frame(pageLayer, 1280, 264, 480, 560, surface)
        paragraph(p, "Вход с телефона", 40, 48, 400, 132, 48, ink)
        paragraph(
            p,
            "Телевизор запомнит аккаунт. Авторизация завершится автоматически.",
            40,
            224,
            400,
            240,
            32,
        )
        if (auth.error.isNotBlank())
            paragraph(pageLayer, auth.error, 200, 956, 1500, 64, 28, Color.rgb(231, 168, 176))
        hint(
            if (auth.code.isBlank()) "OK — получить одноразовый код    Назад — к началу"
            else "Вход завершится автоматически    Назад — отменить ожидание"
        )
    }

    private val lyricViews = mutableMapOf<Int, TextView>()
    private var lyricJob: Job? = null

    private fun loadLyrics() {
        val t = s.track ?: return
        if (t.id == lyricTrack) return
        lyricJob?.cancel()
        lyricTrack = t.id
        lyrics = Lyrics()
        lyricError = ""
        lyricLoading = true
        lyricIndex = -1
        reading = false
        lyricJob =
            scope.launch {
                try {
                    val value =
                        if (s.demo)
                            Lyrics(
                                listOf(
                                        "Город просыпается с нами",
                                        "Слышишь, как начинается день",
                                        "Этот свет остаётся внутри",
                                        "И ведёт нас немного вперёд",
                                        "За окном открываются окна",
                                        "Мы идём навстречу утру",
                                    )
                                    .mapIndexed { i, line -> LyricLine(i * 9000L, line) },
                                true,
                            )
                        else engine.api.lyrics(t.id)
                    if (s.track?.id == t.id) lyrics = value
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lyricError = e.message ?: "Не удалось загрузить текст"
                }
                lyricLoading = false
                if (page in listOf("home", "player", "lyrics")) build(activeFocus)
            }
    }

    private fun playerPage() {
        val t = s.track
        if (t == null) {
            paragraph(pageLayer, "Твоя музыка ждёт", 248, 240, 1400, 150, 72, ink)
            button(pageLayer, "wave", "Включить Мою волну", 248, 460, 600) {
                if (s.profile == null) go("login") else engine.task { engine.wave() }
            }
            hint("OK — включить    Назад — к подборке")
            return
        }
        if (ambient) {
            artwork(pageLayer, t.title, t.cover, 216, 180, 616)
            label(pageLayer, t.title, 944, 302, 840, 86, 72, ink, true)
            label(pageLayer, t.artist, 944, 416, 840, 43, 36, muted)
            lineText = paragraph(pageLayer, "", 944, 559, 840, 112, 46, ink)
            label(pageLayer, s.outputName, 944, 240, 840, 44, 28, muted)
            playerTime = label(pageLayer, "", 216, 842, 900, 31, 26, muted)
            label(pageLayer, "OK — показать управление", 944, 954, 840, 31, 26, muted)
            return
        }
        artwork(pageLayer, t.title, t.cover, 200, 184, 536)
        label(pageLayer, s.source.uppercase(), 864, 184, 840, 24, 24, muted)
        val noLyrics =
            !lyricLoading &&
                lyrics.lines.isEmpty() &&
                (lyricError.isNotBlank() || lyricTrack == t.id)
        val titleY = if (noLyrics) 184 else 232
        val title = label(pageLayer, t.title, 864, titleY, 744, 62, 64, ink, true)
        focusable(title, "player-album", "tonal", Color.TRANSPARENT) {
            if (t.albumId.isNotBlank()) openCollection(t.albumCard())
            else {
                dialogTrack = t
                dialogQueueIndex = null
                showModal("track-actions")
            }
        }
        if (t.explicit) {
            val width = (title.paint.measureText(t.title) / scale).toInt().coerceAtMost(688)
            title.layoutParams = title.layoutParams.apply { this.width = px(width) }
            val chip = frame(pageLayer, 864 + width + 16, titleY + 18, 26, 26, raised)
            chip.background = round(raised, 6)
            label(chip, "E", 0, 0, 26, 26, 24, ink, true).gravity = Gravity.CENTER
        }
        val like =
            item(pageLayer, "player-like", 1632, titleY - 17, 96, 96) {
                engine.task { engine.like(t) }
            }
        like.contentDescription = if (t.liked) "Убрать лайк" else "Нравится"
        symbol(like, "heart", 24, 24, 48, if (t.liked) colour() else ink)
        val artistY = if (noLyrics) 270 else 318
        val artist = label(pageLayer, t.artist, 864, artistY, 840, 34, 34, muted)
        focusable(artist, "player-artist", "tonal", Color.TRANSPARENT) {
            t.artists
                .firstOrNull { it.id.isNotBlank() }
                ?.let { openCollection(Card(it.id, "artist", it.name)) }
        }
        artist.setTextColor(muted)
        if (noLyrics) {
            paragraph(pageLayer, "Яндекс не вернул текст этой песни", 864, 414, 840, 50, 38, ink)
            paragraph(
                pageLayer,
                "Музыка продолжает играть. Можно повторить запрос или остаться на обложке.",
                864,
                484,
                840,
                80,
                30,
                muted,
            )
            button(pageLayer, "lyrics-retry", "Повторить запрос", 864, 580, 390) {
                lyricTrack = ""
                loadLyrics()
            }
        } else {
            previousLine = label(pageLayer, "", 864, 432, 840, 34, 34, muted)
            lineText =
                label(pageLayer, "", 864, 490, 840, 43, 46, ink).also {
                    it.typeface = Typeface.create(font, 500, false)
                }
            nextLine = label(pageLayer, "", 864, 557, 840, 34, 34, muted)
        }
        label(pageLayer, "Музыка там, где ты", 200, 748, 1200, 31, 26, muted)
        transport()
        hint(
            if (seekDraft != null)
                "← / → — ±5 секунд    Удержание — быстрее    OK — перейти    Назад — отменить"
            else "↑ — шкала перемотки    OK — выбранное действие    Назад — к подборке"
        )
        if (seekDraft != null) {
            val p = glass(overlay, 864, 488, 840, 256)
            label(p, "ПЕРЕМОТКА", 32, 28, 628, 40, 26, muted)
            label(
                p,
                "${timeLabel(s.positionMs)}  →  ${timeLabel(seekDraft!!)}",
                32,
                90,
                628,
                80,
                56,
                ink,
                true,
            )
            label(p, "OK — перейти   ·   Назад — отменить", 32, 190, 628, 44, 28, muted)
        }
    }

    private fun transport() {
        val g = glass(overlay, 96, 822, 1728, 168)
        val seek =
            button(g, "seek", "", 16, 8, 1696, 44) {
                seekDraft = s.positionMs
                build("seek")
            }
        seek.background = round(Color.TRANSPARENT, 12)
        progress = TvProgress(this, colour(), scale)
        place(g, progress!!, 92, 20, 1542, 24)
        playerTime = label(g, "", 16, 16, 74, 32, 26, muted)
        label(g, timeLabel(s.durationMs), 1658, 16, 54, 32, 26, muted)
        button(g, "previous", "Предыдущий", 16, 72, 312, 64) { engine.task { engine.advance(-1) } }
        button(
            g,
            "pause",
            if (s.loading) "Ждём…" else if (s.playing) "Пауза" else "Играть",
            348,
            72,
            197,
            64,
        ) {
            engine.task { engine.toggle() }
        }
        button(g, "next", "Следующий", 565, 72, 290, 64) { engine.task { engine.advance(1) } }
        button(g, "text", "Текст", 1076, 72, 190, 64) {
            if (page == "lyrics") back() else go("lyrics", "follow")
        }
        button(g, "queue", "Очередь", 1286, 72, 238, 64) { go("queue") }
        button(g, "more", "Ещё", 1544, 72, 168, 64) { showModal("player-more") }
        root.post {
            listOf("previous", "pause", "next", "text", "queue", "more").forEach {
                buttons[it]?.nextFocusUpId = buttons["seek"]?.id ?: View.NO_ID
            }
        }
    }

    private fun lyricsPage() {
        val t = s.track ?: return
        artwork(pageLayer, t.title, t.cover, 248, 160, 88)
        label(pageLayer, t.title, 368, 161, 980, 50, 42, ink, true)
        label(pageLayer, t.artist, 368, 219, 980, 36, 30, muted)
        button(
            pageLayer,
            "follow",
            if (reading) "Следовать за песней" else "Следуем за песней",
            1360,
            160,
            464,
            96,
        ) {
            reading = false
            updateClock(true)
        }
        lyricViews.clear()
        val body = viewport(248, 312, 1576, 480, "lyrics", lyrics.lines.size * 100 + 380)
        for ((i, line) in lyrics.lines.withIndex()) {
            val v =
                button(body, "line-$i", line.text, 0, i * 100, 1532, 88) {
                    if (line.timeMs != null) engine.task { engine.seek(line.timeMs) }
                    reading = false
                    updateClock(true)
                }
            v.gravity = Gravity.CENTER_VERTICAL
            v.setPadding(px(12), 0, px(12), 0)
            v.background = round(Color.TRANSPARENT)
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPixels(45))
            lyricViews[i] = v
        }
        if (lyrics.lines.isEmpty()) {
            paragraph(
                body,
                lyricError.ifBlank {
                    "Яндекс не вернул текст этой песни. Музыка продолжает играть."
                },
                12,
                24,
                1480,
                180,
                40,
            )
            button(body, "lyrics-retry", "Повторить запрос", 12, 244, 600) {
                lyricTrack = ""
                loadLyrics()
            }
        }
        transport()
        hint("↑ / ↓ — читать строки    OK на строке — перейти к ней    Назад — обложка")
    }

    private fun updateClock(force: Boolean = false) {
        if (!::engine.isInitialized) return
        val pos = seekDraft ?: s.positionMs
        playerTime?.text =
            if (ambient) "${timeLabel(pos)} / ${timeLabel(s.durationMs)}" else timeLabel(pos)
        progress?.progress = if (s.durationMs > 0) (pos * 10000 / s.durationMs).toInt() else 0
        miniSubtitle?.text =
            s.track?.let { "${it.artist} · ${timeLabel(pos)} / ${timeLabel(s.durationMs)}" }
                ?: "Выбери, с чего начать"
        val active =
            lyrics.lines.indexOfLast {
                it.timeMs != null && it.timeMs <= s.positionMs + s.settings.lyricOffsetMs
            }
        if (active != lyricIndex || force) {
            lyricIndex = active
            lineText?.text =
                lyrics.lines.getOrNull(active.coerceAtLeast(0))?.text
                    ?: if (lyricLoading) "Загружаем текст…"
                    else if (lyricError.isBlank()) "" else "Текст пока недоступен"
            previousLine?.text = lyrics.lines.getOrNull(active - 1)?.text ?: ""
            nextLine?.text = lyrics.lines.getOrNull(active.coerceAtLeast(0) + 1)?.text ?: ""
            lyricViews.forEach { (i, v) ->
                if (!v.hasFocus()) {
                    v.setTextColor(if (i == active) ink else muted)
                    v.typeface = Typeface.create(font, if (i == active) 500 else 400, false)
                    v.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        textPixels(if (i == active) 58 else 46),
                    )
                }
            }
            if (page == "lyrics" && !reading && active >= 0)
                scrolls["lyrics"]?.smoothScrollTo(0, px((active * 100 - 164).coerceAtLeast(0)))
        }
        if (s.playing && s.output == "phone")
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun queuePage() {
        label(pageLayer, "Очередь", 260, 160, 1500, 80, 60, ink, true)
        label(
            pageLayer,
            if (moving != null) "↑ / ↓ — место · OK — применить · Назад — отменить"
            else
                s.source +
                    (if (s.source.startsWith("Моя волна")) " · пополняется автоматически" else ""),
            260,
            248,
            1550,
            56,
            30,
            muted,
        )
        val order = s.queue.indices.toMutableList()
        moving?.let { m ->
            if (m.from in order.indices && m.target in order.indices) {
                val v = order.removeAt(m.from)
                order.add(m.target, v)
            }
        }
        val body = viewport(248, 280, 1576, 800, "queue", order.size * 104 + 300)
        order.forEachIndexed { position, i ->
            val t = s.queue[i]
            trackRow(body, t, i, 0, position * 104, 1576, "queue-$i") {
                engine.task { engine.jump(i) }
            }
            if (moving?.from == i) frame(body, 0, position * 104 - 4, 1576, 4, ink)
        }
        if (order.isEmpty())
            paragraph(
                body,
                "Очередь пока пуста. Выбери музыку в коллекции или включи волну.",
                16,
                40,
                1450,
                180,
                38,
            )
        hint(
            if (moving != null)
                "Перемещаем «${s.queue.getOrNull(moving!!.from)?.title}» · OK — применить · Назад — отменить"
            else "OK — включить    → / удержание OK — действия    Назад — плеер"
        )
    }

    private fun settingsPage() {
        label(pageLayer, "Настройки", 260, 160, 1500, 58, 48, ink, true)
        button(
            pageLayer,
            "settings-updates",
            "Лад ${BuildConfig.VERSION_NAME} · Обновления",
            1336,
            164,
            488,
            64,
        ) {
            showModal("updates")
        }
        val body = frame(pageLayer, 248, 244, 1576, 708)
        val accentName =
            when (s.settings.accent) {
                0xFF83AED3 -> "голубой"
                0xFFB1A0D5 -> "сиреневый"
                0xFFB6C5A1 -> "зелёный"
                0xFFDAAA92 -> "персиковый"
                else -> "светлый"
            }
        val delay = getSharedPreferences("tv-ui", MODE_PRIVATE).getLong("ambient-seconds", 30)
        settingRow(
            body,
            "setting-filter",
            "Не воспроизводить 18+",
            if (s.settings.blockExplicit) "Включено · только в Ладе"
            else "Выключено · только в Ладе",
            0,
        ) {
            engine.settings(s.settings.copy(blockExplicit = !s.settings.blockExplicit))
        }
        settingRow(
            body,
            "setting-appearance",
            "Оформление",
            "C4 · $accentName акцент · OLED " + if (s.settings.oled) "вкл." else "выкл.",
            88,
        ) {
            showModal("appearance")
        }
        settingRow(
            body,
            "setting-glass",
            "Стекло",
            if (s.settings.glass) "Включено · облегчённый режим для ТВ" else "Выключено",
            176,
        ) {
            engine.settings(s.settings.copy(glass = !s.settings.glass))
        }
        settingRow(
            body,
            "setting-scale",
            "Размер интерфейса",
            if (s.settings.scale == 1f) "Обычный · можно увеличить" else "Увеличенный · 115%",
            264,
        ) {
            engine.settings(s.settings.copy(scale = if (s.settings.scale >= 1.1f) 1f else 1.15f))
        }
        settingRow(
            body,
            "setting-ambient",
            "Режим картины",
            if (delay == 0L) "Только вручную" else "Через $delay секунд без нажатий",
            352,
        ) {
            showModal("ambient-settings")
        }
        settingRow(
            body,
            "setting-account",
            "Аккаунт",
            if (s.profile?.plus == true) "Яндекс Плюс · сменить или выйти"
            else "Войти или сменить аккаунт",
            440,
        ) {
            showModal("account")
        }
        settingRow(
            body,
            "setting-wave",
            "Яркость волны",
            "${s.settings.waveIntensity.toInt()}%",
            528,
        ) {
            val values = listOf(0f, 25f, 50f, 75f, 100f)
            engine.settings(
                s.settings.copy(
                    waveIntensity =
                        values[(values.indexOf(s.settings.waveIntensity) + 1) % values.size]
                )
            )
        }
        settingRow(body, "setting-wave-smoothing", "Плавность движения",
            "${s.settings.waveSmoothing.toInt()}% · меньше — резче, больше — мягче", 616) {
            val values = listOf(0f, 25f, 50f, 75f, 100f)
            engine.settings(s.settings.copy(waveSmoothing = values[(values.indexOf(s.settings.waveSmoothing) + 1) % values.size]))
        }
        hint("OK — изменить    ↑ / ↓ — настройка    Назад — меню")
    }

    private fun drawUpdates(dim: FrameLayout) {
        val u = updater.state.value
        updatePhase = u.phase
        val p = glass(dim, 960, 96, 864, 888)
        label(p, "Обновления Лада", 40, 40, 784, 68, 44, ink, true)
        label(p, "Лад ${BuildConfig.VERSION_NAME} · стабильный выпуск", 40, 120, 784, 48, 28, muted)
        val title =
            when (u.phase) {
                "checking" -> "Проверяем GitHub…"
                "available" -> "Доступен Лад ${u.version}"
                "downloading" -> "Скачивание обновления"
                "ready" -> "Лад ${u.version} готов к установке"
                "current" -> "Установлена последняя версия"
                "error" -> "Не удалось обновить Лад"
                "installing" -> "Установка обновления"
                else -> "Обновления с GitHub"
            }
        label(p, title, 40, 208, 784, 64, 34, ink, true)
        updateProgressLabel =
            paragraph(
                p,
                if (u.phase == "downloading") "Загружено ${u.progress}% · можно слушать музыку"
                else
                    u.message.ifBlank {
                        if (u.phase == "ready")
                            "Откроется системное окно установки. Вход, настройки и очередь сохранятся."
                        else "Проверка и загрузка выполняются по твоей команде."
                    },
                40,
                292,
                784,
                132,
                28,
            )
        if (u.notes.isNotBlank() && u.phase in listOf("available", "ready"))
            paragraph(p, u.notes.take(600), 40, 440, 784, 184, 26)
        when (u.phase) {
            "available" ->
                button(
                    p,
                    "update-download",
                    "Скачать · ${(u.size+1048575)/1048576} МБ",
                    40,
                    672,
                    784,
                    72,
                ) {
                    updater.download()
                }
            "ready" ->
                button(p, "update-install", "Установить обновление", 40, 672, 784, 72) {
                    updater.install(this)
                }
            "checking",
            "downloading" ->
                button(p, "update-cancel", "Отменить", 40, 672, 784, 72) { updater.cancel() }
            "installing" ->
                label(p, "Подтверди установку в системном окне", 40, 672, 784, 72, 26, muted)
            else ->
                button(p, "update-check", "Проверить обновления", 40, 672, 784, 72) {
                    updater.check()
                }
        }
        button(p, "update-close", "Закрыть", 40, 776, 784, 64) {
            modal = null
            build(returnFocus)
        }
    }

    private fun drawWavePreferences(dim: FrameLayout) {
        val p = glass(dim, 800, 96, 1024, 888)
        label(p, "Настроить волну", 40, 32, 944, 64, 40, ink, true)
        label(p, "Твои настройки Яндекс Музыки", 40, 104, 944, 40, 26, muted)
        if (wavePreferenceLoading) label(p, "Загружаю настройки…", 40, 200, 944, 64, 28, muted)
        if (wavePreferenceError.isNotBlank()) {
            if (wavePreferences == null)
                paragraph(p, wavePreferenceError, 40, 168, 944, 100, 26, muted)
            else label(p, wavePreferenceError, 40, 700, 944, 28, 20, muted)
        }
        var y = 176
        wavePreferences?.groups?.forEach { g ->
            label(p, g.name, 40, y, 944, 40, 28, ink, true)
            y += 44
            val columns = 3
            val width = 300
            g.values.forEachIndexed { i, v ->
                val id = "wave-choice-${g.key}-$i"
                val b =
                    button(
                        p,
                        id,
                        (if (wavePreferences?.selected?.get(g.key) == v.value) "✓ " else "") +
                            v.name,
                        40 + (i % columns) * 312,
                        y + (i / columns) * 60,
                        width,
                        52,
                    ) {
                        wavePreferences =
                            wavePreferences!!.copy(
                                selected = wavePreferences!!.selected + (g.key to v.value)
                            )
                        build(id)
                    }
                b.contentDescription =
                    v.name +
                        if (wavePreferences?.selected?.get(g.key) == v.value) " · выбрано" else ""
            }
            y += ((g.values.size + columns - 1) / columns) * 60 + 16
        }
        if (wavePreferences != null && !wavePreferenceLoading)
            button(p, "wave-apply", "Со следующего трека", 40, 736, 944, 64) {
                wavePreferenceLoading = true
                wavePreferenceError = ""
                build("wave-close")
                scope.launch {
                    try {
                        engine.applyWavePreferences(wavePreferences!!)
                        modal = null
                        build("wave")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        wavePreferenceError = e.message ?: "Не удалось сохранить настройки"
                    } finally {
                        wavePreferenceLoading = false
                        if (modal == "wave-settings") build("wave-close")
                    }
                }
            }
        button(p, "wave-close", "Закрыть", 40, 816, 944, 56) {
            modal = null
            build(returnFocus)
        }
    }

    private fun drawModal(type: String) {
        if (type == "move") return
        buttons.values.forEach { it.isFocusable = false }
        buttons.clear()
        actions.clear()
        val dim = frame(overlay, 0, 0, 1920, 1080, Color.argb(128, 0, 0, 0))
        if (type == "updates") {
            drawUpdates(dim)
            return
        }
        if (type == "wave-settings") {
            drawWavePreferences(dim)
            return
        }
        if (type == "devices") {
            drawDevices(dim)
            return
        }
        val p = glass(dim, 960, 96, 864, 888)
        var y = 48
        fun text(title: String, sub: String = "") {
            label(p, title, 40, y, 784, 64, 44, ink, true)
            y += 72
            if (sub.isNotBlank()) {
                paragraph(p, sub, 40, y, 784, 88, 30)
                y += 96
            }
        }
        fun choice(id: String, title: String, action: () -> Unit) {
            button(p, id, title, 40, y, 784, 64, action)
            y +=
                if (type in listOf("appearance", "ambient-settings", "account", "accents")) 100
                else 108
        }
        when (type) {
            "appearance" -> {
                text("Оформление", "Цвета C4 и настоящий чёрный фон")
                choice("appearance-accent", "Акцент") {
                    modal = "accents"
                    build()
                }
                choice(
                    "appearance-oled",
                    if (s.settings.oled) "OLED · включён" else "OLED · выключен",
                ) {
                    engine.settings(s.settings.copy(oled = !s.settings.oled))
                }
                choice("appearance-done", "Готово") {
                    modal = null
                    build(returnFocus)
                }
            }
            "ambient-settings" -> {
                text("Режим картины", "Когда скрывать управление плеером")
                for (value in listOf(30L, 60L, 300L, 0L)) choice(
                    "ambient-$value",
                    if (value == 0L) "Только вручную" else "Через $value секунд",
                ) {
                    getSharedPreferences("tv-ui", MODE_PRIVATE)
                        .edit()
                        .putLong("ambient-seconds", value)
                        .apply()
                    modal = null
                    build(returnFocus)
                }
            }
            "account" -> {
                text("Аккаунт", s.profile?.name ?: "Войти в Яндекс")
                choice("account-change", "Сменить аккаунт") { go("login", "login-code") }
                choice("account-logout", "Выйти из аккаунта") {
                    modal = "logout"
                    build()
                }
                choice("account-done", "Готово") {
                    modal = null
                    build(returnFocus)
                }
            }
            "track-actions" -> {
                val t = dialogTrack ?: s.track ?: return
                artwork(p, t.title, t.cover, 40, 40, 88)
                label(p, t.title, 152, 40, 672, 42, 32, ink, true)
                label(p, t.artist, 152, 96, 672, 36, 28, muted)
                y = 164
                choice("action-like", if (t.liked) "Убрать лайк" else "Нравится") {
                    modal = null
                    engine.task { engine.like(t) }
                    build(returnFocus)
                }
                choice("action-next", "Играть следующим") {
                    modal = null
                    runCatching { engine.add(listOf(t), true) }
                        .onFailure { engine.notice(it.message ?: "Ошибка очереди") }
                    build(returnFocus)
                }
                choice("action-add", "В конец очереди") {
                    modal = null
                    runCatching { engine.add(listOf(t)) }
                        .onFailure { engine.notice(it.message ?: "Ошибка очереди") }
                    build(returnFocus)
                }
                val qi = dialogQueueIndex
                if (qi != null && qi != s.index) {
                    choice("action-move", "Переместить в очереди") {
                        modal = "move"
                        moving = QueueMove(qi, s.index, s.queue.size)
                        build("queue-$qi")
                    }
                    choice("action-remove", "Убрать из очереди") {
                        modal = null
                        engine.remove(qi)
                        build("queue-${(qi-1).coerceAtLeast(0)}")
                    }
                } else if (t.albumId.isNotBlank())
                    choice("action-album", "Перейти к альбому") {
                        modal = null
                        openCollection(t.albumCard())
                    }
                val a = t.artists.firstOrNull { it.id.isNotBlank() }
                if (a != null && y < 760)
                    choice("action-artist", "Перейти к исполнителю") {
                        modal = null
                        openCollection(Card(a.id, "artist", a.name))
                    }
            }
            "collection-actions" -> {
                val c = opened ?: return
                label(p, c.title, 40, 40, 784, 60, 44, ink, true)
                label(
                    p,
                    if (c.type == "album") "Альбом" else "Плейлист",
                    40,
                    116,
                    784,
                    36,
                    30,
                    muted,
                )
                y = 172
                suspend fun tracks() =
                    if (catalogCache.containsKey(c.key)) catalogCache[c.key]!!.tracks
                    else if (s.demo) MusicEngine.demoTracks else engine.api.collection(c).tracks
                choice("action-play", "Слушать") {
                    modal = null
                    fetch {
                        val list = tracks()
                        val i =
                            list.indexOfFirst {
                                it.available && (!s.settings.blockExplicit || !it.explicit)
                            }
                        check(i >= 0) { "Нет подходящих треков" }
                        engine.play(list, i, c.title)
                    }
                }
                choice("action-shuffle", "Перемешать") {
                    modal = null
                    fetch {
                        val list =
                            tracks()
                                .filter {
                                    it.available && (!s.settings.blockExplicit || !it.explicit)
                                }
                                .shuffled()
                        check(list.isNotEmpty()) { "Нет подходящих треков" }
                        engine.play(list, 0, c.title)
                    }
                }
                choice("action-next", "Играть следующим") {
                    modal = null
                    fetch { engine.add(tracks(), true) }
                }
                choice("action-add", "В конец очереди") {
                    modal = null
                    fetch { engine.add(tracks()) }
                }
                val liked =
                    if (c.type == "album") library.albums.any { it.id == c.id }
                    else library.playlists.any { it.key == c.key }
                choice(
                    "action-like",
                    if (liked) "Убрать из коллекции" else "Добавить в коллекцию",
                ) {
                    modal = null
                    fetch {
                        if (!s.demo)
                            engine.api.like(
                                s.profile!!.uid,
                                c.id,
                                if (c.type == "album") "album" else "playlist",
                                !liked,
                            )
                        loadLibrary()
                    }
                }
            }
            "player-more" -> {
                text("Музыка на телевизоре", s.outputName)
                choice("more-like", if (s.track?.liked == true) "Убрать лайк" else "Нравится") {
                    modal = null
                    s.track?.let { engine.task { engine.like(it) } }
                    build(returnFocus)
                }
                choice(
                    "more-shuffle",
                    if (s.shuffle) "Выключить перемешивание" else "Перемешать очередь",
                ) {
                    modal = null
                    engine.shuffle()
                    build(returnFocus)
                }
                choice(
                    "more-repeat",
                    "Повтор: " + listOf("выключен", "трек", "очередь")[s.repeat],
                ) {
                    engine.repeat()
                }
                choice("more-output", "Где звучит музыка") { openOutputs() }
                choice("more-ambient", "Режим картины") {
                    modal = null
                    ambient = true
                    build()
                }
                choice("more-volume", "Громкость: ${(s.volume*100).toInt()}%") {
                    modal = "volume"
                    build()
                }
            }
            "volume" -> {
                text("Громкость", s.outputName)
                choice("vol-down", "Тише −5%") { engine.task { engine.volume(s.volume - .05f) } }
                choice("vol-up", "Громче +5%") { engine.task { engine.volume(s.volume + .05f) } }
                choice("vol-mute", if (s.volume > 0) "Выключить звук" else "Включить звук") {
                    engine.task { engine.volume(if (s.volume > 0) 0f else .4f) }
                }
            }
            "accents" -> {
                text("Акцент", "Фокус всегда отмечен контрастным кольцом")
                for ((i, c) in
                    listOf(0xFFE2E6EC, 0xFF83AED3, 0xFFB1A0D5, 0xFFB6C5A1, 0xFFDAAA92)
                        .withIndex()) choice(
                    "accent-$i",
                    listOf("Светлый", "Голубой", "Сиреневый", "Зелёный", "Персиковый")[i],
                ) {
                    engine.settings(s.settings.copy(accent = c))
                }
            }
            "logout" -> {
                text("Выйти из аккаунта?", "Вход на других устройствах не изменится.")
                choice("logout-cancel", "Остаться") {
                    modal = null
                    build(returnFocus)
                }
                choice("logout-yes", "Выйти") {
                    engine.logout()
                    home = Catalog()
                    library = Library()
                    catalogCache.clear()
                    history.clear()
                    modal = null
                    page = "home"
                    build("wave")
                }
            }
        }
        label(p, "Назад — вернуться", 40, 812, 784, 40, 26, muted)
    }

    private fun outputPickerButton() {
        val title = "Звук: " + if (s.output == "phone") "этот телевизор" else s.outputName
        button(pageLayer, "output-picker", title, 1390, 64, 434, 64) { openOutputs() }
    }

    private fun openOutputs() {
        showModal("devices")
        scanOutputs()
    }

    private fun scanOutputs() {
        if (deviceScanJob?.isActive == true) return
        devicesLoading = true
        devicesError = ""
        if (modal == "devices") build(activeFocus)
        deviceScanJob =
            scope.launch {
                try {
                    engine.refreshDevices()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    devicesError = e.message ?: "Не удалось найти Станции"
                } finally {
                    devicesLoading = false
                    if (modal == "devices") build(activeFocus)
                }
            }
    }

    private fun selectOutput(device: Device?) {
        if (pendingOutputId != null) return
        if (
            (device == null && s.output == "phone") ||
                (device?.id == s.deviceId && s.output == "station" && s.connection == "connected")
        ) {
            modal = null
            build(returnFocus)
            return
        }
        engine.task {
            pendingOutputId = device?.id ?: "tv"
            build(activeFocus)
            try {
                if (device == null) engine.transferPhone()
                else {
                    var target = device
                    if (target.host.isBlank() && !s.demo) {
                        deviceScanJob?.join()
                        target = s.devices.find { it.id == device.id } ?: device
                        if (target.host.isBlank()) {
                            engine.refreshDevices()
                            target = s.devices.find { it.id == device.id } ?: device
                        }
                    }
                    check(s.demo || target.host.isNotBlank()) {
                        "Станция не найдена в сети. Подключи телевизор и колонку к одной домашней сети и обнови список."
                    }
                    engine.transfer(target)
                }
                modal = null
                build(returnFocus)
            } finally {
                pendingOutputId = null
                if (modal == "devices") build(activeFocus)
            }
        }
    }

    private fun drawDevices(dim: FrameLayout) {
        val p = glass(dim, 888, 80, 936, 920)
        label(p, "Где звучит музыка", 40, 40, 856, 55, 46, ink, true)
        paragraph(
            p,
            "Песня и позиция сохранятся. Обложка и текст останутся на телевизоре.",
            40,
            119,
            856,
            64,
            30,
        )
        button(
            p,
            "device-tv",
            when {
                pendingOutputId == "tv" -> "Переносим на телевизор…"
                s.output == "phone" -> "Этот телевизор · сейчас"
                else -> "Этот телевизор"
            },
            40,
            199,
            856,
            80,
        ) {
            selectOutput(null)
        }
        val scroll =
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = false
                clipToPadding = false
            }
        val devices = FrameLayout(this)
        scroll.addView(
            devices,
            FrameLayout.LayoutParams(-1, px((s.devices.size * 100).coerceAtLeast(188))),
        )
        place(p, scroll, 40, 299, 856, 188)
        s.devices.forEachIndexed { i, d ->
            val suffix =
                when {
                    pendingOutputId == d.id -> " · подключаем…"
                    s.deviceId == d.id && s.output == "station" ->
                        if (s.connection == "connected") " · сейчас" else " · переподключаемся…"
                    d.host.isNotBlank() || s.demo -> " · в сети"
                    devicesLoading -> " · ищем в сети…"
                    else -> " · не найдена в сети"
                }
            button(devices, "device-${d.id}", d.name + suffix, 0, i * 100, 856, 88) {
                selectOutput(d)
            }
        }
        if (s.devices.isEmpty())
            paragraph(
                devices,
                when {
                    devicesLoading -> "Ищем Станции в аккаунте и домашней сети…"
                    devicesError.isNotBlank() -> devicesError
                    else ->
                        "Станции пока не найдены. Они должны быть в этом аккаунте Яндекса и в одной сети с телевизором."
                },
                24,
                24,
                808,
                144,
                28,
                muted,
            )
        button(
            p,
            "devices-refresh",
            if (devicesLoading) "Ищем Станции…" else "Обновить список",
            40,
            507,
            856,
            64,
        ) {
            scanOutputs()
        }
        paragraph(
            p,
            if (devicesError.isNotBlank() && s.devices.isNotEmpty()) devicesError
            else "Если Станция пропадёт, звук на ТВ сам не включится.",
            40,
            595,
            856,
            48,
            24,
            muted,
        )
        button(p, "more-volume", "Громкость · ${(s.volume * 100).toInt()}%", 40, 655, 856, 64) {
            modal = "volume"
            build()
        }
        button(p, "devices-done", "Готово", 40, 743, 856, 64) {
            modal = null
            build(returnFocus)
        }
        label(
            p,
            "↑ / ↓ — устройство    OK — выбрать    Назад — вернуться",
            40,
            835,
            856,
            32,
            22,
            muted,
        )
    }

    private fun scrollParent(v: View): ScrollView? {
        var p = v.parent
        while (p is View) {
            if (p is ScrollView) return p
            p = p.parent
        }
        return null
    }

    private fun keepFocusVisible(view: View) {
        val scrolling = scrollParent(view) ?: return
        scrolling.post {
            if (!view.hasFocus()) return@post
            val v = IntArray(2)
            val c = IntArray(2)
            view.getLocationInWindow(v)
            scrolling.getLocationInWindow(c)
            val withDock =
                s.profile != null &&
                    page !in listOf("login", "player", "lyrics", "search", "settings")
            val bottom =
                minOf(
                    c[1] + scrolling.height,
                    px(if (withDock) 888 else if (page == "lyrics") 792 else 984),
                )
            val delta =
                if (v[1] + view.height > bottom) v[1] + view.height - bottom + px(8)
                else if (v[1] < c[1]) v[1] - c[1] - px(8) else 0
            if (delta != 0) scrolling.smoothScrollBy(0, delta)
        }
    }

    private fun moveFocus(code: Int) {
        val current = buttons[activeFocus]?.takeIf { it.hasFocus() } ?: root.findFocus() ?: return
        if (modal == null && !menu && buttons.containsKey("output-picker")) {
            val firstRow =
                activeFocus in
                    listOf(
                        "wave",
                        "liked-play",
                        "liked-open",
                        "collection-play",
                        "collection-shuffle",
                        "collection-actions",
                        "setting-filter",
                        "nav-home",
                        "player-album",
                        "follow",
                    ) || activeFocus.startsWith("key-0-")
            if (code == KeyEvent.KEYCODE_DPAD_UP && firstRow) {
                outputReturnFocus = activeFocus
                buttons["output-picker"]?.requestFocus()
                return
            }
            if (
                activeFocus == "output-picker" &&
                    code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            ) {
                (buttons[outputReturnFocus]
                        ?: buttons.values.firstOrNull { it.tag != "output-picker" })
                    ?.requestFocus()
                return
            }
        }
        if (menu && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            menu = false
            build(returnFocus)
            return
        }
        if (
            page in listOf("player", "lyrics") &&
                code == KeyEvent.KEYCODE_DPAD_UP &&
                activeFocus in listOf("previous", "pause", "next", "text", "queue", "more")
        ) {
            buttons["seek"]?.requestFocus()
            return
        }
        if (page == "player" && activeFocus == "seek" && code == KeyEvent.KEYCODE_DPAD_DOWN) {
            buttons["pause"]?.requestFocus()
            return
        }
        val origin = IntArray(2)
        current.getLocationInWindow(origin)
        if (
            code == KeyEvent.KEYCODE_DPAD_LEFT &&
                modal == null &&
                !menu &&
                page !in listOf("player", "lyrics", "login") &&
                !activeFocus.startsWith("nav-") &&
                (origin[0] <= px(320) || activeFocus.matches(Regex("key-[0-9]+-0")))
        ) {
            returnFocus = activeFocus
            menu = true
            build("nav-" + if (page in listOf("collection", "library")) "library" else page)
            return
        }
        val cx = origin[0] + current.width / 2f
        val cy = origin[1] + current.height / 2f
        val scroll = scrollParent(current)
        val vertical = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP
        fun score(v: View): Float {
            if (v === current || !v.isFocusable || !v.isShown) return Float.MAX_VALUE
            val pt = IntArray(2)
            v.getLocationInWindow(pt)
            val dx = pt[0] + v.width / 2f - cx
            val dy = pt[1] + v.height / 2f - cy
            val forward =
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -dx
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dx
                    KeyEvent.KEYCODE_DPAD_UP -> -dy
                    else -> dy
                }
            val cross = if (vertical) abs(dx) else abs(dy)
            if (forward < 8) return Float.MAX_VALUE
            return forward + cross * 4f
        }
        val candidates = buttons.values.filter { score(it) < Float.MAX_VALUE }
        val same =
            if (vertical && scroll != null)
                candidates.filter {
                    scrollParent(it) === scroll &&
                        abs((it.left + it.width / 2f) - (current.left + current.width / 2f)) <
                            px(400)
                }
            else emptyList()
        val target = (if (same.isNotEmpty()) same else candidates).minByOrNull { score(it) }
        if (target != null) {
            if (page == "lyrics" && target.tag?.toString()?.startsWith("line-") == true)
                reading = true
            target.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        val now = SystemClock.elapsedRealtime()
        lastInput = now
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.repeatCount == 0) back()
                return true
            }
            if (
                key in
                    listOf(
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                    )
            ) {
                if (event.repeatCount == 0)
                    engine.task {
                        if (key == KeyEvent.KEYCODE_MEDIA_PLAY) engine.resume()
                        else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) engine.pause()
                        else engine.toggle()
                    }
                return true
            }
            if (key == KeyEvent.KEYCODE_MEDIA_NEXT || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                if (event.repeatCount == 0)
                    engine.task {
                        engine.advance(if (key == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1)
                    }
                return true
            }
            if (ambient && key in listOf(19, 20, 21, 22, 23, 66)) {
                ambient = false
                build("pause")
                return true
            }
            val m = moving
            if (m != null) {
                when (key) {
                    19,
                    20 -> {
                        m.step(if (key == 19) -1 else 1)
                        build("queue-${m.from}")
                    }
                    23,
                    66 -> {
                        engine.move(m.from, m.target)
                        moving = null
                        modal = null
                        build("queue-${m.target}")
                    }
                }
                return true
            }
            if (seekDraft != null) {
                when (key) {
                    21,
                    22 -> {
                        seekDraft =
                            (seekDraft!! +
                                    (if (key == 21) -1 else 1) *
                                        (if (event.repeatCount > 5) 15000 else 5000))
                                .coerceIn(0, s.durationMs)
                        build("seek")
                    }
                    23,
                    66 -> {
                        val pos = seekDraft!!
                        seekDraft = null
                        engine.task { engine.seek(pos) }
                        build("seek")
                    }
                }
                return true
            }
            if (key in listOf(19, 20, 21, 22)) {
                moveFocus(key)
                return true
            }
            if (key == KeyEvent.KEYCODE_MENU) {
                dialogTrack = s.track
                dialogQueueIndex = null
                showModal("track-actions")
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_UP && key == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
