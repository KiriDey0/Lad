// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.foundation.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun LoginView(
    engine: MusicEngine,
    auth: AuthState,
    browser: (String) -> Unit,
    clipboard: ClipboardManager,
    bottom: Dp,
    localMusic: () -> Unit = {},
) {
    val p = LocalPalette.current
    var token by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(Icons.Outlined.GraphicEq, null, Modifier.size(64.dp), tint = p.accent)
        Label("Музыка здесь.\nЗвук — где хочешь.", 32, weight = FontWeight.SemiBold)
        Label(
            "Твоя Яндекс Музыка на телефоне и Станции. Очередь, текст песни и любимые альбомы рядом.",
            16,
            p.muted,
        )
        if (auth.code.isBlank()) {
            Action(
                "Войти через Яндекс",
                Icons.Outlined.ArrowOutward,
                enabled = !auth.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                engine.beginLogin()
            }
        } else
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(p.surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Label("КОД ПОДТВЕРЖДЕНИЯ", 11, p.muted)
                Label(
                    auth.code,
                    28,
                    weight = FontWeight.SemiBold,
                    modifier =
                        Modifier.clickable {
                            clipboard.setText(AnnotatedString(auth.code))
                            engine.notice("Код скопирован")
                        },
                )
                Label(
                    "Подтверди вход на странице Яндекса. Это окно можно оставить открытым.",
                    14,
                    p.muted,
                )
                Action(
                    "Открыть Яндекс",
                    Icons.Outlined.OpenInNew,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val url = auth.url
                    if (
                        url.startsWith("https://") &&
                            (android.net.Uri.parse(url).host.orEmpty().endsWith("yandex.ru") ||
                                android.net.Uri.parse(url).host.orEmpty() == "ya.ru")
                    )
                        browser(url)
                    else engine.notice("Яндекс вернул неизвестный адрес входа")
                }
                TextButton({ engine.beginLogin() }) { Text("Запросить новый код") }
            }
        if (auth.loading) CircularProgressIndicator()
        if (auth.error.isNotBlank()) ErrorBox(auth.error) { engine.beginLogin() }
        Action("Музыка на устройстве", Icons.Outlined.Folder, true, modifier = Modifier.fillMaxWidth(), onClick = localMusic)
        Action(
            "Посмотреть интерфейс",
            Icons.Outlined.Headphones,
            true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            engine.cancelLogin()
            engine.demo()
        }
        TextButton({ advanced = !advanced }) {
            Text(if (advanced) "Скрыть вход с токеном" else "Войти с токеном · для отладки")
        }
        if (advanced) {
            OutlinedTextField(
                token,
                { token = it },
                Modifier.fillMaxWidth(),
                label = { Text("OAuth-токен Яндекс Музыки") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            Action(
                "Войти с токеном",
                Icons.Outlined.Login,
                enabled = token.isNotBlank() && !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                importing = true
                engine.task {
                    try {
                        engine.login(token)
                        token = ""
                    } finally {
                        importing = false
                    }
                }
            }
        }
        Label("Сессия хранится в зашифрованном виде на этом телефоне.", 12, p.muted)
    }
}

@Composable
fun SettingToggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Label(title, 15, weight = FontWeight.Medium)
            Label(subtitle, 12, LocalPalette.current.muted, modifier = Modifier.padding(top = 6.dp))
        }
        Switch(value, onChange, Modifier.semantics { contentDescription = title })
    }
}

