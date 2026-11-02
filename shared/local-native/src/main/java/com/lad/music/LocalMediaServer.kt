// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class LocalByteRange(val start:Long,val end:Long) {
    companion object {
        fun parse(header:String?,size:Long):LocalByteRange {
            require(size>0)
            if(header==null)return LocalByteRange(0,size-1)
            val m=Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: throw IllegalArgumentException("Invalid range")
            val (a,b)=m.destructured
            require(a.isNotEmpty()||b.isNotEmpty())
            if(a.isEmpty()) {val suffix=b.toLong();require(suffix>0);return LocalByteRange((size-suffix).coerceAtLeast(0),size-1)}
            val start=a.toLong();val end=if(b.isEmpty())size-1 else b.toLong().coerceAtMost(size-1)
            require(start in 0 until size && end>=start)
            return LocalByteRange(start,end)
        }
    }
}

/** Only the authenticated selected Station may read original bytes through this socket. */
class LocalMediaServer(private val context:Context,private val library:LocalLibrary) {
    private var server:ServerSocket?=null
    private var peer=""
    private var host=""
    private var token=""
    private val prepared=ConcurrentHashMap<String,java.io.File>()
    private val reads=ConcurrentHashMap<String,LocalTransferRead>()
    private val connections=ConcurrentHashMap.newKeySet<Socket>()
    private val workers=Executors.newFixedThreadPool(3) { r->Thread(r,"lad-local-transfer").apply {isDaemon=true} }
    @Synchronized fun url(id:String,address:String,pcm:java.io.File?=null):String {
        library.uri(id)
        if(server==null || peer!=address) {
            close()
            peer=address
            host=DatagramSocket().use {it.connect(InetAddress.getByName(peer),1961);it.localAddress.hostAddress!!}
            token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","")
            val listener=ServerSocket(0,8,InetAddress.getByName(host));server=listener
            Thread({
                while(!listener.isClosed) {
                    val socket=try {listener.accept()}catch(e:Exception){break}
                    if(socket.inetAddress.hostAddress!=address || connections.size>=8){socket.close();continue}
                    socket.soTimeout=10000;connections.add(socket)
                    workers.execute {try{serve(socket)}catch(e:Exception){}finally{connections.remove(socket);runCatching {socket.close()}}}
                }
            },"lad-local-listener").apply {isDaemon=true;start()}
        }
        if(pcm!=null)prepared[id]=pcm else prepared.remove(id)
        reads[id]=LocalTransferRead()
        return "http://$host:${server!!.localPort}/$token/$id"
    }
    private fun serve(socket:Socket) {
        var read:LocalTransferRead?=null
        try {
        val input=BufferedInputStream(socket.getInputStream())
        var headerBytes=0
        fun line():String {
            val bytes=java.io.ByteArrayOutputStream()
            while(true) {val b=input.read();if(b<0)throw java.io.EOFException();headerBytes++;require(headerBytes<=16384);if(b==10)break;if(b!=13)bytes.write(b)}
            return bytes.toString("US-ASCII")
        }
        val request=line().split(' ')
        val out=socket.getOutputStream()
        fun header(status:String,fields:Map<String,String> = emptyMap()) {
            read?.status=status.substringBefore(' ').toInt()
            val text="HTTP/1.1 $status\r\nConnection: close\r\nCache-Control: no-store\r\n"+fields.entries.joinToString(""){"${it.key}: ${it.value}\r\n"}+"\r\n"
            out.write(text.toByteArray(Charsets.US_ASCII))
        }
        if(request.size!=3 || request[0] !in setOf("GET","HEAD")){header("405 Method Not Allowed");return}
        val id=localAudioRequestId(request[1],token) ?: run {header("404 Not Found");return}
        read=reads[id]
        read?.requested=true
        var rangeHeader:String?=null
        while(true){val h=line();if(h.isEmpty())break;if(h.substringBefore(':').equals("Range",true))rangeHeader=h.substringAfter(':').trim()}
        val uri=try{library.uri(id)}catch(e:Exception){header("404 Not Found");return}
        val pcm=prepared[id]
        val fd=try{if(pcm!=null)ParcelFileDescriptor.open(pcm,ParcelFileDescriptor.MODE_READ_ONLY) else context.contentResolver.openFileDescriptor(uri,"r")}catch(e:Exception){null}
        if(fd==null){header("404 Not Found");return}
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { file ->
            val size=fd.statSize.takeIf {it>0} ?: file.channel.size()
            val range=try {LocalByteRange.parse(rangeHeader,size)}catch(e:Exception){header("416 Range Not Satisfiable",mapOf("Content-Range" to "bytes */$size"));return}
            val length=range.end-range.start+1
            val fields=mutableMapOf("Content-Type" to (if(pcm!=null)"audio/wav" else context.contentResolver.getType(uri)?:"audio/mpeg"),"Content-Length" to "$length","Accept-Ranges" to "bytes")
            if(rangeHeader!=null)fields["Content-Range"]="bytes ${range.start}-${range.end}/$size"
            header(if(rangeHeader==null)"200 OK" else "206 Partial Content",fields)
            if(request[0]=="HEAD")return
            file.channel.position(range.start)
            val buffer=ByteArray(64*1024);var left=length
            while(left>0){val n=file.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());if(n<0)break;out.write(buffer,0,n);read?.bytes?.addAndGet(n.toLong());left-=n}
        }
        out.flush()
        } catch(error:Exception) {
            read?.failure=error.javaClass.simpleName
            throw error
        }
    }
    internal fun diagnostics(id:String):String = reads[id]?.describe() ?: "Нет данных о запросе аудиофайла."
    @Synchronized fun close() {
        runCatching {server?.close()};server=null
        connections.forEach {runCatching {it.close()}};connections.clear()
        prepared.clear()
        reads.clear()
    }
}
