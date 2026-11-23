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
import android.view.WindowManager
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

data class ChannelItem(val name: String, val url: String, val category: String, val type: String, val logo: String = "", val streamId: String = "", val epgId: String = "")
data class EpgProgram(val title: String, val timeRange: String, val progress: Int)
data class AccountProfile(val profileName: String, val mode: String, val server: String = "", val user: String = "", val pass: String = "", val m3uUrl: String = "")

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
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
        favoriteUrls.addAll(getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).getStringSet("FAVORITES_SET", emptySet()) ?: emptySet())
        buildMainInterface()
        loadCachedOrFetch()
    }
// P1
    @OptIn(UnstableApi::class)
    private fun buildMainInterface() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B0D13")) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        val topHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 14, 24, 10) }
        topHeader.addView(TextView(this).apply { text = "OTTOPLAYER"; setTextColor(Color.parseColor("#38BDF8")); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        topHeader.addView(TextView(this).apply { text = "🔄 Güncelle"; setTextColor(Color.WHITE); textSize = 13f; setBackgroundColor(Color.parseColor("#1E293B")); setPadding(22, 12, 22, 12); setOnClickListener { forceRefreshActiveAccount() } })
        root.addView(topHeader)

        miniPlayerBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, (195 * resources.displayMetrics.density).toInt()).apply { setMargins(18, 4, 18, 10) }
        }
        miniPlayerView = PlayerView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1); useController = false }
        miniPlayerBox.addView(miniPlayerView)

        tvMiniTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setBackgroundColor(Color.parseColor("#99000000")); setPadding(16, 8, 16, 8)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(12, 12, 0, 0) }
        }
        miniPlayerBox.addView(tvMiniTitle)

        miniPlayerBox.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 15f; setBackgroundColor(Color.parseColor("#AA000000")); setPadding(20, 10, 20, 10)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 12, 12, 0) }
            setOnClickListener { stopMiniPlayer() }
        })

        miniPlayerBox.addView(TextView(this).apply {
            text = "⛶ Tam Ekran"; setTextColor(Color.parseColor("#38BDF8")); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setBackgroundColor(Color.parseColor("#CC0F172A")); setPadding(22, 12, 22, 12)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 12, 12) }
            setOnClickListener { currentPlayingChannel?.let { stopMiniPlayer(); openFullPlayer(it) } }
        })
        root.addView(miniPlayerBox)

        val filterBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 8, 20, 8) }
        tvCategoryFilter = TextView(this).apply {
            text = "❖ Tüm kanallar ▼"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { showCategoryPickerDialog() }
        }
        filterBar.addView(tvCategoryFilter)
        filterBar.addView(TextView(this).apply { text = "⊞"; textSize = 22f; setTextColor(Color.parseColor("#A78BFA")); setPadding(18, 6, 18, 6); setOnClickListener { isGridMode = true; recyclerView.layoutManager = GridLayoutManager(this@MainActivity, 2); adapter.notifyDataSetChanged() } })
        filterBar.addView(TextView(this).apply { text = "☰"; textSize = 22f; setTextColor(Color.parseColor("#94A3B8")); setPadding(18, 6, 18, 6); setOnClickListener { isGridMode = false; recyclerView.layoutManager = LinearLayoutManager(this@MainActivity); adapter.notifyDataSetChanged() } })
        root.addView(filterBar)

        etSearch = EditText(this).apply {
            hint = "🔍 Kanal, film veya dizi ara..."; setHintTextColor(Color.parseColor("#64748B")); setTextColor(Color.WHITE); textSize = 14f; setBackgroundColor(Color.parseColor("#161922")); setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(20, 0, 20, 10) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilters() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        root.addView(etSearch)

        tvStatusBanner = TextView(this).apply { text = "Liste bekleniyor..."; setTextColor(Color.parseColor("#94A3B8")); textSize = 12f; setPadding(24, 4, 24, 8) }
        root.addView(tvStatusBanner)

        recyclerView = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@MainActivity, 2); setPadding(14, 4, 14, 14); clipToPadding = false; layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        adapter = ChannelGridAdapter()
        recyclerView.adapter = adapter
        root.addView(recyclerView)

        val bottomNav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#12141C")); setPadding(8, 12, 8, 12); weightSum = 5f }
        val tabs = listOf(Triple("🏠", "Favoriler", "FAV"), Triple("📺", "TV", "LIVE"), Triple("🎬", "Filmler", "MOVIE"), Triple("🍿", "Diziler", "SERIES"), Triple("⚙️", "Hesaplar", "SETTINGS"))
        for ((icon, label, tabKey) in tabs) {
            val tabItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener {
                    if (tabKey == "SETTINGS") showUserPanelDialog()
                    else {
                        activeTab = tabKey; selectedCategory = "ALL"; etSearch.setText("")
                        tvCategoryFilter.text = when (tabKey) { "FAV" -> "❤️ Favoriler ▼"; "LIVE" -> "❖ Tüm kanallar ▼"; "MOVIE" -> "❖ Tüm filmler ▼"; else -> "❖ Tüm diziler ▼" }
                        applyFilters()
                    }
                }
            }
            tabItem.addView(TextView(this).apply { text = icon; textSize = 18f; gravity = Gravity.CENTER })
            tabItem.addView(TextView(this).apply { text = label; setTextColor(Color.parseColor("#CBD5E1")); textSize = 11f; gravity = Gravity.CENTER })
            bottomNav.addView(tabItem)
        }
        root.addView(bottomNav)
        setContentView(root)
    }