@Composable
fun SettingsView(
    s: MusicState,
    engine: MusicEngine,
    modifier: Modifier,
    bottom: Dp,
    restartInterface: () -> Unit,
) {
    val p = LocalPalette.current
    val settings = s.settings
    var audioEditor by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(p.surface)
                .padding(16.dp)
        ) {
            Label(s.profile?.name ?: "Аккаунт", 20, weight = FontWeight.SemiBold)
            Label(
                if (s.demo) "Демонстрация · без звука"
                else if (s.profile?.plus == true) "Яндекс Плюс" else "Яндекс Музыка",
                13,
                p.muted,
            )
        }
        Section("Оформление")
        SettingToggle("Экран блокировки", "Три стиля · ${settings.lockScreen.style.title}", settings.lockScreen.enabled) {
            engine.settings(settings.copy(lockScreen = settings.lockScreen.copy(enabled = it)))
        }
        val lockContext = androidx.compose.ui.platform.LocalContext.current
        Action("Оформление экрана блокировки", Icons.Outlined.Lock, tonal = true, modifier = Modifier.fillMaxWidth()) {
            lockContext.startActivity(android.content.Intent(lockContext, LockScreenSettingsActivity::class.java))
        }
        SettingToggle("OLED", "Настоящий чёрный фон", settings.oled) {
            engine.settings(settings.copy(oled = it))
        }
        SettingToggle("Стекло", "C4 · blur 14 · мобильная подложка 76%", settings.glass) {
            engine.settings(settings.copy(glass = it))
        }
        SettingToggle("Цветная тема", "Оттенок акцента в поверхностях", settings.tinted) {
            engine.settings(settings.copy(tinted = it))
        }
        Label("Акцент", 16, weight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0xFFE2E6EC, 0xFF83AED3, 0xFFB1A0D5, 0xFFB6C5A1, 0xFFDAAA92).forEach { color ->
                Box(
                    Modifier.size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(color))
                        .border(
                            if (settings.accent == color) 3.dp else 0.dp,
                            p.text,
                            RoundedCornerShape(24.dp),
                        )
                        .clickable { engine.settings(settings.copy(accent = color)) }
                        .semantics { contentDescription = "Цвет акцента ${color.toString(16)}" }
                )
            }
        }
        Section("Размер интерфейса")
        Label("${(settings.scale*100).toInt()}%", 14, p.muted)
        C4Slider(
            settings.scale,
            { engine.settings(settings.copy(scale = it)) },
            valueRange = .85f..1.2f,
            steps = 6,
            modifier = Modifier.semantics { contentDescription = "Размер интерфейса" },
        )
        Section("Моя волна")
        Label("Яркость волны · ${settings.waveIntensity.toInt()}%", 14, p.muted)
        C4Slider(
            settings.waveIntensity,
            { engine.settings(settings.copy(waveIntensity = it)) },
            valueRange = 0f..100f,
            steps = 19,
            modifier = Modifier.semantics { contentDescription = "Яркость волны" },
        )
        Label("Плавность движения · ${settings.waveSmoothing.toInt()}%", 14, p.muted)
        C4Slider(
            settings.waveSmoothing,
            { engine.settings(settings.copy(waveSmoothing = it)) },
            valueRange = 0f..100f,
            steps = 19,
            modifier = Modifier.semantics { contentDescription = "Плавность движения" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Резче", 12, p.muted)
            Label("Мягче", 12, p.muted)
        }
        Label("Меньше — резкая реакция на музыку. Больше — мягкие и спокойные движения.", 13, p.muted)
        Section("Звук")
        SettingToggle(
            "Обработка звука",
            "10-полосный эквалайзер и native playback effect для этого телефона.",
            settings.audioEnabled,
        ) {
            engine.settings(settings.copy(audioEnabled = it))
        }
        Label("Пресет", 16, weight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("flat" to "Ровный", "warm" to "Тёплый свет", "rock" to "Реп / рок", "night" to "Ночной", "custom" to "Свой профиль").forEach { (id, title) ->
                FilterChip(
                    selected = settings.audioPreset == id,
                    onClick = {
                        if (id == "custom") { audioEditor = true; return@FilterChip }
                        val gains = when (id) {
                            "flat" -> List(10) { 0f }
                            "rock" -> listOf(3.5f, 3f, 1.5f, -1f, -1.5f, 0f, 1.5f, 2f, 2f, 1.5f)
                            "night" -> listOf(-2f, -1f, 0f, 1f, 1.5f, 1f, .5f, -.5f, -1f, -1.5f)
                            else -> listOf(2.5f, 2f, 1f, 0f, -.5f, 0f, .5f, 1f, 1f, .5f)
                        }
                        engine.settings(settings.copy(audioPreset = id, audioBands = gains))
                    },
                    label = { Text(title, maxLines = 1) },
                )
            }
        }
        Action(
            "Открыть эквалайзер",
            Icons.Outlined.Tune,
            tonal = true,
            modifier = Modifier.fillMaxWidth(),
        ) { audioEditor = true }
        Label("Предусиление · ${if (settings.audioPreampDb > 0) "+" else ""}${settings.audioPreampDb.toInt()} dB", 14, p.muted)
        C4Slider(
            settings.audioPreampDb,
            { engine.settings(settings.copy(audioPreampDb = it, audioPreset = "custom")) },
            valueRange = -12f..6f,
            steps = 35,
            modifier = Modifier.semantics { contentDescription = "Предусиление звука" },
        )
        SettingToggle(
            "Синхронизировать громкость с устройством",
            "Кнопки телефона управляют Ладом и Станцией. Выключено по умолчанию.",
            settings.syncVolume,
        ) {
            engine.settings(settings.copy(syncVolume = it))
        }
        Section("Контент")
        SettingToggle(
            "Не воспроизводить 18+",
            "Пропускать треки с маркировкой Яндекса. Действует только в Ладе.",
            settings.blockExplicit,
        ) {
            engine.settings(settings.copy(blockExplicit = it))
        }
        Label(
            "Настройки аккаунта Яндекса живут отдельно. Музыка, включённая голосом или из другого приложения, не фильтруется Ладом.",
            12,
            p.muted,
        )
        Section("Синхронный текст")
        Label("Сдвиг: ${settings.lyricOffsetMs} мс", 14, p.muted)
        C4Slider(
            settings.lyricOffsetMs.toFloat(),
            { engine.settings(settings.copy(lyricOffsetMs = (it / 100).toLong() * 100)) },
            valueRange = -3000f..3000f,
            steps = 59,
            modifier = Modifier.semantics { contentDescription = "Сдвиг синхронизации текста" },
        )
        Action(
            "Сбросить оформление",
            Icons.Outlined.RestartAlt,
            true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            engine.settings(
                Settings(
                    blockExplicit = settings.blockExplicit,
                    lyricOffsetMs = settings.lyricOffsetMs,
                    syncVolume = settings.syncVolume,
                    classicDesign = settings.classicDesign,
                )
            )
        }
        Action(
            if (s.demo) "Выйти из демо" else "Выйти из аккаунта",
            Icons.Outlined.Logout,
            true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            engine.logout()
        }
        UpdatesCard()
        Label(
            "Лад · Android ${installedAppVersion(androidx.compose.ui.platform.LocalContext.current).name}\nНеофициальный клиент Яндекс Музыки",
            12,
            p.muted,
        )
        Section("Версия интерфейса")
        SettingToggle(
            "Вернуться к старому дизайну",
            "Интерфейс перезапустится после смены дизайна.",
            settings.classicDesign,
        ) { classic ->
            engine.settings(engine.state.value.settings.copy(classicDesign = classic))
            restartInterface()
        }
    }
    if (audioEditor) AudioEqualizerDialog(settings, engine) { audioEditor = false }
}

