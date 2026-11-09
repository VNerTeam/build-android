package com.lightplayer.android

import android.Manifest
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: VideoAdapter
    private lateinit var countText: TextView
    private lateinit var search: EditText
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyBtn: Button
    private lateinit var busyBox: LinearLayout
    private lateinit var busyText: TextView

    private var all: List<VideoItem> = emptyList()
    private val io = Executors.newSingleThreadExecutor()
    private var deleteSource = false
    private var previewDlg: Dialog? = null

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderPicked(uri)
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            val uris = ArrayList<Uri>()
            val cd = d.clipData
            if (cd != null) {
                for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri)
            } else {
                val u = d.data
                if (u != null) uris.add(u)
            }
            if (uris.isNotEmpty()) runImport(uris)
        }
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    private fun hideOn(): Boolean = Util.prefs(this).getBoolean("hide", false)
    private fun sortMode(): Int = Util.prefs(this).getInt("sort", 0)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        adapter.submit(emptyList())
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onStop() {
        super.onStop()
        previewDlg?.dismiss()
        previewDlg = null
    }

    /* ---------------- giao dien ---------------- */

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Col.BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(4), dp(4))
        }
        val title = TextView(this).apply {
            text = "LightPlayer"
            textSize = 20f
            setTextColor(Col.FG)
            typeface = Typeface.DEFAULT_BOLD
        }
        countText = TextView(this).apply {
            setTextColor(Col.DIM)
            textSize = 13f
            setPadding(dp(10), 0, 0, 0)
        }
        val menuBtn = TextView(this).apply {
            text = "⋮"
            textSize = 26f
            setTextColor(Col.FG)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(2), dp(14), dp(2))
            setOnClickListener { showMenu(it) }
        }
        header.addView(title)
        header.addView(countText)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(menuBtn)
        root.addView(header)

        search = EditText(this).apply {
            hint = "Tìm video..."
            setHintTextColor(Col.DIM)
            setTextColor(Col.FG)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Col.PANEL)
                cornerRadius = dp(10).toFloat()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { applyFilter() }
            })
        }
        val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sp.setMargins(dp(12), dp(4), dp(12), dp(6))
        root.addView(search, sp)

        val frame = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setPadding(0, 0, 0, dp(88))
        }
        adapter = VideoAdapter(this, { v -> play(v, 0L) }, { v -> showItemMenu(v) })
        recycler.adapter = adapter
        frame.addView(recycler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), dp(60))
            visibility = View.GONE
        }
        emptyText = TextView(this).apply {
            setTextColor(Col.DIM)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        emptyBtn = Button(this)
        emptyBox.addView(emptyText)
        emptyBox.addView(emptyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        frame.addView(emptyBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val fab = TextView(this).apply {
            text = "＋"
            textSize = 26f
            setTextColor(Col.BG)
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Col.ACC)
            }
            setOnClickListener { startImport() }
        }
        val fl = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
        fl.setMargins(0, 0, dp(18), dp(22))
        frame.addView(fab, fl)

        busyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        busyBox.addView(ProgressBar(this))
        busyText = TextView(this).apply {
            setTextColor(Col.FG)
            setPadding(dp(12), 0, 0, 0)
        }
        busyBox.addView(busyText)
        frame.addView(busyBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun setBusy(on: Boolean, msg: String = "") {
        busyText.text = msg
        busyBox.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /* ---------------- nap danh sach ---------------- */

    private fun reload() {
        if (!Storage.hasAccess(this)) {
            showNoAccess()
            return
        }
        val root = Storage.root(this)
        if (!root.exists()) root.mkdirs()
        val sort = sortMode()
        io.execute {
            val list = Storage.scan(root)
            val sorted = if (sort == 0) list.sortedWith(Comparator { a, b -> Util.naturalCompare(a.title, b.title) })
            else list.sortedByDescending { it.modified }
            runOnUiThread {
                all = sorted
                applyFilter()
                updateEmpty()
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val list = if (q.isEmpty()) all else all.filter { it.title.lowercase().contains(q) }
        adapter.submit(list)
        countText.text = if (all.isEmpty()) "" else if (q.isEmpty()) "${all.size} video" else "${list.size}/${all.size}"
    }

    private fun updateEmpty() {
        if (all.isNotEmpty()) {
            emptyBox.visibility = View.GONE
            return
        }
        emptyBox.visibility = View.VISIBLE
        emptyText.text = "Thư mục trống\n" + Storage.root(this).absolutePath +
            "\n\nBấm ＋ để thêm video, hoặc chép file thẳng vào thư mục này."
        emptyBtn.text = "Thêm video"
        emptyBtn.setOnClickListener { startImport() }
    }

    private fun showNoAccess() {
        all = emptyList()
        adapter.submit(emptyList())
        countText.text = ""
        emptyBox.visibility = View.VISIBLE
        emptyText.text = "Cần quyền truy cập bộ nhớ để dùng thư mục video riêng:\n" +
            Storage.root(this).absolutePath + "\n\nVideo nằm ngoài dữ liệu app nên gỡ app không bị mất."
        emptyBtn.text = "Cấp quyền"
        emptyBtn.setOnClickListener { requestAccess() }
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Storage.accessIntent(this))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    /* ---------------- phat / menu ---------------- */

    private fun play(v: VideoItem, startMs: Long) {
        val list = adapter.items
        Playlist.items = list
        val i = list.indexOf(v)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("index", if (i < 0) 0 else i)
                .putExtra("startMs", startMs)
        )
    }

    private fun showItemMenu(v: VideoItem) {
        val labels = arrayOf("Xem trước", "Phát từ đầu", "Xóa khỏi thiết bị")
        AlertDialog.Builder(this)
            .setTitle(v.title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> previewDlg = PreviewDialog(this, v) { pos -> play(v, pos) }.show()
                    1 -> {
                        Util.prefs(this).edit().remove("pos:" + v.key).apply()
                        play(v, 0L)
                    }
                    2 -> confirmDelete(v)
                }
            }
            .show()
    }

    private fun confirmDelete(v: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("Xóa video?")
            .setMessage(v.title + "\n\nFile sẽ bị xóa khỏi bộ nhớ, không khôi phục được.")
            .setPositiveButton("Xóa") { _, _ ->
                if (Storage.deleteItem(this, v)) reload() else toast("Không xóa được file")
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Thêm video vào thư mục")
        pm.menu.add(0, 2, 1, if (hideOn()) "Ẩn khỏi thư viện: BẬT" else "Ẩn khỏi thư viện: TẮT")
        pm.menu.add(0, 3, 2, if (sortMode() == 0) "Sắp xếp: Tên A-Z" else "Sắp xếp: Mới nhất")
        pm.menu.add(0, 4, 3, "Đổi thư mục lưu")
        pm.menu.add(0, 5, 4, "Quét lại")
        pm.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> startImport()
                2 -> toggleHide()
                3 -> {
                    Util.prefs(this).edit().putInt("sort", if (sortMode() == 0) 1 else 0).apply()
                    reload()
                }
                4 -> treePicker.launch(null)
                5 -> reload()
            }
            true
        }
        pm.show()
    }

    /* ---------------- an / hien (dau . dau ten file) ---------------- */

    private fun toggleHide() {
        if (!Storage.hasAccess(this)) { showNoAccess(); return }
        val newVal = !hideOn()
        val msg = if (newVal)
            "Thêm dấu . vào đầu tên mọi video trong thư mục (file bị Gallery/thư viện bỏ qua). Trong LightPlayer tên vẫn hiển thị bình thường, không có dấu chấm."
        else
            "Bỏ dấu . ở đầu tên các video. Video sẽ hiện lại trong thư viện ảnh/Gallery."
        AlertDialog.Builder(this)
            .setTitle(if (newVal) "Bật ẩn khỏi thư viện?" else "Tắt ẩn khỏi thư viện?")
            .setMessage(msg)
            .setPositiveButton("Đồng ý") { _, _ -> applyHide(newVal) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun applyHide(v: Boolean) {
        setBusy(true, "Đang đổi tên file...")
        val root = Storage.root(this)
        val app = applicationContext
        io.execute {
            val res = Storage.setHidden(app, root, v)
            runOnUiThread {
                Util.prefs(this).edit().putBoolean("hide", v).apply()
                setBusy(false)
                toast("Đã đổi " + res.changed + " file" + (if (res.skipped > 0) ", bỏ qua " + res.skipped + " file (trùng tên hoặc không đổi được)" else ""))
                reload()
            }
        }
    }

    /* ---------------- doi thu muc ---------------- */

    private fun onFolderPicked(uri: Uri) {
        val f = Storage.treeUriToPath(uri)
        if (f == null) {
            toast("Không đọc được đường dẫn thư mục này")
            return
        }
        Storage.setRoot(this, f)
        toast("Thư mục lưu: " + f.absolutePath)
        reload()
    }

    /* ---------------- them video vao thu muc ---------------- */

    private fun startImport() {
        if (!Storage.hasAccess(this)) { showNoAccess(); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val msg = TextView(this).apply {
            text = "Chọn video để chép vào:\n" + Storage.root(this@MainActivity).absolutePath
            textSize = 14f
        }
        val cb = CheckBox(this).apply { text = "Xóa bản gốc sau khi chép xong" }
        box.addView(msg)
        box.addView(cb)
        AlertDialog.Builder(this)
            .setTitle("Thêm video vào thư mục")
            .setView(box)
            .setPositiveButton("Chọn file") { _, _ ->
                deleteSource = cb.isChecked
                launchPicker()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun launchPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        importPicker.launch(i)
    }

    private fun runImport(uris: List<Uri>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val label = TextView(this).apply { textSize = 14f }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        box.addView(label)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val cancel = AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Đang chép...")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Dừng") { _, _ -> cancel.set(true) }
            .create()
        dlg.show()

        val hide = hideOn()
        val del = deleteSource
        val dest = Storage.root(this)
        val app = applicationContext
        io.execute {
            val res = Storage.importUris(app, uris, dest, hide, del, { i, total, name, done, size ->
                runOnUiThread {
                    label.text = "(" + (i + 1) + "/" + total + ") " + name
                    bar.progress = if (size > 0) (done * 1000 / size).toInt() else 0
                }
            }, { cancel.get() })
            runOnUiThread {
                dlg.dismiss()
                var msg = "Đã chép " + res.ok + " file"
                if (res.failed > 0) msg += ", lỗi " + res.failed
                if (del) msg += ", xóa được " + res.deletedSources + " bản gốc"
                toast(msg)
                reload()
            }
        }
    }
}
