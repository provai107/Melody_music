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
import com.braveplayer.music.model.ArtistAlbumItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for albums and singles carousel in the Artist Page.
 */
class ArtistAlbumAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onAlbumClicked: (ArtistAlbumItem) -> Unit
) : RecyclerView.Adapter<ArtistAlbumAdapter.AlbumViewHolder>() {

    private val albums = mutableListOf<ArtistAlbumItem>()

    companion object {
        private val imageCache = LruCache<String, Bitmap>(30)
    }

    fun submitList(newAlbums: List<ArtistAlbumItem>) {
        albums.clear()
        albums.addAll(newAlbums)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_playlist_card_compact,
            parent,
            false
        )
        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(albums[position])
    }

    override fun getItemCount(): Int = albums.size

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivPlaylistCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvPlaylistTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvPlaylistSubtitle)
        private var imageJob: Job? = null

        fun bind(album: ArtistAlbumItem) {
            imageJob?.cancel()
            tvTitle.text = album.title
            tvSubtitle.text = album.year?.let { "$it • Album" } ?: "Album"
            itemView.contentDescription = "Album ${album.title}"

            val url = album.imageUrl
            if (!url.isNullOrBlank()) {
                val cached = imageCache.get(url)
                if (cached != null) {
                    ivCover.setImageBitmap(cached)
                } else {
                    ivCover.setImageResource(R.drawable.ic_library_music)
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
                ivCover.setImageResource(R.drawable.ic_library_music)
            }

            itemView.setOnClickListener { onAlbumClicked(album) }
        }
    }
}
