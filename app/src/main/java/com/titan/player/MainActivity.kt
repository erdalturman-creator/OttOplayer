package com.titan.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class ChannelItem(
    val name: String,
    val url: String,
    val category: String,
    val type: String,
    val logo: String = "",
    val streamId: String = "",
    val epgId: String = ""
)

data class EpgProgram(
    val title: String,
    val timeRange: String,
    val progress: Int
)

data class AccountProfile(
    val profileName: String,
    val mode: String,
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val m3uUrl: String = ""
)

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val allChannels = mutableListOf<ChannelItem>()
    private val displayedChannels = mutableListOf<ChannelItem>()
    private val favoriteUrls = mutableSetOf<String>()
    private val epgCache = mutableMapOf<String, EpgProgram>()
    private val fetchingEpgIds = mutableSetOf<String>()

    private var miniPlayer: ExoPlayer? = null
    private lateinit var miniPlayerBox: FrameLayout
    private lateinit var miniPlayerView: PlayerView
    private lateinit var tvMiniTitle: TextView
    private var currentPlayingChannel: ChannelItem? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChannelGridAdapter
    private lateinit var tvCategoryFilter: TextView
    private lateinit var etSearch: EditText
    private lateinit var tvStatusBanner: TextView

    private var activeTab = "LIVE"
    private var selectedCategory = "ALL"
    private var isGridMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadFavorites()
        buildMainInterface()
        loadCachedOrFetch()
    }

    @OptIn(UnstableApi::class)
    private fun buildMainInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0D13"))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 14, 24, 10)
        }
        val tvBrand = TextView(this).apply {
            text = "OTTOPLAYER"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSync = TextView(this).apply {
            text = "🔄 Güncelle"
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(22, 12, 22, 12)
            setOnClickListener { forceRefreshActiveAccount() }
        }
        topHeader.addView(tvBrand)
        topHeader.addView(btnSync)
        root.addView(topHeader)

        miniPlayerBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            val h = (195 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply {
                setMargins(18, 4, 18, 10)
            }
        }

        miniPlayerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
        }
        miniPlayerBox.addView(miniPlayerView)

        tvMiniTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(16, 8, 16, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(12, 12, 0, 0) }
        }
        miniPlayerBox.addView(tvMiniTitle)

        val btnCloseMini = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(20, 10, 20, 10)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, 12, 12, 0) }
            setOnClickListener { stopMiniPlayer() }
        }
        miniPlayerBox.addView(btnCloseMini)

        val btnFullScreen = TextView(this).apply {
            text = "⛶ Tam Ekran"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#CC0F172A"))
            setPadding(22, 12, 22, 12)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(0, 0, 12, 12) }
            setOnClickListener {
                currentPlayingChannel?.let { ch ->
                    stopMiniPlayer()
                    openFullPlayer(ch)
                }
            }
        }
        miniPlayerBox.addView(btnFullScreen)
        root.addView(miniPlayerBox)

        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 8, 20, 8)
        }

        tvCategoryFilter = TextView(this).apply {
            text = "❖ Tüm kanallar ▼"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showCategoryPickerDialog() }
        }

        val btnGridIcon = TextView(this).apply {
            text = "⊞"
            textSize = 22f
            setTextColor(Color.parseColor("#A78BFA"))
            setPadding(18, 6, 18, 6)
            setOnClickListener {
                isGridMode = true
                recyclerView.layoutManager = GridLayoutManager(this@MainActivity, 2)
                adapter.notifyDataSetChanged()
            }
        }

        val btnListIcon = TextView(this).apply {
            text = "☰"
            textSize = 22f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(18, 6, 18, 6)
            setOnClickListener {
                isGridMode = false
                recyclerView.layoutManager = LinearLayoutManager(this@MainActivity)
                adapter.notifyDataSetChanged()
            }
        }

        filterBar.addView(tvCategoryFilter)
        filterBar.addView(btnGridIcon)
        filterBar.addView(btnListIcon)
        root.addView(filterBar)

        etSearch = EditText(this).apply {
            hint = "🔍 Kanal, film veya dizi ara..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#161922"))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(20, 0, 20, 10) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilters() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        root.addView(etSearch)

        tvStatusBanner = TextView(this).apply {
            text = "Liste bekleniyor..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(24, 4, 24, 8)
        }
        root.addView(tvStatusBanner)

        recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            setPadding(14, 4, 14, 14)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        adapter = ChannelGridAdapter()
        recyclerView.adapter = adapter
        root.addView(recyclerView)

        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#12141C"))
            setPadding(8, 12, 8, 12)
            weightSum = 5f
        }

        val tabs = listOf(
            Triple("🏠", "Favoriler", "FAV"),
            Triple("📺", "TV", "LIVE"),
            Triple("🎬", "Filmler", "MOVIE"),
            Triple("🍿", "Diziler", "SERIES"),
            Triple("⚙️", "Hesaplar", "SETTINGS")
        )

        for ((icon, label, tabKey) in tabs) {
            val tabItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (tabKey == "SETTINGS") {
                        showUserPanelDialog()
                    } else {
                        activeTab = tabKey
                        selectedCategory = "ALL"
                        etSearch.setText("")
                        tvCategoryFilter.text = when (tabKey) {
                            "FAV" -> "❤️ Favoriler ▼"
                            "LIVE" -> "❖ Tüm kanallar ▼"
                            "MOVIE" -> "❖ Tüm filmler ▼"
                            "SERIES" -> "❖ Tüm diziler ▼"
                            else -> "❖ Tümü ▼"
                        }
                        applyFilters()
                    }
                }
            }
            tabItem.addView(TextView(this).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
            })
            tabItem.addView(TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 11f
                gravity = Gravity.CENTER
            })
            bottomNav.addView(tabItem)
        }

        root.addView(bottomNav)
        setContentView(root)
    }

    inner class ChannelGridAdapter : RecyclerView.Adapter<ChannelGridAdapter.Holder>() {

        inner class Holder(
            val card: LinearLayout,
            val ivLogo: ImageView,
            val tvFallbackLogo: TextView,
            val btnFav: TextView,
            val tvName: TextView,
            val tvEpgTitle: TextView,
            val tvEpgTime: TextView,
            val progressBar: ProgressBar
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#161922"))
                cornerRadius = 24f
            }

            val card = LinearLayout(this@MainActivity).apply {
                orientation = if (isGridMode) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                background = cardBg
                setPadding(16, 16, 16, 16)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(10, 10, 10, 10) }
            }

            val logoFrame = FrameLayout(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#1F2330"))
                layoutParams = if (isGridMode) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 210)
                } else {
                    LinearLayout.LayoutParams(180, 140).apply { rightMargin = 16 }
                }
            }

            val ivLogo = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(20, 20, 20, 20)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            val tvFallbackLogo = TextView(this@MainActivity).apply {
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(12, 12, 12, 12)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            val btnFav = TextView(this@MainActivity).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#66000000"))
                setPadding(12, 4, 12, 4)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply { setMargins(0, 8, 8, 0) }
            }

            logoFrame.addView(tvFallbackLogo)
            logoFrame.addView(ivLogo)
            logoFrame.addView(btnFav)
            card.addView(logoFrame)

            val infoBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = if (isGridMode) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 12
                    }
                } else {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            }

            val tvName = TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val tvEpgTitle = TextView(this@MainActivity).apply {
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 4, 0, 2)
            }

            val tvEpgTime = TextView(this@MainActivity).apply {
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
            }

            val progressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 35
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10).apply {
                    topMargin = 6
                }
            }

            infoBox.addView(tvName)
            infoBox.addView(tvEpgTitle)
            infoBox.addView(tvEpgTime)
            infoBox.addView(progressBar)
            card.addView(infoBox)

            return Holder(card, ivLogo, tvFallbackLogo, btnFav, tvName, tvEpgTitle, tvEpgTime, progressBar)
        }

        override fun getItemCount(): Int = displayedChannels.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = displayedChannels[position]
            holder.tvName.text = item.name
            holder.tvFallbackLogo.text = item.name.take(14)

            if (item.logo.startsWith("http")) {
                holder.ivLogo.visibility = View.VISIBLE
                holder.ivLogo.load(item.logo) {
                    crossfade(true)
                    listener(
                        onSuccess = { _, _ -> holder.tvFallbackLogo.visibility = View.GONE },
                        onError = { _, _ ->
                            holder.ivLogo.visibility = View.GONE
                            holder.tvFallbackLogo.visibility = View.VISIBLE
                        }
                    )
                }
            } else {
                holder.ivLogo.visibility = View.GONE
                holder.tvFallbackLogo.visibility = View.VISIBLE
            }

            val isFav = favoriteUrls.contains(item.url)
            holder.btnFav.text = if (isFav) "❤️" else "🤍"
            holder.btnFav.setOnClickListener {
                if (favoriteUrls.contains(item.url)) favoriteUrls.remove(item.url)
                else favoriteUrls.add(item.url)
                saveFavorites()
                notifyItemChanged(position)
            }

            val epg = epgCache[item.streamId] ?: epgCache[item.epgId]
            if (epg != null) {
                holder.tvEpgTitle.visibility = View.VISIBLE
                holder.tvEpgTime.visibility = View.VISIBLE
                holder.progressBar.visibility = View.VISIBLE
                holder.tvEpgTitle.text = epg.title
                holder.tvEpgTime.text = epg.timeRange
                holder.progressBar.progress = epg.progress
            } else {
                holder.tvEpgTitle.text = item.category
                holder.tvEpgTime.text = if (item.type == "LIVE") "Canlı Yayın" else item.type
                holder.progressBar.visibility = View.GONE
                if (item.type == "LIVE" && item.streamId.isNotEmpty()) {
                    fetchShortEpgForChannel(item.streamId, position)
                }
            }

            holder.card.setOnClickListener {
                if (item.type == "LIVE") {
    
