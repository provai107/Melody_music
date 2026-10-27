package com.braveplayer.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class HomeCollectionItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String? = null,
    val songs: List<Song> = emptyList()
)

/**
 * Adapter for Recommended Albums and Playlists carousel.
 */
class CuratedPlaylistCardAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onCollectionClicked: (HomeCollectionItem) -> Unit,
    private val onPlayClicked: (HomeCollectionItem) -> Unit
) : RecyclerView.Adapter<CuratedPlaylistCardAdapter.PlaylistViewHolder>() {

    private val items = mutableListOf<HomeCollectionItem>()

    companion object {
        private val playlistImageCache = LruCache<String, Bitmap>(50)
    }

    fun submitItems(newItems: List<HomeCollectionItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_playlist_card_compact,
            parent,
            false
        )
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivPlaylistCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvPlaylistTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvPlaylistSubtitle)
        private val btnQuickPlay: FrameLayout = itemView.findViewById(R.id.btnPlaylistQuickPlay)
        private var imageLoadJob: Job? = null

        fun bind(item: HomeCollectionItem) {
            tvTitle.text = item.title
            tvSubtitle.text = item.subtitle

            imageLoadJob?.cancel()
            val imageUrl = item.imageUrl
            if (!imageUrl.isNullOrBlank()) {
                val cached = playlistImageCache.get(imageUrl)
                if (cached != null) {
                    ivCover.setImageBitmap(cached)
                } else {
                    ivCover.setImageResource(R.drawable.ic_library_music)
                    imageLoadJob = scope.launch {
                        val bitmap = fetchBitmap(imageUrl)
                        if (bitmap != null) {
                            playlistImageCache.put(imageUrl, bitmap)
                            ivCover.setImageBitmap(bitmap)
                        }
                    }
                }
            } else {
                ivCover.setImageResource(R.drawable.ic_library_music)
            }

            itemView.setOnClickListener {
                onCollectionClicked(item)
            }

            btnQuickPlay.setOnClickListener {
                onPlayClicked(item)
            }
        }

        private suspend fun fetchBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null) {
                        return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
