// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.lad.music

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.*

@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val tonal: Color,
    val accent: Color,
    val text: Color = Color(0xFFF1F3F5),
    val muted: Color = Color(0xFFAAAFB9),
    val scale: Float = 1f,
)

val LocalPalette = staticCompositionLocalOf {
    Palette(
        Color(0xFF0C0D0F),
        Color(0xFF17191D),
        Color(0xFF282B31),
        Color(0xFF30353D),
        Color(0xFFE2E6EC),
    )
}
val Golos = FontFamily(Font(R.font.golos))

@Composable
fun LadTheme(settings: Settings, content: @Composable () -> Unit) {
    val accent = Color(settings.accent)
    val tinted = settings.tinted
    val p =
        Palette(
            if (settings.oled) Color.Black
            else if (tinted) lerp(Color(0xFF0C0D0F), accent, .035f) else Color(0xFF0C0D0F),
            if (tinted) lerp(Color(0xFF17191D), accent, .08f) else Color(0xFF17191D),
            if (tinted) lerp(Color(0xFF282B31), accent, .08f) else Color(0xFF282B31),
            lerp(Color(0xFF282B31), accent, .08f),
            accent,
            scale = settings.scale,
        )
    val base = Typography()
    fun style(s: androidx.compose.ui.text.TextStyle) = s.copy(fontFamily = Golos)
    val typography =
        base.copy(
            bodyLarge = style(base.bodyLarge),
            bodyMedium = style(base.bodyMedium),
            bodySmall = style(base.bodySmall),
            titleLarge = style(base.titleLarge),
            titleMedium = style(base.titleMedium),
            titleSmall = style(base.titleSmall),
            headlineLarge = style(base.headlineLarge),
            headlineMedium = style(base.headlineMedium),
            headlineSmall = style(base.headlineSmall),
            labelLarge = style(base.labelLarge),
            labelMedium = style(base.labelMedium),
            labelSmall = style(base.labelSmall),
        )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme =
                darkColorScheme(
                    primary = p.accent,
                    onPrimary = p.bg,
                    background = p.bg,
                    onBackground = p.text,
                    surface = p.surface,
                    onSurface = p.text,
                    surfaceVariant = p.raised,
                    onSurfaceVariant = p.muted,
                    outline = Color.White.copy(alpha = .14f),
                    secondary = p.accent,
                    secondaryContainer = p.tonal,
                    onSecondaryContainer = p.accent,
                    primaryContainer = p.tonal,
                    onPrimaryContainer = p.accent,
                ),
            typography = typography,
            content = content,
        )
    }
}

val LocalBackdropReveal = staticCompositionLocalOf<((Any, Boolean) -> Unit)?> { null }

val LocalBackdrop = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun Scene(modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val backdrop = LocalBackdrop.current
    Box(modifier.then(if (enabled && backdrop != null) Modifier.haze(backdrop) else Modifier)) {
        content()
    }
}

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    popup: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalPalette.current
    val backdrop = LocalBackdrop.current
    val supported = Build.VERSION.SDK_INT >= 31 && enabled && backdrop != null
    val material =
        if (supported) {
            Modifier.hazeChild(
                state = backdrop!!,
                style =
                    HazeStyle(
                        backgroundColor = p.bg,
                        tint = HazeTint(p.surface.copy(alpha = if (popup) .75f else .76f)),
                        blurRadius = 14.dp,
                        noiseFactor = 0f,
                        fallbackTint = HazeTint(p.surface),
                    ),
            ) {
                blurEnabled = true
                inputScale = HazeInputScale.Fixed(.5f)
            }
        } else Modifier.background(p.surface)
    Box(
        modifier.clip(shape).then(material).border(1.dp, Color.White.copy(alpha = .188f), shape),
        content = content,
    )
}
