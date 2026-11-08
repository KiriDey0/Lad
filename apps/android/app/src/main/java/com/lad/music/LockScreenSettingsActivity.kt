// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class LockScreenSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val engine = (application as LadApplication).engine
            val state by engine.state.collectAsStateWithLifecycle()
            LadTheme(state.settings) { LockScreenChooser(state, engine, ::finish) }
        }
    }
}

private val lockSettingsSaver = Saver<LockScreenSettings, String>(save = { it.json().toString() }, restore = { LockScreenSettings.parse(it) })

@Composable
private fun LockScreenChooser(s: MusicState, engine: MusicEngine, back: () -> Unit) {
    val context = LocalContext.current
    val p = LocalPalette.current
    var draft by rememberSaveable(stateSaver = lockSettingsSaver) { mutableStateOf(s.settings.lockScreen) }
    var details by rememberSaveable { mutableStateOf(false) }
    var fullPreview by rememberSaveable { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }
    val visual = rememberLockVisual(s)
    val overlayRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (AndroidSettings.canDrawOverlays(context)) { draft = draft.copy(automatic = true); permissionMessage = "" }
        else permissionMessage = "Разрешение не включено. Плеер по-прежнему можно открыть из уведомления."
    }
    BackHandler(details || fullPreview) { if (fullPreview) fullPreview = false else details = false }
    Column(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Tool(Icons.Outlined.ChevronLeft,"Назад") { if(details) details=false else back() }
            Label(if(details)"Настроить «${draft.style.title}»" else "Экран блокировки",19,weight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(!details) {
                Label("Под настроение",26,weight=FontWeight.SemiBold)
                Label("Выбери стиль. Его можно сменить в любой момент.",13,p.muted)
                Spacer(Modifier.height(4.dp))
                LockStyle.entries.forEach { style ->
                    val selected=draft.style==style
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if(selected)p.tonal else p.surface)
                        .border(1.dp,if(selected)p.accent.copy(alpha=.6f) else Color.White.copy(alpha=.06f),RoundedCornerShape(20.dp))
                        .selectable(selected,role=Role.RadioButton){draft=draft.copy(style=style)}.padding(12.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        LockMiniPreview(s,visual,style,draft.options(style),Modifier.size(62.dp,134.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Label(style.title,17,weight=FontWeight.SemiBold)
                            Label(style.description,12,p.muted)
                            TextButton({draft=draft.copy(style=style);fullPreview=true},contentPadding=PaddingValues(0.dp)) {
                                Label("Развернуть",12);Spacer(Modifier.width(6.dp));Icon(Icons.Outlined.OpenInFull,null,Modifier.size(14.dp))
                            }
                        }
                        RadioButton(selected,null,Modifier.size(24.dp))
                    }
                }
                Action("Настроить детали",Icons.Outlined.Tune,tonal=true,modifier=Modifier.fillMaxWidth()){details=true}
                Label("У каждого стиля сохраняются свои настройки.",12,p.muted)
                Action("Открыть текущий плеер",Icons.Outlined.LockOpen,tonal=true,modifier=Modifier.fillMaxWidth()) {
                    // Commit first so this opens the exact design the user just chose.
                    engine.settings(engine.state.value.settings.copy(lockScreen=draft.copy(enabled=true)))
                    context.startActivity(Intent(context,LockScreenActivity::class.java))
                }
            } else {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.surface).padding(16.dp),
                    horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
                    LockMiniPreview(s,visual,draft.style,draft.options(),Modifier.size(90.dp,195.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Label(draft.style.title,20,weight=FontWeight.SemiBold)
                        Label("Изменения сразу видны в предпросмотре.",13,p.muted)
                        TextButton({fullPreview=true}){Text("Развернуть")}
                    }
                }
                val o=draft.options()
                if(draft.style!=LockStyle.MUSIC) {
                    Section("Часы")
                    LockSegments(LockClock.entries.map{it.title},o.clock.ordinal){draft=draft.withOptions(o.copy(clock=LockClock.entries[it]))}
                    Label("Формат времени",14)
                    LockSegments(LockTimeFormat.entries.map{it.title},o.format.ordinal){draft=draft.withOptions(o.copy(format=LockTimeFormat.entries[it]))}
                    SettingToggle("Показывать секунды","По умолчанию выключены",o.seconds){draft=draft.withOptions(o.copy(seconds=it))}
                    SettingToggle("Показывать дату","День недели и число",o.date){draft=draft.withOptions(o.copy(date=it))}
                }
                Section("Атмосфера")
                LockSegments(LockTone.entries.map{it.title},o.tone.ordinal){draft=draft.withOptions(o.copy(tone=LockTone.entries[it]))}
                Label("Смена трека",14)
                LockSegments(LockMotion.entries.map{it.title},o.motion.ordinal){draft=draft.withOptions(o.copy(motion=LockMotion.entries[it]))}
            }
            Section("Появление плеера")
            SettingToggle("При включении экрана","Во время прослушивания и в течение часа после паузы",draft.automatic) { enabled ->
                if(!enabled)draft=draft.copy(automatic=false)
                else if(AndroidSettings.canDrawOverlays(context))draft=draft.copy(automatic=true)
                else {
                    permissionMessage="Для автоматического появления разреши Ладу показываться поверх других приложений."
                    runCatching { overlayRequest.launch(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}"))) }
                        .onFailure {permissionMessage="Открой настройки Android → Приложения → Лад → Поверх других приложений."}
                }
            }
            if(permissionMessage.isNotBlank())Label(permissionMessage,13,p.muted)
            Label("Без автоматического появления: нажми на системный плеер на экране блокировки. PIN и отпечаток остаются включены.",12,p.muted)
            Section("Поведение экрана")
            SettingToggle(
                "Не выключать экран",
                "Пока открыт плеер Лада на экране блокировки",
                draft.keepScreenOn,
            ) { draft = draft.copy(keepScreenOn = it) }
            SettingToggle(
                "Не выключать экран с текстом",
                "Только когда вместо обложки открыт синхронный текст",
                draft.keepScreenOnLyrics,
            ) { draft = draft.copy(keepScreenOnLyrics = it) }
            Spacer(Modifier.height(12.dp))
        }
        Button({engine.settings(engine.state.value.settings.copy(lockScreen=draft.copy(enabled=true)));back()},
            Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)) {
            Text("Использовать «${draft.style.title}»")
        }
    }
    if(fullPreview)Dialog({fullPreview=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize()) {
            LadTheme(s.settings.copy(scale=1f,accent=0xFFE2E6EC,tinted=false)) {
                LockScreenScene(s,visual,draft.style,draft.options(),Modifier.fillMaxSize(),preview=true)
            }
            IconButton({fullPreview=false},Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Outlined.Close,"Закрыть предпросмотр",tint=Color.White)
            }
        }
    }
}

