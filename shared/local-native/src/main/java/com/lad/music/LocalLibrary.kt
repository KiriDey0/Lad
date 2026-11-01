// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LocalFileUnavailable(message: String) : IllegalStateException(message)
data class LocalFolder(val id:String,val uri:String,val name:String,val count:Int,val status:String)

/** A device-owned catalogue. SAF grants are the only source of file access. */
class LocalLibrary(private val context: Context) {
    private val resolver = context.contentResolver
    private val lock = Any()
    private val scanMutex = Mutex()
    private val stopped = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val revision = MutableStateFlow(0)
    val scanning = MutableStateFlow(false)
    private val covers = File(context.filesDir, "local-covers").apply { mkdirs() }
    private val helper = object: SQLiteOpenHelper(context,"local-library.db",null,2) {
        override fun onCreate(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE folders(id TEXT PRIMARY KEY,uri TEXT UNIQUE,name TEXT,status TEXT)")
            db.execSQL("CREATE TABLE tracks(id TEXT PRIMARY KEY,folder TEXT,relative TEXT,uri TEXT,stamp TEXT,seen TEXT,data TEXT,lyrics TEXT)")
            db.execSQL("CREATE INDEX tracks_folder ON tracks(folder)")
            db.execSQL("CREATE TABLE likes(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE playlists(id TEXT PRIMARY KEY,title TEXT,items TEXT)")
        }
        override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) { db.execSQL("CREATE TABLE IF NOT EXISTS playlists(id TEXT PRIMARY KEY,title TEXT,items TEXT)") }
    }
    private val db get() = helper.writableDatabase
    private var cachedTracks: List<Track>? = null
    private fun change() { synchronized(lock) { cachedTracks = null }; revision.value += 1 }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    fun folders():List<LocalFolder> = synchronized(lock) {
        db.rawQuery("SELECT f.id,f.uri,f.name,f.status,COUNT(t.id) FROM folders f LEFT JOIN tracks t ON t.folder=f.id WHERE status!='detached' GROUP BY f.id ORDER BY f.name",null).use { c ->
            buildList { while(c.moveToNext()) add(LocalFolder(c.getString(0),c.getString(1),c.getString(2),c.getInt(4),c.getString(3))) }
        }
    }
    suspend fun add(uri:Uri) {
        val id = synchronized(lock) {
            db.rawQuery("SELECT id FROM folders WHERE uri=?",arrayOf(uri.toString())).use { c ->
                if(c.moveToFirst()) c.getString(0) else "local-folder-"+UUID.randomUUID().toString().replace("-","")
            }
        }
        val name=if(uri.scheme=="file")File(uri.path!!).also {require(it.isDirectory)}.name else {
            val root=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
            resolver.query(root,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())it.getString(0) else null } ?: "Музыка"
        }
        synchronized(lock) { db.insertWithOnConflict("folders",null,ContentValues().apply { put("id",id);put("uri",uri.toString());put("name",name);put("status","ready") },SQLiteDatabase.CONFLICT_REPLACE) }
        change();scan(id)
    }
    fun remove(id:String) { stopped.add(id);synchronized(lock) { db.execSQL("UPDATE folders SET status='detached' WHERE id=?",arrayOf(id)) };change() }
    suspend fun scan(id:String) = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val f=folders().find { it.id==id } ?: return@withContext
            stopped.remove(id)
            val token=UUID.randomUUID().toString()
            scanning.value=true
            synchronized(lock) { db.execSQL("UPDATE folders SET status='scanning' WHERE id=?",arrayOf(id)) }
            try {
                if(Uri.parse(f.uri).scheme=="file") {
                    scanFiles(f,token)
                    finishScan(id,token)
                    return@withContext
                }
                val tree=Uri.parse(f.uri);val rootId=DocumentsContract.getTreeDocumentId(tree)
                val todo=java.util.ArrayDeque<Pair<String,String>>()
                todo.add(rootId to "")
                var count=0
                while(todo.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    if(folders().none { it.id==id })return@withContext
                    val (parent,prefix)=todo.removeFirst()
                    val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
                    val projection=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val cursor=resolver.query(children,projection,null,null,null) ?: throw LocalFileUnavailable("Нет доступа к папке")
                    cursor.use { c -> while(c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        if(id in stopped)return@withContext
                        val documentId=c.getString(0);val name=c.getString(1);val mime=c.getString(2)
                        val relative=prefix+name
                        if(mime==DocumentsContract.Document.MIME_TYPE_DIR) { if(!name.startsWith('.'))todo.add(documentId to "$relative/");continue }
                        if(name.substringAfterLast('.',"").lowercase() !in setOf("mp3","flac","m4a","mp4","aac","ogg","opus","wav","aif","aiff"))continue
                        val uri=DocumentsContract.buildDocumentUriUsingTree(tree,documentId)
                        val trackId="local-track-"+hash(id+"/"+relative)
                        val stamp="artists2:${c.getLong(3)}:${c.getLong(4)}"
                        val unchanged=synchronized(lock) { db.rawQuery("SELECT stamp FROM tracks WHERE id=?",arrayOf(trackId)).use { it.moveToFirst()&&it.getString(0)==stamp } }
                        if(unchanged) synchronized(lock) { db.execSQL("UPDATE tracks SET seen=?,uri=? WHERE id=?",arrayOf(token,uri.toString(),trackId)) }
                        else try {
                            val (track,lyrics)=metadata(uri,name,trackId)
                            synchronized(lock) { db.insertWithOnConflict("tracks",null,ContentValues().apply {put("id",trackId);put("folder",id);put("relative",relative);put("uri",uri.toString());put("stamp",stamp);put("seen",token);put("data",track.json().toString());put("lyrics",lyrics)},SQLiteDatabase.CONFLICT_REPLACE) }
                        } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException)throw e }
                        count++;if(count%50==0)change()
                    } }
                }
                finishScan(id,token)
            } catch(e:Exception) {
                if(e is kotlinx.coroutines.CancellationException)throw e
                synchronized(lock) { db.execSQL("UPDATE folders SET status='unavailable' WHERE id=? AND status!='detached'",arrayOf(id)) }
                throw LocalFileUnavailable("Папка недоступна. Подключи накопитель или выбери папку заново.")
            } finally { scanning.value=false;change() }
        }
    }
    private fun finishScan(id:String,token:String) = synchronized(lock) {
        if(id in stopped)return@synchronized
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id,data,seen FROM tracks WHERE folder=?",arrayOf(id)).use {c->
                while(c.moveToNext()) {
                    val data=JSONObject(c.getString(1)).put("available",c.getString(2)==token)
                    db.execSQL("UPDATE tracks SET data=? WHERE id=?",arrayOf(data.toString(),c.getString(0)))
                }
            }
            db.execSQL("UPDATE folders SET status='ready' WHERE id=? AND status!='detached'",arrayOf(id))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    private suspend fun scanFiles(folder:LocalFolder,token:String) {
        val root=File(Uri.parse(folder.uri).path!!).canonicalFile
        check(root.isDirectory){"Папка недоступна"}
        var count=0
        for(file in root.walkTopDown().onEnter { it==root || (!java.nio.file.Files.isSymbolicLink(it.toPath()) && !it.name.startsWith('.') && it.canonicalPath.startsWith(root.path+File.separator)) }) {
            currentCoroutineContext().ensureActive()
            if(folder.id in stopped)return
            if(!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath()) || file.extension.lowercase() !in setOf("mp3","flac","m4a","mp4","aac","ogg","opus","wav","aif","aiff"))continue
            val relative=file.relativeTo(root).path
            val id="local-track-"+hash(folder.id+"/"+relative)
            val stamp="artists2:${file.length()}:${file.lastModified()}"
            val old=synchronized(lock) {db.rawQuery("SELECT stamp FROM tracks WHERE id=?",arrayOf(id)).use {it.moveToFirst()&&it.getString(0)==stamp}}
            val uri=Uri.fromFile(file)
            if(old)synchronized(lock) {db.execSQL("UPDATE tracks SET seen=?,uri=? WHERE id=?",arrayOf(token,uri.toString(),id))}
            if(!old)try {
                val (track,lyrics)=metadata(uri,file.name,id)
                synchronized(lock) {db.insertWithOnConflict("tracks",null,ContentValues().apply {put("id",id);put("folder",folder.id);put("relative",relative);put("uri",uri.toString());put("stamp",stamp);put("seen",token);put("data",track.json().toString());put("lyrics",lyrics)},SQLiteDatabase.CONFLICT_REPLACE)}
            }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e}
            count++;if(count%50==0)change()
        }
    }
    private fun metadata(uri:Uri,name:String,id:String):Pair<Track,String> {
        val tags=runCatching { resolver.openInputStream(uri)!!.use(LocalFileTags::read) }.getOrDefault(LocalFileTags.Tags())
        val reader=MediaMetadataRetriever()
        try {
            reader.setDataSource(context,uri)
            fun tag(key:Int)=reader.extractMetadata(key).orEmpty()
            val artist=tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { "Неизвестный исполнитель" }
            val album=tag(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { "Без альбома" }
            val albumArtist=tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).ifBlank { artist }
            var cover:String?=null
            val embedded=reader.embeddedPicture
            if(embedded!=null && embedded.size<=8_000_000) {
                val key=hash(id+":"+embedded.contentHashCode())
                val file=File(covers,"$key.jpg")
                if(!file.exists()) {
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeByteArray(embedded,0,embedded.size,bounds)
                    if(bounds.outWidth.toLong()*bounds.outHeight in 1L..36_000_000L) {
                        val options=BitmapFactory.Options().apply { while(bounds.outWidth/inSampleSize.coerceAtLeast(1)>600 || bounds.outHeight/inSampleSize.coerceAtLeast(1)>600)inSampleSize=if(inSampleSize<=1)2 else inSampleSize*2 }
                        BitmapFactory.decodeByteArray(embedded,0,embedded.size,options)?.let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) };bitmap.recycle() }
                    }
                }
                if(file.exists())cover=Uri.fromFile(file).toString()
            }
            return Track(id,tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { name.substringBeforeLast('.') },tags.artists.ifEmpty { listOf(artist) }.map { ArtistRef("local-artist-"+hash(it.lowercase()),it) },"local-album-"+hash(albumArtist.lowercase()+"\n"+album.lowercase()),album,tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull()?:0,cover,explicit=tags.rating.lowercase() in setOf("1","true","explicit","18","18+")) to tags.lyrics
        } finally { reader.release() }
    }
    fun format(id:String):String = synchronized(lock) {db.rawQuery("SELECT relative FROM tracks WHERE id=?",arrayOf(id)).use {c->check(c.moveToFirst());c.getString(0).substringAfterLast('.').uppercase()}}
    fun uri(id:String):Uri = synchronized(lock) {
        db.rawQuery("SELECT t.uri,f.uri FROM tracks t JOIN folders f ON f.id=t.folder WHERE t.id=? AND f.status!='detached'",arrayOf(id)).use { c ->
            if(!c.moveToFirst())throw LocalFileUnavailable("Файл недоступен в библиотеке этого устройства")
            val uri=Uri.parse(c.getString(0))
            if(uri.scheme=="file") {
                val path=File(uri.path!!).canonicalPath
                val root=File(Uri.parse(c.getString(1)).path!!).canonicalPath
                if(!path.startsWith(root+File.separator))throw LocalFileUnavailable("Файл находится вне выбранной папки")
            }
            uri
        }
    }
    fun resolve(id:String):Track = synchronized(lock) {
        val track=tracks().firstOrNull { it.id==id } ?: throw LocalFileUnavailable("Локальный файл не найден")
        try { resolver.openAssetFileDescriptor(uri(id),"r")?.use {} ?: throw LocalFileUnavailable("Файл перемещён") }
        catch(e:Exception) { throw LocalFileUnavailable("Файл перемещён или папка отключена. Проверь папки в коллекции.") }
        track.copy(liked=isLiked(id),available=true)
    }
    fun tracks(folder:String?=null):List<Track> = synchronized(lock) {
        if (folder != null) {
            val ids = db.rawQuery("SELECT id FROM tracks WHERE folder=?", arrayOf(folder)).use { c ->
                buildSet { while(c.moveToNext())add(c.getString(0)) }
            }
            return@synchronized tracks().filter { it.id in ids }
        }
        cachedTracks?.let { return@synchronized it }
        val result = db.rawQuery("SELECT t.id,t.data FROM tracks t JOIN folders f ON f.id=t.folder WHERE f.status!='detached' ORDER BY t.relative COLLATE NOCASE",null).use { c ->
            LocalArtists.normalize(buildList { while(c.moveToNext())add(Track.fromJson(JSONObject(c.getString(1))).copy(liked=isLiked(c.getString(0)))) })
        }
        cachedTracks = result
        result
    }
    private fun isLiked(id:String)=db.rawQuery("SELECT 1 FROM likes WHERE id=?",arrayOf(id)).use { it.moveToFirst() }
    fun like(id:String,liked:Boolean) { synchronized(lock) { if(liked)db.insertWithOnConflict("likes",null,ContentValues().apply {put("id",id)},SQLiteDatabase.CONFLICT_IGNORE) else db.delete("likes","id=?",arrayOf(id)) };change() }
    fun library():Library {
        val tracks=tracks()
        val playlists=synchronized(lock) { db.rawQuery("SELECT id,title,items FROM playlists ORDER BY rowid DESC",null).use {c->buildList {while(c.moveToNext()){val items=org.json.JSONArray(c.getString(2));add(Card(c.getString(0),"playlist",c.getString(1),"${items.length()} треков",cover=items.optJSONObject(0)?.text("cover")?.takeIf {it.isNotBlank()}))}}} }
        return Library(playlists=playlists,albums=tracks.groupBy { it.albumId }.map { (_,ts)->ts.first().albumCard() },artists=tracks.flatMap { t->t.artists.map { a->a to t } }.groupBy { it.first.id }.map { (id,entries)->Card(id,"artist",entries.first().first.name,cover=entries.first().second.cover) })
    }
    fun createPlaylist(title:String,tracks:List<Track> = emptyList()):Card {
        val name=title.trim().take(160);require(name.isNotBlank()){"Введи название плейлиста"};require(tracks.size<=5000)
        val id="local-playlist-"+UUID.randomUUID().toString().replace("-","")
        synchronized(lock) {db.insertOrThrow("playlists",null,ContentValues().apply {put("id",id);put("title",name);put("items",org.json.JSONArray(tracks.map {it.json()}).toString())})}
        change();return Card(id,"playlist",name)
    }
    fun addPlaylist(id:String,tracks:List<Track>) {
        synchronized(lock) {
            val items=db.rawQuery("SELECT items FROM playlists WHERE id=?",arrayOf(id)).use {c->check(c.moveToFirst());org.json.JSONArray(c.getString(0))}
            require(items.length()+tracks.size<=5000){"В плейлисте может быть до 5000 треков"}
            tracks.forEach {items.put(it.json())}
            db.execSQL("UPDATE playlists SET items=? WHERE id=?",arrayOf(items.toString(),id))
        };change()
    }
    fun catalog(card:Card):Catalog {
        if(card.id.startsWith("local-playlist-")) {
            val tracks=synchronized(lock) {db.rawQuery("SELECT items FROM playlists WHERE id=?",arrayOf(card.id)).use {c->check(c.moveToFirst());org.json.JSONArray(c.getString(0)).objects().map(Track::fromJson)}}
                .map {if(it.isLocal)runCatching {resolve(it.id)}.getOrElse {_ -> it.copy(available=false)} else it}
            val normalized=LocalArtists.normalize(this.tracks()+tracks).takeLast(tracks.size)
            return Catalog(card.title,normalized,total=normalized.size)
        }
        val tracks=tracks(if(card.id.startsWith("local-folder-"))card.id else null)
        val legacyMatches = if(card.type=="artist") synchronized(lock) {
            db.rawQuery("SELECT id,data FROM tracks",null).use { c ->
                buildSet { while(c.moveToNext()) {
                    val old=Track.fromJson(JSONObject(c.getString(1)))
                    if(old.artists.any { it.id==card.id }) add(c.getString(0))
                } }
            }
        } else emptySet()
        val selected=when {card.id=="local-liked"->tracks.filter {it.liked};card.type=="album"&&!card.id.startsWith("local-folder-")->tracks.filter {it.albumId==card.id};card.type=="artist"->tracks.filter {t->t.id in legacyMatches || t.artists.any {it.id==card.id}};else->tracks}
        return Catalog(card.title,selected,total=selected.size)
    }
    fun lyrics(id:String):Lyrics {
        val source=uri(id)
        val row=synchronized(lock) {db.rawQuery("SELECT t.lyrics,t.relative,f.uri FROM tracks t JOIN folders f ON f.id=t.folder WHERE t.id=?",arrayOf(id)).use { c -> check(c.moveToFirst());Triple(c.getString(0),c.getString(1),c.getString(2)) }}
        var text=row.first
        if(source.scheme=="file") {
            val file=File(source.path!!)
            for(ext in listOf("lrc","LRC","txt","TXT")) {
                val candidate=File(file.parentFile,file.nameWithoutExtension+".$ext")
                if(candidate.isFile && !java.nio.file.Files.isSymbolicLink(candidate.toPath()) && candidate.length()<=1_000_000) {
                    val bytes=candidate.readBytes()
                    text=runCatching {Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()}.getOrElse {String(bytes,charset("windows-1251"))};break
                }
            }
            return parseLyrics(text.removePrefix("\uFEFF"))
        }
        // Provider document IDs are opaque. Resolve siblings by querying the granted tree.
        val tree=Uri.parse(row.third)
        var parent=DocumentsContract.getTreeDocumentId(tree)
        val segments=row.second.split('/')
        for(directory in segments.dropLast(1)) {
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
            val found=resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use {c->
                var result:String?=null
                while(c.moveToNext())if(c.getString(1)==directory && c.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR){result=c.getString(0);break}
                result
            } ?: return parseLyrics(text.removePrefix("\uFEFF"))
            parent=found
        }
        val stem=segments.last().substringBeforeLast('.')
        val candidates=mutableMapOf<String,Uri>()
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent),arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {c->
            while(c.moveToNext())if(c.getString(1) in listOf("$stem.lrc","$stem.LRC","$stem.txt","$stem.TXT"))candidates[c.getString(1)]=DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0))
        }
        for(ext in listOf("lrc","LRC","txt","TXT")) {
            val candidate=candidates["$stem.$ext"] ?: continue
            val bytes=runCatching {resolver.openInputStream(candidate)?.use {it.readBytesBounded(1_000_000)}}.getOrNull() ?: continue
            text=runCatching {Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()}.getOrElse {String(bytes,charset("windows-1251"))};break
        }
        return parseLyrics(text.removePrefix("\uFEFF"))
    }
    private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray { val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=limit);out.write(buffer,0,n)};return out.toByteArray() }
}
