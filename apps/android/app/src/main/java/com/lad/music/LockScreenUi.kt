// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
package com.lad.music

import android.provider.Settings as AndroidSettings
import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

data class LockVisual(val track: Track?, val art: LockArtwork?, val index: Int = 0)

@Composable
internal fun rememberLockVisual(s: MusicState): LockVisual {
    val app = LocalContext.current.applicationContext as LadApplication
    val track = s.track
    val visual by produceState(LockVisual(track, app.lockArtwork.cached(track?.cover), s.index), track?.id, track?.cover) {
        val art = app.lockArtwork.load(track?.cover)
        value = LockVisual(track, art, s.index)
    }
    return visual
}

@Composable
internal fun LockPlayer(s: MusicState, engine: MusicEngine, close: () -> Unit, unlock: () -> Unit) {
    val visual = rememberLockVisual(s)
    val context = LocalContext.current
    val animatorScale = remember { AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    val options = s.settings.lockScreen.options().let { if (animatorScale == 0f) it.copy(motion = LockMotion.OFF) else it }
    LockScreenScene(s, visual, s.settings.lockScreen.style, options, Modifier.fillMaxSize(), false, close, unlock) { action ->
        when (action) {
            "toggle" -> engine.task { engine.toggle() }
            "previous" -> engine.task { engine.advance(-1) }
            "next" -> engine.task { engine.advance(1) }
            "like" -> s.track?.let { engine.task { engine.like(it) } }
            "shuffle" -> engine.shuffle()
            "repeat" -> engine.repeat()
            else -> if (action.startsWith("seek:")) action.removePrefix("seek:").toLongOrNull()?.let { pos -> engine.task { engine.seek(pos) } }
        }
    }
}

@Composable
internal fun LockScreenScene(
    s: MusicState, visual: LockVisual, style: LockStyle, options: LockStyleOptions,
    modifier: Modifier = Modifier, preview: Boolean = true, close: () -> Unit = {}, unlock: () -> Unit = {},
    command: (String) -> Unit = {},
) {
    val music = style == LockStyle.MUSIC
    val atmosphere = style == LockStyle.ATMOSPHERE
    val art = visual.art
    val t = visual.track
    val p = LocalPalette.current
    val density = LocalDensity.current
    val motion = if (preview) LockMotion.OFF else options.motion
    var up by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.background(Color(0xFF0C0D0F)).pointerInput(preview) {
        if (!preview) detectVerticalDragGestures(onDragStart = { up = 0f }, onDragEnd = {
            if (up < -with(density) { 72.dp.toPx() }) unlock()
            up = 0f
        }) { change, dy -> up += dy; change.consume() }
    }) {
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        if (options.tone != LockTone.BLACK) Crossfade(art, animationSpec = tween(if (motion == LockMotion.NORMAL) 600 else if (motion == LockMotion.REDUCED) 120 else 0), label = "lock background") { image ->
            if (image != null) Image(image.backdrop.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low)
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF36444A), Color(0xFF172728), Color(0xFF29242B)))))
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF0C0D0F).copy(alpha = when(options.tone) { LockTone.SOFT -> .24f; LockTone.DARK -> .43f; LockTone.BLACK -> 1f })))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0C0D0F).copy(alpha = .18f), Color.Transparent, Color(0xFF0C0D0F).copy(alpha = if(atmosphere) .74f else .9f)))))
        val topInset = if (preview) 24.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(24.dp)
        val bottomInset = if (preview) 20.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(12.dp)
        Column(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                LockText("Лад", 12, p.text.copy(alpha=.7f), modifier = Modifier.weight(1f))
                if (preview) Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = p.text.copy(alpha=.6f))
                else IconButton(close, Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Закрыть плеер", Modifier.size(20.dp), tint=p.text.copy(alpha=.7f)) }
            }
            if (!music) LockClockView(options, atmosphere, preview, Modifier.fillMaxWidth().padding(horizontal=28.dp))
            else { Spacer(Modifier.height(8.dp)); LockText("Сейчас играет", 16, weight=FontWeight.Medium); Spacer(Modifier.height(10.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(32.dp), horizontalArrangement=if(atmosphere||music) Arrangement.Center else Arrangement.Start,
                verticalAlignment=Alignment.CenterVertically) {
                Icon(if(s.output=="station")Icons.Outlined.Speaker else Icons.Outlined.Smartphone, null, Modifier.size(13.dp), tint=p.text.copy(alpha=.7f))
                Spacer(Modifier.width(6.dp)); LockText(s.outputName, 12, p.text.copy(alpha=.7f), maxLines=1)
            }
            Spacer(Modifier.weight(if(music) .75f else .35f))
            val controlsHeight = if (preview) 210.dp else (218 * density.fontScale.coerceIn(1f, 1.4f)).dp
            val clockHeight = if(music) 82.dp else if(options.clock==LockClock.COMPACT) 138.dp else 180.dp
            val available = (viewportHeight - topInset - bottomInset - controlsHeight - clockHeight - 78.dp).coerceAtLeast(120.dp)
            val edge = minOf(viewportWidth - if(music)32.dp else if(atmosphere)84.dp else 64.dp, available, 460.dp)
            AnimatedContent(visual, transitionSpec = {
                val ms = if(motion==LockMotion.OFF)0 else if(motion==LockMotion.REDUCED)120 else 280
                val distance = with(density) { 12.dp.roundToPx() } * if(targetState.index>=initialState.index)1 else -1
                (fadeIn(tween(ms)) + slideInHorizontally(tween(ms)) { if(motion==LockMotion.NORMAL) distance else 0 })
                    .togetherWith(fadeOut(tween(if(ms==0)0 else 160)) + slideOutHorizontally(tween(ms)) { if(motion==LockMotion.NORMAL)-distance else 0 })
            }, contentKey={it.track?.id to it.track?.cover}, modifier=Modifier.size(edge).clip(RoundedCornerShape(24.dp)), label="lock cover") { v ->
                if(v.art!=null) Image(v.art.cover.asImageBitmap(), "Обложка: ${v.track?.title.orEmpty()}", Modifier.fillMaxSize(), contentScale=ContentScale.Crop)
                else Artwork(null,v.track?.title.orEmpty(),modifier=Modifier.fillMaxSize())
            }
            Spacer(Modifier.weight(if(music) .9f else .35f))
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp)
                .clip(RoundedCornerShape(24.dp)).then(if(atmosphere)Modifier.background(Color(0xFF17191D).copy(alpha=.63f)) else Modifier)
                .padding(horizontal=12.dp,vertical=if(atmosphere)12.dp else 0.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
                    AnimatedContent(t?.id, transitionSpec={ fadeIn(tween(if(motion==LockMotion.OFF)0 else 160)).togetherWith(fadeOut(tween(if(motion==LockMotion.OFF)0 else 100))) },
                        modifier=Modifier.weight(1f),label="lock metadata") { id ->
                        // Outgoing text is captured by AnimatedContent's retained composition.
                        val track = remember(id) { t }
                        Column(Modifier.fillMaxWidth()) {
                            LockText(track?.title ?: "Твоя музыка", if(music)26 else 22, weight=FontWeight.SemiBold, maxLines=1)
                            Spacer(Modifier.height(4.dp)); LockText(track?.artist?.ifBlank{"Лад"} ?: "Выбери трек в Ладе",14,p.text.copy(alpha=.72f),maxLines=1)
                        }
                    }
                    LockButton(if(s.track?.liked==true)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        if(s.track?.liked==true)"Убрать из любимого" else "Добавить в любимое",enabled=s.track!=null,preview=preview) { command("like") }
                }
                LockSeek(s, preview, command)
                Row(Modifier.fillMaxWidth().height(80.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    LockButton(Icons.Outlined.Shuffle,"Перемешать",active=s.shuffle,preview=preview){command("shuffle")}
                    LockButton(Icons.Outlined.SkipPrevious,"Предыдущий трек",preview=preview,enabled=s.track!=null&&!s.loading){command("previous")}
                    FilledIconButton({command("toggle")},Modifier.size(64.dp),enabled=preview||s.track!=null,
                        shape=RoundedCornerShape(22.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=p.accent,contentColor=Color(0xFF0C0D0F))) {
                        Icon(if(s.playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(s.playing)"Пауза" else "Продолжить",Modifier.size(30.dp))
                    }
                    LockButton(Icons.Outlined.SkipNext,"Следующий трек",preview=preview,enabled=s.track!=null&&!s.loading){command("next")}
                    LockButton(if(s.repeat==1)Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,"Повтор",active=s.repeat!=0,preview=preview){command("repeat")}
                }
            }
            Spacer(Modifier.weight(.3f))
            Box(Modifier.fillMaxWidth().height(44.dp).then(if(preview)Modifier else Modifier.clickable(onClick=unlock)),contentAlignment=Alignment.Center) {
                LockText(if(preview)"Смахни вверх, чтобы разблокировать" else "Смахни вверх или нажми, чтобы разблокировать",10,p.text.copy(alpha=.5f),maxLines=1)
            }
        }
    }
}

