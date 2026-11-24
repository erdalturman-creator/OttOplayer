package com.titan.player

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import java.net.URL
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
    private var savedPosition: Long = 0L
    private var savedPlayWhenReady: Boolean = true
    private var pendingDownloadAfterPermission = false

    @Volatile
    private var isDownloading = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setControlsVisibility(false) }
    private val hideGestureRunnable = Runnable { gestureIndicator.visibility = View.GONE }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            if (pendingDownloadAfterPermission) {
                pendingDownloadAfterPermission = false
                performDownload()
            }
        }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        currentStreamUrl = intent.getStringExtra("STREAM_URL")?.trim().orEmpty()
        currentStreamTitle = intent.getStringExtra("STREAM_TITLE")?.trim().orEmpty().ifEmpty { "OttOplayer" }

        if (currentStreamUrl.isEmpty()) {
            finish()
            return
        }

        if (savedInstanceState != null) {
            savedPosition = savedInstanceState.getLong("SAVED_POSITION", 0L)
            savedPlayWhenReady = savedInstanceState.getBoolean("SAVED_PLAY_WHEN_READY", true)
            resizeModeIndex = savedInstanceState.getInt("RESIZE_MODE_INDEX", 0)
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            useController = false
        }
        val resizeModes = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Sığdır",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Doldur",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom"
        )
        playerView.resizeMode = resizeModes[resizeModeIndex.coerceIn(0, resizeModes.lastIndex)].first
        root.addView(playerView)

        topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        }
        ViewCompat.setOnApplyWindowInsetsListener(topOverlay) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left + 16, sys.top + 24, sys.right + 16, 16)
            insets
        }

        topOverlay.addView(createIconButton(android.R.drawable.ic_media_previous) { finish() })
        topOverlay.addView(
            TextView(this).apply {
                text = currentStreamTitle
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(12, 0, 12, 0) }
            }
        )

        val iconScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        iconRow.addView(createIconButton(android.R.drawable.stat_sys_download) {
            resetHideTimer()
            requestDownloadWithPermissionCheck()
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_lock_silent_mode_off) {
            resetHideTimer()
            showTrackDialog(C.TRACK_TYPE_AUDIO)
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_sort_by_size) {
            resetHideTimer()
            showTrackDialog(C.TRACK_TYPE_TEXT)
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_crop) {
            resetHideTimer()
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeModeIndex].first
            showGestureToast("Ekran: ${resizeModes[resizeModeIndex].second}")
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_always_landscape_portrait) {
            resetHideTimer()
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_lock_idle_lock) {
            isLocked = true
            setControlsVisibility(false)
            unlockButton.visibility = View.VISIBLE
            showGestureToast("Kilitlendi")
        })

        iconScroll.addView(iconRow)
        topOverlay.addView(iconScroll)
        root.addView(topOverlay)

        centerOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }
        val btnRew = createCircleControlButton(android.R.drawable.ic_media_rew, 64) {
            resetHideTimer()
            seekBy(-15_000L)
        }
        btnPlayPause = createCircleControlButton(android.R.drawable.ic_media_pause, 84) {
            resetHideTimer()
            player?.let { exo ->
                if (exo.isPlaying) {
                    exo.pause()
                } else {
                    exo.play()
                }
            }
        }
        val btnFf = createCircleControlButton(android.R.drawable.ic_media_ff, 64) {
            resetHideTimer()
            seekBy(15_000L)
        }
        centerOverlay.addView(btnRew)
        centerOverlay.addView(btnPlayPause)
        centerOverlay.addView(btnFf)
        root.addView(centerOverlay)

        unlockButton = createIconButton(android.R.drawable.ic_lock_lock) {
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
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
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

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return false
                val w = root.width.coerceAtLeast(1)
                if (e.x < w / 2f) {
                    seekBy(-15_000L)
                    showGestureToast("⏪ -15 sn")
                } else {
                    seekBy(15_000L)
                    showGestureToast("⏩ +15 sn")
                }
                resetHideTimer()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (isLocked || e1 == null || abs(dy) <= abs(dx)) return false
                val w = root.width.coerceAtLeast(1)
                val h = root.height.coerceAtLeast(1)
                if (e1.x < w / 2f) {
                    val lp = window.attributes
                    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                    val nb = (cur + (dy / h) * 1.5f).coerceIn(0.05f, 1.0f)
                    lp.screenBrightness = nb
                    window.attributes = lp
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

        root.setOnTouchListener { v, ev ->
            gestureDetector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        setContentView(root)
        resetHideTimer()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer(currentStreamUrl, savedPosition, savedPlayWhenReady)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(hideRunnable)
        player?.let { exo ->
            if (exo.duration != C.TIME_UNSET && exo.currentPosition < exo.duration) {
                savedPosition = exo.currentPosition.coerceAtLeast(0L)
            }
            savedPlayWhenReady = exo.playWhenReady
            exo.release()
        }
        player = null
        playerView.player = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: savedPosition
        val playing = player?.playWhenReady ?: savedPlayWhenReady
        outState.putLong("SAVED_POSITION", pos)
        outState.putBoolean("SAVED_PLAY_WHEN_READY", playing)
        outState.putInt("RESIZE_MODE_INDEX", resizeModeIndex)
    }

    private fun seekBy(deltaMs: Long) {
        player?.let { exo ->
            val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
            val clamped = if (exo.duration != C.TIME_UNSET) target.coerceAtMost(exo.duration) else target
            exo.seekTo(clamped)
        }
    }

    private fun createIconButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        val sz = (48 * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(8, 0, 8, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun createCircleControlButton(iconRes: Int, dp: Int, onClick: () -> Unit): ImageButton {
        val sz = (dp * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(28, 0, 28, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun setControlsVisibility(visible: Boolean) {
        controlsVisible = visible
        val vis = if (visible) View.VISIBLE else View.GONE
        topOverlay.visibility = vis
        centerOverlay.visibility = vis
        if (visible) resetHideTimer() else mainHandler.removeCallbacks(hideRunnable)
    }

    private fun resetHideTimer() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, 4000)
    }

    private fun showGestureToast(msg: String) {
        gestureIndicator.text = msg
        gestureIndicator.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideGestureRunnable)
        mainHandler.postDelayed(hideGestureRunnable, 1200)
    }

    private fun requestDownloadWithPermissionCheck() {
        if (currentStreamUrl.isEmpty()) return
        if (isDownloading) {
            Toast.makeText(this, "Zaten devam eden bir indirme var", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownloadAfterPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        performDownload()
    }

    private fun performDownload() {
        if (currentStreamUrl.isEmpty() || isDownloading) return
        isDownloading = true
        Toast.makeText(this, "İndirme başlatıldı", Toast.LENGTH_LONG).show()

        val chId = "ottoplayer_dl"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "İndirmeler", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notifId = ((System.currentTimeMillis() % 900_000) + 1000).toInt()
        val builder = NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(currentStreamTitle)
            .setContentText("Bağlanıyor...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        nm.notify(notifId, builder.build())

        Thread {
            var out: OutputStream? = null
            var mediaUri: Uri? = null
            var fallbackFile: File? = null
            try {
                val lowerUrl = currentStreamUrl.lowercase()
                val isHls = lowerUrl.contains(".m3u8")
                val ext = when {
                    isHls -> ".ts"
                    lowerUrl.contains(".mkv") -> ".mkv"
                    lowerUrl.contains(".mp4") -> ".mp4"
                    else -> ".ts"
                }
                val mimeType = when (ext) {
                    ".mp4" -> "video/mp4"
                    ".mkv" -> "video/x-matroska"
                    else -> "video/mp2t"
                }
                val safeTitle = currentStreamTitle
                    .replace(Regex("[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ _-]"), "")
                    .trim()
                    .ifEmpty { "Video" }
                val fName = "${safeTitle}_$notifId$ext"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OttOplayer")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    mediaUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        ?: throw Exception("MediaStore kaydı oluşturulamadı")
                    out = contentResolver.openOutputStream(mediaUri)
                        ?: throw Exception("Çıktı akışı açılamadı")
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OttOplayer").apply { mkdirs() }
                    fallbackFile = File(dir, fName)
                    out = FileOutputStream(fallbackFile)
                }

                var totalBytesWritten = 0L
                var lastNotifTime = 0L

                if (isHls) {
                    val segmentUrls = resolveHlsSegments(currentStreamUrl)
                    if (segmentUrls.isEmpty()) throw Exception("HLS segmenti bulunamadı")

                    segmentUrls.forEachIndexed { index, segUrl ->
                        val req = Request.Builder().url(segUrl).header("User-Agent", "VLC/3.0.18").build()
                        downloadClient.newCall(req).execute().use { segRes ->
                            if (!segRes.isSuccessful) throw Exception("Segment HTTP ${segRes.code}")
                            val body = segRes.body ?: throw Exception("Boş segment")
                            body.byteStream().use { inp ->
                                val buf = ByteArray(64 * 1024)
                                var r: Int
                                while (inp.read(buf).also { r = it } != -1) {
                                    out?.write(buf, 0, r)
                                    totalBytesWritten += r
                                }
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastNotifTime > 1000 || index == segmentUrls.lastIndex) {
                            lastNotifTime = now
                            val mb = totalBytesWritten / (1024 * 1024)
                            builder.setContentText("HLS İndiriliyor (${index + 1}/${segmentUrls.size}) - $mb MB")
                            nm.notify(notifId, builder.build())
                        }
                    }
                } else {
                    val req = Request.Builder().url(currentStreamUrl).header("User-Agent", "VLC/3.0.18").build()
                    downloadClient.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
                        val body = res.body ?: throw Exception("Boş yanıt")
                        val total = body.contentLength()
                        body.byteStream().use { inp ->
                            val buf = ByteArray(64 * 1024)
                            var r: Int
                            while (inp.read(buf).also { r = it } != -1) {
                                out?.write(buf, 0, r)
                                totalBytesWritten += r
                                val now = System.currentTimeMillis()
                                if (now - lastNotifTime > 1000) {
                                    lastNotifTime = now
                                    val mb = totalBytesWritten / (1024 * 1024)
                                    val text = if (total > 0) {
                                        "İndiriliyor: $mb MB / ${total / (1024 * 1024)} MB (%${(totalBytesWritten * 100 / total).toInt()})"
                                    } else {
                                        "İndiriliyor: $mb MB..."
                                    }
                                    builder.setContentText(text)
                                    nm.notify(notifId, builder.build())
                                }
                            }
                        }
                    }
                }

                out?.flush()
                out?.close()
                out = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                    val doneValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    contentResolver.update(mediaUri, doneValues, null, null)
                }

                val finalMb = totalBytesWritten / (1024 * 1024)
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText("Bitti: Download/OttOplayer/$fName ($finalMb MB)")
                    .setOngoing(false)
                nm.notify(notifId, builder.build())

                mainHandler.post {
                    Toast.makeText(this, "İndirme tamamlandı: $fName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                try { out?.close() } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                    try { contentResolver.delete(mediaUri, null, null) } catch (_: Exception) {}
                } else {
                    fallbackFile?.delete()
                }
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText("Hata: ${e.message ?: "Bilinmeyen hata"}")
                    .setOngoing(false)
                nm.notify(notifId, builder.build())
                mainHandler.post {
                    Toast.makeText(this, "İndirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isDownloading = false
            }
        }.start()
    }

    private fun resolveHlsSegments(playlistUrl: String): List<String> {
        val req = Request.Builder().url(playlistUrl).header("User-Agent", "VLC/3.0.18").build()
        val content = downloadClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw Exception("Manifest HTTP ${res.code}")
            res.body?.string() ?: throw Exception("Boş manifest")
        }

        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val base = URL(playlistUrl)

        val variantUrls = mutableListOf<String>()
        val segmentUrls = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("#")) continue
            val resolved: String = URL(base, line).toString()
            if (resolved.lowercase().contains(".m3u8")) {
                variantUrls.add(resolved)
            } else {
                segmentUrls.add(resolved)
            }
        }

        if (segmentUrls.isNotEmpty()) return segmentUrls
        if (variantUrls.isNotEmpty()) return resolveHlsSegments(variantUrls.first())
        return emptyList()
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(url: String, startPositionMs: Long, playWhenReady: Boolean) {
        player?.release()
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player = ExoPlayer.Builder(this, renderersFactory).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    btnPlayPause.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Hata: ${error.localizedMessage ?: error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
            exo.playWhenReady = playWhenReady
            exo.prepare()
            if (startPositionMs > 0L) {
                exo.seekTo(startPositionMs)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun showTrackDialog(trackType: Int) {
        val exo = player ?: return
        val opts = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()

        if (trackType == C.TRACK_TYPE_TEXT) {
            opts.add("Altyazıyı Kapat")
            acts.add {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                showGestureToast("Altyazı kapatıldı")
            }
        }

        for (g in exo.currentTracks.groups) {
            if (g.type == trackType) {
                for (i in 0 until g.length) {
                    if (g.isTrackSupported(i)) {
                        val f = g.getTrackFormat(i)
                        val label = f.label ?: "Parça ${i + 1}"
                        val lang = f.language ?: "Dil"
                        val selectedMark = if (g.isTrackSelected(i)) " ✓" else ""
                        opts.add("$label ($lang)$selectedMark")
                        acts.add {
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(trackType, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                .build()
                            showGestureToast("$label seçildi")
                        }
                    }
                }
            }
        }

        if (opts.isEmpty()) {
            Toast.makeText(this, "Alternatif parça yok", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(if (trackType == C.TRACK_TYPE_AUDIO) "Ses Seçimi" else "Altyazı Seçimi")
            .setItems(opts.toTypedArray()) { _, w -> acts[w]() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
// SON
