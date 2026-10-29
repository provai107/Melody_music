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
import com.braveplayer.music.model.ArtistSearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for rendering matching artist results in the search screen.
 */
class ArtistSearchAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onArtistClicked: (ArtistSearchResult) -> Unit
) : RecyclerView.Adapter<ArtistSearchAdapter.ArtistViewHolder>() {

    private val artists = mutableListOf<ArtistSearchResult>()

    companion object {
        private val imageCache = LruCache<String, Bitmap>(30)
    }

    fun submitList(newArtists: List<ArtistSearchResult>) {
        artists.clear()
        artists.addAll(newArtists)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_artist_search_card,
            parent,
            false
        )
        return ArtistViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        holder.bind(artists[position])
    }

    override fun getItemCount(): Int = artists.size

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivSearchArtistAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvSearchArtistName)
        private val tvRole: TextView = itemView.findViewById(R.id.tvSearchArtistRole)
        private var imageJob: Job? = null

        fun bind(item: ArtistSearchResult) {
            imageJob?.cancel()
            tvName.text = item.name
            tvRole.text = item.role
            itemView.contentDescription = "Artist ${item.name}"

            val url = item.imageUrl
            if (!url.isNullOrBlank()) {
                val cached = imageCache.get(url)
                if (cached != null) {
                    ivAvatar.setImageBitmap(cached)
                } else {
                    ivAvatar.setImageResource(R.drawable.ic_music_note)
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
                                            ivAvatar.setImageBitmap(bitmap)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else {
                ivAvatar.setImageResource(R.drawable.ic_music_note)
            }

            itemView.setOnClickListener { onArtistClicked(item) }
        }
    }
}
