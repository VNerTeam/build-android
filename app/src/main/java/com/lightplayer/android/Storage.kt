package com.lightplayer.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Thu muc video rieng nam tren bo nho trong/the SD (khong nam trong du lieu app -> go app khong mat video). */
object Storage {
    fun defaultRoot(): File = File(Environment.getExternalStorageDirectory(), "LightPlayer")

    fun root(ctx: Context): File {
        val p = Util.prefs(ctx).getString("root", null)
        return if (p.isNullOrEmpty()) defaultRoot() else File(p)
    }

    fun setRoot(ctx: Context, f: File) {
        Util.prefs(ctx).edit().putString("root", f.absolutePath).apply()
    }

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Man hinh "Cho phep quan ly tat ca tep" cho rieng app nay (Android 11+). */
    fun accessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))

    /** Doi Uri cay thu muc cua SAF (vd primary:LightPlayer) thanh duong dan that. */
    fun treeUriToPath(uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            val vol = parts[0]
            val rel = if (parts.size > 1) parts[1] else ""
            val base = if (vol == "primary") Environment.getExternalStorageDirectory() else File("/storage/$vol")
            if (rel.isEmpty()) base else File(base, rel)
        } catch (_: Exception) {
            null
        }
    }

    fun scan(root: File): List<VideoItem> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<VideoItem>()
        for (f in root.walkTopDown().maxDepth(8)) {
            if (f.isFile && Util.MEDIA_EXT.contains(f.extension.lowercase(Locale.ROOT))) out.add(VideoItem(f))
        }
        return out
    }

    private fun scanPaths(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
    }

    private fun renameSidecars(from: File, to: File) {
        for (ext in Util.SUB_EXT) {
            val a = File(from.parentFile, from.nameWithoutExtension + "." + ext)
            val b = File(to.parentFile, to.nameWithoutExtension + "." + ext)
            if (a.exists() && !b.exists()) a.renameTo(b)
        }
    }

    class HideResult(val changed: Int, val skipped: Int)

    /**
     * An/hien: them/bo dau '.' o dau ten file video (kem phu de cung ten).
     * File bat dau bang '.' bi MediaStore/Gallery bo qua. Quet lai duong dan cu+moi
     * de muc cu bien khoi thu vien nhanh hon.
     */
    fun setHidden(ctx: Context, root: File, hide: Boolean): HideResult {
        var changed = 0
        var skipped = 0
        val paths = ArrayList<String>()
        for (v in scan(root)) {
            val f = v.file
            val newName: String? = if (hide) {
                if (f.name.startsWith(".")) null else "." + f.name
            } else {
                if (f.name.startsWith(".")) f.name.trimStart('.').ifEmpty { null } else null
            }
            if (newName == null) continue
            val target = File(f.parentFile, newName)
            if (target.exists()) { skipped++; continue }
            if (f.renameTo(target)) {
                changed++
                renameSidecars(f, target)
                paths.add(f.absolutePath)
                paths.add(target.absolutePath)
            } else {
                skipped++
            }
        }
        scanPaths(ctx, paths)
        return HideResult(changed, skipped)
    }

    fun deleteItem(ctx: Context, v: VideoItem): Boolean {
        val ok = v.file.delete()
        if (ok) {
            for (ext in Util.SUB_EXT) File(v.file.parentFile, v.file.nameWithoutExtension + "." + ext).delete()
            scanPaths(ctx, listOf(v.file.absolutePath))
        }
        return ok
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            f = File(dir, "$base ($n)$ext")
            if (!f.exists()) return f
            n++
        }
    }

    class ImportResult(val ok: Int, val failed: Int, val deletedSources: Int)

    /** Chep cac file nguoi dung chon (content://) vao thu muc rieng. Ghi vao .part roi doi ten. */
    fun importUris(
        ctx: Context,
        uris: List<Uri>,
        dest: File,
        hide: Boolean,
        deleteSource: Boolean,
        onProgress: (index: Int, total: Int, name: String, done: Long, size: Long) -> Unit,
        cancelled: () -> Boolean
    ): ImportResult {
        dest.mkdirs()
        var ok = 0
        var failed = 0
        var deleted = 0
        val paths = ArrayList<String>()
        for ((i, uri) in uris.withIndex()) {
            if (cancelled()) break
            var name = "video_" + System.currentTimeMillis() + ".mp4"
            var size = -1L
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {
            }
            name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trimStart('.')
            if (name.isEmpty()) name = "video.mp4"
            val target = uniqueFile(dest, if (hide) ".$name" else name)
            val part = File(dest, target.name + ".part")
            onProgress(i, uris.size, name, 0L, size)
            var success = false
            var aborted = false
            try {
                val input = ctx.contentResolver.openInputStream(uri)
                if (input == null) {
                    failed++
                    continue
                }
                input.use { ins ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport >= 2L * 1024 * 1024) {
                                onProgress(i, uris.size, name, done, size)
                                lastReport = done
                            }
                            if (cancelled()) { aborted = true; break }
                        }
                    }
                }
                success = !aborted && part.renameTo(target)
            } catch (_: Exception) {
                success = false
            }
            if (!success) {
                part.delete()
                if (!aborted) failed++
                continue
            }
            ok++
            paths.add(target.absolutePath)
            if (deleteSource) {
                try {
                    if (DocumentsContract.deleteDocument(ctx.contentResolver, uri)) deleted++
                } catch (_: Exception) {
                }
            }
        }
        scanPaths(ctx, paths)
        return ImportResult(ok, failed, deleted)
    }
}
