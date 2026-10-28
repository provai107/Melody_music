package com.braveplayer.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.discovery.PersonalizedMix

/**
 * Adapter for large horizontal Daily Mixes carousel cards.
 */
class DailyMixCardAdapter(
    private val onMixClicked: (PersonalizedMix) -> Unit,
    private val onPlayClicked: (PersonalizedMix) -> Unit,
    private val onShuffleClicked: (PersonalizedMix) -> Unit
) : RecyclerView.Adapter<DailyMixCardAdapter.DailyMixViewHolder>() {

    private val mixes = mutableListOf<PersonalizedMix>()

    fun submitMixes(newList: List<PersonalizedMix>) {
        mixes.clear()
        mixes.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DailyMixViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_mix_card_large,
            parent,
            false
        )
        return DailyMixViewHolder(view)
    }

    override fun onBindViewHolder(holder: DailyMixViewHolder, position: Int) {
        holder.bind(mixes[position])
    }

    override fun getItemCount(): Int = mixes.size

    inner class DailyMixViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvBadge: TextView = itemView.findViewById(R.id.tvMixBadge)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvMixTitle)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvMixDescription)
        private val btnPlay: Button = itemView.findViewById(R.id.btnPlayMix)
        private val btnShuffle: ImageButton = itemView.findViewById(R.id.btnShuffleMix)

        fun bind(mix: PersonalizedMix) {
            tvBadge.text = mix.category.ifBlank { "DAILY MIX" }
            tvTitle.text = mix.title
            tvDescription.text = mix.description
            itemView.contentDescription = "${mix.title}, ${mix.description}"
            btnPlay.contentDescription = "Play ${mix.title}"
            btnShuffle.contentDescription = "Shuffle ${mix.title}"

            itemView.setOnClickListener {
                onMixClicked(mix)
            }

            btnPlay.setOnClickListener {
                onPlayClicked(mix)
            }

            btnShuffle.setOnClickListener {
                onShuffleClicked(mix)
            }
        }
    }
}
