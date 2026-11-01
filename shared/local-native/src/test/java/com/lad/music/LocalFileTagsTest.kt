// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LocalFileTagsTest {
    @Test fun flacVorbisLyricsKeepAllTimestamps() {
        val lyrics="[00:00.000]First\n"+"x".repeat(4000)+"\n[00:42.125]Last"
        val entries=listOf("LYRICS=$lyrics","EXPLICIT=1","ARTIST=A","ARTIST=B")
        val body=java.io.ByteArrayOutputStream()
        fun word(n:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        body.write(word(0));body.write(word(entries.size))
        entries.forEach {val b=it.toByteArray();body.write(word(b.size));body.write(b)}
        val b=body.toByteArray()
        val header=byteArrayOf(0x84.toByte(),(b.size ushr 16).toByte(),(b.size ushr 8).toByte(),b.size.toByte())
        val tags=LocalFileTags.read(ByteArrayInputStream("fLaC".toByteArray()+header+b))
        assertEquals(lyrics,tags.lyrics);assertEquals("1",tags.rating);assertEquals(listOf("A","B"),tags.artists)
    }
    @Test fun id3Utf8EmbeddedLyrics() {
        val text="[00:01.50]Текст\n[00:05.00]Ещё строка"
        val content=byteArrayOf(3)+"rus".toByteArray()+byteArrayOf(0)+text.toByteArray()
        val frame="USLT".toByteArray()+ByteBuffer.allocate(4).putInt(content.size).array()+byteArrayOf(0,0)+content
        val n=frame.size
        val header="ID3".toByteArray()+byteArrayOf(3,0,0,(n ushr 21 and 127).toByte(),(n ushr 14 and 127).toByte(),(n ushr 7 and 127).toByte(),(n and 127).toByte())
        assertEquals(text,LocalFileTags.read(ByteArrayInputStream(header+frame)).lyrics)
    }
    @Test fun id3MultipleArtistValuesAreReadWithoutFlattening() {
        val content=byteArrayOf(3)+"A\u0000B".toByteArray()
        val frame="TPE1".toByteArray()+ByteBuffer.allocate(4).putInt(content.size).array()+byteArrayOf(0,0)+content
        val n=frame.size
        val header="ID3".toByteArray()+byteArrayOf(3,0,0,(n ushr 21 and 127).toByte(),(n ushr 14 and 127).toByte(),(n ushr 7 and 127).toByte(),(n and 127).toByte())
        assertEquals(listOf("A","B"),LocalFileTags.read(ByteArrayInputStream(header+frame)).artists)
    }
    @Test fun invalidOrMultipleRangesCannotEscapeTheFile() {
        assertEquals(LocalByteRange(0,99),LocalByteRange.parse(null,100))
        assertEquals(LocalByteRange(10,99),LocalByteRange.parse("bytes=10-",100))
        assertEquals(LocalByteRange(90,99),LocalByteRange.parse("bytes=-10",100))
        assertEquals(LocalByteRange(0,99),LocalByteRange.parse("bytes=0-9999",100))
        for(header in listOf("bytes=100-","bytes=20-10","bytes=0-1,3-4","bytes=-0")) {
            try {LocalByteRange.parse(header,100);fail(header)}catch(expected:IllegalArgumentException){}
        }
    }
}
