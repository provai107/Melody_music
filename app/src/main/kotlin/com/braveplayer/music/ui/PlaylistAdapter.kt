package com.braveplayer.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.playlist.Playlist

/**
 * RecyclerView adapter for user playlists in My Collection.
 */
class PlaylistAdapter(
    private val onPlaylistSelected: (Playlist) -> Unit,
    private val onPlaylistOptions: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    private val playlists = mutableListOf<Playlist>()

    fun submitList(newPlaylists: List<Playlist>) {
        playlists.clear()
        playlists.addAll(newPlaylists)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount(): Int = playlists.size

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvPlaylistCount)
        private val btnOptions: ImageView = itemView.findViewById(R.id.btnPlaylistOptions)

        fun bind(playlist: Playlist) {
            tvName.text = playlist.name
            tvCount.text = "${playlist.songCount} tracks"
            itemView.contentDescription = "Playlist ${playlist.name}, ${playlist.songCount} tracks"
            btnOptions.contentDescription = "Options for playlist ${playlist.name}"

            itemView.setOnClickListener {
                onPlaylistSelected(playlist)
            }

            btnOptions.setOnClickListener {
                onPlaylistOptions(playlist)
            }
        }
    }
}