// P2
    inner class ChannelGridAdapter : RecyclerView.Adapter<ChannelGridAdapter.Holder>() {
        inner class Holder(val card: LinearLayout, val ivLogo: ImageView, val tvFallback: TextView, val btnFav: TextView, val tvName: TextView, val tvEpgTitle: TextView, val tvEpgTime: TextView, val progress: ProgressBar) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(this@MainActivity).apply {
                orientation = if (isGridMode) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(Color.parseColor("#161922")); cornerRadius = 24f }
                setPadding(16, 16, 16, 16)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(10, 10, 10, 10) }
            }
            val logoFrame = FrameLayout(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#1F2330"))
                layoutParams = if (isGridMode) LinearLayout.LayoutParams(-1, 210) else LinearLayout.LayoutParams(180, 140).apply { rightMargin = 16 }
            }
            val ivLogo = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(20, 20, 20, 20); layoutParams = FrameLayout.LayoutParams(-1, -1) }
            val tvFallback = TextView(this@MainActivity).apply { setTextColor(Color.parseColor("#94A3B8")); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 2; setPadding(12, 12, 12, 12); layoutParams = FrameLayout.LayoutParams(-1, -1) }
            val btnFav = TextView(this@MainActivity).apply { textSize = 16f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#66000000")); setPadding(12, 4, 12, 4); layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 8, 8, 0) } }
            logoFrame.addView(tvFallback); logoFrame.addView(ivLogo); logoFrame.addView(btnFav)
            card.addView(logoFrame)

            val infoBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = if (isGridMode) LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 } else LinearLayout.LayoutParams(0, -2, 1f)
            }
            val tvName = TextView(this@MainActivity).apply { setTextColor(Color.WHITE); textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val tvEpgTitle = TextView(this@MainActivity).apply { setTextColor(Color.parseColor("#CBD5E1")); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 4, 0, 2) }
            val tvEpgTime = TextView(this@MainActivity).apply { setTextColor(Color.parseColor("#94A3B8")); textSize = 11f }
            val progress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; layoutParams = LinearLayout.LayoutParams(-1, 10).apply { topMargin = 6 } }
            infoBox.addView(tvName); infoBox.addView(tvEpgTitle); infoBox.addView(tvEpgTime); infoBox.addView(progress)
            card.addView(infoBox)
            return Holder(card, ivLogo, tvFallback, btnFav, tvName, tvEpgTitle, tvEpgTime, progress)
        }

        override fun getItemCount() = displayedChannels.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = displayedChannels[position]
            holder.tvName.text = item.name; holder.tvFallback.text = item.name.take(14)
            if (item.logo.startsWith("http")) {
                holder.ivLogo.visibility = View.VISIBLE
                holder.ivLogo.load(item.logo) {
                    crossfade(true)
                    listener(onSuccess = { _, _ -> holder.tvFallback.visibility = View.GONE }, onError = { _, _ -> holder.ivLogo.visibility = View.GONE; holder.tvFallback.visibility = View.VISIBLE })
                }
            } else { holder.ivLogo.visibility = View.GONE; holder.tvFallback.visibility = View.VISIBLE }

            holder.btnFav.text = if (favoriteUrls.contains(item.url)) "❤️" else "🤍"
            holder.btnFav.setOnClickListener {
                if (favoriteUrls.contains(item.url)) favoriteUrls.remove(item.url) else favoriteUrls.add(item.url)
                getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).edit().putStringSet("FAVORITES_SET", favoriteUrls.toSet()).apply()
                notifyItemChanged(position)
            }

            val epg = epgCache[item.streamId] ?: epgCache[item.epgId]
            if (epg != null) {
                holder.tvEpgTitle.text = epg.title; holder.tvEpgTime.text = epg.timeRange; holder.progress.progress = epg.progress; holder.progress.visibility = View.VISIBLE
            } else {
                holder.tvEpgTitle.text = item.category; holder.tvEpgTime.text = if (item.type == "LIVE") "Canlı Yayın" else item.type; holder.progress.visibility = View.GONE
                if (item.type == "LIVE" && item.streamId.isNotEmpty()) fetchShortEpgForChannel(item.streamId, position)
            }

            holder.card.setOnClickListener { if (item.type == "LIVE") startMiniPlayer(item) else { stopMiniPlayer(); openFullPlayer(item) } }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startMiniPlayer(item: ChannelItem) {
        currentPlayingChannel = item; tvMiniTitle.text = item.name; miniPlayerBox.visibility = View.VISIBLE
        if (miniPlayer == null) {
            miniPlayer = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)).build()
            miniPlayerView.player = miniPlayer
        }
        miniPlayer?.apply { setMediaItem(MediaItem.fromUri(item.url)); prepare(); playWhenReady = true }
    }

    private fun stopMiniPlayer() { miniPlayer?.stop(); miniPlayer?.release(); miniPlayer = null; miniPlayerBox.visibility = View.GONE }

    private fun openFullPlayer(item: ChannelItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply { putExtra("STREAM_URL", item.url); putExtra("STREAM_TITLE", item.name) })
    }
