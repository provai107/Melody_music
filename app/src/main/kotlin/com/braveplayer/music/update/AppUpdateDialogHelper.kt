package com.braveplayer.music.update

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.braveplayer.music.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Controller managing the Melody-branded BottomSheetDialog for update notification,
 * real-time download progress, and verified APK installation launch.
 */
class AppUpdateDialogHelper(
    private val activity: Activity,
    private val updateManager: AppUpdateManager,
    private val scope: CoroutineScope
) {
    private var downloadJob: Job? = null
    private var downloadedApkFile: File? = null

    fun showUpdateDialog(metadata: UpdateMetadata, onDismissed: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)
        dialog.setContentView(view)

        val tvTitle: TextView = view.findViewById(R.id.tvUpdateTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvUpdateSubtitle)
        val ivClose: ImageView = view.findViewById(R.id.ivCloseUpdateDialog)
        val tvVersionName: TextView = view.findViewById(R.id.tvUpdateVersionName)
        val tvVersionCode: TextView = view.findViewById(R.id.tvUpdateVersionCode)
        val tvMandatoryBadge: TextView = view.findViewById(R.id.tvUpdateMandatoryBadge)
        val tvReleaseNotes: TextView = view.findViewById(R.id.tvUpdateReleaseNotes)

        val layoutProgress: LinearLayout = view.findViewById(R.id.layoutUpdateProgress)
        val tvStatus: TextView = view.findViewById(R.id.tvUpdateStatus)
        val tvProgressPercent: TextView = view.findViewById(R.id.tvUpdateProgressPercent)
        val tvProgressBytes: TextView = view.findViewById(R.id.tvUpdateProgressBytes)
        val pbDownload: ProgressBar = view.findViewById(R.id.pbUpdateDownload)
        val tvError: TextView = view.findViewById(R.id.tvUpdateError)

        val btnLater: Button = view.findViewById(R.id.btnUpdateLater)
        val btnAction: Button = view.findViewById(R.id.btnUpdateAction)

        tvVersionName.text = "Melody v${metadata.versionName}"
        tvVersionCode.text = "Build ${metadata.versionCode} • Official Release"
        tvReleaseNotes.text = metadata.releaseNotes.ifEmpty { "Bug fixes and performance improvements." }

        if (metadata.mandatory) {
            tvTitle.text = "Critical Update Required"
            tvSubtitle.text = "Please update Melody to continue enjoying uninterrupted music."
            tvMandatoryBadge.visibility = View.VISIBLE
            btnLater.visibility = View.GONE
            ivClose.visibility = View.GONE
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        } else {
            btnLater.visibility = View.VISIBLE
            ivClose.visibility = View.VISIBLE
            dialog.setCancelable(true)
        }

        ivClose.setOnClickListener {
            updateManager.recordDismissal(metadata.versionCode)
            dialog.dismiss()
        }

        btnLater.setOnClickListener {
            updateManager.recordDismissal(metadata.versionCode)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            downloadJob?.cancel()
            onDismissed?.invoke()
        }

        btnAction.setOnClickListener {
            val file = downloadedApkFile
            if (file != null && file.exists()) {
                // Already downloaded and verified
                try {
                    updateManager.startInstallation(activity, file)
                } catch (e: Exception) {
                    tvError.text = "Unable to start installation: ${e.message}"
                    tvError.visibility = View.VISIBLE
                }
                return@setOnClickListener
            }

            // Start download
            btnAction.isEnabled = false
            btnAction.text = "Downloading..."
            btnLater.isEnabled = !metadata.mandatory
            tvError.visibility = View.GONE
            layoutProgress.visibility = View.VISIBLE
            pbDownload.progress = 0
            tvStatus.text = "Connecting to GitHub..."

            downloadJob = scope.launch {
                val result = updateManager.downloadAndVerifyApk(metadata) { bytesRead, totalBytes, percent ->
                    scope.launch(Dispatchers.Main) {
                        if (percent >= 0) {
                            pbDownload.isIndeterminate = false
                            pbDownload.progress = percent
                            tvProgressPercent.text = "$percent%"
                        } else {
                            pbDownload.isIndeterminate = true
                            tvProgressPercent.text = ""
                        }

                        val mbRead = bytesRead / (1024.0 * 1024.0)
                        if (totalBytes > 0) {
                            val mbTotal = totalBytes / (1024.0 * 1024.0)
                            tvProgressBytes.text = String.format(Locale.US, "%.1f MB / %.1f MB", mbRead, mbTotal)
                        } else {
                            tvProgressBytes.text = String.format(Locale.US, "%.1f MB downloaded", mbRead)
                        }
                        tvStatus.text = "Downloading Melody v${metadata.versionName}..."
                    }
                }

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { apkFile ->
                            downloadedApkFile = apkFile
                            tvStatus.text = "Verified: SHA-256 & Signature matched!"
                            tvProgressPercent.text = "100%"
                            pbDownload.progress = 100
                            btnAction.isEnabled = true
                            btnAction.text = "Install Now"
                            btnAction.setOnClickListener {
                                try {
                                    updateManager.startInstallation(activity, apkFile)
                                } catch (e: Exception) {
                                    tvError.text = "Unable to start installation: ${e.message}"
                                    tvError.visibility = View.VISIBLE
                                }
                            }
                            // Prompt installation flow
                            try {
                                updateManager.startInstallation(activity, apkFile)
                            } catch (e: Exception) {
                                tvError.text = "Permission notice: ${e.message}"
                                tvError.visibility = View.VISIBLE
                            }
                        },
                        onFailure = { error ->
                            tvError.text = error.localizedMessage ?: "Download failed"
                            tvError.visibility = View.VISIBLE
                            btnAction.isEnabled = true
                            btnAction.text = "Retry"
                            tvStatus.text = "Download failed"
                        }
                    )
                }
            }
        }

        dialog.show()
    }
}
