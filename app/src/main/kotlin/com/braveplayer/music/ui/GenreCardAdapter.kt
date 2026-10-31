package com.braveplayer.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.model.GenreCardTheme
import com.braveplayer.music.model.SearchResponseParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for reference-inspired vibrant genre cards with bold titles
 * and tilted artwork clipped to rounded corners.
 */
class GenreCardAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onGenreClicked: (GenreCardTheme) -> Unit
) : RecyclerView.Adapter<GenreCardAdapter.GenreViewHolder>() {

    private val genres = mutableListOf<GenreCardTheme>()

    companion object {
        private val genreImageCache = LruCache<String, Bitmap>(50)
    }

    fun submitData(newGenres: List<GenreCardTheme>) {
        genres.clear()
        genres.addAll(newGenres)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GenreViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_genre_card_reference,
            parent,
            false
        )
        return GenreViewHolder(view)
    }

    override fun onBindViewHolder(holder: GenreViewHolder, position: Int) {
        holder.bind(genres[position])
    }

    override fun getItemCount(): Int = genres.size

    inner class GenreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: CardView = itemView.findViewById(R.id.cardGenreRoot)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvGenreTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvGenreSubtitle)
        private val ivArtwork: ImageView = itemView.findViewById(R.id.ivGenreArtwork)
        private var imageLoadJob: Job? = null

        fun bind(item: GenreCardTheme) {
            imageLoadJob?.cancel()

            // Dynamic vibrant background color
            try {
                val color = Color.parseColor(item.backgroundColorHex)
                cardRoot.setCardBackgroundColor(color)
            } catch (_: Exception) {
                cardRoot.setCardBackgroundColor(Color.parseColor("#8E24AA"))
            }

            tvTitle.text = item.displayName
            tvSubtitle.text = item.subtitle
            itemView.contentDescription = "Genre ${item.displayName}, ${item.subtitle}"

            // Load artwork into tilted card
            val url = item.artworkUrl
            if (!url.isNullOrBlank() && !SearchResponseParser.isJioSaavnPlaceholder(url)) {
                val cached = genreImageCache.get(url)
                if (cached != null) {
                    ivArtwork.setImageBitmap(cached)
                } else {
                    ivArtwork.setImageResource(R.drawable.ic_melody_logo)
                    imageLoadJob = scope.launch(Dispatchers.IO) {
                        try {
                            val request = Request.Builder().url(url).build()
                            val response = httpClient.newCall(request).execute()
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                if (bytes != null) {
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bitmap != null) {
                                        genreImageCache.put(url, bitmap)
                                        withContext(Dispatchers.Main) {
                                            ivArtwork.setImageBitmap(bitmap)
                                        }
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    ivArtwork.setImageResource(R.drawable.ic_melody_logo)
                                }
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                ivArtwork.setImageResource(R.drawable.ic_melody_logo)
                            }
                        }
                    }
                }
            } else {
                ivArtwork.setImageResource(R.drawable.ic_melody_logo)
            }

            itemView.setOnClickListener {
                onGenreClicked(item)
            }
        }
    }
}