@Composable
private fun LockText(text:String,size:Int,color:Color=LocalPalette.current.text,weight:FontWeight=FontWeight.Normal,
    modifier:Modifier=Modifier,maxLines:Int=Int.MAX_VALUE) = Text(text,modifier,fontFamily=Golos,fontSize=size.sp,color=color,
    fontWeight=weight,maxLines=maxLines,overflow=TextOverflow.Ellipsis)

@Composable
private fun LockClockView(options: LockStyleOptions, centered: Boolean, preview:Boolean, modifier:Modifier) {
    val context=LocalContext.current
    val locale=Locale.getDefault()
    val twentyFour=when(options.format){LockTimeFormat.SYSTEM->DateFormat.is24HourFormat(context);LockTimeFormat.H24->true;LockTimeFormat.H12->false}
    val now by produceState(if(preview)Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,15);set(Calendar.MINUTE,30);set(Calendar.SECOND,24)}.timeInMillis else System.currentTimeMillis(),options.seconds,preview){
        if(!preview)while(true){value=System.currentTimeMillis();val unit=if(options.seconds)1000L else 60000L;delay(unit-System.currentTimeMillis()%unit)}
    }
    val date=remember(locale){SimpleDateFormat("EEEE, d MMMM",locale)}
    val time=remember(locale,twentyFour){SimpleDateFormat(if(twentyFour)"HH:mm" else "h:mm",locale)}
    Column(modifier.height(if(options.clock==LockClock.COMPACT)100.dp else 142.dp),
        horizontalAlignment=if(centered)Alignment.CenterHorizontally else Alignment.Start,verticalArrangement=Arrangement.Center) {
        if(options.date) LockText(date.format(Date(now)).replaceFirstChar{it.titlecase(locale)},13,LocalPalette.current.text.copy(alpha=.75f))
        Row(verticalAlignment=Alignment.Bottom) {
            LockText(time.format(Date(now)),when(options.clock){LockClock.LARGE->72;LockClock.LIGHT->78;LockClock.COMPACT->48},
                weight=when(options.clock){LockClock.LARGE->FontWeight.SemiBold;LockClock.LIGHT->FontWeight.Light;LockClock.COMPACT->FontWeight.Medium})
            if(options.seconds){Spacer(Modifier.width(6.dp));LockText(SimpleDateFormat(":ss",locale).format(Date(now)),18,LocalPalette.current.text.copy(alpha=.6f),modifier=Modifier.padding(bottom=13.dp))}
            if(!twentyFour){Spacer(Modifier.width(6.dp));LockText(SimpleDateFormat("a",locale).format(Date(now)),12,LocalPalette.current.text.copy(alpha=.7f),modifier=Modifier.padding(bottom=13.dp))}
        }
    }
}

