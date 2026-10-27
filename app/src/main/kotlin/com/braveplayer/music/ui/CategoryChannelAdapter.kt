package com.braveplayer.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.discovery.GenreCategory

/**
 * Adapter for the 2-column "Explore by Mood & Genre" channel grid.
 * Displays large rounded cards (~148dp) with prominent category title, subtitle,
 * decorative artistic icon, and subtle badge.
 */
class CategoryChannelAdapter(
    private val categories: List<GenreCategory>,
    private val onChannelClick: (GenreCategory) -> Unit
) : RecyclerView.Adapter<CategoryChannelAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_channel_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(categories[position])
    }

    override fun getItemCount(): Int = categories.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvChannelTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvChannelSubtitle)
        private val tvIcon: TextView = itemView.findViewById(R.id.tvChannelDecorativeIcon)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvChannelBadge)

        fun bind(category: GenreCategory) {
            tvTitle.text = category.name
            tvSubtitle.text = category.subtitle
            tvIcon.text = category.emoji
            tvBadge.text = "CHANNEL"

            itemView.setOnClickListener { onChannelClick(category) }
        }
    }
}
