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
import com.braveplayer.music.model.ReleaseItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for the full-view Latest Releases bottom sheet.
 */
class LatestReleasesSheetAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onReleaseClicked: (ReleaseItem) -> Unit,
    private val onPlayClicked: (ReleaseItem) -> Unit
) : RecyclerView.Adapter<LatestReleasesSheetAdapter.RowViewHolder>() {

    private val releases = mutableListOf<ReleaseItem>()

    companion object {
        private val sheetImageCache = LruCache<String, Bitmap>(60)
    }

    fun submitData(newReleases: List<ReleaseItem>) {
        releases.clear()
        releases.addAll(newReleases)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_release_sheet_row,
            parent,
            false
        )
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(releases[position])
    }

    override fun getItemCount(): Int = releases.size

    inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivRowCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvRowTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvRowArtist)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvRowBadge)
        private val btnPlay: ImageView = itemView.findViewById(R.id.btnRowPlay)
        private var imageJob: Job? = null

        fun bind(item: ReleaseItem) {
            imageJob?.cancel()

            tvTitle.text = item.title
            val subtitle = if (item.album.isNotBlank() && item.album != "Unknown Album") {
                "${item.artists} • ${item.album}"
            } else {
                item.artists
            }
            tvArtist.text = subtitle
            tvBadge.text = item.releaseTag

            val url = item.imageUrl
            if (!url.isNullOrBlank()) {
                val cached = sheetImageCache.get(url)
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
                                        sheetImageCache.put(url, bitmap)
                                        withContext(Dispatchers.Main) {
                                            ivCover.setImageBitmap(bitmap)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Keep placeholder
                        }
                    }
                }
            } else {
                ivCover.setImageResource(R.drawable.ic_music_note)
            }

            itemView.setOnClickListener { onReleaseClicked(item) }
            btnPlay.setOnClickListener { onPlayClicked(item) }
        }
    }
}
