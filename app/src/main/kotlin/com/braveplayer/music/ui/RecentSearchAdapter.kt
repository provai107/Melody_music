package com.braveplayer.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.playlist.SearchHistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for recent searches list on the Explore screen.
 * Displays compact horizontal rows with square thumbnails, title, secondary metadata,
 * and individual item removal.
 */
class RecentSearchAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onItemClick: (SearchHistoryItem) -> Unit,
    private val onRemoveClick: (SearchHistoryItem) -> Unit
) : RecyclerView.Adapter<RecentSearchAdapter.ViewHolder>() {

    companion object {
        private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(1024)
        private val imageCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private val items = mutableListOf<SearchHistoryItem>()

    fun submitList(newItems: List<SearchHistoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_search_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivRecentSearchThumb)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvRecentSearchTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvRecentSearchSubtitle)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnRemoveRecentSearch)

        fun bind(item: SearchHistoryItem) {
            tvTitle.text = item.title
            tvSubtitle.text = item.subtitle

            // Load thumbnail
            ivThumb.setImageResource(R.drawable.ic_music_note)
            val url = item.imageUrl
            if (!url.isNullOrBlank()) {
                val cached = imageCache.get(url)
                if (cached != null) {
                    ivThumb.setImageBitmap(cached)
                } else {
                    ivThumb.tag = url
                    scope.launch(Dispatchers.IO) {
                        try {
                            val request = Request.Builder().url(url).build()
                            val response = httpClient.newCall(request).execute()
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                if (bytes != null) {
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bitmap != null) {
                                        imageCache.put(url, bitmap)
                                        withContext(Dispatchers.Main) {
                                            if (ivThumb.tag == url) {
                                                ivThumb.setImageBitmap(bitmap)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Fallback remains ic_music_note
                        }
                    }
                }
            }

            itemView.setOnClickListener { onItemClick(item) }
            btnRemove.setOnClickListener { onRemoveClick(item) }
        }
    }
}
