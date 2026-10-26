package com.braveplayer.music.lyrics

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R

class SyncedLyricsAdapter(
    private val onLineClicked: ((timeMs: Long) -> Unit)? = null
) : RecyclerView.Adapter<SyncedLyricsAdapter.LyricLineViewHolder>() {

    private var lines: List<LyricLine> = emptyList()
    private var isSynced: Boolean = false
    private var activeIndex: Int = -1

    fun submitLines(newLines: List<LyricLine>, synced: Boolean) {
        this.lines = newLines
        this.isSynced = synced
        this.activeIndex = -1
        notifyDataSetChanged()
    }

    fun setActiveIndex(newIndex: Int) {
        if (newIndex == activeIndex) return
        val oldIndex = activeIndex
        activeIndex = newIndex
        if (oldIndex in lines.indices) {
            notifyItemChanged(oldIndex)
        }
        if (activeIndex in lines.indices) {
            notifyItemChanged(activeIndex)
        }
    }

    fun getActiveIndex(): Int = activeIndex

    fun getLines(): List<LyricLine> = lines

    fun isSyncedLyrics(): Boolean = isSynced

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricLineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return LyricLineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricLineViewHolder, position: Int) {
        val line = lines[position]
        holder.bind(line, isSynced, position == activeIndex)
    }

    override fun getItemCount(): Int = lines.size

    inner class LyricLineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLyricLine: TextView = itemView.findViewById(R.id.tvLyricLine)

        fun bind(line: LyricLine, synced: Boolean, isActive: Boolean) {
            tvLyricLine.text = line.text

            if (synced) {
                if (isActive) {
                    // Current singing line: high-emphasis bright white / bold
                    tvLyricLine.setTextColor(Color.parseColor("#FFFFFF"))
                    tvLyricLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    tvLyricLine.setTypeface(null, Typeface.BOLD)
                    tvLyricLine.alpha = 1.0f
                } else {
                    // Inactive lines: subtly dimmed translucent
                    tvLyricLine.setTextColor(Color.parseColor("#88FFFFFF"))
                    tvLyricLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                    tvLyricLine.setTypeface(null, Typeface.NORMAL)
                    tvLyricLine.alpha = 0.45f
                }

                itemView.setOnClickListener {
                    if (line.timeMs >= 0L) {
                        onLineClicked?.invoke(line.timeMs)
                    }
                }
            } else {
                // Plain static lyrics: normal clean white presentation
                tvLyricLine.setTextColor(Color.parseColor("#EEEEEE"))
                tvLyricLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                tvLyricLine.setTypeface(null, Typeface.NORMAL)
                tvLyricLine.alpha = 1.0f
                itemView.isClickable = false
            }
        }
    }
}
