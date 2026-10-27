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

/**
 * Reusable horizontal song card adapter for music carousels:
 * - Recently Played
 * - Trending Right Now
 * - Made for You
 * - Most Played
 */
class HorizontalSongCardAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onSongClicked: (Song) -> Unit,
    private val onPlayClicked: (Song) -> Unit,
    private val onOptionClicked: (Song) -> Unit,
    private val showRankBadge: Boolean = false
) : RecyclerView.Adapter<HorizontalSongCardAdapter.SongCardViewHolder>() {

    private val songs = mutableListOf<Song>()
    private val playCounts = mutableMapOf<String, Int>()

    companion object {
        private val imageCache = LruCache<String, Bitmap>(100)
    }

    fun submitData(newSongs: List<Song>, counts: Map<String, Int>? = null) {
        songs.clear()
        songs.addAll(newSongs)
        playCounts.clear()
        if (counts != null) {
            playCounts.putAll(counts)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongCardViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_song_card_compact,
            parent,
            false
        )
        return SongCardViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongCardViewHolder, position: Int) {
        holder.bind(songs[position], position)
    }

    override fun getItemCount(): Int = songs.size

    inner class SongCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCardCover)
        private val btnQuickPlay: FrameLayout = itemView.findViewById(R.id.btnCardQuickPlay)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvCardBadge)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvCardTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvCardArtist)
        private val btnOptions: ImageView = itemView.findViewById(R.id.btnCardOptions)
        private var imageLoadJob: Job? = null

        fun bind(song: Song, position: Int) {
            tvTitle.text = song.title
            tvArtist.text = if (song.artists.isNotBlank()) song.artists else song.album

            // Badge display logic
            val count = playCounts[song.id]
            if (count != null && count > 0) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = "$count plays"
            } else if (showRankBadge) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = "#${position + 1}"
            } else {
                tvBadge.visibility = View.GONE
            }

            // Cover Image Loading with memory cache and coroutines
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

            itemView.setOnClickListener {
                onSongClicked(song)
            }

            btnQuickPlay.setOnClickListener {
                onPlayClicked(song)
            }

            btnOptions.setOnClickListener {
                onOptionClicked(song)
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
