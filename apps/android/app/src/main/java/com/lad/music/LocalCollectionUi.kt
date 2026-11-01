// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable fun CollectionSources(source:String,select:(String)->Unit,engine:MusicEngine) {
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=4.dp)) {
        items(listOf("all" to "Всё","yandex" to "Яндекс Музыка","local" to "На устройстве")) { (id,title)->
            FilterChip(source==id,{select(id)},label={Label(title,12,maxLines=1)})
        }
        item {Tool(Icons.Outlined.CreateNewFolder,"Добавить папку",onClick={engine.requestLocalFolder()})}
    }
}

@Composable fun LocalFoldersView(engine:MusicEngine,folders:List<LocalFolder>,bottom:Dp,open:(Card)->Unit,modifier:Modifier=Modifier) {
    val p=LocalPalette.current
    LazyColumn(modifier.fillMaxWidth(),contentPadding=PaddingValues(top=12.dp,bottom=bottom),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Label("Файлы остаются в твоих папках. Вложенные папки тоже входят в библиотеку.",13,p.muted)}
        if(folders.isEmpty())item {Action("Выбрать папку",Icons.Outlined.CreateNewFolder,onClick={engine.requestLocalFolder()})}
        items(folders,key={it.id}) {f->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).clickable {open(Card(f.id,"album",f.name))}.padding(vertical=12.dp)) {
                    Label(f.name,16,maxLines=1)
                    Label("${f.count} треков"+if(f.status=="scanning")" · Обновляем" else "",12,p.muted)
                    if(f.status=="unavailable")Label("Нет доступа. Выбери папку заново.",12,p.muted)
                }
                Tool(Icons.Outlined.Refresh,"Обновить папку",onClick={engine.task {engine.localLibrary.scan(f.id)}})
                Tool(Icons.Outlined.RemoveCircleOutline,"Убрать папку из Лада; файлы останутся",onClick={engine.localLibrary.remove(f.id)})
            }
        }
        item {Action("Добавить папку",Icons.Outlined.Add,onClick={engine.requestLocalFolder()})}
    }
}
