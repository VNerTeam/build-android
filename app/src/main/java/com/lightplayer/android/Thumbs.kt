package com.lightplayer.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thumbnail cho list: 1) cache dia  2) anh bia nhung (MKV attachment / MP4 covr / MP3 APIC)
 * 3) khung hinh o ~10% thoi luong. Chi tao khi item hien ra (RecyclerView bind).
 */
object Thumbs {
    private const val W = 320
    private val pool: ExecutorService = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun key(v: VideoItem): String {
        val d = MessageDigest.getInstance("SHA-1").digest((v.title + "|" + v.size + "|" + v.modified).toByteArray())
        val sb = StringBuilder()
        for (b in d) sb.append(String.format(Locale.US, "%02x", b))
        return sb.substring(0, 24)
    }

    fun load(ctx: Context, v: VideoItem, target: ImageView) {
        val k = key(v)
        target.tag = k
        val cached = mem.get(k)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageDrawable(null)
        val app = ctx.applicationContext
        pool.execute {
            if (target.tag != k) return@execute // view da duoc tai su dung cho muc khac
            val bmp = fetch(app, v, k)
            if (bmp != null) {
                mem.put(k, bmp)
                main.post { if (target.tag == k) target.setImageBitmap(bmp) }
            }
        }
    }

    private fun fetch(ctx: Context, v: VideoItem, k: String): Bitmap? {
        val dir = File(ctx.cacheDir, "thumbs")
        dir.mkdirs()
        val jpg = File(dir, "$k.jpg")
        val none = File(dir, "$k.none")
        if (jpg.exists()) return BitmapFactory.decodeFile(jpg.path)
        if (none.exists()) return null
        var bmp: Bitmap? = null
        try { bmp = fromCover(v) } catch (_: Throwable) {}
        if (bmp == null && !v.isAudio) {
            try { bmp = fromFrame(v) } catch (_: Throwable) {}
        }
        val out = bmp
        if (out != null) {
            try {
                FileOutputStream(jpg).use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            } catch (_: Exception) {}
        } else {
            try { none.createNewFile() } catch (_: Exception) {}
        }
        return out
    }

    private fun fromCover(v: VideoItem): Bitmap? {
        var data: ByteArray? = null
        val ext = v.file.extension.lowercase(Locale.ROOT)
        if (ext == "mkv" || ext == "webm") data = Cover.mkv(v.file)
        if (data == null) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(v.file.absolutePath)
                data = r.embeddedPicture
            } finally {
                r.release()
            }
        }
        val d = data ?: return null
        return decode(d)
    }

    private fun decode(data: ByteArray): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= W) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeByteArray(data, 0, data.size, o2)
    }

    private fun fromFrame(v: VideoItem): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(v.file.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atMs = if (dur > 0) minOf((dur * 0.1).toLong(), 90_000L) else 1000L
            var f = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (f == null) f = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val fr = f ?: return null
            if (fr.width <= W) return fr
            val h = fr.height * W / fr.width
            val s = Bitmap.createScaledBitmap(fr, W, h, true)
            if (s !== fr) fr.recycle()
            return s
        } finally {
            r.release()
        }
    }
}
