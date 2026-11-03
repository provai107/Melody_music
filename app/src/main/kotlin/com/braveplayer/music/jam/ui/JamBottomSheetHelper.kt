package com.braveplayer.music.jam.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.music.R
import com.braveplayer.music.jam.model.JamRole
import com.braveplayer.music.jam.service.JamSessionManager
import com.braveplayer.music.model.Song
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.net.URL

class JamBottomSheetHelper(
    private val activity: Activity,
    private val jamSessionManager: JamSessionManager = JamSessionManager.getInstance(activity)
) {

    private var activeRoomDialog: BottomSheetDialog? = null
    private var scope: CoroutineScope? = null

    fun showJamSheet(onAddSongToJamQueue: (() -> Unit)? = null) {
        if (jamSessionManager.isInJam()) {
            showActiveJamRoomSheet(onAddSongToJamQueue)
        } else {
            showJamEntrySheet(onAddSongToJamQueue)
        }
    }

    private fun showJamEntrySheet(onAddSongToJamQueue: (() -> Unit)? = null) {
        val dialog = BottomSheetDialog(activity, R.style.Theme_BraveMusicPlayer)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_jam_entry, null)
        dialog.setContentView(view)

        val tvUserLabel = view.findViewById<TextView>(R.id.tvJamUserLabel)
        val btnEditName = view.findViewById<TextView>(R.id.btnJamEditName)
        val btnStart = view.findViewById<Button>(R.id.btnStartJam)
        val etRoomCode = view.findViewById<EditText>(R.id.etJamRoomCode)
        val btnJoin = view.findViewById<Button>(R.id.btnJoinJam)
        val pbLoading = view.findViewById<ProgressBar>(R.id.pbJamLoading)
        val btnClose = view.findViewById<ImageView>(R.id.btnJamClose)

        fun refreshName() {
            tvUserLabel.text = "Listening as: ${jamSessionManager.getDisplayName()}"
        }
        refreshName()

        btnEditName.setOnClickListener {
            showEditDisplayNameDialog {
                refreshName()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        btnStart.setOnClickListener {
            pbLoading.visibility = View.VISIBLE
            btnStart.isEnabled = false
            btnJoin.isEnabled = false

            jamSessionManager.createJam { result ->
                activity.runOnUiThread {
                    pbLoading.visibility = View.GONE
                    btnStart.isEnabled = true
                    btnJoin.isEnabled = true
                    if (result.isSuccess) {
                        dialog.dismiss()
                        showActiveJamRoomSheet(onAddSongToJamQueue)
                        Toast.makeText(activity, "Jam Room Created!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Failed to start Jam: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnJoin.setOnClickListener {
            val code = etRoomCode.text.toString().trim()
            if (code.length < 4) {
                Toast.makeText(activity, "Please enter a valid room code (e.g. JAM-4821)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pbLoading.visibility = View.VISIBLE
            btnStart.isEnabled = false
            btnJoin.isEnabled = false

            jamSessionManager.joinJam(code) { result ->
                activity.runOnUiThread {
                    pbLoading.visibility = View.GONE
                    btnStart.isEnabled = true
                    btnJoin.isEnabled = true
                    if (result.isSuccess) {
                        dialog.dismiss()
                        showActiveJamRoomSheet(onAddSongToJamQueue)
                        Toast.makeText(activity, "Joined Jam Room!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Failed to join: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showActiveJamRoomSheet(onAddSongToJamQueue: (() -> Unit)? = null) {
        activeRoomDialog?.dismiss()
        val dialog = BottomSheetDialog(activity, R.style.Theme_BraveMusicPlayer)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_jam_room, null)
        dialog.setContentView(view)
        activeRoomDialog = dialog

        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        val tvRoomCode = view.findViewById<TextView>(R.id.tvJamRoomCode)
        val layoutCodePill = view.findViewById<LinearLayout>(R.id.layoutJamCodePill)
        val btnShare = view.findViewById<ImageView>(R.id.btnJamShare)
        val btnCollapse = view.findViewById<ImageView>(R.id.btnJamRoomCollapse)
        val tvSyncStatus = view.findViewById<TextView>(R.id.tvJamSyncStatus)
        val tvHostIndicator = view.findViewById<TextView>(R.id.tvJamHostIndicator)

        val ivArtwork = view.findViewById<ImageView>(R.id.ivJamTrackArtwork)
        val tvTrackTitle = view.findViewById<TextView>(R.id.tvJamTrackTitle)
        val tvTrackArtists = view.findViewById<TextView>(R.id.tvJamTrackArtists)
        val btnPlayPause = view.findViewById<ImageView>(R.id.btnJamPlayPause)

        val tvReactionOverlay = view.findViewById<TextView>(R.id.tvJamActiveReaction)
        val tvParticipantsHeader = view.findViewById<TextView>(R.id.tvJamParticipantsHeader)
        val rvParticipants = view.findViewById<RecyclerView>(R.id.rvJamParticipants)
        val tvQueueHeader = view.findViewById<TextView>(R.id.tvJamQueueHeader)
        val tvQueueEmpty = view.findViewById<TextView>(R.id.tvJamQueueEmpty)
        val rvQueue = view.findViewById<RecyclerView>(R.id.rvJamQueue)
        val btnAddTrack = view.findViewById<TextView>(R.id.btnJamAddTrack)
        val btnLeave = view.findViewById<Button>(R.id.btnLeaveJam)

        val participantAdapter = JamParticipantAdapter()
        rvParticipants.adapter = participantAdapter

        val queueAdapter = JamQueueAdapter(
            currentGuestId = null,
            isHost = jamSessionManager.isHost(),
            onRemoveClick = { item ->
                jamSessionManager.removeFromQueue(item.queueItemId)
            }
        )
        rvQueue.adapter = queueAdapter

        btnCollapse.setOnClickListener { dialog.dismiss() }

        // Copy room code on tap
        layoutCodePill.setOnClickListener {
            val code = jamSessionManager.currentRoom.value?.roomCode ?: return@setOnClickListener
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Melody Jam Code", code))
            Toast.makeText(activity, "Room Code copied: $code", Toast.LENGTH_SHORT).show()
        }

        // Share invite link
        btnShare.setOnClickListener {
            val code = jamSessionManager.currentRoom.value?.roomCode ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Join my Melody Jam!")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "🎵 Listen to music together with me on Melody!\n\nJoin my Jam room with code: $code\nOr open: https://melody.app/jam/join?code=$code"
                )
            }
            activity.startActivity(Intent.createChooser(shareIntent, "Invite to Melody Jam"))
        }

        // Emoji reactions
        val emojis = mapOf(
            R.id.btnReactionHeart to "❤️",
            R.id.btnReactionFire to "🔥",
            R.id.btnReactionMusic to "🎵",
            R.id.btnReactionClap to "👏",
            R.id.btnReactionRocket to "🚀"
        )
        for ((btnId, emoji) in emojis) {
            view.findViewById<TextView>(btnId)?.setOnClickListener {
                jamSessionManager.sendReaction(emoji)
                showTemporaryReactionBubble(tvReactionOverlay, "You reacted: $emoji")
            }
        }

        btnAddTrack.setOnClickListener {
            dialog.dismiss()
            onAddSongToJamQueue?.invoke()
        }

        btnLeave.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(if (jamSessionManager.isHost()) "End Jam Room?" else "Leave Jam?")
                .setMessage(
                    if (jamSessionManager.isHost())
                        "Are you sure you want to end this Jam session for everyone?"
                    else
                        "You will disconnect from this synchronized listening session."
                )
                .setPositiveButton("Leave") { _, _ ->
                    jamSessionManager.leaveJam()
                    dialog.dismiss()
                    Toast.makeText(activity, "Left Jam session", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Coroutine collection for live room updates
        scope?.cancel()
        val jobScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope = jobScope

        jobScope.launch {
            jamSessionManager.currentRoom.collectLatest { room ->
                if (room == null) {
                    dialog.dismiss()
                    return@collectLatest
                }

                tvRoomCode.text = room.roomCode
                val isHost = (jamSessionManager.myRole.value == JamRole.HOST)
                val hostName = room.participants.find { it.role == JamRole.HOST }?.displayName ?: "Host"
                tvHostIndicator.text = if (isHost) "👑 You are Host" else "Host: $hostName"
                btnLeave.text = if (isHost) "End Jam Room" else "Leave Jam"

                // Track artwork & metadata
                val pb = room.playbackState
                if (pb.songSummary != null) {
                    tvTrackTitle.text = pb.songSummary.title
                    tvTrackArtists.text = "${pb.songSummary.artists} • ${pb.songSummary.album}"
                    btnPlayPause.setImageResource(
                        if (pb.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    )

                    val imgUrl = pb.songSummary.imageUrl
                    if (!imgUrl.isNullOrBlank()) {
                        launch(Dispatchers.IO) {
                            try {
                                val stream = URL(imgUrl).openStream()
                                val bmp = BitmapFactory.decodeStream(stream)
                                if (bmp != null) {
                                    withContext(Dispatchers.Main) {
                                        ivArtwork.setImageBitmap(bmp)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    tvTrackTitle.text = "No track playing"
                    tvTrackArtists.text = "Play a song to begin"
                    btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
                    ivArtwork.setImageResource(R.drawable.ic_melody_logo)
                }

                // Participants
                tvParticipantsHeader.text = "Participants (${room.participants.size})"
                participantAdapter.submitList(room.participants)

                // Collaborative Queue
                queueAdapter.submitList(room.queue)
                tvQueueEmpty.visibility = if (room.queue.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        jobScope.launch {
            jamSessionManager.driftInfo.collectLatest { info ->
                val drift = info.driftMs
                val statusText = when {
                    jamSessionManager.isHost() -> "● Host • Broadcasting"
                    drift < 150 -> "● Connected • In Sync"
                    info.isCorrecting -> "● Fine-tuning Sync (${drift}ms)"
                    else -> "● Syncing..."
                }
                tvSyncStatus.text = statusText
            }
        }

        jobScope.launch {
            jamSessionManager.reactionsFlow.collectLatest { reaction ->
                showTemporaryReactionBubble(tvReactionOverlay, "${reaction.displayName} reacted: ${reaction.emoji}")
            }
        }

        dialog.setOnDismissListener {
            scope?.cancel()
            scope = null
            activeRoomDialog = null
        }

        dialog.show()
    }

    private fun showTemporaryReactionBubble(textView: TextView, text: String) {
        textView.text = text
        textView.visibility = View.VISIBLE
        textView.animate().alpha(1f).setDuration(200).withEndAction {
            textView.postDelayed({
                textView.animate().alpha(0f).setDuration(300).withEndAction {
                    textView.visibility = View.GONE
                }
            }, 2500)
        }
    }

    private fun showEditDisplayNameDialog(onSaved: () -> Unit) {
        val input = EditText(activity).apply {
            setText(jamSessionManager.getDisplayName())
            setSelectAllOnFocus(true)
            maxLines = 1
        }

        AlertDialog.Builder(activity)
            .setTitle("Your Jam Display Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    jamSessionManager.updateDisplayName(newName)
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Checks intent for incoming deep link invitations (e.g. melody://jam/join?code=JAM-4821).
     */
    fun handleDeepLink(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        val code = data.getQueryParameter("code")
        if (!code.isNullOrBlank()) {
            Toast.makeText(activity, "Joining Jam Room: $code...", Toast.LENGTH_SHORT).show()
            jamSessionManager.joinJam(code) { result ->
                activity.runOnUiThread {
                    if (result.isSuccess) {
                        showActiveJamRoomSheet()
                    } else {
                        Toast.makeText(activity, "Could not join Jam: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return true
        }
        return false
    }
}
