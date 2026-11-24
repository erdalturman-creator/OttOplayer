package com.titan.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class MediaHubSection {
    LIVE_TV,
    MOVIES,
    SERIES
}

data class MediaCatalogItem(
    val id: String,
    val number: Int,
    val title: String,
    val streamUrl: String,
    val trailerUrl: String = "",
    val section: MediaHubSection = MediaHubSection.LIVE_TV,
    val category: String = "GENEL",
    val year: String = "2024",
    val genre: String = "Canlı / Genel",
    val durationText: String = "Canlı",
    val rating: String = "8.5",
    val plot: String = "",
    val cast: List<String> = emptyList(),
    val addedOrder: Int = 0
)

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var topOverlay: LinearLayout
    private lateinit var centerOverlay: LinearLayout
    private lateinit var unlockButton: ImageButton
    private lateinit var gestureIndicator: TextView
    private lateinit var btnPlayPause: ImageButton

    private val allItems = mutableListOf<MediaCatalogItem>()
    private val activeItems = mutableListOf<MediaCatalogItem>()
    private val favoriteUrls = mutableSetOf<String>()
    private val accountProfiles = mutableListOf<String>()
    private var activeAccount = "Varsayılan Hesap"

    private var currentChannelIndex = 0
    private var currentStreamUrl = ""
    private var currentStreamTitle = "by erdal trmn tv player"
    private var currentItemMeta: MediaCatalogItem? = null

    private var isLocked = false
    private var controlsVisible = true
    private var resizeModeIndex = 0
    private var savedPosition: Long = 0L
    private var savedPlayWhenReady: Boolean = true
    private var pendingDownloadAfterPermission = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setControlsVisibility(false) }
    private val hideGestureRunnable = Runnable { gestureIndicator.visibility = View.GONE }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            if (pendingDownloadAfterPermission) {
                pendingDownloadAfterPermission = false
                startBackgroundDownloadService()
            }
        }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialUrl = intent.getStringExtra("STREAM_URL")?.trim().orEmpty()
        val initialTitle = intent.getStringExtra("STREAM_TITLE")?.trim().orEmpty().ifEmpty { "by erdal trmn tv player" }

        loadAccountsAndFavoritesAndCatalog(initialUrl, initialTitle)

        if (activeItems.isEmpty() && initialUrl.isEmpty()) {
            finish()
            return
        }

        val currentItem = activeItems.getOrNull(currentChannelIndex.coerceIn(0, (activeItems.size - 1).coerceAtLeast(0)))
        if (currentItem != null) {
            currentItemMeta = currentItem
            currentStreamUrl = currentItem.streamUrl
            currentStreamTitle = currentItem.title
        } else {
            currentStreamUrl = initialUrl
            currentStreamTitle = initialTitle
        }

        if (savedInstanceState != null) {
            savedPosition = savedInstanceState.getLong("SAVED_POSITION", 0L)
            savedPlayWhenReady = savedInstanceState.getBoolean("SAVED_PLAY_WHEN_READY", true)
            resizeModeIndex = savedInstanceState.getInt("RESIZE_MODE_INDEX", 0)
            currentChannelIndex = savedInstanceState.getInt("CURRENT_CHANNEL_INDEX", currentChannelIndex)
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
            setBackgroundColor(Color.parseColor("#B3000000"))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        }
        ViewCompat.setOnApplyWindowInsetsListener(topOverlay) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left + 12, sys.top + 18, sys.right + 12, 12)
            insets
        }

        topOverlay.addView(createIconButton(android.R.drawable.ic_media_previous) { finish() })

        titleView = TextView(this).apply {
            text = formatHeaderTitle()
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 0, 8, 0) }
        }
        topOverlay.addView(titleView)

        val iconScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        iconRow.addView(createIconButton(android.R.drawable.ic_menu_compass) {
            resetHideTimer()
            showCategoryHubDialog()
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_info_details) {
            resetHideTimer()
            showCurrentItemDetailDialog()
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_media_rew) {
            resetHideTimer()
            switchChannel(-1)
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_media_ff) {
            resetHideTimer()
            switchChannel(1)
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_agenda) {
            resetHideTimer()
            showChannelManagerDialog()
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_share) {
            resetHideTimer()
            showPlatformLoginDialog()
        })
        iconRow.addView(createIconButton(android.R.drawable.stat_sys_download) {
            resetHideTimer()
            requestDownloadWithPermissionCheck()
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_lock_silent_mode_off) {
            resetHideTimer()
            showVideoAndAudioSelectorDialog(initialTabAudio = true)
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_sort_by_size) {
            resetHideTimer()
            showSubtitleTrackDialog()
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_crop) {
            resetHideTimer()
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeModeIndex].first
            showGestureToast("Ekran: ${resizeModes[resizeModeIndex].second}")
        })
        iconRow.addView(createIconButton(android.R.drawable.ic_menu_preferences) {
            resetHideTimer()
            showSettingsMenuDialog()
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
            showGestureToast("⏪ -15 sn")
        }
        btnPlayPause = createCircleControlButton(android.R.drawable.ic_media_pause, 84) {
            resetHideTimer()
            player?.let { exo ->
                if (exo.isPlaying) exo.pause() else exo.play()
            }
        }
        val btnFf = createCircleControlButton(android.R.drawable.ic_media_ff, 64) {
            resetHideTimer()
            seekBy(15_000L)
            showGestureToast("⏩ +15 sn")
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
        updateHeaderTitle()
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
        outState.putInt("CURRENT_CHANNEL_INDEX", currentChannelIndex)
    }

    private fun formatHeaderTitle(): String {
        val current = activeItems.getOrNull(currentChannelIndex)
        val numPrefix = current?.let { "${it.number} · " } ?: ""
        return "$numPrefix$currentStreamTitle"
    }

    private fun updateHeaderTitle() {
        titleView.text = formatHeaderTitle()
    }

    private fun loadAccountsAndFavoritesAndCatalog(initialUrl: String, initialTitle: String) {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        val savedAccounts = prefs.getStringSet("account_profiles", setOf("Varsayılan Hesap")) ?: setOf("Varsayılan Hesap")
        accountProfiles.clear()
        accountProfiles.addAll(savedAccounts.filter { it.isNotBlank() }.ifEmpty { listOf("Varsayılan Hesap") })
        activeAccount = prefs.getString("active_account", accountProfiles.first()).orEmpty().ifBlank { accountProfiles.first() }

        val deletedSet = prefs.getStringSet("deleted_channel_urls", emptySet())?.toSet() ?: emptySet()
        val deletedIds = prefs.getStringSet("deleted_item_ids", emptySet())?.toSet() ?: emptySet()
        val deletedCategories = prefs.getStringSet("deleted_category_keys", emptySet())?.toSet() ?: emptySet()
        val extraSet = prefs.getStringSet("custom_channel_entries", emptySet())?.toMutableSet() ?: mutableSetOf()
        favoriteUrls.clear()
        favoriteUrls.addAll(prefs.getStringSet("favorite_urls", emptySet()) ?: emptySet())

        val rawList = mutableListOf<MediaCatalogItem>()

        val possibleTitleKeys = listOf("CHANNEL_TITLES", "STREAM_TITLES", "TITLES", "NAMES")
        val possibleUrlKeys = listOf("CHANNEL_URLS", "STREAM_URLS", "URLS", "LINKS")
        var importedFromExtras = false

        for (tKey in possibleTitleKeys) {
            for (uKey in possibleUrlKeys) {
                val tList = intent.getStringArrayListExtra(tKey)
                val uList = intent.getStringArrayListExtra(uKey)
                if (tList != null && uList != null && tList.size == uList.size && uList.isNotEmpty()) {
                    for (i in uList.indices) {
                        val u = uList[i].trim()
                        val t = tList[i].trim().ifEmpty { "Kanal ${i + 1}" }
                        if (u.isNotEmpty() && rawList.none { it.streamUrl == u }) {
                            rawList.add(
                                MediaCatalogItem(
                                    id = "intent_$i",
                                    number = 400 + i,
                                    title = t,
                                    streamUrl = u,
                                    section = MediaHubSection.LIVE_TV,
                                    category = "CANLI TV",
                                    addedOrder = i
                                )
                            )
                        }
                    }
                    importedFromExtras = true
                    break
                }
            }
            if (importedFromExtras) break
        }

        if (initialUrl.isNotEmpty()) {
            val entry = "$initialTitle|||$initialUrl"
            if (!extraSet.contains(entry) && initialUrl !in deletedSet) {
                extraSet.add(entry)
                prefs.edit().putStringSet("custom_channel_entries", extraSet).apply()
            }
            if (rawList.none { it.streamUrl == initialUrl }) {
                rawList.add(
                    0,
                    MediaCatalogItem(
                        id = "initial_0",
                        number = 430,
                        title = initialTitle,
                        streamUrl = initialUrl,
                        section = MediaHubSection.LIVE_TV,
                        category = "CANLI TV",
                        addedOrder = 0
                    )
                )
            }
        }

        var extraCounter = rawList.size + 1
        for (item in extraSet) {
            val parts = item.split("|||", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank() && rawList.none { it.streamUrl == parts[1] }) {
                rawList.add(
                    MediaCatalogItem(
                        id = "saved_$extraCounter",
                        number = 430 + extraCounter,
                        title = parts[0].ifBlank { "Kanal $extraCounter" },
                        streamUrl = parts[1],
                        section = MediaHubSection.LIVE_TV,
                        category = "CANLI TV",
                        addedOrder = extraCounter
                    )
                )
                extraCounter++
            }
        }

        val fallbackUrl = initialUrl.ifEmpty { rawList.firstOrNull()?.streamUrl.orEmpty() }
        for (sample in sampleCatalogItems(fallbackUrl)) {
            if (rawList.none { it.id == sample.id }) {
                rawList.add(sample)
            }
        }

        allItems.clear()
        allItems.addAll(rawList)

        activeItems.clear()
        activeItems.addAll(
            rawList.filter { item ->
                val catKey = "${item.section.name}::${item.category.uppercase(Locale.getDefault())}"
                val urlBlocked = item.streamUrl != fallbackUrl && item.streamUrl in deletedSet
                item.id !in deletedIds && !urlBlocked && catKey !in deletedCategories
            }
        )

        val matchIdx = activeItems.indexOfFirst {
            (initialUrl.isNotEmpty() && it.streamUrl == initialUrl && it.title == initialTitle) ||
                (initialUrl.isNotEmpty() && it.streamUrl == initialUrl)
        }
        currentChannelIndex = if (matchIdx >= 0) matchIdx else 0
    }

    private fun sampleCatalogItems(fallbackUrl: String): List<MediaCatalogItem> = listOf(
        MediaCatalogItem(
            id = "ch_429",
            number = 429,
            title = "TR: BEIN SPORTS SD KOTA DOSTU",
            streamUrl = fallbackUrl,
            section = MediaHubSection.LIVE_TV,
            category = "TR BEIN SPORT",
            genre = "Canlı Spor",
            durationText = "Canlı",
            rating = "9.0",
            plot = "Canlı spor yayını — SD düşük veri modu.",
            addedOrder = 101
        ),
        MediaCatalogItem(
            id = "ch_430",
            number = 430,
            title = "TR: BEIN SPORTS 1 HD",
            streamUrl = fallbackUrl,
            section = MediaHubSection.LIVE_TV,
            category = "TR BEIN SPORT",
            genre = "Canlı Spor",
            durationText = "Canlı",
            rating = "9.5",
            plot = "Canlı HD spor karşılaşmaları ve stüdyo yayınları.",
            addedOrder = 102
        ),
        MediaCatalogItem(
            id = "ch_431",
            number = 431,
            title = "TR: BEIN SPORTS 1 HEVC",
            streamUrl = fallbackUrl,
            section = MediaHubSection.LIVE_TV,
            category = "TR BEIN SPORT",
            genre = "Canlı Spor",
            durationText = "Canlı",
            rating = "9.4",
            plot = "Yüksek verimli HEVC canlı spor akışı.",
            addedOrder = 103
        ),
        MediaCatalogItem(
            id = "ch_doc_1",
            number = 201,
            title = "TR: NATIONAL GEOGRAPHIC HD",
            streamUrl = fallbackUrl,
            section = MediaHubSection.LIVE_TV,
            category = "TR BELGESEL",
            genre = "Belgesel",
            durationText = "Canlı",
            rating = "8.8",
            plot = "Doğa, bilim ve keşif belgeselleri.",
            addedOrder = 104
        ),
        MediaCatalogItem(
            id = "ch_kid_1",
            number = 301,
            title = "TR: MINIKA COCUK",
            streamUrl = fallbackUrl,
            section = MediaHubSection.LIVE_TV,
            category = "TR COCUK",
            genre = "Çocuk",
            durationText = "Canlı",
            rating = "8.6",
            plot = "Çocuk ve aile yayınları.",
            addedOrder = 105
        ),
        MediaCatalogItem(
            id = "ch_tabii_1",
            number = 450,
            title = "TR: TABII SPOR 1",
            streamUrl = fallbackUrl,
            section = MediaHubSection.LIVE_TV,
            category = "TR TABII SPOR",
            genre = "Canlı Spor",
            durationText = "Canlı",
            rating = "8.9",
            plot = "Canlı lig ve turnuva yayınları.",
            addedOrder = 106
        ),
        MediaCatalogItem(
            id = "ch_ssport_1",
            number = 460,
            title = "TR: S SPORT HD",
            streamUrl = fallbackUrl,
            section = MediaHubSection.LIVE_TV,
            category = "TR S SPORT",
            genre = "Canlı Spor",
            durationText = "Canlı",
            rating = "8.7",
            plot = "Basketbol, motor sporları ve canlı yayınlar.",
            addedOrder = 107
        ),
        MediaCatalogItem(
            id = "mov_975",
            number = 1001,
            title = "9,75",
            streamUrl = fallbackUrl,
            trailerUrl = fallbackUrl,
            section = MediaHubSection.MOVIES,
            category = "TR IMDB TOP 250",
            year = "2020-10-22",
            genre = "Dram",
            durationText = "01h 57m",
            rating = "2.7",
            plot = "Ahmet, 1990'ların Güneydoğu Türkiye'de askerlik görevi sırasında, hayatının geri kalanında aklına takılmayacak bir şey yaşar. İstanbul'da Gezi Parkı eylemlerinin kaotik günlerinin başladığı 2013 yılında Ahmet, beyin tümörü taşıdığını öğrenir.",
            cast = listOf("Nejat İşler", "Funda Eryiğit", "Yusuf Mirza Zarg", "İsmail Mirhat Zarg", "Menderes Samancılar"),
            addedOrder = 108
        ),
        MediaCatalogItem(
            id = "mov_nf_1",
            number = 1002,
            title = "GÖLGELER İÇİNDE",
            streamUrl = fallbackUrl,
            trailerUrl = fallbackUrl,
            section = MediaHubSection.MOVIES,
            category = "TR NETFLIX",
            year = "2024",
            genre = "Gerilim",
            durationText = "02h 04m",
            rating = "7.8",
            plot = "Karanlık sırların peşindeki soruşturma beklenmedik bir noktaya taşınır.",
            cast = listOf("Başrol Oyuncu 1", "Başrol Oyuncu 2"),
            addedOrder = 109
        ),
        MediaCatalogItem(
            id = "mov_amz_1",
            number = 1003,
            title = "MAVZER",
            streamUrl = fallbackUrl,
            trailerUrl = fallbackUrl,
            section = MediaHubSection.MOVIES,
            category = "TR AMAZON",
            year = "2020",
            genre = "Dram",
            durationText = "01h 32m",
            rating = "7.4",
            plot = "Yüksek rakımlı dağ köyünde geçen çarpıcı bir dram hikayesi.",
            cast = listOf("Serhat Kılıç", "Ozan Acar"),
            addedOrder = 110
        ),
        MediaCatalogItem(
            id = "ser_1",
            number = 2001,
            title = "BAŞLANGIÇLAR SEZON 1",
            streamUrl = fallbackUrl,
            trailerUrl = fallbackUrl,
            section = MediaHubSection.SERIES,
            category = "TR BOXSET",
            year = "2025",
            genre = "Dram / Gizem",
            durationText = "8 Bölüm",
            rating = "8.4",
            plot = "Yeni bir başlangıcın eşiğindeki karakterlerin kesişen hikayeleri.",
            cast = listOf("Kadro Üyesi A", "Kadro Üyesi B"),
            addedOrder = 111
        )
    )

    private fun saveDeletedItems(itemsToDelete: Collection<MediaCatalogItem>) {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        val existingIds = prefs.getStringSet("deleted_item_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        existingIds.addAll(itemsToDelete.map { it.id })
        prefs.edit().putStringSet("deleted_item_ids", existingIds).apply()
    }

    private fun saveDeletedCategory(section: MediaHubSection, categoryName: String) {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getStringSet("deleted_category_keys", emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add("${section.name}::${categoryName.uppercase(Locale.getDefault())}")
        prefs.edit().putStringSet("deleted_category_keys", existing).apply()
    }

    private fun deleteEntireCategoryList(
        section: MediaHubSection,
        categoryName: String,
        onComplete: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle("Listeyi Sil")
            .setMessage("\"$categoryName\" listesini komple silmek ister misiniz?")
            .setPositiveButton("Evet, Sil") { _, _ ->
                val matching = activeItems.filter {
                    it.section == section && it.category.equals(categoryName, ignoreCase = true)
                }
                val removedIds = matching.map { it.id }.toSet()
                activeItems.removeAll { it.id in removedIds }
                saveDeletedCategory(section, categoryName)
                if (matching.isNotEmpty()) {
                    saveDeletedItems(matching)
                }

                Toast.makeText(this, "\"$categoryName\" listesi silindi", Toast.LENGTH_SHORT).show()
                if (activeItems.isNotEmpty()) {
                    currentChannelIndex = currentChannelIndex.coerceIn(0, activeItems.lastIndex)
                    updateHeaderTitle()
                }
                onComplete()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun saveCustomChannel(title: String, url: String) {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getStringSet("custom_channel_entries", emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add("$title|||$url")
        prefs.edit().putStringSet("custom_channel_entries", existing).apply()
    }

    private fun persistAccounts() {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("account_profiles", accountProfiles.toSet())
            .putString("active_account", activeAccount)
            .apply()
    }

    private fun showCategoryHubDialog() {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        val deletedCats = prefs.getStringSet("deleted_category_keys", emptySet()) ?: emptySet()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF0F172A"))
            setPadding(18, 18, 18, 14)
        }

        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 14) }
        }
        topHeader.addView(
            TextView(this).apply {
                text = "by erdal trmn tv player · $activeAccount"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
        )
        val btnTopUpdate = Button(this).apply {
            text = "Güncelle"
            textSize = 12f
        }
        topHeader.addView(btnTopUpdate)
        root.addView(topHeader)

        var dialogRef: AlertDialog? = null

        btnTopUpdate.setOnClickListener {
            triggerCatalogUpdate()
            dialogRef?.dismiss()
            showCategoryHubDialog()
        }

        val dateStr = SimpleDateFormat("EEE dd MMM", Locale("tr")).format(Date()).uppercase(Locale("tr"))
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        root.addView(
            TextView(this).apply {
                text = "$timeStr  $dateStr  ·  Kategoriyi silmek için basılı tutun"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 12) }
            }
        )

        val sectionCards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 14) }
        }
        sectionCards.addView(createHubCard("📺", "Canlı TV", "TV Kategorileri") {
            dialogRef?.dismiss()
            showLiveTvCatalogDialog()
        })
        sectionCards.addView(createHubCard("🎬", "Filmler", "Film Kategorileri") {
            dialogRef?.dismiss()
            showVodCatalogDialog(MediaHubSection.MOVIES)
        })
        sectionCards.addView(createHubCard("🎞️", "Dizi", "Dizi Kategorileri") {
            dialogRef?.dismiss()
            showVodCatalogDialog(MediaHubSection.SERIES)
        })
        root.addView(sectionCards)

        val catScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 340)
        }
        val catCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catScroll.addView(catCol)
        root.addView(catScroll)

        fun buildAllCategories(): List<Triple<MediaHubSection, String, String>> {
            val out = mutableListOf<Triple<MediaHubSection, String, String>>()
            val liveCats = allItems.filter { it.section == MediaHubSection.LIVE_TV }.map { it.category }.distinct()
            for (c in liveCats) {
                val key = "${MediaHubSection.LIVE_TV.name}::${c.uppercase(Locale.getDefault())}"
                if (key !in deletedCats) out.add(Triple(MediaHubSection.LIVE_TV, c, "Canlı TV"))
            }
            val vodDefaults = listOf("TR 3D", "TR IMDB TOP 250", "TR BOXSET", "TR NETFLIX", "TR AMAZON", "TR BOLLYWOD")
            val movieCats = (vodDefaults + allItems.filter { it.section == MediaHubSection.MOVIES }.map { it.category }).distinct()
            for (c in movieCats) {
                val key = "${MediaHubSection.MOVIES.name}::${c.uppercase(Locale.getDefault())}"
                if (key !in deletedCats) out.add(Triple(MediaHubSection.MOVIES, c, "Filmler"))
            }
            val seriesCats = (vodDefaults + allItems.filter { it.section == MediaHubSection.SERIES }.map { it.category }).distinct()
            for (c in seriesCats) {
                val key = "${MediaHubSection.SERIES.name}::${c.uppercase(Locale.getDefault())}"
                if (key !in deletedCats) out.add(Triple(MediaHubSection.SERIES, c, "Diziler"))
            }
            return out
        }

        fun refreshCategoryRows() {
            catCol.removeAllViews()
            val list = buildAllCategories()
            if (list.isEmpty()) {
                catCol.addView(
                    TextView(this).apply {
                        text = "Gösterilecek kategori yok. Üstteki Güncelle butonuna basabilirsiniz."
                        setTextColor(Color.LTGRAY)
                        setPadding(12, 20, 12, 20)
                    }
                )
                return
            }
            for ((sec, catName, secLabel) in list) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#FF1E293B"))
                    setPadding(18, 16, 18, 16)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 8) }
                    setOnClickListener {
                        dialogRef?.dismiss()
                        if (sec == MediaHubSection.LIVE_TV) showLiveTvCatalogDialog()
                        else showVodCatalogDialog(sec)
                    }
                    setOnLongClickListener {
                        deleteEntireCategoryList(sec, catName) {
                            refreshCategoryRows()
                        }
                        true
                    }
                }
                row.addView(
                    TextView(this).apply {
                        text = catName
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                )
                row.addView(
                    TextView(this).apply {
                        text = secLabel
                        setTextColor(Color.parseColor("#FF93C5FD"))
                        textSize = 12f
                    }
                )
                catCol.addView(row)
            }
        }

        refreshCategoryRows()

        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF111827"))
            setPadding(8, 12, 8, 8)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 12, 0, 0) }
        }
        bottomNav.addView(createBottomNavTab("Favoriler") {
            dialogRef?.dismiss()
            showFavoritesDialog()
        })
        bottomNav.addView(createBottomNavTab("TV") {
            dialogRef?.dismiss()
            showLiveTvCatalogDialog()
        })
        bottomNav.addView(createBottomNavTab("Filmler") {
            dialogRef?.dismiss()
            showVodCatalogDialog(MediaHubSection.MOVIES)
        })
        bottomNav.addView(createBottomNavTab("Diziler") {
            dialogRef?.dismiss()
            showVodCatalogDialog(MediaHubSection.SERIES)
        })
        bottomNav.addView(createBottomNavTab("Ayarlar") {
            showSettingsMenuDialog()
        })
        root.addView(bottomNav)

        dialogRef = AlertDialog.Builder(this)
            .setView(root)
            .setNegativeButton("Kapat", null)
            .create()
        dialogRef.show()
    }

    private fun createBottomNavTab(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(6, 12, 6, 12)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun showSettingsMenuDialog() {
        val options = arrayOf(
            "Güncelleme",
            "Hesap Seç",
            "Hesap Ekle",
            "Kullanıcı Değiştir"
        )
        AlertDialog.Builder(this)
            .setTitle("Ayarlar ($activeAccount)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> triggerCatalogUpdate()
                    1 -> showSelectAccountDialog()
                    2 -> showAddAccountDialog()
                    3 -> showSwitchUserDialog()
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun triggerCatalogUpdate() {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("deleted_category_keys")
            .remove("deleted_item_ids")
            .apply()
        loadAccountsAndFavoritesAndCatalog(currentStreamUrl, currentStreamTitle)
        updateHeaderTitle()
        Toast.makeText(this, "Liste ve kategoriler güncellendi", Toast.LENGTH_SHORT).show()
    }

    private fun showSelectAccountDialog() {
        val list = accountProfiles.toTypedArray()
        val currentIdx = list.indexOf(activeAccount).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Hesap Seç")
            .setSingleChoiceItems(list, currentIdx) { dialog, which ->
                activeAccount = list[which]
                persistAccounts()
                Toast.makeText(this, "Seçilen hesap: $activeAccount", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showAddAccountDialog() {
        val input = EditText(this).apply {
            hint = "Yeni hesap adı"
            setTextColor(Color.BLACK)
        }
        AlertDialog.Builder(this)
            .setTitle("Hesap Ekle")
            .setView(input)
            .setPositiveButton("Ekle") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Hesap adı boş olamaz", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!accountProfiles.contains(name)) {
                    accountProfiles.add(name)
                }
                activeAccount = name
                persistAccounts()
                Toast.makeText(this, "Hesap eklendi ve aktif edildi: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showSwitchUserDialog() {
        if (accountProfiles.size <= 1) {
            showAddAccountDialog()
            return
        }
        val nextIdx = (accountProfiles.indexOf(activeAccount) + 1) % accountProfiles.size
        activeAccount = accountProfiles[nextIdx]
        persistAccounts()
        Toast.makeText(this, "Kullanıcı değiştirildi: $activeAccount", Toast.LENGTH_SHORT).show()
    }

    private fun showFavoritesDialog() {
        val favItems = allItems.filter { it.streamUrl.isNotEmpty() && it.streamUrl in favoriteUrls }
        if (favItems.isEmpty()) {
            Toast.makeText(this, "Favori içerik bulunmuyor", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = favItems.map { "${it.number} - ${it.title}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Favoriler")
            .setItems(labels) { _, which ->
                playCatalogItem(favItems[which])
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun toggleFavoriteForCurrentItem() {
        if (currentStreamUrl.isEmpty()) return
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        if (favoriteUrls.contains(currentStreamUrl)) {
            favoriteUrls.remove(currentStreamUrl)
            showGestureToast("Favorilerden çıkarıldı")
        } else {
            favoriteUrls.add(currentStreamUrl)
            showGestureToast("Favorilere eklendi")
        }
        prefs.edit().putStringSet("favorite_urls", favoriteUrls.toSet()).apply()
    }

    private fun createHubCard(icon: String, title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF1F2937"))
            setPadding(14, 20, 14, 20)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 6, 0) }
            setOnClickListener { onClick() }

            addView(
                TextView(this@PlayerActivity).apply {
                    text = icon
                    textSize = 22f
                    gravity = Gravity.CENTER
                }
            )
            addView(
                TextView(this@PlayerActivity).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 8, 0, 2)
                }
            )
            addView(
                TextView(this@PlayerActivity).apply {
                    text = subtitle
                    setTextColor(Color.parseColor("#FF9CA3AF"))
                    textSize = 10f
                    gravity = Gravity.CENTER
                }
            )
        }
    }

    private fun showLiveTvCatalogDialog() {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        val deletedCats = prefs.getStringSet("deleted_category_keys", emptySet()) ?: emptySet()

        fun currentLiveSource() = activeItems.filter { it.section == MediaHubSection.LIVE_TV }
        fun currentCategories(): List<String> {
            val baseCats = allItems
                .filter { it.section == MediaHubSection.LIVE_TV }
                .map { it.category }
                .distinct()
                .filter { "${MediaHubSection.LIVE_TV.name}::${it.uppercase(Locale.getDefault())}" !in deletedCats }
            return if (baseCats.isEmpty()) emptyList() else listOf("TÜMÜ") + baseCats
        }

        var selectedCategory = "TÜMÜ"
        var searchQuery = ""

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF0F172A"))
            setPadding(16, 16, 16, 16)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 12) }
        }
        topBar.addView(
            TextView(this).apply {
                text = "by erdal trmn tv player · Canlı TV"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
        )
        val searchBox = EditText(this).apply {
            hint = "Kanal ara..."
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(240, -2)
        }
        topBar.addView(searchBox)
        rootLayout.addView(topBar)

        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 600)
        }

        val leftCatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(240, -1).apply { setMargins(0, 0, 12, 0) }
        }
        val leftCatCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leftCatScroll.addView(leftCatCol)

        val rightListScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val rightListCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightListScroll.addView(rightListCol)

        bodyRow.addView(leftCatScroll)
        bodyRow.addView(rightListScroll)
        rootLayout.addView(bodyRow)

        var dialogRef: AlertDialog? = null

        fun refreshRightList() {
            rightListCol.removeAllViews()
            val liveSource = currentLiveSource()
            val filtered = liveSource.filter { item ->
                val catOk = selectedCategory == "TÜMÜ" || item.category.equals(selectedCategory, ignoreCase = true)
                val queryOk = searchQuery.isBlank() ||
                    item.title.contains(searchQuery, ignoreCase = true) ||
                    item.number.toString().contains(searchQuery)
                catOk && queryOk
            }

            if (filtered.isEmpty()) {
                rightListCol.addView(
                    TextView(this).apply {
                        text = "Bu listede gösterilecek kanal yok"
                        setTextColor(Color.LTGRAY)
                        setPadding(16, 24, 16, 24)
                    }
                )
                return
            }

            for (item in filtered) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(
                        if (item.title == currentStreamTitle) Color.parseColor("#FF1E3A8A")
                        else Color.parseColor("#FF1E293B")
                    )
                    setPadding(18, 16, 18, 16)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
                    setOnClickListener {
                        dialogRef?.dismiss()
                        playCatalogItem(item)
                    }
                }
                card.addView(
                    TextView(this).apply {
                        text = item.number.toString()
                        setTextColor(Color.parseColor("#FF93C5FD"))
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(80, -2)
                    }
                )
                card.addView(
                    TextView(this).apply {
                        text = item.title
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                )
                rightListCol.addView(card)
            }
        }

        fun rebuildCategories() {
            leftCatCol.removeAllViews()
            val cats = currentCategories()
            if (selectedCategory !in cats) {
                selectedCategory = cats.firstOrNull() ?: "TÜMÜ"
            }
            for (cat in cats) {
                val active = cat.equals(selectedCategory, ignoreCase = true)
                leftCatCol.addView(
                    TextView(this).apply {
                        text = cat
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setBackgroundColor(
                            if (active) Color.parseColor("#FF2563EB")
                            else Color.parseColor("#FF1E293B")
                        )
                        setPadding(16, 18, 16, 18)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 8) }
                        setOnClickListener {
                            selectedCategory = cat
                            rebuildCategories()
                            refreshRightList()
                        }
                        if (cat != "TÜMÜ") {
                            setOnLongClickListener {
                                deleteEntireCategoryList(MediaHubSection.LIVE_TV, cat) {
                                    rebuildCategories()
                                    refreshRightList()
                                }
                                true
                            }
                        }
                    }
                )
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                refreshRightList()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        rebuildCategories()
        refreshRightList()

        dialogRef = AlertDialog.Builder(this)
            .setView(rootLayout)
            .setPositiveButton("Kapat", null)
            .create()
        dialogRef.show()
    }

    private fun showVodCatalogDialog(section: MediaHubSection) {
        val prefs = getSharedPreferences("erdal_trmn_tv_player_prefs", Context.MODE_PRIVATE)
        val deletedCats = prefs.getStringSet("deleted_category_keys", emptySet()) ?: emptySet()
        val defaultCats = listOf("TR 3D", "TR IMDB TOP 250", "TR BOXSET", "TR NETFLIX", "TR AMAZON", "TR BOLLYWOD")

        fun currentCategories(): List<String> {
            val fromItems = allItems
                .filter { it.section == section }
                .map { it.category }
                .distinct()
            val merged = (defaultCats + fromItems).distinct().filter {
                "${section.name}::${it.uppercase(Locale.getDefault())}" !in deletedCats
            }
            return if (merged.isEmpty()) emptyList() else listOf("TÜMÜ") + merged
        }

        var selectedCategory = "TÜMÜ"
        var sortMode = 0
        var searchQuery = ""

        val sortLabels = listOf(
            "Varsayılana Göre Sırala",
            "Son Eklenenlere Göre Sırala",
            "Yayın Tarihine Göre Sırala",
            "Derecelendirmeye Göre Sırala"
        )

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF0F172A"))
            setPadding(16, 16, 16, 16)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 12) }
        }
        val sectionTitle = if (section == MediaHubSection.MOVIES) "Filmler" else "Diziler"
        topBar.addView(
            TextView(this).apply {
                text = "$sectionTitle · Listeyi silmek için basılı tutun"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
        )
        val searchInput = EditText(this).apply {
            hint = "Ara..."
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(180, -2)
        }
        val btnSort = Button(this).apply {
            text = sortLabels[sortMode]
            textSize = 11f
        }
        topBar.addView(searchInput)
        topBar.addView(btnSort)
        rootLayout.addView(topBar)

        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 600)
        }

        val catScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(220, -1).apply { setMargins(0, 0, 12, 0) }
        }
        val catCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catScroll.addView(catCol)

        val gridScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val gridLayout = GridLayout(this).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        gridScroll.addView(gridLayout)

        bodyRow.addView(catScroll)
        bodyRow.addView(gridScroll)
        rootLayout.addView(bodyRow)

        var dialogRef: AlertDialog? = null

        fun refreshGrid() {
            gridLayout.removeAllViews()
            val sectionItems = activeItems.filter { it.section == section }
            val filtered = sectionItems.filter { item ->
                val catOk = selectedCategory == "TÜMÜ" || item.category.equals(selectedCategory, ignoreCase = true)
                val qOk = searchQuery.isBlank() || item.title.contains(searchQuery, ignoreCase = true)
                catOk && qOk
            }
            val sorted = when (sortMode) {
                1 -> filtered.sortedByDescending { it.addedOrder }
                2 -> filtered.sortedByDescending { it.year }
                3 -> filtered.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
                else -> filtered
            }

            if (sorted.isEmpty()) {
                gridLayout.addView(
                    TextView(this).apply {
                        text = "Bu listede gösterilecek içerik yok"
                        setTextColor(Color.LTGRAY)
                        setPadding(16, 24, 16, 24)
                    }
                )
                return
            }

            for (item in sorted) {
                val posterCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#FF1E293B"))
                    setPadding(14, 20, 14, 20)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(8, 8, 8, 8)
                    }
                    setOnClickListener {
                        dialogRef?.dismiss()
                        showItemDetailDialog(item)
                    }
                }
                posterCard.addView(
                    TextView(this).apply {
                        text = "★ ${item.rating}"
                        setTextColor(Color.parseColor("#FFFBBF24"))
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                )
                posterCard.addView(
                    TextView(this).apply {
                        text = item.title
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 2
                        gravity = Gravity.CENTER
                        setPadding(0, 8, 0, 4)
                    }
                )
                posterCard.addView(
                    TextView(this).apply {
                        text = "${item.year} · ${item.genre}"
                        setTextColor(Color.LTGRAY)
                        textSize = 10f
                        gravity = Gravity.CENTER
                    }
                )
                gridLayout.addView(posterCard)
            }
        }

        fun rebuildCategories() {
            catCol.removeAllViews()
            val cats = currentCategories()
            if (selectedCategory !in cats) {
                selectedCategory = cats.firstOrNull() ?: "TÜMÜ"
            }
            for (cat in cats) {
                val isSel = cat.equals(selectedCategory, ignoreCase = true)
                catCol.addView(
                    TextView(this).apply {
                        text = cat
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setBackgroundColor(
                            if (isSel) Color.parseColor("#FF2563EB")
                            else Color.parseColor("#FF1E293B")
                        )
                        setPadding(14, 16, 14, 16)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 8) }
                        setOnClickListener {
                            selectedCategory = cat
                            rebuildCategories()
                            refreshGrid()
                        }
                        if (cat != "TÜMÜ") {
                            setOnLongClickListener {
                                deleteEntireCategoryList(section, cat) {
                                    rebuildCategories()
                                    refreshGrid()
                                }
                                true
                            }
                        }
                    }
                )
            }
        }

        btnSort.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sıralama")
                .setItems(sortLabels.toTypedArray()) { _, which ->
                    sortMode = which
                    btnSort.text = sortLabels[sortMode]
                    refreshGrid()
                }
                .show()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                refreshGrid()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        rebuildCategories()
        refreshGrid()

        dialogRef = AlertDialog.Builder(this)
            .setView(rootLayout)
            .setPositiveButton("Kapat", null)
            .create()
        dialogRef.show()
    }

    private fun showCurrentItemDetailDialog() {
        val item = currentItemMeta ?: MediaCatalogItem(
            id = "current",
            number = currentChannelIndex + 1,
            title = currentStreamTitle,
            streamUrl = currentStreamUrl,
            plot = "Aktif oynatılan yayın akışı."
        )
        showItemDetailDialog(item)
    }

    private fun showItemDetailDialog(item: MediaCatalogItem) {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF111827"))
            setPadding(24, 24, 24, 24)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        col.addView(
            TextView(this).apply {
                text = item.title
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        col.addView(
            TextView(this).apply {
                text = "${item.year}  ·  ${item.genre}  ·  ${item.durationText}  ·  ★ ${item.rating}"
                setTextColor(Color.parseColor("#FFD1D5DB"))
                textSize = 13f
                setPadding(0, 8, 0, 14)
            }
        )
        col.addView(
            TextView(this).apply {
                text = item.plot.ifBlank { "Açıklama bulunmuyor." }
                setTextColor(Color.parseColor("#FFE5E7EB"))
                textSize = 14f
                setPadding(0, 0, 0, 18)
            }
        )

        var dialogRef: AlertDialog? = null
        val isFav = favoriteUrls.contains(item.streamUrl)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 20) }
        }
        val btnPlay = Button(this).apply {
            text = "▶ Oynat"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 8, 0) }
        }
        val btnTrailer = Button(this).apply {
            text = "Fragmanı Oynat"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) }
        }
        val btnBookmark = Button(this).apply {
            text = if (isFav) "🔖 Kayıtlı" else "🔖 Kaydet"
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(8, 0, 0, 0) }
        }

        btnPlay.setOnClickListener {
            dialogRef?.dismiss()
            playCatalogItem(item)
        }
        btnTrailer.setOnClickListener {
            val target = item.trailerUrl.ifBlank { item.streamUrl }
            dialogRef?.dismiss()
            if (target.isNotEmpty()) {
                currentStreamUrl = target
                currentStreamTitle = "${item.title} (Fragman)"
                updateHeaderTitle()
                initializePlayer(currentStreamUrl, 0L, true)
            } else {
                Toast.makeText(this, "Fragman bağlantısı yok", Toast.LENGTH_SHORT).show()
            }
        }
        btnBookmark.setOnClickListener {
            currentStreamUrl = item.streamUrl
            toggleFavoriteForCurrentItem()
            btnBookmark.text = if (favoriteUrls.contains(item.streamUrl)) "🔖 Kayıtlı" else "🔖 Kaydet"
        }

        btnRow.addView(btnPlay)
        btnRow.addView(btnTrailer)
        btnRow.addView(btnBookmark)
        col.addView(btnRow)

        if (item.cast.isNotEmpty()) {
            col.addView(
                TextView(this).apply {
                    text = "Oyuncu"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 10)
                }
            )
            val castRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (actor in item.cast) {
                castRow.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setBackgroundColor(Color.parseColor("#FF1F2937"))
                        setPadding(14, 14, 14, 14)
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 10, 0) }
                        addView(
                            TextView(this@PlayerActivity).apply {
                                text = "👤"
                                textSize = 22f
                                gravity = Gravity.CENTER
                            }
                        )
                        addView(
                            TextView(this@PlayerActivity).apply {
                                text = actor
                                setTextColor(Color.WHITE)
                                textSize = 12f
                                gravity = Gravity.CENTER
                                setPadding(0, 6, 0, 0)
                            }
                        )
                    }
                )
            }
            val castScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            castScroll.addView(castRow)
            col.addView(castScroll)
        }

        scroll.addView(col)
        dialogRef = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Kapat", null)
            .create()
        dialogRef.show()
    }

    private fun playCatalogItem(item: MediaCatalogItem) {
        val idxInActive = activeItems.indexOfFirst { it.id == item.id || it.title == item.title }
        if (idxInActive >= 0) {
            currentChannelIndex = idxInActive
        } else {
            activeItems.add(item)
            currentChannelIndex = activeItems.lastIndex
        }
        currentItemMeta = item
        currentStreamUrl = item.streamUrl
        currentStreamTitle = item.title
        savedPosition = 0L
        savedPlayWhenReady = true
        updateHeaderTitle()
        showGestureToast("▶ ${item.title}")
        initializePlayer(currentStreamUrl, 0L, true)
    }

    private fun switchChannel(offset: Int) {
        if (activeItems.size <= 1) {
            Toast.makeText(this, "Geçiş yapılacak başka kanal yok", Toast.LENGTH_SHORT).show()
            return
        }
        val nextIndex = (currentChannelIndex + offset + activeItems.size) % activeItems.size
        playChannelAt(nextIndex)
    }

    private fun playChannelAt(index: Int) {
        if (index !in activeItems.indices) return
        currentChannelIndex = index
        val target = activeItems[index]
        currentItemMeta = target
        currentStreamUrl = target.streamUrl
        currentStreamTitle = target.title
        savedPosition = 0L
        savedPlayWhenReady = true
        updateHeaderTitle()
        showGestureToast("📺 ${target.title}")
        initializePlayer(currentStreamUrl, 0L, true)
    }

    private fun showChannelManagerDialog() {
        if (activeItems.isEmpty()) {
            Toast.makeText(this, "Kanal listesi boş", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = activeItems.mapIndexed { idx, ch ->
            val activeMark = if (idx == currentChannelIndex) " ▶" else ""
            "${ch.number} - ${ch.title}$activeMark"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Hızlı Kanal Geçişi & Silme")
            .setItems(labels) { _, which ->
                playChannelAt(which)
            }
            .setNeutralButton("Toplu Sil") { _, _ ->
                showBulkDeleteChannelsDialog()
            }
            .setNegativeButton("Tekli Sil") { _, _ ->
                showSingleDeleteChannelDialog()
            }
            .setPositiveButton("Kapat", null)
            .show()
    }

    private fun showSingleDeleteChannelDialog() {
        if (activeItems.isEmpty()) return
        val labels = activeItems.map { "${it.number} - ${it.title}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Silinecek Kanalı Seçin")
            .setItems(labels) { _, which ->
                val removed = activeItems.removeAt(which)
                saveDeletedItems(listOf(removed))
                Toast.makeText(this, "Silindi: ${removed.title}", Toast.LENGTH_SHORT).show()
                if (activeItems.isEmpty()) {
                    finish()
                } else {
                    playChannelAt(which.coerceIn(0, activeItems.lastIndex))
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showBulkDeleteChannelsDialog() {
        if (activeItems.isEmpty()) return
        val labels = activeItems.map { "${it.number} - ${it.title}" }.toTypedArray()
        val checked = BooleanArray(activeItems.size) { false }

        AlertDialog.Builder(this)
            .setTitle("Toplu Silinecek Kanallar")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Seçilenleri Sil") { _, _ ->
                val toRemove = activeItems.indices.filter { checked[it] }.map { activeItems[it] }
                if (toRemove.isEmpty()) {
                    Toast.makeText(this, "Kanal seçilmedi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val removedIds = toRemove.map { it.id }.toSet()
                val currentRemoved = activeItems.getOrNull(currentChannelIndex)?.id in removedIds
                activeItems.removeAll { it.id in removedIds }
                saveDeletedItems(toRemove)
                Toast.makeText(this, "${toRemove.size} kanal silindi", Toast.LENGTH_SHORT).show()
                if (activeItems.isEmpty()) {
                    finish()
                } else if (currentRemoved) {
                    playChannelAt(0)
                } else {
                    val idx = activeItems.indexOfFirst { it.id == currentItemMeta?.id }
                    if (idx >= 0) {
                        currentChannelIndex = idx
                        updateHeaderTitle()
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showPlatformLoginDialog() {
        val platforms = listOf(
            "Netflix" to "https://www.netflix.com/tr/login",
            "Amazon Prime Video" to "https://www.primevideo.com",
            "Disney+" to "https://www.disneyplus.com/login",
            "YouTube" to "https://m.youtube.com",
            "Özel Web / Portal Girişi" to ""
        )
        val names = platforms.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Platform Girişleri")
            .setItems(names) { _, which ->
                val (pName, pUrl) = platforms[which]
                if (pUrl.isNotEmpty()) {
                    openPlatformWebView(pName, pUrl)
                } else {
                    showCustomUrlLoginDialog()
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showCustomUrlLoginDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val titleInput = EditText(this).apply {
            hint = "Platform / Kanal Adı"
            setTextColor(Color.BLACK)
        }
        val urlInput = EditText(this).apply {
            hint = "https://... veya yayın URL (.m3u8 / .mp4)"
            setTextColor(Color.BLACK)
        }
        container.addView(titleInput)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Özel Giriş veya Yayın Ekle")
            .setView(container)
            .setPositiveButton("Aç / Kaydet") { _, _ ->
                val customTitle = titleInput.text.toString().trim().ifEmpty { "Özel Platform" }
                val raw = urlInput.text.toString().trim()
                if (raw.isEmpty()) {
                    Toast.makeText(this, "Adres boş olamaz", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                val lower = normalized.lowercase(Locale.getDefault())
                if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".ts")) {
                    saveCustomChannel(customTitle, normalized)
                    val newItem = MediaCatalogItem(
                        id = "custom_${System.currentTimeMillis()}",
                        number = 900 + activeItems.size,
                        title = customTitle,
                        streamUrl = normalized,
                        addedOrder = activeItems.size + 1
                    )
                    playCatalogItem(newItem)
                } else {
                    openPlatformWebView(customTitle, normalized)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPlatformWebView(platformName: String, startUrl: String) {
        val webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FF111827"))
            setPadding(16, 16, 16, 16)
        }
        val tvTitle = TextView(this).apply {
            text = "$platformName Giriş"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnPlayCurrent = Button(this).apply {
            text = "Yayını Oynat"
            textSize = 12f
        }
        val btnClose = Button(this).apply {
            text = "Kapat"
            textSize = 12f
        }
        topBar.addView(tvTitle)
        topBar.addView(btnPlayCurrent)
        topBar.addView(btnClose)

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = WebSettings.getDefaultUserAgent(this@PlayerActivity)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
        }

        var dialogRef: AlertDialog? = null

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUrl = request?.url?.toString().orEmpty()
                val lower = targetUrl.lowercase(Locale.getDefault())
                if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv")) {
                    saveCustomChannel(platformName, targetUrl)
                    dialogRef?.dismiss()
                    playCatalogItem(
                        MediaCatalogItem(
                            id = "web_${System.currentTimeMillis()}",
                            number = 950 + activeItems.size,
                            title = platformName,
                            streamUrl = targetUrl,
                            addedOrder = activeItems.size + 1
                        )
                    )
                    return true
                }
                return false
            }
        }

        btnPlayCurrent.setOnClickListener {
            val activeUrl = webView.url?.trim().orEmpty()
            if (activeUrl.isNotEmpty()) {
                saveCustomChannel(platformName, activeUrl)
                dialogRef?.dismiss()
                playCatalogItem(
                    MediaCatalogItem(
                        id = "web_${System.currentTimeMillis()}",
                        number = 950 + activeItems.size,
                        title = platformName,
                        streamUrl = activeUrl,
                        addedOrder = activeItems.size + 1
                    )
                )
            }
        }
        btnClose.setOnClickListener { dialogRef?.dismiss() }

        webContainer.addView(topBar)
        webContainer.addView(webView)
        webView.loadUrl(startUrl)

        dialogRef = AlertDialog.Builder(this)
            .setView(webContainer)
            .setOnDismissListener {
                webView.stopLoading()
                webView.destroy()
            }
            .create()
        dialogRef.show()
    }

    @OptIn(UnstableApi::class)
    private fun showVideoAndAudioSelectorDialog(initialTabAudio: Boolean) {
        val exo = player ?: return
        var isAudioTab = initialTabAudio

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF18181B"))
            setPadding(24, 20, 24, 16)
        }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 16) }
        }
        val btnTabVideo = Button(this).apply {
            text = "VİDEO"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) }
        }
        val btnTabAudio = Button(this).apply {
            text = "SES"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 0, 0) }
        }
        tabRow.addView(btnTabVideo)
        tabRow.addView(btnTabAudio)
        root.addView(tabRow)

        val optionsScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 380)
        }
        val optionsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        optionsScroll.addView(optionsCol)
        root.addView(optionsScroll)

        var selectedAction: (() -> Unit)? = null
        var selectedLabel = ""

        data class ChoiceRow(val title: String, val action: () -> Unit)

        fun buildChoices(): List<ChoiceRow> {
            val list = mutableListOf<ChoiceRow>()
            if (!isAudioTab) {
                list.add(
                    ChoiceRow("Yok") {
                        exo.trackSelectionParameters = exo.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                            .build()
                    }
                )
                list.add(
                    ChoiceRow("Otomatik") {
                        exo.trackSelectionParameters = exo.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .build()
                    }
                )
                for (g in exo.currentTracks.groups) {
                    if (g.type == C.TRACK_TYPE_VIDEO) {
                        for (i in 0 until g.length) {
                            if (g.isTrackSupported(i)) {
                                val f: Format = g.getTrackFormat(i)
                                val resText = if (f.width > 0 && f.height > 0) "${f.width} × ${f.height}" else (f.label ?: "Video ${i + 1}")
                                val fpsText = if (f.frameRate > 0f) " (${f.frameRate.toInt()} fps)" else ""
                                val groupRef: TrackGroup = g.mediaTrackGroup
                                list.add(
                                    ChoiceRow("$resText$fpsText") {
                                        exo.trackSelectionParameters = exo.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                                            .setOverrideForType(TrackSelectionOverride(groupRef, i))
                                            .build()
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                list.add(
                    ChoiceRow("Otomatik Ses") {
                        exo.trackSelectionParameters = exo.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .build()
                    }
                )
                for (g in exo.currentTracks.groups) {
                    if (g.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until g.length) {
                            if (g.isTrackSupported(i)) {
                                val f: Format = g.getTrackFormat(i)
                                val label = f.label ?: "Ses ${i + 1}"
                                val lang = f.language?.uppercase(Locale.getDefault()) ?: "DİL"
                                val codec = f.sampleMimeType?.substringAfter("/")?.uppercase(Locale.getDefault()) ?: ""
                                val ch = if (f.channelCount > 0) " · ${f.channelCount}ch" else ""
                                val extra = if (codec.isNotEmpty()) " [$codec$ch]" else ""
                                val groupRef: TrackGroup = g.mediaTrackGroup
                                list.add(
                                    ChoiceRow("$label ($lang)$extra") {
                                        exo.trackSelectionParameters = exo.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                            .setOverrideForType(TrackSelectionOverride(groupRef, i))
                                            .build()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            return list
        }

        fun refreshOptions() {
            optionsCol.removeAllViews()
            btnTabVideo.setBackgroundColor(if (!isAudioTab) Color.parseColor("#FF2563EB") else Color.parseColor("#FF27272A"))
            btnTabAudio.setBackgroundColor(if (isAudioTab) Color.parseColor("#FF2563EB") else Color.parseColor("#FF27272A"))

            val choices = buildChoices()
            if (choices.isNotEmpty() && selectedAction == null) {
                selectedAction = choices[1.coerceAtMost(choices.lastIndex)].action
                selectedLabel = choices[1.coerceAtMost(choices.lastIndex)].title
            }

            val rowViews = mutableListOf<Pair<TextView, TextView>>()
            for (choice in choices) {
                val isSelected = choice.title == selectedLabel
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12, 18, 12, 18)
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                }
                val tvLabel = TextView(this).apply {
                    text = choice.title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val tvRadio = TextView(this).apply {
                    text = if (isSelected) "◉" else "○"
                    setTextColor(if (isSelected) Color.parseColor("#FF60A5FA") else Color.LTGRAY)
                    textSize = 18f
                }
                row.addView(tvLabel)
                row.addView(tvRadio)
                rowViews.add(tvLabel to tvRadio)

                row.setOnClickListener {
                    selectedAction = choice.action
                    selectedLabel = choice.title
                    for ((lbl, rad) in rowViews) {
                        val hit = lbl.text.toString() == selectedLabel
                        rad.text = if (hit) "◉" else "○"
                        rad.setTextColor(if (hit) Color.parseColor("#FF60A5FA") else Color.LTGRAY)
                    }
                }
                optionsCol.addView(row)
            }
        }

        btnTabVideo.setOnClickListener {
            isAudioTab = false
            selectedAction = null
            selectedLabel = ""
            refreshOptions()
        }
        btnTabAudio.setOnClickListener {
            isAudioTab = true
            selectedAction = null
            selectedLabel = ""
            refreshOptions()
        }

        refreshOptions()

        var dialogRef: AlertDialog? = null
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 16, 0, 0) }
        }
        val btnCancel = Button(this).apply {
            text = "İptal"
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 16, 0) }
            setOnClickListener { dialogRef?.dismiss() }
        }
        val btnSubmit = Button(this).apply {
            text = "Gönder"
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setOnClickListener {
                selectedAction?.invoke()
                dialogRef?.dismiss()
                if (selectedLabel.isNotEmpty()) {
                    showGestureToast("✓ $selectedLabel")
                }
            }
        }
        footer.addView(btnCancel)
        footer.addView(btnSubmit)
        root.addView(footer)

        dialogRef = AlertDialog.Builder(this)
            .setView(root)
            .create()
        dialogRef.show()
    }

    @OptIn(UnstableApi::class)
    private fun showSubtitleTrackDialog() {
        val exo = player ?: return
        val opts = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()

        opts.add("Altyazıyı Kapat")
        acts.add {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            showGestureToast("Altyazı kapatıldı")
        }

        for (g in exo.currentTracks.groups) {
            if (g.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until g.length) {
                    if (g.isTrackSupported(i)) {
                        val f = g.getTrackFormat(i)
                        val label = f.label ?: "Altyazı ${i + 1}"
                        val lang = f.language ?: "Dil"
                        val mark = if (g.isTrackSelected(i)) " ✓" else ""
                        opts.add("$label ($lang)$mark")
                        acts.add {
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                .build()
                            showGestureToast("$label seçildi")
                        }
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Altyazı Seçimi")
            .setItems(opts.toTypedArray()) { _, w -> acts[w]() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun seekBy(deltaMs: Long) {
        player?.let { exo ->
            val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
            val clamped = if (exo.duration != C.TIME_UNSET) target.coerceAtMost(exo.duration) else target
            exo.seekTo(clamped)
        }
    }

    private fun createIconButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        val sz = (44 * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(5, 0, 5, 0) }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownloadAfterPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startBackgroundDownloadService()
    }

    private fun startBackgroundDownloadService() {
        if (currentStreamUrl.isEmpty()) return
        val svcIntent = Intent(this, StreamDownloadService::class.java).apply {
            putExtra("STREAM_URL", currentStreamUrl)
            putExtra("STREAM_TITLE", currentStreamTitle)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
        Toast.makeText(this, "İndirme arka planda başlatıldı", Toast.LENGTH_LONG).show()
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(url: String, startPositionMs: Long, playWhenReady: Boolean) {
        player?.release()
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

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

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}

class StreamDownloadService : Service() {

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeWorkers = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val streamUrl = intent?.getStringExtra("STREAM_URL")?.trim().orEmpty()
        val streamTitle = intent?.getStringExtra("STREAM_TITLE")?.trim().orEmpty().ifEmpty { "by erdal trmn tv player" }

        if (streamUrl.isEmpty()) {
            stopIfIdle()
            return START_NOT_STICKY
        }

        val chId = "erdal_trmn_tv_player_bg_dl"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "Arka Plan İndirmeleri", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notifId = ((System.currentTimeMillis() % 800_000) + 2000).toInt()
        val builder = NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(streamTitle)
            .setContentText("Arka planda bağlanıyor...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        startForeground(notifId, builder.build())

        synchronized(this) { activeWorkers++ }

        Thread {
            var out: OutputStream? = null
            var mediaUri: Uri? = null
            var fallbackFile: File? = null
            try {
                val lowerUrl = streamUrl.lowercase(Locale.getDefault())
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
                val safeTitle = streamTitle
                    .replace(Regex("[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ _-]"), "")
                    .trim()
                    .ifEmpty { "Video" }
                val fName = "${safeTitle}_$notifId$ext"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/by erdal trmn tv player")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    mediaUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        ?: throw Exception("MediaStore kaydı oluşturulamadı")
                    out = contentResolver.openOutputStream(mediaUri)
                        ?: throw Exception("Çıktı akışı açılamadı")
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "by erdal trmn tv player").apply { mkdirs() }
                    fallbackFile = File(dir, fName)
                    out = FileOutputStream(fallbackFile)
                }

                var totalBytesWritten = 0L
                var lastNotifTime = 0L

                if (isHls) {
                    val segmentUrls = resolveHlsSegments(streamUrl)
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
                    val req = Request.Builder().url(streamUrl).header("User-Agent", "VLC/3.0.18").build()
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
                val doneBuilder = NotificationCompat.Builder(this@StreamDownloadService, chId)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(streamTitle)
                    .setContentText("Bitti: Download/by erdal trmn tv player/$fName ($finalMb MB)")
                    .setOngoing(false)
                nm.notify(notifId + 1, doneBuilder.build())

                mainHandler.post {
                    Toast.makeText(applicationContext, "İndirme tamamlandı: $fName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                try { out?.close() } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                    try { contentResolver.delete(mediaUri, null, null) } catch (_: Exception) {}
                } else {
                    fallbackFile?.delete()
                }
                val errBuilder = NotificationCompat.Builder(this@StreamDownloadService, chId)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(streamTitle)
                    .setContentText("Hata: ${e.message ?: "Bilinmeyen hata"}")
                    .setOngoing(false)
                nm.notify(notifId + 1, errBuilder.build())
                mainHandler.post {
                    Toast.makeText(applicationContext, "İndirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                synchronized(this@StreamDownloadService) { activeWorkers-- }
                stopIfIdle()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        synchronized(this) {
            if (activeWorkers <= 0) {
                activeWorkers = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
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
            if (resolved.lowercase(Locale.getDefault()).contains(".m3u8")) {
                variantUrls.add(resolved)
            } else {
                segmentUrls.add(resolved)
            }
        }

        if (segmentUrls.isNotEmpty()) return segmentUrls
        if (variantUrls.isNotEmpty()) return resolveHlsSegments(variantUrls.first())
        return emptyList()
    }
}
// SON
