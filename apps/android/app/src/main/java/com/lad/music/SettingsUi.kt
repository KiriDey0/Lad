// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.foundation.*
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
import androidx.compose.ui.unit.*

@Composable
fun LoginView(
    engine: MusicEngine,
    auth: AuthState,
    browser: (String) -> Unit,
    clipboard: ClipboardManager,
    bottom: Dp,
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
fun SettingsView(s: MusicState, engine: MusicEngine, modifier: Modifier, bottom: Dp) {
    val p = LocalPalette.current
    val settings = s.settings
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
        Section("Звук")
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
        Label(
            "Лад · Android ${BuildConfig.VERSION_NAME}\nНеофициальный клиент Яндекс Музыки",
            12,
            p.muted,
        )
    }
}
