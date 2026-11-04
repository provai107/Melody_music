package com.braveplayer.music.jam.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.jam.model.JamParticipant
import com.braveplayer.music.jam.model.JamRole

class JamParticipantAdapter : RecyclerView.Adapter<JamParticipantAdapter.ViewHolder>() {

    private val participants = mutableListOf<JamParticipant>()

    fun submitList(list: List<JamParticipant>) {
        participants.clear()
        participants.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_jam_participant, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(participants[position])
    }

    override fun getItemCount(): Int = participants.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAvatar: TextView = itemView.findViewById(R.id.tvParticipantAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvParticipantName)
        private val ivCrown: ImageView = itemView.findViewById(R.id.ivParticipantCrown)

        fun bind(participant: JamParticipant) {
            tvName.text = participant.displayName
            val initial = participant.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            tvAvatar.text = initial

            ivCrown.visibility = if (participant.role == JamRole.HOST) View.VISIBLE else View.GONE
        }
    }
}
