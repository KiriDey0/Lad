// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Read tags, never rewrite files. Bounded independently of the media decoder. */
object LocalFileTags {
    data class Tags(val lyrics: String = "", val rating: String = "")
    private const val LIMIT = 8 * 1024 * 1024
    private fun read(input: InputStream, length: Int): ByteArray {
        require(length in 0..LIMIT)
        val data = ByteArray(length)
        var count = 0
        while (count < length) {
            val n = input.read(data, count, length - count)
            check(n > 0) { "Truncated metadata" }
            count += n
        }
        return data
    }
    private fun skip(input: InputStream, length: Int) {
        var left = length.toLong()
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) left -= n else { check(input.read() >= 0); left-- }
        }
    }
    private fun size(data: ByteArray, offset: Int, syncSafe: Boolean = false): Int {
        var value = 0
        repeat(4) { value = (value shl if (syncSafe) 7 else 8) or (data[offset + it].toInt() and if (syncSafe) 127 else 255) }
        return value
    }
    fun read(stream: InputStream): Tags {
        val input = if (stream is BufferedInputStream) stream else stream.buffered()
        val signature = read(input, 4)
        if (String(signature, Charsets.US_ASCII) == "fLaC") {
            repeat(256) {
                val h = read(input, 4)
                val length = ((h[1].toInt() and 255) shl 16) or ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
                if ((h[0].toInt() and 127) == 4 && length <= LIMIT) return vorbis(read(input, length))
                skip(input, length)
                if (h[0].toInt() and 128 != 0) return Tags()
            }
        } else if (String(signature.copyOfRange(0, 3), Charsets.US_ASCII) == "ID3") {
            val version = signature[3].toInt()
            if (version !in 3..4) return Tags()
            val rest = read(input, 6)
            val length = size(rest, 2, true)
            if (length > LIMIT) return Tags()
            var bytes = read(input, length)
            if ((rest[1].toInt() and 128) != 0) {
                val out = java.io.ByteArrayOutputStream(bytes.size)
                var i = 0
                while (i < bytes.size) {
                    out.write(bytes[i].toInt())
                    if (bytes[i].toInt() and 255 == 255 && i+1 < bytes.size && bytes[i+1].toInt() == 0) i++
                    i++
                }
                bytes = out.toByteArray()
            }
            return id3(bytes, version, (rest[1].toInt() and 64) != 0)
        }
        return Tags()
    }
    private fun vorbis(bytes: ByteArray): Tags {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun text(): String {
            check(b.remaining() >= 4)
            val n = b.int
            check(n in 0..b.remaining())
            return ByteArray(n).also { b.get(it) }.toString(Charsets.UTF_8)
        }
        text()
        check(b.remaining() >= 4)
        val count = b.int
        check(count in 0..100000)
        var lyrics = ""; var rating = ""
        repeat(count) {
            val entry = text()
            val key = entry.substringBefore('=').uppercase(Locale.ROOT)
            if (key in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS") && lyrics.isBlank()) lyrics = entry.substringAfter('=', "").take(1_000_000)
            if (key in setOf("EXPLICIT", "ITUNESADVISORY", "RATING")) rating = entry.substringAfter('=', "")
        }
        return Tags(lyrics, rating)
    }
    private fun id3(bytes: ByteArray, version: Int, extended: Boolean): Tags {
        var offset = if (extended && bytes.size >= 4) size(bytes, 0, version == 4).let { if (version == 3) it + 4 else it } else 0
        var lyrics = ""
        while (offset >= 0 && offset + 10 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val n = size(bytes, offset+4, version == 4)
            if (n <= 0 || n > bytes.size - offset - 10) break
            val flags = bytes[offset+9].toInt() and 255
            val data = bytes.copyOfRange(offset+10, offset+10+n)
            offset += 10+n
            if (flags != 0 || name !in setOf("USLT", "SYLT") || data.size < 5) continue
            val encoding = data[0].toInt()
            val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> continue }
            val unit = if (encoding in 1..2) 2 else 1
            fun end(start: Int): Int {
                var i = start
                while (i+unit <= data.size) {
                    if ((0 until unit).all { data[i+it].toInt() == 0 }) return i
                    i += unit
                }
                return data.size
            }
            if (name == "USLT" && lyrics.isBlank()) {
                val start = (end(4) + unit).coerceAtMost(data.size)
                lyrics = String(data, start, data.size-start, charset).trimEnd('\u0000')
            } else if (name == "SYLT" && data.size >= 7 && data[4].toInt() == 2) {
                var i = (end(6)+unit).coerceAtMost(data.size)
                val lines = mutableListOf<String>()
                while (i < data.size) {
                    val stop = end(i)
                    val next = stop + unit
                    if (next+4 > data.size) break
                    val ms = size(data, next).toLong() and 0xffffffffL
                    val words = String(data, i, stop-i, charset)
                    lines += String.format(Locale.ROOT, "[%02d:%06.3f]%s", ms/60000, (ms%60000)/1000.0, words)
                    i = next+4
                }
                if (lines.isNotEmpty()) return Tags(lines.joinToString("\n").take(1_000_000))
            }
        }
        return Tags(lyrics.take(1_000_000))
    }
}
