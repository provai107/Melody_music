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
import com.braveplayer.music.model.PopularArtistItem
import com.braveplayer.music.model.SearchResponseParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for circular Popular Artists carousel on the home page.
 */
class PopularArtistAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onArtistClicked: (PopularArtistItem) -> Unit
) : RecyclerView.Adapter<PopularArtistAdapter.ArtistViewHolder>() {

    private val artists = mutableListOf<PopularArtistItem>()

    companion object {
        private val artistImageCache = LruCache<String, Bitmap>(40)
    }

    fun submitData(newArtists: List<PopularArtistItem>) {
        artists.clear()
        artists.addAll(newArtists)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_popular_artist_card,
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
        private val ivImage: ImageView = itemView.findViewById(R.id.ivArtistImage)
        private val tvName: TextView = itemView.findViewById(R.id.tvArtistName)
        private val tvRole: TextView = itemView.findViewById(R.id.tvArtistRole)
        private var imageJob: Job? = null

        fun bind(item: PopularArtistItem) {
            imageJob?.cancel()

            tvName.text = item.name
            tvRole.text = item.role
            itemView.contentDescription = "Artist ${item.name}"

            val url = item.imageUrl
            if (!url.isNullOrBlank() && !SearchResponseParser.isJioSaavnPlaceholder(url)) {
                val cached = artistImageCache.get(url)
                if (cached != null) {
                    ivImage.setImageBitmap(cached)
                } else {
                    ivImage.setImageResource(R.drawable.ic_melody_logo)
                    imageJob = scope.launch(Dispatchers.IO) {
                        try {
                            val request = Request.Builder().url(url).build()
                            val response = httpClient.newCall(request).execute()
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                if (bytes != null) {
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bitmap != null) {
                                        artistImageCache.put(url, bitmap)
                                        withContext(Dispatchers.Main) {
                                            ivImage.setImageBitmap(bitmap)
                                        }
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    ivImage.setImageResource(R.drawable.ic_melody_logo)
                                }
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                ivImage.setImageResource(R.drawable.ic_melody_logo)
                            }
                        }
                    }
                }
            } else {
                ivImage.setImageResource(R.drawable.ic_melody_logo)
            }

            itemView.setOnClickListener { onArtistClicked(item) }
        }
    }
}
