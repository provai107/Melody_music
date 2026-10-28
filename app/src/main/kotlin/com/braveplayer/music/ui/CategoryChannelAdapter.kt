package com.braveplayer.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.discovery.GenreCategory

/**
 * Adapter for the 2-column "Browse All" / Mood & Genre channel grid.
 * Displays rounded cards with prominent category title, subtitle,
 * decorative icon, and subtle maroon, violet, and midnight-blue ambient backgrounds.
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
        holder.bind(categories[position], position)
    }

    override fun getItemCount(): Int = categories.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutContainer: RelativeLayout = itemView.findViewById(R.id.layoutChannelCard)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvChannelTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvChannelSubtitle)
        private val tvIcon: TextView = itemView.findViewById(R.id.tvChannelDecorativeIcon)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvChannelBadge)

        fun bind(category: GenreCategory, position: Int) {
            tvTitle.text = category.name
            tvSubtitle.text = category.subtitle
            tvIcon.text = category.emoji
            tvBadge.text = "BROWSE"

            // Cycle between subtle dark maroon, restrained violet, and midnight-blue backgrounds
            val bgRes = when (position % 3) {
                1 -> R.drawable.bg_channel_card_violet
                2 -> R.drawable.bg_channel_card_blue
                else -> R.drawable.bg_channel_card_maroon
            }
            layoutContainer.setBackgroundResource(bgRes)

            itemView.setOnClickListener { onChannelClick(category) }
        }
    }
}