// P3
    private fun fetchShortEpgForChannel(streamId: String, adapterPos: Int) {
        if (fetchingEpgIds.contains(streamId)) return
        val active = getSavedProfiles().getOrNull(getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).getInt("ACTIVE_INDEX", 0)) ?: return
        if (active.mode != "XTREAM") return
        fetchingEpgIds.add(streamId)
        Thread {
            try {
                val res = httpGetString("${active.server}/player_api.php?username=${active.user}&password=${active.pass}&action=get_short_epg&stream_id=$streamId&limit=1")
                val listings = JsonParser.parseString(res).asJsonObject.getAsJsonArray("epg_listings") ?: return@Thread
                if (listings.size() > 0) {
                    val first = listings[0].asJsonObject
                    val raw = first.get("title")?.asString ?: ""
                    val title = try { String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { raw }
                    val s = first.get("start_timestamp")?.asLong ?: 0L; val e = first.get("stop_timestamp")?.asLong ?: 0L
                    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    val range = if (s > 0 && e > 0) "${fmt.format(Date(s * 1000))} – ${fmt.format(Date(e * 1000))}" else ""
                    val now = System.currentTimeMillis() / 1000
                    val prog = if (e > s && now >= s) (((now - s) * 100) / (e - s)).toInt().coerceIn(5, 100) else 25
                    if (title.isNotEmpty()) {
                        epgCache[streamId] = EpgProgram(title, range, prog)
                        runOnUiThread { if (adapterPos in displayedChannels.indices) adapter.notifyItemChanged(adapterPos) }
                    }
                }
            } catch (_: Exception) {} finally { fetchingEpgIds.remove(streamId) }
        }.start()
    }

    private fun showCategoryPickerDialog() {
        val base = if (activeTab == "FAV") allChannels.filter { favoriteUrls.contains(it.url) } else allChannels.filter { it.type == activeTab }
        val cats = listOf("Tümü (${base.size})") + base.map { it.category }.distinct().sorted()
        AlertDialog.Builder(this).setTitle("Kategori Seçin").setItems(cats.toTypedArray()) { _, w ->
            selectedCategory = if (w == 0) "ALL" else cats[w]
            tvCategoryFilter.text = if (w == 0) "❖ Tümü (${base.size}) ▼" else "❖ $selectedCategory ▼"
            applyFilters()
        }.show()
    }

    private fun applyFilters() {
        val q = etSearch.text.toString().trim().lowercase()
        displayedChannels.clear()
        displayedChannels.addAll(allChannels.filter {
            (if (activeTab == "FAV") favoriteUrls.contains(it.url) else it.type == activeTab) &&
            (selectedCategory == "ALL" || it.category == selectedCategory) &&
            (q.isEmpty() || it.name.lowercase().contains(q) || it.category.lowercase().contains(q))
        })
        adapter.notifyDataSetChanged()
        tvStatusBanner.text = "${displayedChannels.size} içerik listeleniyor"
    }

    private fun getCacheFile(index: Int) = File(filesDir, "playlist_cache_$index.json")
    private fun getSavedProfiles(): MutableList<AccountProfile> = gson.fromJson(getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).getString("PROFILES_JSON", "[]"), object : TypeToken<MutableList<AccountProfile>>() {}.type) ?: mutableListOf()
    private fun saveProfiles(list: List<AccountProfile>, activeIndex: Int) = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).edit().putString("PROFILES_JSON", gson.toJson(list)).putInt("ACTIVE_INDEX", activeIndex).apply()

    private fun loadCachedOrFetch() {
        val profiles = getSavedProfiles(); val idx = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).getInt("ACTIVE_INDEX", 0)
        if (profiles.getOrNull(idx) == null) { tvStatusBanner.text = "Sağ alttaki 'Hesaplar' sekmesinden liste ekleyin."; return }
        val file = getCacheFile(idx)
        if (file.exists()) {
            val cached: List<ChannelItem>? = try { gson.fromJson(file.readText(), object : TypeToken<List<ChannelItem>>() {}.type) } catch (_: Exception) { null }
            if (!cached.isNullOrEmpty()) { allChannels.clear(); allChannels.addAll(cached); applyFilters(); return }
        }
        forceRefreshActiveAccount()
    }

    private fun forceRefreshActiveAccount() {
        val profiles = getSavedProfiles(); val idx = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).getInt("ACTIVE_INDEX", 0)
        val active = profiles.getOrNull(idx) ?: run { showAccountDialog(); return }
        if (active.mode == "XTREAM") fetchXtreamFastApi(active, idx) else fetchM3uStreamed(active.m3uUrl, active.profileName, idx)
    }

    private fun fetchXtreamFastApi(acc: AccountProfile, idx: Int) {
        runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); tvStatusBanner.text = "1/3: Canlı TV kanalları indiriliyor..." }
        Thread {
            try {
                val baseApi = "${acc.server}/player_api.php?username=${acc.user}&password=${acc.pass}"
                val result = mutableListOf<ChannelItem>()

                val liveCats = fetchCategoryMap("$baseApi&action=get_live_categories")
                val liveJson = httpGetString("$baseApi&action=get_live_streams")
                if (liveJson.startsWith("[")) for (el in JsonParser.parseString(liveJson).asJsonArray) {
                    val o = el.asJsonObject; val name = o.get("name")?.asString ?: continue; val sid = o.get("stream_id")?.asString ?: continue
                    result.add(ChannelItem(name, "${acc.server}/live/${acc.user}/${acc.pass}/$sid.ts", liveCats[o.get("category_id")?.asString ?: ""] ?: "Canlı TV", "LIVE", o.get("stream_icon")?.asString ?: "", sid, o.get("epg_channel_id")?.asString ?: ""))
                }
                runOnUiThread { allChannels.clear(); allChannels.addAll(result); applyFilters(); tvStatusBanner.text = "2/3: Filmler indiriliyor (${result.size} kanal)..." }

                val vodCats = fetchCategoryMap("$baseApi&action=get_vod_categories")
                val vodJson = httpGetString("$baseApi&action=get_vod_streams")
                if (vodJson.startsWith("[")) for (el in JsonParser.parseString(vodJson).asJsonArray) {
                    val o = el.asJsonObject; val name = o.get("name")?.asString ?: continue; val sid = o.get("stream_id")?.asString ?: continue; val ext = o.get("container_extension")?.asString ?: "mp4"
                    result.add(ChannelItem(name, "${acc.server}/movie/${acc.user}/${acc.pass}/$sid.$ext", vodCats[o.get("category_id")?.asString ?: ""] ?: "Filmler", "MOVIE", o.get("stream_icon")?.asString ?: "", sid))
                }
                runOnUiThread { allChannels.clear(); allChannels.addAll(result); applyFilters(); tvStatusBanner.text = "3/3: Diziler indiriliyor (${result.size} içerik)..." }

                val serCats = fetchCategoryMap("$baseApi&action=get_series_categories")
                val serJson = httpGetString("$baseApi&action=get_series")
                if (serJson.startsWith("[")) for (el in JsonParser.parseString(serJson).asJsonArray) {
                    val o = el.asJsonObject; val name = o.get("name")?.asString ?: continue; val sid = o.get("series_id")?.asString ?: continue
                    result.add(ChannelItem(name, "${acc.server}/series/${acc.user}/${acc.pass}/$sid.mp4", serCats[o.get("category_id")?.asString ?: ""] ?: "Diziler", "SERIES", o.get("cover")?.asString ?: "", sid))
                }

                getCacheFile(idx).writeText(gson.toJson(result))
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); allChannels.clear(); allChannels.addAll(result); applyFilters(); Toast.makeText(this, "${acc.profileName} tamamlandı (${result.size})", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); tvStatusBanner.text = "Hata: ${e.message}" } }
        }.start()
    }

    private fun fetchCategoryMap(url: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val res = httpGetString(url)
            if (res.startsWith("[")) for (el in JsonParser.parseString(res).asJsonArray) {
                val o = el.asJsonObject; val id = o.get("category_id")?.asString ?: continue; map[id] = o.get("category_name")?.asString ?: "Genel"
            }
        } catch (_: Exception) {}
        return map
    }

    private fun httpGetString(url: String): String {
        client.newCall(Request.Builder().url(url).header("User-Agent", "VLC/3.0.18").header("Connection", "close").build()).execute().use { res ->
            if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
            return res.body?.string() ?: ""
        }
    }

    private fun fetchM3uStreamed(url: String, label: String, idx: Int) {
        runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); tvStatusBanner.text = "M3U indiriliyor..." }
        Thread {
            try {
                val parsed = mutableListOf<ChannelItem>()
                client.newCall(Request.Builder().url(url).header("User-Agent", "VLC/3.0.18").header("Connection", "close").build()).execute().use { res ->
                    if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
                    var name = "Bilinmeyen"; var grp = "Genel"; var logo = ""; var epgId = ""
                    val epRegex = Regex("""(?i)\b(s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{1,3}|sezon|bölüm|episode)\b""")
                    res.body?.byteStream()?.bufferedReader()?.forEachLine { line ->
                        val t = line.trim()
                        if (t.startsWith("#EXTINF:")) {
                            name = t.substringAfterLast(",").trim(); grp = Regex("""group-title="([^"]*)"""").find(t)?.groupValues?.get(1) ?: "Genel"
                            logo = Regex("""tvg-logo="([^"]*)"""").find(t)?.groupValues?.get(1) ?: ""; epgId = Regex("""tvg-id="([^"]*)"""").find(t)?.groupValues?.get(1) ?: ""
                        } else if (t.startsWith("http")) {
                            val lu = t.lowercase(); val lg = grp.lowercase()
                            val isS = lu.contains("/series/") || lg.contains("dizi") || lg.contains("series") || epRegex.containsMatchIn(name)
                            val isM = !isS && (lu.contains("/movie/") || lg.contains("film") || lg.contains("movie") || lg.contains("sinema") || lg.contains("vod") || lu.endsWith(".mp4") || lu.endsWith(".mkv"))
                            parsed.add(ChannelItem(name, t, grp, if (isS) "SERIES" else if (isM) "MOVIE" else "LIVE", logo, Regex("""/(\d+)\.(ts|m3u8|mp4|mkv)$""").find(lu)?.groupValues?.get(1) ?: "", epgId))
                        }
                    }
                }
                getCacheFile(idx).writeText(gson.toJson(parsed))
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); allChannels.clear(); allChannels.addAll(parsed); applyFilters() }
            } catch (e: Exception) { runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); tvStatusBanner.text = "Hata: ${e.message}" } }
        }.start()
    }

    private fun showUserPanelDialog() {
        val profiles = getSavedProfiles(); val activeIdx = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).getInt("ACTIVE_INDEX", 0)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 20, 32, 12) }
        val lv = ListView(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, profiles.mapIndexed { i, p -> "${if (i == activeIdx) "✓ " else "• "}${p.profileName}" }) }
        val dlg = AlertDialog.Builder(this).setTitle("Hesaplar").setView(container).setPositiveButton("+ Yeni Ekle") { _, _ -> showAccountDialog() }
            .setNeutralButton("Sil") { _, _ -> if (activeIdx in profiles.indices) { getCacheFile(activeIdx).delete(); profiles.removeAt(activeIdx); saveProfiles(profiles, 0); allChannels.clear(); applyFilters() } }
            .setNegativeButton("Kapat", null).create()
        lv.setOnItemClickListener { _, _, pos, _ -> saveProfiles(profiles, pos); dlg.dismiss(); loadCachedOrFetch() }
        container.addView(lv); dlg.show()
    }

    private fun showAccountDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 12) }
        val etName = EditText(this).apply { hint = "Hesap Adı" }; val etSrv = EditText(this).apply { hint = "Xtream Sunucu (http://...)" }
        val etUsr = EditText(this).apply { hint = "Kullanıcı Adı" }; val etPwd = EditText(this).apply { hint = "Şifre" }; val etM3u = EditText(this).apply { hint = "veya M3U URL" }
        layout.addView(etName); layout.addView(etSrv); layout.addView(etUsr); layout.addView(etPwd); layout.addView(etM3u)
        AlertDialog.Builder(this).setTitle("Hesap Ekle").setView(ScrollView(this).apply { addView(layout) }).setPositiveButton("Kaydet") { _, _ ->
            val pName = etName.text.toString().trim().ifEmpty { "IPTV" }; val m3u = etM3u.text.toString().trim(); val srv = etSrv.text.toString().trim().removeSuffix("/"); val usr = etUsr.text.toString().trim(); val pwd = etPwd.text.toString().trim()
            val profiles = getSavedProfiles()
            if (m3u.isNotEmpty()) { profiles.add(AccountProfile(pName, "M3U", m3uUrl = m3u)); saveProfiles(profiles, profiles.lastIndex); fetchM3uStreamed(m3u, pName, profiles.lastIndex) }
            else if (srv.isNotEmpty()) { val acc = AccountProfile(pName, "XTREAM", srv, usr, pwd); profiles.add(acc); saveProfiles(profiles, profiles.lastIndex); fetchXtreamFastApi(acc, profiles.lastIndex) }
        }.setNegativeButton("İptal", null).show()
    }

    override fun onStop() { super.onStop(); stopMiniPlayer() }
}
// SON
