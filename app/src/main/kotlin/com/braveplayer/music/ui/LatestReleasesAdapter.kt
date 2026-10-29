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
import com.braveplayer.music.model.ReleaseItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for Latest Releases horizontal carousel on the home page.
 */
class LatestReleasesAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onReleaseClicked: (ReleaseItem) -> Unit,
    private val onPlayClicked: (ReleaseItem) -> Unit
) : RecyclerView.Adapter<LatestReleasesAdapter.ReleaseViewHolder>() {

    private val releases = mutableListOf<ReleaseItem>()

    companion object {
        private val releaseImageCache = LruCache<String, Bitmap>(60)
    }

    fun submitData(newReleases: List<ReleaseItem>) {
        releases.clear()
        releases.addAll(newReleases)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReleaseViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_release_card,
            parent,
            false
        )
        return ReleaseViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReleaseViewHolder, position: Int) {
        holder.bind(releases[position])
    }

    override fun getItemCount(): Int = releases.size

    inner class ReleaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivReleaseCover)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvReleaseBadge)
        private val btnPlay: FrameLayout = itemView.findViewById(R.id.btnReleasePlay)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvReleaseTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvReleaseArtist)
        private val tvDate: TextView = itemView.findViewById(R.id.tvReleaseDate)
        private var imageLoadJob: Job? = null

        fun bind(item: ReleaseItem) {
            imageLoadJob?.cancel()

            tvTitle.text = item.title
            tvArtist.text = item.artists
            tvBadge.text = item.releaseTag
            tvDate.text = item.releaseDate ?: "New Release"
            itemView.contentDescription = "Release ${item.title} by ${item.artists}"

            val url = item.imageUrl
            if (!url.isNullOrBlank()) {
                val cached = releaseImageCache.get(url)
                if (cached != null) {
                    ivCover.setImageBitmap(cached)
                } else {
                    ivCover.setImageResource(R.drawable.ic_music_note)
                    imageLoadJob = scope.launch(Dispatchers.IO) {
                        try {
                            val request = Request.Builder().url(url).build()
                            val response = httpClient.newCall(request).execute()
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                if (bytes != null) {
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bitmap != null) {
                                        releaseImageCache.put(url, bitmap)
                                        withContext(Dispatchers.Main) {
                                            ivCover.setImageBitmap(bitmap)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Leave placeholder
                        }
                    }
                }
            } else {
                ivCover.setImageResource(R.drawable.ic_music_note)
            }

            itemView.setOnClickListener {
                onReleaseClicked(item)
            }

            btnPlay.setOnClickListener {
                onPlayClicked(item)
            }
        }
    }
}
