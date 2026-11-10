package com.lightplayer.android

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {

    private var exo: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var view: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var osd: TextView
    private lateinit var previewImg: ImageView
    private lateinit var audio: AudioManager
    private lateinit var detector: GestureDetector

    private var items: List<VideoItem> = emptyList()
    private var viewUriArg: Uri? = null
    // true = uu tien bo giai ma PHAN MEM (dung khi decoder phan cung bao DECODING_FAILED)
    private var softMode = false
    private val handler = Handler(Looper.getMainLooper())

    // cu chi
    private var longPressing = false
    private var savedSpeed = 1f
    private var scrolling = false
    private var scrollLeft = false
    private var volF = 0f
    private var oriSet = false

    // preview khi keo thanh tua
    private val previewExec = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var pvBusy = false
    private var pvPending = -1L
    private var pvScrubbing = false
    @Volatile private var pvUri: Uri? = null
    private var mmr: MediaMetadataRetriever? = null
    private var mmrUri: Uri? = null

    private val hideOsd = Runnable { osd.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val viewUri: Uri? = if (intent.action == Intent.ACTION_VIEW) intent.data else null
        val startIndex = intent.getIntExtra("index", 0)
        val startMs = intent.getLongExtra("startMs", 0L)
        items = Playlist.items
        if (viewUri == null && items.isEmpty()) {
            finish()
            return
        }
        buildUi()
        setupSystemBars()
        setupPlayer(viewUri, startIndex, startMs)
    }

    /* ---------------- giao dien ---------------- */

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // thanh tren: nut quay lai, ten, nut xoay man hinh (hien cung thanh dieu khien)
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(8), dp(10), dp(8), dp(24))
        topBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
        topBar.visibility = View.GONE
        val back = TextView(this)
        back.text = "←"
        back.textSize = 24f
        back.setTextColor(Color.WHITE)
        back.setPadding(dp(12), dp(4), dp(12), dp(4))
        back.setOnClickListener { finish() }
        titleText = TextView(this)
        titleText.setTextColor(Color.WHITE)
        titleText.textSize = 15f
        titleText.maxLines = 1
        val rotate = TextView(this)
        rotate.text = "⟳"
        rotate.textSize = 24f
        rotate.setTextColor(Color.WHITE)
        rotate.setPadding(dp(12), dp(4), dp(12), dp(4))
        rotate.setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        topBar.addView(back)
        topBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(rotate)

        view = PlayerView(this)
        view.setShowSubtitleButton(true)
        view.controllerShowTimeoutMs = 3000
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            topBar.visibility = if (vis == View.VISIBLE) View.VISIBLE else View.GONE
        })
        detector = GestureDetector(this, Gestures())
        view.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            val a = e.actionMasked
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endGestures()
            true
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        previewImg = ImageView(this)
        previewImg.scaleType = ImageView.ScaleType.CENTER_CROP
        previewImg.setBackgroundColor(Color.BLACK)
        previewImg.visibility = View.GONE
        previewImg.elevation = dp(8).toFloat()
        root.addView(previewImg, FrameLayout.LayoutParams(dp(180), dp(102)))

        osd = TextView(this)
        osd.setTextColor(Color.WHITE)
        osd.textSize = 16f
        osd.setPadding(dp(16), dp(8), dp(16), dp(8))
        osd.background = GradientDrawable().apply {
            setColor(0xAA000000.toInt())
            cornerRadius = dp(18).toFloat()
        }
        osd.visibility = View.GONE
        val ol = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        ol.topMargin = dp(72)
        root.addView(osd, ol)

        setContentView(root)

        // preview khung hinh khi keo thanh tua
        val tb = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        if (tb is TimeBar) {
            tb.addListener(object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) {
                    pvScrubbing = true
                    placePreview(tb, position)
                    requestPreview(position)
                }

                override fun onScrubMove(timeBar: TimeBar, position: Long) {
                    placePreview(tb, position)
                    requestPreview(position)
                }

                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                    pvScrubbing = false
                    previewImg.visibility = View.GONE
                }
            })
        }
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        hideBars()
    }

    private fun hideBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    /* ---------------- trinh phat ---------------- */

    private fun buildItem(v: VideoItem): MediaItem {
        val f = v.file
        val b = MediaItem.Builder()
            .setUri(Uri.fromFile(f))
            .setMediaId(f.absolutePath)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
        val subs = ArrayList<MediaItem.SubtitleConfiguration>()
        for (e in Util.SUB_EXT) {
            val s = File(f.parentFile, f.nameWithoutExtension + "." + e)
            if (s.exists()) {
                subs.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s))
                        .setMimeType(if (e == "srt") MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        }
        if (subs.isNotEmpty()) b.setSubtitleConfigurations(subs)
        return b.build()
    }

    private fun nameOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "Video"
    }

    private fun savedPos(key: String): Long {
        val p = Util.prefs(this).getLong("pos:$key", 0L)
        return if (p > 5000L) p else 0L
    }

    private fun setupPlayer(viewUri: Uri?, startIndex: Int, startMs: Long) {
        viewUriArg = viewUri
        val p = ExoPlayer.Builder(this, SoftFirstFactory(this, softMode)).build()
        exo = p
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        p.setHandleAudioBecomingNoisy(true)
        view.player = p

        val list = ArrayList<MediaItem>()
        var idx = 0
        var pos = startMs
        if (viewUri != null) {
            list.add(
                MediaItem.Builder().setUri(viewUri).setMediaId(viewUri.toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(nameOf(viewUri)).build()).build()
            )
            if (pos <= 0) pos = savedPos(viewUri.toString())
        } else {
            for (v in items) list.add(buildItem(v))
            idx = startIndex.coerceIn(0, items.size - 1)
            if (pos <= 0) pos = savedPos(items[idx].key)
        }

        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                titleText.text = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                pvUri = mediaItem?.localConfiguration?.uri
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!oriSet && videoSize.width > 0) {
                    oriSet = true
                    if (videoSize.width > videoSize.height) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Decoder phan cung loi (vd HEVC) -> thu lai bang decoder phan mem, giu nguyen vi tri
                val retry = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                val cur = exo
                if (retry && !softMode && cur != null) {
                    softMode = true
                    val i = cur.currentMediaItemIndex
                    val at = maxOf(cur.currentPosition, 1L)
                    Toast.makeText(this@PlayerActivity, "Decoder phần cứng lỗi, thử giải mã phần mềm…", Toast.LENGTH_SHORT).show()
                    handler.post {
                        exo?.release()
                        exo = null
                        setupPlayer(viewUriArg, i, at)
                    }
                    return
                }
                val cause = error.cause
                Toast.makeText(
                    this@PlayerActivity,
                    "Không phát được: " + error.errorCodeName +
                        (if (cause != null) "\n" + cause.javaClass.simpleName + ": " + cause.message else ""),
                    Toast.LENGTH_LONG
                ).show()
                val pl = exo
                if (pl != null && pl.hasNextMediaItem()) {
                    pl.seekToNextMediaItem()
                    pl.prepare()
                    pl.play()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val oldItem = oldPosition.mediaItem
                val id = oldItem?.mediaId ?: return
                if (oldItem != newPosition.mediaItem) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) savePos(id, 0L, 0L)
                    else savePos(id, oldPosition.positionMs, C.TIME_UNSET)
                }
            }
        })

        p.setMediaItems(list, idx, pos)
        p.prepare()
        p.playWhenReady = true
        titleText.text = list[idx].mediaMetadata.title?.toString() ?: ""
        pvUri = list[idx].localConfiguration?.uri
        if (pos > 0 && startMs <= 0) showOsd("Tiếp tục " + Util.fmtTime(pos))
    }

    private fun keyOf(mediaId: String): String =
        items.firstOrNull { it.file.absolutePath == mediaId }?.key ?: mediaId

    private fun savePos(mediaId: String, pos: Long, dur: Long) {
        val k = "pos:" + keyOf(mediaId)
        val e = Util.prefs(this).edit()
        if (pos > 5000L && (dur <= 0L || pos < dur - 8000L)) e.putLong(k, pos) else e.remove(k)
        e.apply()
    }

    private fun saveCurrent() {
        val p = exo ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        savePos(id, p.currentPosition, p.duration)
    }

    override fun onStop() {
        super.onStop()
        exo?.pause()
        saveCurrent()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        exo?.release()
        exo = null
        previewExec.execute {
            mmr?.release()
            mmr = null
        }
        previewExec.shutdown()
        super.onDestroy()
    }

    /* ---------------- OSD + cu chi ---------------- */

    private fun showOsd(s: String, ms: Long = 900L) {
        osd.text = s
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, ms)
    }

    private fun seekBy(p: ExoPlayer, ms: Long) {
        val d = p.duration
        val t = p.currentPosition + ms
        p.seekTo(if (d > 0) t.coerceIn(0L, d) else maxOf(0L, t))
    }

    private fun endGestures() {
        if (longPressing) {
            exo?.setPlaybackSpeed(savedSpeed)
            longPressing = false
            handler.removeCallbacks(hideOsd)
            osd.visibility = View.GONE
        }
        scrolling = false
    }

    private inner class Gestures : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (view.isControllerFullyVisible) view.hideController() else view.showController()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val p = exo ?: return true
            val x = e.x / view.width
            if (x < 0.35f) {
                seekBy(p, -10_000L)
                showOsd("−10 giây")
            } else if (x > 0.65f) {
                seekBy(p, 10_000L)
                showOsd("+10 giây")
            } else {
                if (p.isPlaying) p.pause() else p.play()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val p = exo ?: return
            longPressing = true
            savedSpeed = p.playbackParameters.speed
            p.setPlaybackSpeed(2f)
            showOsd("2×", 600_000L)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (longPressing) return false
            val s = e1 ?: return false
            if (!scrolling) {
                if (abs(distanceY) <= abs(distanceX)) return false
                scrolling = true
                scrollLeft = s.x < view.width / 2f
                volF = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
            val delta = distanceY / view.height * 1.4f
            if (scrollLeft) {
                val lp = window.attributes
                val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                val nb = (cur + delta).coerceIn(0.02f, 1f)
                lp.screenBrightness = nb
                window.attributes = lp
                showOsd("Độ sáng " + (nb * 100).toInt() + "%")
            } else {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volF = (volF + delta * max).coerceIn(0f, max.toFloat())
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volF.roundToInt(), 0)
                showOsd("Âm lượng " + (volF / max * 100).toInt() + "%")
            }
            return true
        }
    }

    /* ---------------- preview khung hinh tren thanh tua ---------------- */

    private fun placePreview(tb: View, positionMs: Long) {
        val p = exo ?: return
        val dur = p.duration
        if (dur <= 0L || pvUri == null || p.videoSize.width == 0) return
        val tl = IntArray(2)
        tb.getLocationOnScreen(tl)
        val rl = IntArray(2)
        root.getLocationOnScreen(rl)
        val w = dp(180)
        val h = dp(102)
        val frac = (positionMs.toFloat() / dur).coerceIn(0f, 1f)
        val minX = dp(8).toFloat()
        val maxX = maxOf(minX, (root.width - w - dp(8)).toFloat())
        previewImg.x = (tl[0] - rl[0] + tb.width * frac - w / 2f).coerceIn(minX, maxX)
        previewImg.y = (tl[1] - rl[1] - h - dp(16)).toFloat()
        previewImg.visibility = View.VISIBLE
    }

    private fun requestPreview(posMs: Long) {
        synchronized(lock) {
            pvPending = posMs
            if (pvBusy) return
            pvBusy = true
        }
        try {
            previewExec.execute {
                while (true) {
                    val target: Long
                    synchronized(lock) {
                        target = pvPending
                        pvPending = -1L
                        if (target < 0L) {
                            pvBusy = false
                        }
                    }
                    if (target < 0L) break
                    val uri = pvUri ?: continue
                    val bmp = try { frameAt(uri, target) } catch (_: Throwable) { null }
                    if (bmp != null) runOnUiThread { if (pvScrubbing) previewImg.setImageBitmap(bmp) }
                }
            }
        } catch (_: Exception) {
            synchronized(lock) { pvBusy = false }
        }
    }

    private fun frameAt(uri: Uri, ms: Long): Bitmap? {
        if (mmrUri != uri) {
            mmr?.release()
            mmr = null
            mmrUri = null
            val r = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(applicationContext, uri)
                mmr = r
                mmrUri = uri
            } catch (e: Exception) {
                r.release()
                return null
            }
        }
        val f = mmr?.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        if (f.width <= 320) return f
        val s = Bitmap.createScaledBitmap(f, 320, f.height * 320 / f.width, true)
        if (s !== f) f.recycle()
        return s
    }

    /** Che do phan mem: dua decoder phan mem (OMX.google / c2.android) len dau danh sach decoder cua video. */
    private class SoftFirstFactory(ctx: Context, private val soft: Boolean) : DefaultRenderersFactory(ctx) {
        init {
            setEnableDecoderFallback(true)
        }

        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>
        ) {
            val sel = if (!soft) mediaCodecSelector else MediaCodecSelector { mime, secure, tunneling ->
                mediaCodecSelector.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
            }
            super.buildVideoRenderers(
                context, extensionRendererMode, sel, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out
            )
        }
    }
}
