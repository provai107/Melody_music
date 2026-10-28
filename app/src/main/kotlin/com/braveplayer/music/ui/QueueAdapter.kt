package com.braveplayer.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.media.SongMediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections

sealed class QueueRow {
    data class Header(val title: String, val subtitle: String? = null) : QueueRow()
    data class Track(
        val mediaItem: MediaItem,
        val timelineIndex: Int,
        val isPlaying: Boolean = false,
        val origin: String,
        val contextName: String?
    ) : QueueRow()
}

class QueueAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onItemClicked: (timelineIndex: Int) -> Unit,
    private val onItemRemoved: (timelineIndex: Int) -> Unit,
    private val onItemMoved: (fromTimeline: Int, toTimeline: Int) -> Unit,
    private val onStartDrag: ((viewHolder: RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_TRACK = 1
        private val queueThumbCache = LruCache<String, Bitmap>(60)
    }

    private val rows = mutableListOf<QueueRow>()

    fun submitQueue(newItems: List<MediaItem>, currentIndex: Int) {
        rows.clear()
        val totalCount = newItems.size
        if (totalCount == 0) {
            notifyDataSetChanged()
            return
        }

        // Partition upcoming items (from currentIndex + 1 until end)
        val upcomingManual = mutableListOf<QueueRow.Track>()
        val upcomingContext = mutableListOf<QueueRow.Track>()
        val upcomingRecommended = mutableListOf<QueueRow.Track>()

        val startIndex = (currentIndex + 1).coerceAtLeast(0)
        for (i in startIndex until totalCount) {
            val item = newItems[i]
            val origin = SongMediaItemMapper.getQueueOrigin(item)
            val isRec = SongMediaItemMapper.isRecommended(item)
            val ctxName = SongMediaItemMapper.getContextName(item)
            val trackRow = QueueRow.Track(
                mediaItem = item,
                timelineIndex = i,
                isPlaying = false,
                origin = origin,
                contextName = ctxName
            )

            if (isRec || origin == SongMediaItemMapper.ORIGIN_RECOMMENDED || origin == SongMediaItemMapper.ORIGIN_AUTOPLAY) {
                upcomingRecommended.add(trackRow)
            } else if (origin == SongMediaItemMapper.ORIGIN_MANUAL) {
                upcomingManual.add(trackRow)
            } else {
                upcomingContext.add(trackRow)
            }
        }

        // Section 1: Manually Queued Priority Tracks ("Up Next")
        if (upcomingManual.isNotEmpty()) {
            rows.add(
                QueueRow.Header(
                    title = "UP NEXT",
                    subtitle = "${upcomingManual.size} track${if (upcomingManual.size > 1) "s" else ""} queued by you"
                )
            )
            rows.addAll(upcomingManual)
        }

        // Section 2: Remaining Tracks ("From Your Playlist / Album")
        if (upcomingContext.isNotEmpty()) {
            val firstCtx = upcomingContext.first().contextName
            val headerTitle = if (!firstCtx.isNullOrBlank()) {
                "FROM YOUR PLAYLIST: ${firstCtx.uppercase()}"
            } else {
                "FROM YOUR PLAYLIST / ALBUM"
            }
            rows.add(
                QueueRow.Header(
                    title = headerTitle,
                    subtitle = "${upcomingContext.size} track${if (upcomingContext.size > 1) "s" else ""} remaining"
                )
            )
            rows.addAll(upcomingContext)
        }

        // Section 3: Recommended Next ("Recommended Next" with ✨ badge)
        if (upcomingRecommended.isNotEmpty()) {
            val firstSeed = upcomingRecommended.first().contextName
            val sub = if (!firstSeed.isNullOrBlank()) "✨ $firstSeed • Smart Next Play" else "✨ Tailored to your taste"
            rows.add(
                QueueRow.Header(
                    title = "RECOMMENDED NEXT",
                    subtitle = sub
                )
            )
            rows.addAll(upcomingRecommended)
        }

        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is QueueRow.Header -> VIEW_TYPE_HEADER
            is QueueRow.Track -> VIEW_TYPE_TRACK
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            val v = inflater.inflate(R.layout.item_queue_header, parent, false)
            HeaderViewHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_queue_song, parent, false)
            TrackViewHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is QueueRow.Header -> (holder as HeaderViewHolder).bind(row)
            is QueueRow.Track -> (holder as TrackViewHolder).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    fun isTrackPosition(position: Int): Boolean {
        return position in rows.indices && rows[position] is QueueRow.Track
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (!isTrackPosition(fromPosition) || !isTrackPosition(toPosition)) return false
        val fromTrack = rows[fromPosition] as QueueRow.Track
        val toTrack = rows[toPosition] as QueueRow.Track

        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(rows, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(rows, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        onItemMoved(fromTrack.timelineIndex, toTrack.timelineIndex)
        return true
    }

    fun removeItem(position: Int) {
        if (!isTrackPosition(position)) return
        val track = rows[position] as QueueRow.Track
        rows.removeAt(position)
        notifyItemRemoved(position)

        // If the preceding header is now empty of tracks, remove the header too
        checkAndRemoveEmptyHeaders()

        onItemRemoved(track.timelineIndex)
    }

    private fun checkAndRemoveEmptyHeaders() {
        var i = 0
        while (i < rows.size) {
            if (rows[i] is QueueRow.Header) {
                val nextIsTrack = (i + 1 < rows.size) && (rows[i + 1] is QueueRow.Track)
                if (!nextIsTrack) {
                    rows.removeAt(i)
                    notifyItemRemoved(i)
                    continue
                }
            }
            i++
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvQueueHeaderTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvQueueHeaderSubtitle)

        fun bind(header: QueueRow.Header) {
            tvTitle.text = header.title
            if (!header.subtitle.isNullOrBlank()) {
                tvSubtitle.text = header.subtitle
                tvSubtitle.visibility = View.VISIBLE
            } else {
                tvSubtitle.visibility = View.GONE
            }
        }
    }

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivQueueCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvQueueTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvQueueArtist)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvQueueOriginBadge)
        private val ivIndicator: ImageView = itemView.findViewById(R.id.ivQueuePlayingIndicator)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnQueueRemove)
        private val ivDragHandle: ImageView = itemView.findViewById(R.id.ivQueueDragHandle)

        fun bind(track: QueueRow.Track) {
            val item = track.mediaItem
            val title = item.mediaMetadata.title?.toString() ?: "Unknown Track"
            val artist = item.mediaMetadata.artist?.toString() ?: "Unknown Artist"

            tvTitle.text = title
            tvArtist.text = artist
            ivIndicator.visibility = if (track.isPlaying) View.VISIBLE else View.GONE
            itemView.contentDescription = if (track.isPlaying) "Currently playing: $title by $artist" else "$title by $artist"
            btnRemove.contentDescription = "Remove $title from queue"
            ivDragHandle.contentDescription = "Drag to reorder $title"
            ivCover.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ivIndicator.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            if (SongMediaItemMapper.isRecommended(item) || track.origin == SongMediaItemMapper.ORIGIN_RECOMMENDED) {
                tvBadge.text = "✨ RECOMMENDED"
                tvBadge.visibility = View.VISIBLE
            } else if (track.origin == SongMediaItemMapper.ORIGIN_AUTOPLAY) {
                tvBadge.text = "AUTOPLAY"
                tvBadge.visibility = View.VISIBLE
            } else if (track.origin == SongMediaItemMapper.ORIGIN_MANUAL) {
                tvBadge.text = "QUEUED"
                tvBadge.visibility = View.VISIBLE
            } else {
                tvBadge.visibility = View.GONE
            }

            val imageUrl = item.mediaMetadata.extras?.getString(SongMediaItemMapper.EXTRA_IMAGE_URL)
            loadThumbnail(imageUrl)

            itemView.setOnClickListener {
                onItemClicked(track.timelineIndex)
            }

            btnRemove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    removeItem(pos)
                }
            }

            ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }
        }

        private fun loadThumbnail(url: String?) {
            ivCover.setImageResource(R.drawable.ic_music_note)
            if (url.isNullOrBlank()) return

            val cached = queueThumbCache.get(url)
            if (cached != null) {
                ivCover.setImageBitmap(cached)
                return
            }

            scope.launch {
                val bitmap = fetchBitmap(url)
                if (bitmap != null) {
                    queueThumbCache.put(url, bitmap)
                    ivCover.setImageBitmap(bitmap)
                }
            }
        }

        private suspend fun fetchBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
