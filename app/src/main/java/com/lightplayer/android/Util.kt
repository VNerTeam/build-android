package com.lightplayer.android

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Locale

/** Mau sac dung chung (giong ban desktop). */
object Col {
    val BG = 0xFF0E0F11.toInt()
    val PANEL = 0xFF16181C.toInt()
    val THUMB = 0xFF1C1F24.toInt()
    val FG = 0xFFE7E9EC.toInt()
    val DIM = 0xFF8B929C.toInt()
    val ACC = 0xFF5EC8E5.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Mot video/audio trong thu muc. title = ten hien thi (bo dau . dau ten neu dang an). */
class VideoItem(val file: File) {
    val title: String = Util.stripDots(file.name)
    val size: Long = file.length()
    val modified: Long = file.lastModified()
    val isAudio: Boolean = Util.isAudio(file.name)

    /** Khoa luu vi tri xem do: khong doi khi file bi doi ten them/bot dau . */
    val key: String get() = title + "|" + size
}

/** Danh sach dang phat, truyen tu man hinh list sang trinh phat (tranh nhoi qua Intent). */
object Playlist {
    @Volatile
    var items: List<VideoItem> = emptyList()
}

object Util {
    val MEDIA_EXT = setOf(
        "mp4", "mkv", "webm", "m4v", "mov", "avi", "ogv", "3gp", "ts",
        "mp3", "m4a", "flac", "wav", "opus", "ogg"
    )
    private val AUDIO_EXT = setOf("mp3", "m4a", "flac", "wav", "opus", "ogg")
    val SUB_EXT = listOf("srt", "vtt")

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("lp", Context.MODE_PRIVATE)

    fun isAudio(name: String): Boolean =
        AUDIO_EXT.contains(name.substringAfterLast('.', "").lowercase(Locale.ROOT))

    fun stripDots(name: String): String {
        val s = name.trimStart('.')
        return if (s.isEmpty()) name else s
    }

    fun fmtTime(ms: Long): String {
        val t = if (ms < 0) 0 else ms / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun fmtSize(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i])
        else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    /** So sanh ten kieu "tu nhien": Tap 2 < Tap 10. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                while (si < a.length && a[si].isDigit()) si++
                var sj = j
                while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).trimStart('0')
                val nb = b.substring(j, sj).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = si
                j = sj
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
