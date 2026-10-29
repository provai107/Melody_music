package com.braveplayer.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.model.ArtistProfile
import com.braveplayer.music.model.Song
import com.braveplayer.music.util.ArtistClickHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for rendering songs in the Dedicated Artist Page (Popular songs & Complete Discography).
 */
class ArtistSongAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val showRank: Boolean = false,
    private val onSongSelected: (Song) -> Unit,
    private val onArtistClicked: (String) -> Unit,
    private val onSongOverflow: (Song) -> Unit,
    private val onSongLikeToggled: (Song) -> Unit,
    private val isSongLiked: (String) -> Boolean
) : RecyclerView.Adapter<ArtistSongAdapter.SongViewHolder>() {

    private val songs = mutableListOf<Song>()

    companion object {
        private val imageCache = LruCache<String, Bitmap>(60)
    }

    val currentList: List<Song> get() = songs.toList()

    fun submitList(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_artist_song_row,
            parent,
            false
        )
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position], position + 1)
    }

    override fun getItemCount(): Int = songs.size

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRank: TextView = itemView.findViewById(R.id.tvSongRank)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivArtistSongCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvArtistSongTitle)
        private val tvArtists: TextView = itemView.findViewById(R.id.tvArtistSongArtists)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvArtistSongMeta)
        private val btnLike: ImageView = itemView.findViewById(R.id.btnLikeArtistSong)
        private val btnOverflow: ImageView = itemView.findViewById(R.id.btnArtistSongOverflow)
        private var imageJob: Job? = null

        fun bind(song: Song, rank: Int) {
            imageJob?.cancel()

            if (showRank) {
                tvRank.visibility = View.VISIBLE
                tvRank.text = rank.toString()
            } else {
                tvRank.visibility = View.GONE
            }

            tvTitle.text = song.title

            // Independently clickable artist names
            ArtistClickHelper.attachClickableArtists(
                textView = tvArtists,
                rawArtists = song.artists,
                album = null,
                onArtistClicked = onArtistClicked
            )

            // Build meta text (e.g. "2025 • 3:45" or "10M plays • 3:45")
            val metaParts = mutableListOf<String>()
            val yearOrDate = song.year?.takeIf { it.isNotBlank() } ?: song.releaseDate?.takeIf { it.isNotBlank() }
            if (!yearOrDate.isNullOrBlank()) {
                metaParts.add(yearOrDate)
            } else if (song.playCount != null && song.playCount > 0) {
                metaParts.add("${ArtistProfile.formatMetricCount(song.playCount)} plays")
            }
            if (song.formattedDuration.isNotBlank() && song.formattedDuration != "--:--") {
                metaParts.add(song.formattedDuration)
            }

            if (metaParts.isNotEmpty()) {
                tvMeta.visibility = View.VISIBLE
                tvMeta.text = metaParts.joinToString(" • ")
            } else {
                tvMeta.visibility = View.GONE
            }

            // Like state
            val liked = isSongLiked(song.id)
            updateLikeIcon(liked)

            btnLike.setOnClickListener {
                onSongLikeToggled(song)
                updateLikeIcon(isSongLiked(song.id))
            }

            btnOverflow.setOnClickListener {
                onSongOverflow(song)
            }

            itemView.setOnClickListener {
                onSongSelected(song)
            }

            // Image loading
            val url = song.imageUrl
            if (!url.isNullOrBlank()) {
                val cached = imageCache.get(url)
                if (cached != null) {
                    ivCover.setImageBitmap(cached)
                } else {
                    ivCover.setImageResource(R.drawable.ic_music_note)
                    imageJob = scope.launch(Dispatchers.IO) {
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
                                            ivCover.setImageBitmap(bitmap)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else {
                ivCover.setImageResource(R.drawable.ic_music_note)
            }
        }

        private fun updateLikeIcon(liked: Boolean) {
            if (liked) {
                btnLike.setImageResource(R.drawable.ic_heart_filled)
                btnLike.setColorFilter(ContextCompat.getColor(itemView.context, R.color.accentPrimary))
            } else {
                btnLike.setImageResource(R.drawable.ic_heart_outline)
                btnLike.setColorFilter(ContextCompat.getColor(itemView.context, R.color.textSecondary))
            }
        }
    }
}
