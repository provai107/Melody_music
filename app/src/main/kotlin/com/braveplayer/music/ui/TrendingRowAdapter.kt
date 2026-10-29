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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for vertically stacked trending rows on the Explore screen.
 * Displays 80dp square artwork, rank badge, bold title, content type & artist metadata,
 * and quick playback / options controls.
 */
class TrendingRowAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onSongClicked: (Song) -> Unit,
    private val onPlayClicked: (Song) -> Unit,
    private val onOptionClicked: (Song) -> Unit,
    private val onArtistClicked: ((String) -> Unit)? = null
) : RecyclerView.Adapter<TrendingRowAdapter.ViewHolder>() {

    companion object {
        private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(1024)
        private val imageCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private val songs = mutableListOf<Song>()

    fun submitData(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trending_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(songs[position], position + 1)
    }

    override fun getItemCount(): Int = songs.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivTrendingThumb)
        private val tvRankBadge: TextView = itemView.findViewById(R.id.tvTrendingRankBadge)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTrendingTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvTrendingSubtitle)
        private val btnPlay: ImageView = itemView.findViewById(R.id.btnTrendingPlay)
        private val btnOptions: ImageView = itemView.findViewById(R.id.btnTrendingOptions)
        private val ivChevron: ImageView = itemView.findViewById(R.id.ivTrendingChevron)

        fun bind(song: Song, rank: Int) {
            tvRankBadge.text = "#$rank"
            tvTitle.text = song.title
            val artistStr = if (song.artists.isNotBlank()) song.artists else "Trending Artist"
            if (onArtistClicked != null) {
                com.braveplayer.music.util.ArtistClickHelper.attachClickableArtists(
                    textView = tvSubtitle,
                    rawArtists = artistStr,
                    onArtistClicked = onArtistClicked
                )
            } else {
                tvSubtitle.text = "Song • $artistStr"
                tvSubtitle.movementMethod = null
            }
            itemView.contentDescription = "Trending rank #$rank, ${song.title}, by $artistStr"
            btnPlay.contentDescription = "Play ${song.title}"
            btnOptions.contentDescription = "Options for ${song.title}"
            ivThumb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            btnPlay.visibility = View.VISIBLE
            btnOptions.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE

            ivThumb.setImageResource(R.drawable.ic_music_note)
            val url = song.imageUrl
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

            itemView.setOnClickListener { onSongClicked(song) }
            btnPlay.setOnClickListener { onPlayClicked(song) }
            btnOptions.setOnClickListener { onOptionClicked(song) }
        }
    }
}
