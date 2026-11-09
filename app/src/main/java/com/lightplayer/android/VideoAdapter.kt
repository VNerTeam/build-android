package com.lightplayer.android

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

class VideoAdapter(
    private val ctx: Context,
    private val onClick: (VideoItem) -> Unit,
    private val onLong: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    var items: List<VideoItem> = emptyList()
        private set

    private val dateFmt: DateFormat = DateFormat.getDateInstance(DateFormat.SHORT)

    fun submit(l: List<VideoItem>) {
        items = l
        notifyDataSetChanged()
    }

    class VH(
        val root: LinearLayout,
        val thumb: ImageView,
        val title: TextView,
        val sub: TextView,
        val resume: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Col.THUMB)
                cornerRadius = ctx.dp(8).toFloat()
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        root.addView(thumb, LinearLayout.LayoutParams(ctx.dp(128), ctx.dp(72)))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(ctx).apply {
            setTextColor(Col.FG)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(ctx).apply {
            setTextColor(Col.DIM)
            textSize = 12f
            setPadding(0, ctx.dp(3), 0, 0)
        }
        val resume = TextView(ctx).apply {
            setTextColor(Col.ACC)
            textSize = 12f
            visibility = View.GONE
        }
        col.addView(title)
        col.addView(sub)
        col.addView(resume)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = ctx.dp(12)
        root.addView(col, lp)
        return VH(root, thumb, title, sub, resume)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        h.title.text = v.title
        h.sub.text = Util.fmtSize(v.size) + "  ·  " + dateFmt.format(Date(v.modified))
        val pos = Util.prefs(ctx).getLong("pos:" + v.key, 0L)
        if (pos > 5000L) {
            h.resume.text = "Tiếp tục " + Util.fmtTime(pos)
            h.resume.visibility = View.VISIBLE
        } else {
            h.resume.visibility = View.GONE
        }
        Thumbs.load(ctx, v, h.thumb)
        h.root.setOnClickListener { onClick(v) }
        h.root.setOnLongClickListener { onLong(v); true }
    }
}
