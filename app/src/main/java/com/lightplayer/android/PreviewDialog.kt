package com.lightplayer.android

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Xem trước: sau khi chọn "Xem trước" trong menu giữ lâu, video tự chạy lần lượt các đoạn
 * (10% → 30% → 50% → 70% → 90%, không tiếng) và TIẾP TỤC chạy dù đã thả tay.
 * Chạm vào khung để mở phát tại đúng vị trí đó; chạm ra ngoài / Back để đóng.
 */
class PreviewDialog(
    private val activity: Activity,
    private val item: VideoItem,
    private val onOpen: (Long) -> Unit
) {
    fun show(): Dialog {
        val exo = ExoPlayer.Builder(activity).build()
        exo.volume = 0f
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(item.file)))
        exo.prepare()
        exo.playWhenReady = true

        val screenW = activity.resources.displayMetrics.widthPixels
        val boxW = (screenW * 0.92f).toInt()
        val boxH = boxW * 9 / 16

        val playerView = PlayerView(activity)
        playerView.useController = false
        playerView.player = exo
        playerView.setShutterBackgroundColor(Color.BLACK)

        val box = FrameLayout(activity)
        box.setBackgroundColor(Color.BLACK)
        box.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, boxH))

        val title = TextView(activity)
        title.text = item.title
        title.setTextColor(Color.WHITE)
        title.textSize = 13f
        title.maxLines = 1
        title.setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(16))
        title.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000)
        )
        box.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val hint = TextView(activity)
        hint.text = "Chạm để mở  ·  chạm ra ngoài để đóng"
        hint.setTextColor(0xCCFFFFFF.toInt())
        hint.textSize = 11f
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, activity.dp(14), 0, activity.dp(8))
        hint.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xAA000000.toInt(), 0x00000000)
        )
        box.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val dlg = Dialog(activity)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(box, ViewGroup.LayoutParams(boxW, boxH))
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setCanceledOnTouchOutside(true)

        // Nhảy qua các đoạn của video, lặp mãi cho tới khi đóng
        val handler = Handler(Looper.getMainLooper())
        val fr = floatArrayOf(0.10f, 0.30f, 0.50f, 0.70f, 0.90f)
        var k = 0
        var started = false
        val tick = object : Runnable {
            override fun run() {
                val d = exo.duration
                if (d > 0) {
                    exo.seekTo((d * fr[k % fr.size]).toLong())
                    exo.play()
                    k++
                }
                handler.postDelayed(this, 2200L)
            }
        }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !started) {
                    started = true
                    handler.post(tick)
                } else if (state == Player.STATE_ENDED) {
                    exo.seekTo(0)
                    exo.play()
                }
            }
        })

        playerView.setOnClickListener {
            val pos = exo.currentPosition
            dlg.dismiss()
            onOpen(pos)
        }
        dlg.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            exo.release()
        }
        dlg.show()
        return dlg
    }
}
