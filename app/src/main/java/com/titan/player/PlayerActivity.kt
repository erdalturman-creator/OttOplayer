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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    private var currentStreamUrl: String = ""
    private var currentStreamTitle: String = "OttOplayer"
    private var isLocked = false
    private var controlsVisible = true
    private var resizeModeIndex = 0
    @Volatile private var isDownloading = false

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setControlsVisibility(false) }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        currentStreamUrl = intent.getStringExtra("STREAM_URL") ?: ""
        currentStreamTitle = intent.getStringExtra("STREAM_TITLE") ?: "OttOplayer"

        if (currentStreamUrl.isEmpty()) {
            Toast.makeText(this, "Yayın adresi bulunamadı!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
        }
        root.addView(playerView)

        topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(topOverlay) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left + 16, sys.top + 28, sys.right + 16, 16)
            insets
        }

        val btnBack = createIconButton(android.R.drawable.ic_media_previous, "Geri") {
            finish()
        }
        topOverlay.addView(btnBack)

        val tvTitle = TextView(this).apply {
            text = currentStreamTitle
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(12, 0, 12, 0)
            }
        }
        topOverlay.addView(tvTitle)

        val iconScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        iconRow.addView(createIconButton(android.R.drawable.stat_sys_download, "İndir") {
            resetHideTimer()
            startFullStreamDownload()
        })

        iconRow.addView(createIconButton(android.R.drawable.ic_lock_silent_mode_off, "Ses") {
            resetHideTimer()
            showTrackSelectionDialog(C.TRACK_TYPE_AUDIO)
        })

        iconRow.addView(createIconButton(android.R.drawable.ic_menu_sort_by_size, "Altyazı") {
            resetHideTimer()
            showTrackSelectionDialog(C.TRACK_TYPE_TEXT)
        })

        iconRow.addView(createIconButton(android.R.drawable.ic_menu_crop, "Ekran Oranı") {
            resetHideTimer()
            val modes = listOf(
                AspectRatioFrameLayout.RESIZE_MODE_FIT to "Sığdır",
                AspectRatioFrameLayout.RESIZE_MODE_FILL to "Tam Ekran Doldur",
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Yakınlaştır (Zoom)"
            )
            resizeModeIndex = (resizeModeIndex + 1) % modes.size
            playerView.resizeMode = modes[resizeModeIndex].first
            showGestureToast("Ekran: ${modes[resizeModeIndex].second}")
        })

        iconRow.addView(createIconButton(android.R.drawable.ic_menu_always_landscape_portrait, "Döndür") {
            resetHideTimer()
            val current = resources.configuration.orientation
            requestedOrientation = if (current == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        })

        iconRow.addView(createIconButton(android.R.drawable.ic_lock_idle_lock, "Kilitle") {
            isLocked = true
            setControlsVisibility(false)
            unlockButton.visibility = View.VISIBLE
            showGestureToast("Ekran Kilitlendi")
        })

        iconScroll.addView(iconRow)
        topOverlay.addView(iconScroll)
        root.addView(topOverlay)

        centerOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val btnRew = createCircleControlButton(android.R.drawable.ic_media_rew, 64) {
            resetHideTimer()
            player?.let { it.seekTo((it.currentPosition - 15000).coerceAtLeast(0)) }
        }

        btnPlayPause = createCircleControlButton(android.R.drawable.ic_media_pause, 84) {
            resetHideTimer()
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                } else {
                    it.play()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                }
            }
        }

        val btnFf = createCircleControlButton(android.R.drawable.ic_media_ff, 64) {
            resetHideTimer()
            player?.let { it.seekTo(it.currentPosition + 15000) }
        }

        centerOverlay.addView(btnRew)
        centerOverlay.addView(btnPlayPause)
        centerOverlay.addView(btnFf)
        root.addView(centerOverlay)

        unlockButton = createIconButton(android.R.drawable.ic_lock_lock, "Kilidi Aç") {
            isLocked = false
            unlockButton.visibility = View.GONE
            setControlsVisibility(true)
            showGestureToast("Kilit Açıldı")
        }.apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(130, 130, Gravity.TOP or Gravity.START).apply {
                setMargins(36, 72, 0, 0)
            }
        }
        root.addView(unlockButton)

        gestureIndicator = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#CC111827"))
            setPadding(40, 24, 40, 24)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        root.addView(gestureIndicator)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    unlockButton.visibility = if (unlockButton.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                } else {
                    setControlsVisibility(!controlsVisible)
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked || e1 == null) return false
                if (abs(distanceY) > abs(distanceX)) {
                    val screenWidth = root.width.coerceAtLeast(1)
                    val screenHeight = root.height.coerceAtLeast(1)
                    val deltaPercent = (distanceY / screenHeight) * 1.5f

                    if (e1.x < screenWidth / 2f) {
                        val lp = window.attributes
                        val currentBright = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                        val newBright = (currentBright + deltaPercent).coerceIn(0.05f, 1.0f)
                        lp.screenBrightness = newBright
                        window.attributes = lp
                        showGestureToast("☀️ Parlaklık: %${(newBright * 100).toInt()}")
                    } else {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val step = if (distanceY > 0) 1 else -1
                        if (abs(distanceY) > 12) {
                            val newVol = (curVol + step).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            val pct = ((newVol.toFloat() / maxVol) * 100).toInt()
                            showGestureToast("🔊 Ses: %$pct")
                        }
                    }
                    return true
                }
                return false
            }
        })

        root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        setContentView(root)
        initializePlayer(currentStreamUrl)
        resetHideTimer()
    }

    private fun createIconButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
        val sizePx = (48 * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = desc
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                setMargins(8, 0, 8, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createCircleControlButton(iconRes: Int, sizeDp: Int, onClick: () -> Unit): ImageButton {
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                setMargins(28, 0, 28, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setControlsVisibility(visible: Boolean) {
        controlsVisible = visible
        val vis = if (visible) View.VISIBLE else View.GONE
        topOverlay.visibility = vis
        centerOverlay.visibility = vis
        if (visible) resetHideTimer() else hideHandler.removeCallbacks(hideRunnable)
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 4000)
    }

    private fun showGestureToast(msg: String) {
        gestureIndicator.text = msg
        gestureIndicator.visibility = View.VISIBLE
        gestureIndicator.removeCallbacks(null)
        gestureIndicator.postDelayed({ gestureIndicator.visibility = View.GONE }, 1200)
    }

    private fun startFullStreamDownload() {
        if (currentStreamUrl.isEmpty()) return
        if (isDownloading) {
            Toast.makeText(this, "Zaten bir indirme devam ediyor...", Toast.LENGTH_SHORT).show()
            return
        }

        isDownloading = true
        Toast.makeText(this, "İndirme başlatıldı (Bildirim çubuğundan takip edin)", Toast.LENGTH_LONG).show()

        val channelId = "ottoplayer_downloads"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "OttOplayer İndirmeleri", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }

        val notifId = (System.currentTimeMillis() % 10000).toInt()
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(currentStreamTitle)
            .setContentText("Bağlanıyor...")
            .setOngoing(true)
            .setProgress(100, 0, true)
        nm.notify(notifId, builder.build())

        Thread {
            var outputStream: OutputStream? = null
            try {
                val cleanName = currentStreamTitle
                    .replace(Regex("[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ _-]"), "")
                    .trim()
                    .ifEmpty { "OttOplayer_Video" }

                val req = Request.Builder()
                    .url(currentStreamUrl)
                    .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                    .header("Accept", "*/*")
                    .build()

                val response = downloadClient.newCall(req).execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

                val body = response.body ?: throw Exception("Boş yanıt")
                val totalBytes = body.contentLength()
                val lowerUrl = currentStreamUrl.lowercase()
                val ext = when {
                    lowerUrl.contains(".mkv") -> ".mkv"
                    lowerUrl.contains(".avi") -> ".avi"
                    lowerUrl.contains(".mp4") -> ".mp4"
                    else -> ".ts"
                }
                val fileName = "${cleanName}_${System.currentTimeMillis() % 10000}$ext"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OttOplayer")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("Dosya oluşturulamadı")
                    outputStream = contentResolver.openOutputStream(uri)
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OttOplayer")
                    if (!dir.exists()) dir.mkdirs()
                    outputStream = FileOutputStream(File(dir, fileName))
                }

                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var downloaded: Long = 0
                var read: Int
                var lastUpdate = 0L

                while (input.read(buffer).also { read = it } != -1) {
                    outputStream?.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 1000) {
                        lastUpdate = now
                        val mb = downloaded / (1024 * 1024)
                        if (totalBytes > 0) {
                            val pct = ((downloaded * 100) / totalBytes).toInt()
                            val totalMb = totalBytes / (1024 * 1024)
                            builder.setProgress(100, pct, false)
                                .setContentText("İndiriliyor: $mb MB / $totalMb MB (%$pct)")
                        } else {
                            builder.setProgress(100, 0, true)
                                .setContentText("İndiriliyor: $mb MB kaydedildi...")
                        }
                        nm.notify(notifId, builder.build())
                    }
                }
                outputStream?.flush()
                outputStream?.close()

                val finalMb = downloaded / (1024 * 1024)
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText("Tamamlandı: Download/OttOplayer/$fileName ($finalMb MB)")
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                nm.notify(notifId, builder.build())

                runOnUiThread {
                    Toast.makeText(this, "İndirme bitti ($finalMb MB): Download/OttOplayer/$fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                try { outputStream?.close() } catch (_: Exception) {}
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText("İndirme hatası: ${e.message}")
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                nm.notify(notifId, builder.build())
                runOnUiThread {
                    Toast.makeText(this, "İndirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isDownloading = false
            }
        }.start()
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(url: String) {
        
