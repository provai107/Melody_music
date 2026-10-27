package com.braveplayer.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R

class SearchChipAdapter(
    private val chips: List<String>,
    private val onChipClicked: (query: String) -> Unit
) : RecyclerView.Adapter<SearchChipAdapter.ChipViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_chip, parent, false)
        return ChipViewHolder(v)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(chips[position])
    }

    override fun getItemCount(): Int = chips.size

    inner class ChipViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tvSearchChipText)

        fun bind(text: String) {
            tvText.text = text
            itemView.setOnClickListener {
                onChipClicked(text)
            }
        }
    }
}