@Composable
private fun AudioEqualizerDialog(settings: Settings, engine: MusicEngine, close: () -> Unit) {
    val p = LocalPalette.current
    val frequencies = listOf("31.5", "63", "125", "250", "500", "1 k", "2 k", "4 k", "8 k", "16 k")
    var drafts by remember(settings.audioBands) {
        mutableStateOf(settings.audioBands.map { formatAudioGain(it) }.toMutableList())
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f),
            shape = RoundedCornerShape(28.dp),
            color = p.surface,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Эквалайзер", 24, weight = FontWeight.SemiBold)
                        Label("10 полос · −12…+12 dB · применяется сразу", 12, p.muted)
                    }
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Закрыть") }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("flat" to "Ровный", "warm" to "Тёплый свет", "rock" to "Реп / рок", "night" to "Ночной").forEach { (id, title) ->
                        FilterChip(
                            selected = settings.audioPreset == id,
                            onClick = {
                                val gains = when (id) {
                                    "flat" -> List(10) { 0f }
                                    "rock" -> listOf(3.5f, 3f, 1.5f, -1f, -1.5f, 0f, 1.5f, 2f, 2f, 1.5f)
                                    "night" -> listOf(-2f, -1f, 0f, 1f, 1.5f, 1f, .5f, -.5f, -1f, -1.5f)
                                    else -> listOf(2.5f, 2f, 1f, 0f, -.5f, 0f, .5f, 1f, 1f, .5f)
                                }
                                drafts = gains.map { formatAudioGain(it) }.toMutableList()
                                engine.settings(settings.copy(audioPreset = id, audioBands = gains))
                            },
                            label = { Text(title, maxLines = 1) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    frequencies.forEachIndexed { index, frequency ->
                        val gain = drafts.getOrNull(index)?.toFloatOrNull() ?: 0f
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Label(frequency, 12, p.muted, modifier = Modifier.width(46.dp))
                            C4Slider(
                                gain,
                                { value ->
                                    val next = drafts.toMutableList().apply { set(index, formatAudioGain(value)) }
                                    drafts = next
                                    engine.settings(settings.copy(audioPreset = "custom", audioBands = next.map { it.toFloatOrNull() ?: 0f }))
                                },
                                valueRange = -12f..12f,
                                steps = 47,
                                modifier = Modifier.weight(1f).semantics { contentDescription = "Эквалайзер $frequency Hz" },
                            )
                            OutlinedTextField(
                                value = drafts.getOrElse(index) { "0.0" },
                                onValueChange = { raw ->
                                    val value = raw.replace(',', '.').toFloatOrNull()
                                    if (raw.isBlank() || raw.matches(Regex("[-+]?\\d{0,2}([.]\\d{0,1})?"))) {
                                        drafts = drafts.toMutableList().apply { set(index, raw) }
                                        value?.let { parsed ->
                                            val clamped = parsed.coerceIn(-12f, 12f)
                                            engine.settings(settings.copy(audioPreset = "custom", audioBands = drafts.mapIndexed { i, old -> if (i == index) clamped else old.toFloatOrNull() ?: 0f }))
                                        }
                                    }
                                },
                                modifier = Modifier.width(78.dp),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                suffix = { Text("dB", fontSize = 10.sp) },
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) { Text("Готово") }
                }
            }
        }
    }
}

private fun formatAudioGain(value: Float): String = if (value == 0f) "0.0" else "%.1f".format(java.util.Locale.US, value)
