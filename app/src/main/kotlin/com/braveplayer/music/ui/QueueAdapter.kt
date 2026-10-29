package com.braveplayer.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.player.QueueItem
import com.braveplayer.music.player.QueueOrigin
import com.braveplayer.music.player.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections

sealed class QueueRow {
    data class Header(
        val title: String,
        val subtitle: String? = null,
        val sectionType: QueueOrigin? = null
    ) : QueueRow()

    data class Track(
        val queueItem: QueueItem,
        val upcomingIndex: Int,
        val isPlaying: Boolean = false
    ) : QueueRow()
}

/**
 * Modern, dark, premium queue adapter supporting distinct Play Next,
 * In Queue, Context tracks, and Autoplay recommendation sections,
 * drag-and-drop reordering, and per-track overflow actions.
 */
class QueueAdapter(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onTrackClicked: (queueItem: QueueItem) -> Unit,
    private val onTrackRemoved: (queueItem: QueueItem) -> Unit,
    private val onTrackPlayNext: (queueItem: QueueItem) -> Unit,
    private val onTrackMoved: (fromUpcomingIndex: Int, toUpcomingIndex: Int) -> Unit,
    private val onStartDrag: ((viewHolder: RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_TRACK = 1
        private val queueThumbCache = LruCache<String, Bitmap>(80)
    }

    private val rows = mutableListOf<QueueRow>()

    fun submitSnapshot(snapshot: QueueSnapshot) {
        rows.clear()

        var runningUpcomingIndex = 0

        // Section 1: Play Next (Highest upcoming priority)
        if (snapshot.playNext.isNotEmpty()) {
            val count = snapshot.playNext.size
            rows.add(
                QueueRow.Header(
                    title = "PLAY NEXT",
                    subtitle = "$count track${if (count > 1) "s" else ""} • Plays next in order",
                    sectionType = QueueOrigin.PLAY_NEXT
                )
            )
            for (item in snapshot.playNext) {
                rows.add(
                    QueueRow.Track(
                        queueItem = item,
                        upcomingIndex = runningUpcomingIndex++,
                        isPlaying = false
                    )
                )
            }
        }

        // Section 2: Manually Queued Tracks ("In Queue")
        if (snapshot.manualQueue.isNotEmpty()) {
            val count = snapshot.manualQueue.size
            rows.add(
                QueueRow.Header(
                    title = "IN QUEUE",
                    subtitle = "$count track${if (count > 1) "s" else ""} queued by you",
                    sectionType = QueueOrigin.ADD_TO_QUEUE
                )
            )
            for (item in snapshot.manualQueue) {
                rows.add(
                    QueueRow.Track(
                        queueItem = item,
                        upcomingIndex = runningUpcomingIndex++,
                        isPlaying = false
                    )
                )
            }
        }

        // Section 3: Active Context Tracks (Playlist, Album, Search, History)
        if (snapshot.contextTracks.isNotEmpty()) {
            val contextTitle = snapshot.playbackContext?.title ?: "YOUR PLAYLIST"
            val count = snapshot.contextTracks.size
            val prefix = if (snapshot.isShuffle) "NEXT FROM (SHUFFLED): " else "NEXT FROM: "
            rows.add(
                QueueRow.Header(
                    title = "$prefix${contextTitle.uppercase()}",
                    subtitle = "$count track${if (count > 1) "s" else ""} remaining",
                    sectionType = QueueOrigin.CONTEXT
                )
            )
            for (item in snapshot.contextTracks) {
                rows.add(
                    QueueRow.Track(
                        queueItem = item,
                        upcomingIndex = runningUpcomingIndex++,
                        isPlaying = false
                    )
                )
            }
        }

        // Section 4: Autoplay Recommendations
        if (snapshot.autoplayEnabled && snapshot.autoplayQueue.isNotEmpty()) {
            val count = snapshot.autoplayQueue.size
            rows.add(
                QueueRow.Header(
                    title = "AUTOPLAY RECOMMENDATIONS",
                    subtitle = "✨ Tailored continuous music • $count track${if (count > 1) "s" else ""}",
                    sectionType = QueueOrigin.AUTOPLAY
                )
            )
            for (item in snapshot.autoplayQueue) {
                rows.add(
                    QueueRow.Track(
                        queueItem = item,
                        upcomingIndex = runningUpcomingIndex++,
                        isPlaying = false
                    )
                )
            }
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
        onTrackMoved(fromTrack.upcomingIndex, toTrack.upcomingIndex)
        return true
    }

    fun removeItemAtPosition(position: Int) {
        if (!isTrackPosition(position)) return
        val track = rows[position] as QueueRow.Track
        rows.removeAt(position)
        notifyItemRemoved(position)

        // Clean up empty headers
        checkAndRemoveEmptyHeaders()
        onTrackRemoved(track.queueItem)
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
        private val btnMore: ImageView? = itemView.findViewById(R.id.btnQueueMore)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnQueueRemove)
        private val ivDragHandle: ImageView = itemView.findViewById(R.id.ivQueueDragHandle)

        fun bind(track: QueueRow.Track) {
            val item = track.queueItem
            val song = item.song

            tvTitle.text = song.title.ifEmpty { "Unknown Title" }
            tvArtist.text = song.artists.ifEmpty { "Unknown Artist" }
            ivIndicator.visibility = if (track.isPlaying) View.VISIBLE else View.GONE

            itemView.contentDescription = if (track.isPlaying) {
                "Currently playing: ${song.title} by ${song.artists}"
            } else {
                "${song.title} by ${song.artists}"
            }
            btnRemove.contentDescription = "Remove ${song.title} from queue"
            ivDragHandle.contentDescription = "Drag to reorder ${song.title}"

            // Section badge styling
            when (item.origin) {
                QueueOrigin.PLAY_NEXT -> {
                    tvBadge.text = "PLAY NEXT"
                    tvBadge.visibility = View.VISIBLE
                }
                QueueOrigin.ADD_TO_QUEUE -> {
                    tvBadge.text = "QUEUED"
                    tvBadge.visibility = View.VISIBLE
                }
                QueueOrigin.AUTOPLAY -> {
                    tvBadge.text = "✨ AUTOPLAY"
                    tvBadge.visibility = View.VISIBLE
                }
                QueueOrigin.CONTEXT -> {
                    tvBadge.visibility = View.GONE
                }
            }

            loadThumbnail(song.imageUrl)

            itemView.setOnClickListener {
                onTrackClicked(item)
            }

            btnRemove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    removeItemAtPosition(pos)
                }
            }

            btnMore?.setOnClickListener { v ->
                showTrackPopupMenu(v.context, v, item)
            }

            ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }
        }

        private fun showTrackPopupMenu(context: Context, anchor: View, item: QueueItem) {
            val popup = PopupMenu(context, anchor)
            val menu = popup.menu

            menu.add(0, 1, 0, "▶ Play Now")
            if (item.origin != QueueOrigin.PLAY_NEXT) {
                menu.add(0, 2, 1, "⏭ Play Next")
            }
            menu.add(0, 3, 2, "❌ Remove from Queue")

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        onTrackClicked(item)
                        true
                    }
                    2 -> {
                        onTrackPlayNext(item)
                        true
                    }
                    3 -> {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            removeItemAtPosition(pos)
                        }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
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
