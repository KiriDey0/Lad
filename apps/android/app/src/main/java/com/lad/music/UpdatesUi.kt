// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UpdatesCard() {
    val context=LocalContext.current
    val updater=remember{(context.applicationContext as LadApplication).updates}
    val s by updater.state.collectAsState()
    val palette=LocalPalette.current
    val activity=generateSequence(context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().firstOrNull()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.surface).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Label(when(s.phase){"checking"->"Проверяем GitHub…";"available"->"Доступен Лад ${s.version}";"downloading"->"Скачивание · ${s.progress}%";"ready"->"Обновление загружено";"current"->"Установлена последняя версия";"error"->"Не удалось обновить Лад";"installing"->"Установка обновления";else->"Обновления"},20,weight=FontWeight.SemiBold)
        Label("Лад ${BuildConfig.VERSION_NAME} · стабильный выпуск",13,palette.muted)
        if(s.phase=="downloading") {
            LinearProgressIndicator(progress={s.progress/100f},modifier=Modifier.fillMaxWidth().semantics{contentDescription="Загрузка обновления"},color=palette.accent,trackColor=palette.muted.copy(alpha=.2f))
            Label("Можно продолжать слушать музыку",13,palette.muted)
        }
        if(s.phase=="ready")Label("Лад ${s.version} готов к установке. Откроется системное окно; вход, настройки и очередь сохранятся.",14,palette.muted)
        if(s.message.isNotBlank())Label(s.message,14,palette.muted)
        if(s.notes.isNotBlank()&&s.phase in listOf("available","ready"))Label(s.notes,14,palette.muted)
        when(s.phase) {
            "available"->Action("Скачать обновление · ${(s.size+1048575)/1048576} МБ",Icons.Outlined.Download,modifier=Modifier.fillMaxWidth()){updater.download()}
            "ready"->Action("Установить обновление",Icons.Outlined.SystemUpdate,enabled=activity!=null,modifier=Modifier.fillMaxWidth()){activity?.let{updater.install(it)}}
            "checking","downloading"->Action("Отменить",Icons.Outlined.Close,true,modifier=Modifier.fillMaxWidth()){updater.cancel()}
            "installing"->Label("Подтверди установку в системном окне",14,palette.muted)
            else->Action("Проверить обновления",Icons.Outlined.Refresh,modifier=Modifier.fillMaxWidth()){updater.check()}
        }
    }
}
