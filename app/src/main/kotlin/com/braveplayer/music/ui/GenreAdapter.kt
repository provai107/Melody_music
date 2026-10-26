package com.braveplayer.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.discovery.GenreCategory

class GenreAdapter(
    private val genres: List<GenreCategory>,
    private val onGenreClicked: (GenreCategory) -> Unit
) : RecyclerView.Adapter<GenreAdapter.GenreViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GenreViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_genre_card, parent, false)
        return GenreViewHolder(view)
    }

    override fun onBindViewHolder(holder: GenreViewHolder, position: Int) {
        holder.bind(genres[position])
    }

    override fun getItemCount(): Int = genres.size

    inner class GenreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvEmoji: TextView = itemView.findViewById(R.id.tvGenreEmoji)
        private val tvName: TextView = itemView.findViewById(R.id.tvGenreName)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvGenreSubtitle)

        fun bind(genre: GenreCategory) {
            tvEmoji.text = genre.emoji
            tvName.text = genre.name
            tvSubtitle.text = genre.subtitle

            itemView.setOnClickListener {
                onGenreClicked(genre)
            }
        }
    }
}
