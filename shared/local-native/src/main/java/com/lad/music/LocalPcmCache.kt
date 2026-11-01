// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Decode an unsupported container to temporary PCM. The source is read-only. */
class LocalPcmCache(private val context:Context) {
    private val mutex=Mutex()
    private val directory=File(context.cacheDir,"local-cast-pcm").apply {mkdirs()}
    suspend fun prepare(uri:Uri,id:String):File=mutex.withLock {
        withContext(Dispatchers.IO) {
            val stamp=if(uri.scheme=="file") File(uri.path!!).let { "${it.length()}:${it.lastModified()}" } else runCatching {
                context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE,android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null)?.use {
                    if(it.moveToFirst()) "${it.getLong(0)}:${it.getLong(1)}" else "unknown"
                } ?: "unknown"
            }.getOrDefault("unknown")
            // Unknown providers are decoded again rather than reusing potentially stale audio.
            val identity=if(stamp=="unknown") "$stamp:${System.nanoTime()}" else stamp
            val key=MessageDigest.getInstance("SHA-256").digest("$id:$uri:$identity".toByteArray()).joinToString(""){"%02x".format(it)}
            val target=File(directory,"$key.wav")
            if(target.isFile){target.setLastModified(System.currentTimeMillis());return@withContext target}
            val temp=File(directory,"$key.tmp")
            val extractor=MediaExtractor()
            var decoder:MediaCodec?=null
            try {
                extractor.setDataSource(context,uri,null)
                val track=(0 until extractor.trackCount).firstOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true} ?: error("В файле нет аудио")
                val format=extractor.getTrackFormat(track);extractor.selectTrack(track)
                val mime=format.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный формат")
                format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_FLOAT)
                val codec=MediaCodec.createDecoderByType(mime);decoder=codec
                codec.configure(format,null,null,0);codec.start()
                var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding=AudioFormat.ENCODING_PCM_16BIT
                var width=2
                var written=0L
                var inputEnded=false;var outputEnded=false
                var lastData=SystemClock.elapsedRealtime()
                val info=MediaCodec.BufferInfo()
                RandomAccessFile(temp,"rw").use {output->
                    output.setLength(0);output.write(ByteArray(44))
                    while(!outputEnded) {
                        currentCoroutineContext().ensureActive()
                        check(SystemClock.elapsedRealtime()-lastData<30000){"Декодер не отвечает"}
                        if(!inputEnded) {
                            val inputIndex=codec.dequeueInputBuffer(10000)
                            if(inputIndex>=0) {
                                val buffer=codec.getInputBuffer(inputIndex)!!
                                val n=extractor.readSampleData(buffer,0)
                                if(n<0){codec.queueInputBuffer(inputIndex,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                                else {codec.queueInputBuffer(inputIndex,0,n,extractor.sampleTime,0);extractor.advance()}
                            }
                        }
                        val index=codec.dequeueOutputBuffer(info,10000)
                        if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(written==0L){"Формат изменился во время декодирования"}
                            val out=codec.outputFormat
                            rate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding=if(out.containsKey(MediaFormat.KEY_PCM_ENCODING))out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            width=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)3 else if(encoding==AudioFormat.ENCODING_PCM_32BIT)4 else if(encoding==AudioFormat.ENCODING_PCM_24BIT_PACKED)3 else 2
                        } else if(index>=0) {
                            try {
                                if(info.size>0) {
                                    val buffer=codec.getOutputBuffer(index)!!
                                    buffer.position(info.offset);buffer.limit(info.offset+info.size)
                                    val data=if(encoding==AudioFormat.ENCODING_PCM_FLOAT) {
                                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                                        ByteArray(info.size/4*3).also {pcm->var p=0;while(buffer.remaining()>=4){val value=(buffer.float.coerceIn(-1f,1f)*8388608.0).roundToInt().coerceIn(-8388608,8388607);pcm[p++]=value.toByte();pcm[p++]=(value shr 8).toByte();pcm[p++]=(value shr 16).toByte()}}
                                    } else ByteArray(info.size).also {buffer.get(it)}
                                    written+=data.size
                                    check(written<=512L*1024*1024){"Для передачи файла нужно больше 512 МБ временного места"}
                                    output.write(data);lastData=SystemClock.elapsedRealtime()
                                }
                                outputEnded=(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0
                            } finally {codec.releaseOutputBuffer(index,false)}
                        }
                    }
                    check(written>0){"Декодер не вернул звук"}
                    val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    header.put("RIFF".toByteArray()).putInt((written+36).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate*channels*width).putShort((channels*width).toShort()).putShort((width*8).toShort()).put("data".toByteArray()).putInt(written.toInt())
                    output.seek(0);output.write(header.array())
                }
                check(temp.renameTo(target)){"Не удалось подготовить временный файл"}
                directory.listFiles()?.filter {it.extension=="wav"}?.sortedByDescending {it.lastModified()}?.drop(2)?.forEach {it.delete()}
                target
            } finally {
                runCatching {decoder?.stop()};runCatching {decoder?.release()};extractor.release();temp.delete()
            }
        }
    }
}
