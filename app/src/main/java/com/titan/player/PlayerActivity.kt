package com.titan.player

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topOverlay: LinearLayout
    private lateinit var centerOverlay: LinearLayout
    private lateinit var unlockButton: ImageButton
    private lateinit var gestureIndicator: TextView
    private lateinit var btnPlayPause: ImageButton

    private var currentStreamUrl = ""
    private var currentStreamTitle = "OttOplayer"
    private var isLocked = false
    private var controlsVisible = true
    private var resizeModeIndex = 0
    @Volatile private var isDownloading = false

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setControlsVisibility(false) }
    private val downloadClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentStreamUrl = intent.getStringExtra("STREAM_URL") ?: ""
        currentStreamTitle = intent.getStringExtra("STREAM_TITLE") ?: "OttOplayer"
        if (currentStreamUrl.isEmpty()) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1); useController = false }
        root.addView(playerView)

        topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        }
        ViewCompat.setOnApplyWindowInsetsListener(topOverlay) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left + 16, sys.top + 28, sys.right + 16, 16)
            insets
        }

        topOverlay.addView(createIconButton(android.R.drawable.ic_media_previous) { finish() })
        topOverlay.addView(TextView(this).apply { text = currentStreamTitle; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(12, 0, 12, 0) } })

        val iconScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        iconRow.addView(createIconButton(android.R.drawable.stat_sys_download) { resetHideTimer(); startFullStreamDownload() })
        iconRow.addView(createIconButton(android.R.drawable.ic_lock_silent_mode_off) { resetHideTimer(); showTrackDialog(C.TRACK_TYPE_AUDIO) })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_sort_by_size) { resetHideTimer(); showTrackDialog(C.TRACK_TYPE_TEXT) })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_crop) {
            resetHideTimer()
            val modes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT to "Sığdır", AspectRatioFrameLayout.RESIZE_MODE_FILL to "Doldur", AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom")
            resizeModeIndex = (resizeModeIndex + 1) % modes.size
            playerView.resizeMode = modes[resizeModeIndex].first
            showGestureToast("Ekran: ${modes[resizeModeIndex].second}")
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_always_landscape_portrait) {
            resetHideTimer()
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_lock_idle_lock) { isLocked = true; setControlsVisibility(false); unlockButton.visibility = View.VISIBLE; showGestureToast("Kilitlendi") })
        iconScroll.addView(iconRow)
        topOverlay.addView(iconScroll)
        root.addView(topOverlay)

        centerOverlay = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER) }
        val btnRew = createCircleControlButton(android.R.drawable.ic_media_rew, 64) { resetHideTimer(); player?.let { it.seekTo((it.currentPosition - 15000).coerceAtLeast(0)) } }
        btnPlayPause = createCircleControlButton(android.R.drawable.ic_media_pause, 84) {
            resetHideTimer()
            player?.let { if (it.isPlaying) { it.pause(); btnPlayPause.setImageResource(android.R.drawable.ic_media_play) } else { it.play(); btnPlayPause.setImageResource(android.R.drawable.ic_media_pause) } }
        }
        val btnFf = createCircleControlButton(android.R.drawable.ic_media_ff, 64) { resetHideTimer(); player?.let { it.seekTo(it.currentPosition + 15000) } }
        centerOverlay.addView(btnRew); centerOverlay.addView(btnPlayPause); centerOverlay.addView(btnFf)
        root.addView(centerOverlay)

        unlockButton = createIconButton(android.R.drawable.ic_lock_lock) { isLocked = false; unlockButton.visibility = View.GONE; setControlsVisibility(true); showGestureToast("Kilit Açıldı") }.apply {
            visibility = View.GONE; layoutParams = FrameLayout.LayoutParams(130, 130, Gravity.TOP or Gravity.START).apply { setMargins(36, 72, 0, 0) }
        }
        root.addView(unlockButton)

        gestureIndicator = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setBackgroundColor(Color.parseColor("#CC111827")); setPadding(40, 24, 40, 24); visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }
        root.addView(gestureIndicator)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) unlockButton.visibility = if (unlockButton.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                else setControlsVisibility(!controlsVisible)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (isLocked || e1 == null || abs(dy) <= abs(dx)) return false
                val w = root.width.coerceAtLeast(1); val h = root.height.coerceAtLeast(1)
                if (e1.x < w / 2f) {
                    val lp = window.attributes
                    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                    val nb = (cur + (dy / h) * 1.5f).coerceIn(0.05f, 1.0f)
                    lp.screenBrightness = nb; window.attributes = lp
                    showGestureToast("☀️ Parlaklık: %${(nb * 100).toInt()}")
                } else if (abs(dy) > 12) {
                    val maxV = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val curV = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val nv = (curV + if (dy > 0) 1 else -1).coerceIn(0, maxV)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                    showGestureToast("🔊 Ses: %${((nv.toFloat() / maxV) * 100).toInt()}")
                }
                return true
            }
        })
        root.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev); true }

        setContentView(root)
        initializePlayer(currentStreamUrl)
        resetHideTimer()
    }

    private fun createIconButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        val sz = (48 * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply { setImageResource(iconRes); setColorFilter(Color.WHITE); setBackgroundColor(Color.parseColor("#44FFFFFF")); layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(8, 0, 8, 0) }; setOnClickListener { onClick() } }
    }

    private fun createCircleControlButton(iconRes: Int, dp: Int, onClick: () -> Unit): ImageButton {
        val sz = (dp * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply { setImageResource(iconRes); setColorFilter(Color.WHITE); setBackgroundColor(Color.parseColor("#88000000")); layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(28, 0, 28, 0) }; setOnClickListener { onClick() } }
    }

    private fun setControlsVisibility(visible: Boolean) {
        controlsVisible = visible
        val vis = if (visible) View.VISIBLE else View.GONE
        topOverlay.visibility = vis; centerOverlay.visibility = vis
        if (visible) resetHideTimer() else hideHandler.removeCallbacks(hideRunnable)
    }

    private fun resetHideTimer() { hideHandler.removeCallbacks(hideRunnable); hideHandler.postDelayed(hideRunnable, 4000) }

    private fun showGestureToast(msg: String) {
        gestureIndicator.text = msg; gestureIndicator.visibility = View.VISIBLE
        gestureIndicator.removeCallbacks(null); gestureIndicator.postDelayed({ gestureIndicator.visibility = View.GONE }, 1200)
    }

    private fun startFullStreamDownload() {
        if (currentStreamUrl.isEmpty() || isDownloading) return
        isDownloading = true
        Toast.makeText(this, "İndirme başlatıldı", Toast.LENGTH_LONG).show()
        val chId = "ottoplayer_dl"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(chId, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
        val notifId = (System.currentTimeMillis() % 10000).toInt()
        val builder = NotificationCompat.Builder(this, chId).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(currentStreamTitle).setContentText("Bağlanıyor...").setOngoing(true)
        nm.notify(notifId, builder.build())

        Thread {
            var out: OutputStream? = null
            try {
                val res = downloadClient.newCall(Request.Builder().url(currentStreamUrl).header("User-Agent", "VLC/3.0.18").build()).execute()
                if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
                val body = res.body ?: throw Exception("Boş")
                val total = body.contentLength()
                val ext = when { currentStreamUrl.lowercase().contains(".mkv") -> ".mkv"; currentStreamUrl.lowercase().contains(".mp4") -> ".mp4"; else -> ".ts" }
                val fName = "${currentStreamTitle.replace(Regex("[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ _-]"), "").trim().ifEmpty { "Video" }}_${notifId}$ext"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, fName); put(MediaStore.Downloads.MIME_TYPE, "video/mp4"); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OttOplayer") }
                    out = contentResolver.openOutputStream(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!)
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OttOplayer").apply { mkdirs() }
                    out = FileOutputStream(File(dir, fName))
                }

                val inp = body.byteStream(); val buf = ByteArray(64 * 1024); var dl = 0L; var r: Int; var last = 0L
                while (inp.read(buf).also { r = it } != -1) {
                    out?.write(buf, 0, r); dl += r
                    if (System.currentTimeMillis() - last > 1000) {
                        last = System.currentTimeMillis()
                        val mb = dl / (1024 * 1024)
                        builder.setContentText(if (total > 0) "İndiriliyor: $mb MB / ${total / (1024 * 1024)} MB (%${(dl * 100 / total).toInt()})" else "İndiriliyor: $mb MB...")
                        nm.notify(notifId, builder.build())
                    }
                }
                out?.flush(); out?.close()
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText("Bitti: Download/OttOplayer/$fName (${dl / (1024 * 1024)} MB)").setOngoing(false)
                nm.notify(notifId, builder.build())
                runOnUiThread { Toast.makeText(this, "İndirme tamamlandı: $fName", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                try { out?.close() } catch (_: Exception) {}
                builder.setSmallIcon(android.R.drawable.stat_notify_error).setContentText("Hata: ${e.message}").setOngoing(false); nm.notify(notifId, builder.build())
            } finally { isDownloading = false }
        }.start()
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(url: String) {
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { Toast.makeText(this@PlayerActivity, "Hata: ${error.message}", Toast.LENGTH_LONG).show() } })
            exo.prepare(); exo.playWhenReady = true
        }
    }

    private fun showTrackDialog(trackType: Int) {
        val exo = player ?: return
        val opts = mutableListOf<String>(); val acts = mutableListOf<() -> Unit>()
        if (trackType == C.TRACK_TYPE_TEXT) {
            opts.add("Altyazıyı Kapat")
            acts.add { exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        }
        for (g in exo.currentTracks.groups) if (g.type == trackType) {
            for (i in 0 until g.length) if (g.isTrackSupported(i)) {
                val f = g.getTrackFormat(i)
                opts.add("${f.label ?: "Parça ${i + 1}"} (${f.language ?: "Dil"})${if (g.isTrackSelected(i)) " ✓" else ""}")
                acts.add { exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(trackType, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build() }
            }
        }
        if (opts.isEmpty()) { Toast.makeText(this, "Alternatif parça yok", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle(if (trackType == C.TRACK_TYPE_AUDIO) "Ses Seçimi" else "Altyazı Seçimi").setItems(opts.toTypedArray()) { _, w -> acts[w]() }.setNegativeButton("Kapat", null).show()
    }

    override fun onStop() { super.onStop(); hideHandler.removeCallbacks(hideRunnable); player?.release(); player = null }
}
// SON