@Composable
private fun LockButton(icon:ImageVector,label:String,enabled:Boolean=true,active:Boolean=false,preview:Boolean=false,onClick:()->Unit) {
    IconButton(onClick,Modifier.size(48.dp),enabled=preview||enabled) { Icon(icon,label,Modifier.size(24.dp),
        tint=LocalPalette.current.text.copy(alpha=if(!enabled&&!preview).3f else if(active)1f else .75f)) }
}

@Composable
private fun LockSeek(s:MusicState,preview:Boolean,command:(String)->Unit){
    val duration=s.durationMs.coerceAtLeast(0)
    var drag by remember(s.track?.id){mutableStateOf<Float?>(null)}
    val position=(drag?.toLong()?:s.positionMs).coerceIn(0,duration)
    Column {
        C4Slider(drag?:position.toFloat(),{drag=it},valueRange=0f..duration.coerceAtLeast(1).toFloat(),
            onValueChangeFinished={drag?.let{command("seek:${it.toLong()}")};drag=null},enabled=!preview&&duration>0,
            modifier=Modifier.fillMaxWidth().height(36.dp).semantics{contentDescription="Позиция трека"})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            LockText(timeLabel(position),11,LocalPalette.current.text.copy(alpha=.7f));LockText("−${timeLabel((duration-position).coerceAtLeast(0))}",11,LocalPalette.current.text.copy(alpha=.7f))
        }
    }
}
