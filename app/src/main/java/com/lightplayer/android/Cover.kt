package com.lightplayer.android

import java.io.File
import java.io.RandomAccessFile

/**
 * Doc anh bia nhung trong MKV/WebM (attachment anh, vd cover.jpg).
 * Chi doc dung doan can thiet, khong nap ca file. Thuan Kotlin/JVM.
 * (MP4/M4A/MP3 da co MediaMetadataRetriever.embeddedPicture lo.)
 */
object Cover {
    private const val MAX_COVER = 8L * 1024 * 1024

    private class Elem(val id: Long, val size: Long, val dataStart: Long)

    /** Phan tich header phan tu EBML trong mang b (n byte hop le); dataStart tinh tu base. */
    private fun parseElem(b: ByteArray, n: Int, base: Long): Elem? {
        if (n < 2) return null
        var l = 1
        var m = 0x80
        while (l <= 4 && (b[0].toInt() and m) == 0) { l++; m = m shr 1 }
        if (l > 4 || l >= n) return null
        var id = 0L
        for (i in 0 until l) id = id * 256 + (b[i].toInt() and 0xFF)
        val sb = b[l].toInt() and 0xFF
        var sl = 1
        m = 0x80
        while (sl <= 8 && (sb and m) == 0) { sl++; m = m shr 1 }
        if (sl > 8 || l + sl > n) return null
        var sz = (sb and (m - 1)).toLong()
        var allOnes = (sb and (m - 1)) == m - 1
        for (i in 1 until sl) {
            val v = b[l + i].toInt() and 0xFF
            sz = sz * 256 + v
            if (v != 0xFF) allOnes = false
        }
        return Elem(id, if (allOnes) -1L else sz, base + l + sl)
    }

    private fun readElem(raf: RandomAccessFile, pos: Long, fileSize: Long): Elem? {
        if (pos < 0 || pos >= fileSize) return null
        val b = ByteArray(16)
        raf.seek(pos)
        val n = raf.read(b)
        return parseElem(b, n, pos)
    }

    private fun readBytes(raf: RandomAccessFile, pos: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        raf.seek(pos)
        raf.readFully(b)
        return b
    }

    private fun uint(body: ByteArray, s: Int, e: Int): Long {
        var v = 0L
        for (i in s until e) v = v * 256 + (body[i].toInt() and 0xFF)
        return v
    }

    /** SeekHead -> vi tri tuyet doi cua Attachments (neu co). */
    private fun seekFindAttachments(body: ByteArray, segStart: Long): Long? {
        var p = 0
        while (p < body.size - 2) {
            val e = parseElem(body.copyOfRange(p, minOf(p + 16, body.size)), minOf(16, body.size - p), p.toLong()) ?: break
            if (e.size < 0) break
            val s = e.dataStart.toInt()
            val end = s + e.size.toInt()
            if (end > body.size || end <= p) break
            if (e.id == 0x4DBBL) { // Seek
                var q = s
                var sid = -1L
                var spos = -1L
                while (q < end - 2) {
                    val c = parseElem(body.copyOfRange(q, minOf(q + 16, end)), minOf(16, end - q), q.toLong()) ?: break
                    if (c.size < 0) break
                    val cs = c.dataStart.toInt()
                    val ce = cs + c.size.toInt()
                    if (ce > end || ce <= q) break
                    if (c.id == 0x53ABL) sid = uint(body, cs, ce)       // SeekID
                    else if (c.id == 0x53ACL) spos = uint(body, cs, ce) // SeekPosition
                    q = ce
                }
                if (sid == 0x1941A469L && spos >= 0) return segStart + spos
            }
            p = end
        }
        return null
    }

    private class Best(val pos: Long, val len: Int, val isCover: Boolean)

    private fun parseAttachments(raf: RandomAccessFile, att: Elem, size: Long): ByteArray? {
        var best: Best? = null
        val end = if (att.size < 0) size else att.dataStart + att.size
        var p = att.dataStart
        while (p < end) {
            val f = readElem(raf, p, size) ?: break
            if (f.size < 0) break
            if (f.id == 0x61A7L) { // AttachedFile
                var name = ""
                var mime = ""
                var dPos = -1L
                var dLen = -1L
                var q = f.dataStart
                val fe = f.dataStart + f.size
                while (q < fe) {
                    val c = readElem(raf, q, size) ?: break
                    if (c.size < 0) break
                    if (c.id == 0x466EL && c.size < 1024) name = String(readBytes(raf, c.dataStart, c.size.toInt()), Charsets.UTF_8)
                    else if (c.id == 0x4660L && c.size < 128) mime = String(readBytes(raf, c.dataStart, c.size.toInt()), Charsets.ISO_8859_1)
                    else if (c.id == 0x465CL) { dPos = c.dataStart; dLen = c.size }
                    q = c.dataStart + c.size
                }
                val isImage = Regex("^image/(jpeg|png|webp)", RegexOption.IGNORE_CASE).containsMatchIn(mime)
                if (dPos >= 0 && dLen in 1..MAX_COVER && isImage) {
                    val base = name.substringBeforeLast('.')
                    val isCover = Regex("^(small_)?(cover|poster|thumb|folder)", RegexOption.IGNORE_CASE).containsMatchIn(base)
                    val cur = best
                    if (isCover && (cur == null || !cur.isCover)) best = Best(dPos, dLen.toInt(), true)
                    else if (cur == null) best = Best(dPos, dLen.toInt(), false)
                }
            }
            p = f.dataStart + f.size
        }
        val b = best ?: return null
        return readBytes(raf, b.pos, b.len)
    }

    fun mkv(file: File): ByteArray? {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                val eh = readElem(raf, 0, size) ?: return null
                if (eh.id != 0x1A45DFA3L || eh.size < 0) return null
                val seg = readElem(raf, eh.dataStart + eh.size, size) ?: return null
                if (seg.id != 0x18538067L) return null
                val segStart = seg.dataStart
                var p = segStart
                var attPos: Long? = null
                while (p < size) {
                    val e = readElem(raf, p, size) ?: break
                    if (e.id == 0x1941A469L) return parseAttachments(raf, e, size)
                    if (e.id == 0x114D9B74L && e.size in 1..65535) {
                        val found = seekFindAttachments(readBytes(raf, e.dataStart, e.size.toInt()), segStart)
                        if (found != null) attPos = found
                    }
                    if (e.id == 0x1F43B675L || e.size < 0) break // toi Cluster thi dung quet tuan tu
                    p = e.dataStart + e.size
                }
                val ap = attPos
                if (ap != null) {
                    val e = readElem(raf, ap, size)
                    if (e != null && e.id == 0x1941A469L) return parseAttachments(raf, e, size)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
