package com.braveplayer.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.media.SongMediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections

class QueueAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onItemClicked: (index: Int) -> Unit,
    private val onItemRemoved: (index: Int) -> Unit,
    private val onItemMoved: (fromPosition: Int, toPosition: Int) -> Unit,
    private val onStartDrag: ((viewHolder: RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    companion object {
        private val queueThumbCache = LruCache<String, Bitmap>(50)
    }

    private val items = mutableListOf<MediaItem>()
    private var currentPlayingIndex = -1

    fun submitQueue(newItems: List<MediaItem>, currentIndex: Int) {
        items.clear()
        items.addAll(newItems)
        currentPlayingIndex = currentIndex
        notifyDataSetChanged()
    }

    fun setCurrentPlayingIndex(newIndex: Int) {
        if (newIndex == currentPlayingIndex) return
        val old = currentPlayingIndex
        currentPlayingIndex = newIndex
        if (old in items.indices) notifyItemChanged(old)
        if (currentPlayingIndex in items.indices) notifyItemChanged(currentPlayingIndex)
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        onItemMoved(fromPosition, toPosition)
        return true
    }

    fun removeItem(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
            onItemRemoved(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(items[position], position == currentPlayingIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivQueueCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvQueueTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvQueueArtist)
        private val ivIndicator: ImageView = itemView.findViewById(R.id.ivQueuePlayingIndicator)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnQueueRemove)
        private val ivDragHandle: ImageView = itemView.findViewById(R.id.ivQueueDragHandle)

        fun bind(item: MediaItem, isPlaying: Boolean) {
            val title = item.mediaMetadata.title?.toString() ?: "Unknown Track"
            val artist = item.mediaMetadata.artist?.toString() ?: "Unknown Artist"

            tvTitle.text = title
            tvArtist.text = artist
            ivIndicator.visibility = if (isPlaying) View.VISIBLE else View.GONE

            val imageUrl = item.mediaMetadata.extras?.getString(SongMediaItemMapper.EXTRA_IMAGE_URL)
            loadThumbnail(imageUrl)

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClicked(pos)
                }
            }

            btnRemove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    removeItem(pos)
                }
            }

            ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }
        }

        private fun loadThumbnail(url: String?) {
            ivCover.setImageResource(R.drawable.ic_music_note)
            if (url.isNullOrBlank()) return

            val cached = queueThumbCache.get(url)
            if (cached != null) {
                ivCover.setImageBitmap(cached)
                return
            }

            scope.launch {
                val bitmap = fetchBitmap(url)
                if (bitmap != null) {
                    queueThumbCache.put(url, bitmap)
                    ivCover.setImageBitmap(bitmap)
                }
            }
        }

        private suspend fun fetchBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