@Composable
private fun LockSegments(labels:List<String>, selected:Int, choose:(Int)->Unit) {
    val p=LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index,label ->
            Box(Modifier.weight(1f).heightIn(min=44.dp).clip(RoundedCornerShape(12.dp))
                .background(if(index==selected)p.tonal else Color.Transparent)
                .selectable(index==selected,role=Role.RadioButton){choose(index)}.padding(4.dp),contentAlignment=Alignment.Center) {
                Label(label,12,if(index==selected)p.text else p.muted,maxLines=2)
            }
        }
    }
}

/** These are real UI instances: clock/artwork/options match the full-size screen. */
@Composable
private fun LockMiniPreview(s:MusicState,visual:LockVisual,style:LockStyle,options:LockStyleOptions,modifier:Modifier) {
    val density=LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density,1f)) {
        Layout(content={
            LadTheme(s.settings.copy(scale=1f,accent=0xFFE2E6EC,tinted=false)) {
                LockScreenScene(s,visual,style,options,Modifier.fillMaxSize())
            }
        },modifier=modifier.clip(RoundedCornerShape(12.dp)).clearAndSetSemantics{}) { measurables,constraints ->
            val w=390.dp.roundToPx();val h=844.dp.roundToPx()
            val child=measurables.single().measure(Constraints.fixed(w,h))
            layout(constraints.maxWidth,constraints.maxHeight){child.placeWithLayer(0,0){
                scaleX=constraints.maxWidth.toFloat()/w;scaleY=constraints.maxHeight.toFloat()/h;transformOrigin=androidx.compose.ui.graphics.TransformOrigin(0f,0f)
            }}
        }
    }
}
