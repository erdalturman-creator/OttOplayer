package com.titan.player

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var currentStreamUrl: String = ""
    private var currentStreamTitle: String = "OttOplayer_Video"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setShowSubtitleButton(true)
        }
        root.addView(playerView)

        val topRightControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, 24, 24, 0)
            }
        }

        val btnDownload = Button(this).apply {
            text = "⬇️ İndir"
            setBackgroundColor(Color.parseColor("#CC152036"))
            setTextColor(Color.parseColor("#4ADE80"))
            setPadding(24, 10, 24, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 14 }
            setOnClickListener { startDirectDownload() }
        }

        val btnTracks = Button(this).apply {
            text = "🔊 Ses / 💬 Altyazı"
            setBackgroundColor(Color.parseColor("#CC152036"))
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(24, 10, 24, 10)
            setOnClickListener { showTrackSelectionDialog() }
        }

        topRightControls.addView(btnDownload)
        topRightControls.addView(btnTracks)
        root.addView(topRightControls)
        setContentView(root)

        currentStreamUrl = intent.getStringExtra("STREAM_URL") ?: ""
        currentStreamTitle = intent.getStringExtra("STREAM_TITLE") ?: "OttOplayer_Video"

        if (currentStreamUrl.isEmpty()) {
            Toast.makeText(this, "Yayın adresi bulunamadı!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializePlayer(currentStreamUrl)
    }

    private fun startDirectDownload() {
        if (currentStreamUrl.isEmpty()) return
        try {
            val cleanName = currentStreamTitle
                .replace(Regex("[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ _-]"), "")
                .trim()
                .ifEmpty { "OttOplayer_Video" }

            val lower = currentStreamUrl.lowercase()
            val ext = when {
                lower.contains(".mkv") -> ".mkv"
                lower.contains(".avi") -> ".avi"
                lower.contains(".mp4") -> ".mp4"
                else -> ".ts"
            }
            val fileName = "$cleanName$ext"

            val request = DownloadManager.Request(Uri.parse(currentStreamUrl)).apply {
                setTitle(currentStreamTitle)
                setDescription("OttOplayer ile indiriliyor...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "OttOplayer/$fileName")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "İndirme başladı: Download/OttOplayer/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "İndirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(url: String) {
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player = ExoPlayer.Builder(this, renderersFactory).build().also { exoPlayer ->
            playerView.player = exoPlayer
            val mediaItem = MediaItem.fromUri(url)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "Yayın açılamadı: ${error.message}", Toast.LENGTH_LONG).show()
                }
            })
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun showTrackSelectionDialog() {
        val exo = player ?: return
        val tracks = exo.currentTracks
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add("💬 Altyazıyı Kapat")
        actions.add {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            Toast.makeText(this, "Altyazı kapatıldı", Toast.LENGTH_SHORT).show()
        }

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO || group.type == C.TRACK_TYPE_TEXT) {
                val prefix = if (group.type == C.TRACK_TYPE_AUDIO) "🔊 Ses: " else "💬 Altyazı: "
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        val lang = format.language ?: "Bilinmeyen Dil"
                        val label = format.label ?: "Parça ${i + 1}"
                        val selectedMark = if (group.isTrackSelected(i)) " ✓" else ""
                        options.add("$prefix$label ($lang)$selectedMark")
                        actions.add {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(group.type, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                .build()
                            Toast.makeText(this, "$prefix$label ($lang) seçildi", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Seslendirme ve Altyazı Seçenekleri")
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
