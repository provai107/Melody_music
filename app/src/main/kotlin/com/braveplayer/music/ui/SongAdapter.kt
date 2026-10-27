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
import com.braveplayer.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * RecyclerView adapter for displaying JioSaavn song search results and playlist tracks
 * styled with TIDAL-inspired dark visual hierarchy.
 */
class SongAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onSongSelected: (Song) -> Unit,
    private val onSongAddToQueue: ((Song) -> Unit)? = null,
    private val onSongOptions: ((Song) -> Unit)? = null,
    private val onSongLikeToggled: ((Song) -> Unit)? = null,
    private val isSongLiked: ((String) -> Boolean)? = null
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private val songs = mutableListOf<Song>()

    companion object {
        // In-memory image cache for smooth UI scrolling
        private val imageCache = LruCache<String, Bitmap>(100)
    }

    fun submitList(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.bind(song)
    }

    override fun getItemCount(): Int = songs.size

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvSongTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvSongArtist)
        private val tvSongDuration: TextView = itemView.findViewById(R.id.tvSongDuration)
        private val btnLikeSong: ImageView = itemView.findViewById(R.id.btnLikeSong)
        private val btnAddToQueue: ImageView = itemView.findViewById(R.id.btnAddToQueue)
        private var imageLoadJob: Job? = null

        fun bind(song: Song) {
            tvTitle.text = song.title
            val artistText = if (song.album.isNotBlank()) "${song.artists} • ${song.album}" else song.artists
            tvArtist.text = artistText
            tvSongDuration.text = song.formattedDuration

            val liked = isSongLiked?.invoke(song.id) == true
            updateLikeIcon(liked)

            btnLikeSong.setOnClickListener {
                onSongLikeToggled?.invoke(song)
                val newLiked = isSongLiked?.invoke(song.id) == true
                updateLikeIcon(newLiked)
            }

            itemView.setOnClickListener {
                onSongSelected(song)
            }

            itemView.setOnLongClickListener {
                onSongOptions?.invoke(song)
                true
            }

            btnAddToQueue.setOnClickListener {
                if (onSongOptions != null) {
                    onSongOptions.invoke(song)
                } else {
                    onSongAddToQueue?.invoke(song)
                }
            }

            // Cover Image Loading with cache
            imageLoadJob?.cancel()
            val imageUrl = song.imageUrl
            if (!imageUrl.isNullOrBlank()) {
                val cached = imageCache.get(imageUrl)
                if (cached != null) {
                    ivCover.setImageBitmap(cached)
                } else {
                    ivCover.setImageResource(R.drawable.ic_music_note)
                    imageLoadJob = scope.launch {
                        val bitmap = fetchBitmap(imageUrl)
                        if (bitmap != null) {
                            imageCache.put(imageUrl, bitmap)
                            ivCover.setImageBitmap(bitmap)
                        }
                    }
                }
            } else {
                ivCover.setImageResource(R.drawable.ic_music_note)
            }
        }

        private fun updateLikeIcon(liked: Boolean) {
            if (liked) {
                btnLikeSong.setImageResource(R.drawable.ic_ytm_thumb_up_fill)
                btnLikeSong.setColorFilter(itemView.context.getColor(R.color.ytm_icon_active))
            } else {
                btnLikeSong.setImageResource(R.drawable.ic_ytm_thumb_up_outline)
                btnLikeSong.setColorFilter(itemView.context.getColor(R.color.ytm_icon_inactive))
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
