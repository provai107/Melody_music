package com.braveplayer.music.jam.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.jam.model.JamQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class JamQueueAdapter(
    private val currentGuestId: String?,
    private val isHost: Boolean,
    private val onRemoveClick: (JamQueueItem) -> Unit
) : RecyclerView.Adapter<JamQueueAdapter.ViewHolder>() {

    private val items = mutableListOf<JamQueueItem>()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun submitList(newItems: List<JamQueueItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_jam_queue, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivArtwork: ImageView = itemView.findViewById(R.id.ivJamQueueArtwork)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvJamQueueTitle)
        private val tvArtists: TextView = itemView.findViewById(R.id.tvJamQueueArtists)
        private val tvAddedBy: TextView = itemView.findViewById(R.id.tvJamQueueAddedBy)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnJamQueueRemove)

        fun bind(item: JamQueueItem) {
            tvTitle.text = item.songSummary.title
            tvArtists.text = item.songSummary.artists
            tvAddedBy.text = "Added by ${item.addedByName}"

            val canRemove = isHost || (currentGuestId != null && item.addedByGuestId == currentGuestId)
            btnRemove.visibility = if (canRemove) View.VISIBLE else View.GONE
            btnRemove.setOnClickListener { onRemoveClick(item) }

            // Load artwork asynchronously if available
            val imgUrl = item.songSummary.imageUrl
            if (!imgUrl.isNullOrBlank()) {
                scope.launch {
                    try {
                        val bmp = withContext(Dispatchers.IO) {
                            val stream = URL(imgUrl).openStream()
                            BitmapFactory.decodeStream(stream)
                        }
                        if (bmp != null) ivArtwork.setImageBitmap(bmp)
                    } catch (_: Exception) {}
                }
            } else {
                ivArtwork.setImageResource(R.drawable.ic_melody_logo)
            }
        }
    }
}
