package com.braveplayer.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.discovery.MoodCategory

class MoodAdapter(
    private val moods: List<MoodCategory>,
    private val onMoodClicked: (MoodCategory) -> Unit
) : RecyclerView.Adapter<MoodAdapter.MoodViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MoodViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mood_chip, parent, false)
        return MoodViewHolder(view)
    }

    override fun onBindViewHolder(holder: MoodViewHolder, position: Int) {
        holder.bind(moods[position])
    }

    override fun getItemCount(): Int = moods.size

    inner class MoodViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvEmoji: TextView = itemView.findViewById(R.id.tvMoodEmoji)
        private val tvName: TextView = itemView.findViewById(R.id.tvMoodName)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvMoodSubtitle)

        fun bind(mood: MoodCategory) {
            tvEmoji.text = mood.emoji
            tvName.text = mood.name
            tvSubtitle.text = mood.subtitle

            itemView.setOnClickListener {
                onMoodClicked(mood)
            }
        }
    }
}
