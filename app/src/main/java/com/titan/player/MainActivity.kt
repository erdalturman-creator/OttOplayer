package com.titan.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ChannelItem(
    val name: String,
    val url: String,
    val category: String,
    val type: String
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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val allChannels = mutableListOf<ChannelItem>()
    private lateinit var tvStatus: TextView
    private lateinit var tvLiveCount: TextView
    private lateinit var tvMovieCount: TextView
    private lateinit var tvSeriesCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDashboardUI()
        loadActiveAccount()
    }

    private fun setupDashboardUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(48, 36, 48, 36)
        }

        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }

        val title = TextView(this).apply {
            text = "OTTOPLAYER"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        topBar.addView(title)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }
        }

        val btnPanel = Button(this).apply {
            text = "👤 Kullanıcı Paneli"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(24, 12, 24, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 16 }
            setOnClickListener { showUserPanelDialog() }
        }

        val btnAdd = Button(this).apply {
            text = "+ Hesap / M3U Ekle"
            setBackgroundColor(Color.parseColor("#1A2235"))
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12)
            setOnClickListener { showAccountDialog() }
        }

        btnRow.addView(btnPanel)
        btnRow.addView(btnAdd)
        topBar.addView(btnRow)
        root.addView(topBar)

        tvStatus = TextView(this).apply {
            text = "Aktif Liste: Henüz liste eklenmedi (Sağ üstten ekleyin)"
            setTextColor(Color.parseColor("#A0AEC0"))
            setBackgroundColor(Color.parseColor("#131B2E"))
            setPadding(28, 18, 28, 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 28 }
        }
        root.addView(tvStatus)

        val cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        tvLiveCount = TextView(this)
        tvMovieCount = TextView(this)
        tvSeriesCount = TextView(this)

        cardsRow.addView(createCategoryCard("📺", "CANLI TV", tvLiveCount) {
            openChannelList("LIVE", "Canlı TV Kanalları")
        })
        cardsRow.addView(createCategoryCard("🎬", "FİLMLER", tvMovieCount) {
            openChannelList("MOVIE", "Filmler")
        })
        cardsRow.addView(createCategoryCard("🍿", "DİZİLER", tvSeriesCount) {
            openChannelList("SERIES", "Diziler")
        })

        root.addView(cardsRow)
        setContentView(root)
    }

    private fun createCategoryCard(icon: String, title: String, countView: TextView, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#152036"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(12, 0, 12, 0)
            }
            setOnClickListener { onClick() }

            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 38f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 8)
            })
            countView.apply {
                text = "0 İçerik"
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 13f
                gravity = Gravity.CENTER
            }
            addView(countView)
        }
    }

    private fun getSavedProfiles(): MutableList<AccountProfile> {
        val prefs = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE)
        val json = prefs.getString("PROFILES_JSON", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<AccountProfile>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    private fun saveProfiles(list: List<AccountProfile>, activeIndex: Int) {
        getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).edit()
            .putString("PROFILES_JSON", gson.toJson(list))
            .putInt("ACTIVE_INDEX", activeIndex)
            .apply()
    }

    private fun showUserPanelDialog() {
        val profiles = getSavedProfiles()
        val prefs = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE)
        val activeIdx = prefs.getInt("ACTIVE_INDEX", 0)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 12)
        }

        if (profiles.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Kayıtlı hesap bulunmuyor. Sağ üstten '+ Hesap / M3U Ekle' butonunu kullanın."
                setPadding(16, 24, 16, 24)
            })
        } else {
            val active = profiles.getOrNull(activeIdx)
            val infoText = TextView(this).apply {
                text = if (active != null) {
                    "Aktif Hesap: ${active.profileName}\nTür: ${active.mode}\n" +
                    if (active.mode == "XTREAM") "Sunucu: ${active.server}\nKullanıcı: ${active.user}" else "M3U: ${active.m3uUrl.take(45)}..."
                } else "Aktif hesap seçilmedi"
                setPadding(16, 12, 16, 20)
            }
            container.addView(infoText)

            val listView = ListView(this)
            val labels = profiles.mapIndexed { idx, p ->
                val mark = if (idx == activeIdx) "✓ [AKTİF] " else "• "
                "$mark${p.profileName} (${p.mode})"
            }
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

            val dialog = AlertDialog.Builder(this)
                .setTitle("Kullanıcı & Hesap Paneli")
                .setView(container)
                .setPositiveButton("Listeyi Yenile") { _, _ -> loadActiveAccount() }
                .setNeutralButton("Aktif Hesabı Sil") { _, _ ->
                    if (activeIdx in profiles.indices) {
                        profiles.removeAt(activeIdx)
                        saveProfiles(profiles, 0)
                        Toast.makeText(this, "Hesap silindi", Toast.LENGTH_SHORT).show()
                        if (profiles.isNotEmpty()) loadActiveAccount()
                    }
                }
                .setNegativeButton("Kapat", null)
                .create()

            listView.setOnItemClickListener { _, _, pos, _ ->
                saveProfiles(profiles, pos)
                dialog.dismiss()
                loadActiveAccount()
            }
            container.addView(listView)
            dialog.show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Kullanıcı & Hesap Paneli")
            .setView(container)
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showAccountDialog() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 12)
        }

        val etName = EditText(this).apply { hint = "Hesap Adı (Örn: Ev IPTV)" }
        val etServer = EditText(this).apply { hint = "Xtream Sunucu URL (http://...)" }
        val etUser = EditText(this).apply { hint = "Kullanıcı Adı" }
        val etPass = EditText(this).apply { hint = "Şifre" }
        val divider = TextView(this).apply {
            text = "\n— VEYA DOĞRUDAN M3U LİNKİ —\n"
            gravity = Gravity.CENTER
        }
        val etM3u = EditText(this).apply { hint = "M3U / M3U8 Playlist URL" }

        layout.addView(etName)
        layout.addView(etServer)
        layout.addView(etUser)
        layout.addView(etPass)
        layout.addView(divider)
        layout.addView(etM3u)
        scroll.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("Yeni Hesap / M3U Ekle")
            .setView(scroll)
            .setPositiveButton("Kaydet ve Bağlan") { _, _ ->
                val pName = etName.text.toString().trim().ifEmpty { "Hesap ${getSavedProfiles().size + 1}" }
                val m3u = etM3u.text.toString().trim()
                val srv = etServer.text.toString().trim().removeSuffix("/")
                val usr = etUser.text.toString().trim()
                val pwd = etPass.text.toString().trim()

                val profiles = getSavedProfiles()
                if (m3u.isNotEmpty()) {
                    profiles.add(AccountProfile(pName, "M3U", m3uUrl = m3u))
                    saveProfiles(profiles, profiles.lastIndex)
                    fetchM3uPlaylist(m3u, pName)
                } else if (srv.isNotEmpty() && usr.isNotEmpty()) {
                    profiles.add(AccountProfile(pName, "XTREAM", server = srv, user = usr, pass = pwd))
                    saveProfiles(profiles, profiles.lastIndex)
                    fetchXtreamPlaylist(srv, usr, pwd, pName)
                } else {
                    Toast.makeText(this, "Lütfen geçerli bilgi girin", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun loadActiveAccount() {
        val profiles = getSavedProfiles()
        val prefs = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE)
        val idx = prefs.getInt("ACTIVE_INDEX", 0)
        val active = profiles.getOrNull(idx)

        if (active != null) {
            if (active.mode == "M3U") fetchM3uPlaylist(active.m3uUrl, active.profileName)
            else fetchXtreamPlaylist(active.server, active.user, active.pass, active.profileName)
            return
        }

        when (prefs.getString("MODE", "")) {
            "M3U" -> prefs.getString("M3U_URL", "")?.let { if (it.isNotEmpty()) fetchM3uPlaylist(it, "M3U Playlist") }
            "XTREAM" -> {
                val s = prefs.getString("SERVER", "") ?: ""
                val u = prefs.getString("USER", "") ?: ""
                val p = prefs.getString("PASS", "") ?: ""
                if (s.isNotEmpty()) fetchXtreamPlaylist(s, u, p, u)
            }
        }
    }

    private fun fetchM3uPlaylist(url: String, sourceLabel: String) {
        tvStatus.text = "Liste indiriliyor ve kategorize ediliyor..."
        Thread {
            try {
                val req = Request.Builder().url(url).build()
                val body = client.newCall(req).execute().body?.string() ?: ""
                val parsed = mutableListOf<ChannelItem>()
                var currentName = "Bilinmeyen Kanal"
                var currentGroup = "Genel"
                val episodeRegex = Regex("""(?i)\b(s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{1,3}|sezon|bölüm|episode)\b""")

                for (line in body.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXTINF:")) {
                        currentName = trimmed.substringAfterLast(",").trim()
                        val grpMatch = Regex("""group-title="([^"]*)"""").find(trimmed)
                        currentGroup = grpMatch?.groupValues?.get(1) ?: "Genel"
                    } else if (trimmed.startsWith("http")) {
                        val lowerUrl = trimmed.lowercase()
                        val lowerGrp = currentGroup.lowercase()
                        val isSeries = lowerUrl.contains("/series/") ||
                                lowerGrp.contains("dizi") ||
                                lowerGrp.contains("series") ||
                                episodeRegex.containsMatchIn(currentName)

                        val isMovie = !isSeries && (
                                lowerUrl.contains("/movie/") ||
                                lowerGrp.contains("film") ||
                                lowerGrp.contains("movie") ||
                                lowerGrp.contains("sinema") ||
                                lowerGrp.contains("vod") ||
                                lowerUrl.endsWith(".mp4") ||
                                lowerUrl.endsWith(".mkv") ||
                                lowerUrl.endsWith(".avi")
                        )

                        val type = when {
                            isSeries -> "SERIES"
                            isMovie -> "MOVIE"
                            else -> "LIVE"
                        }
                        parsed.add(ChannelItem(currentName, trimmed, currentGroup, type))
                    }
                }

                runOnUiThread {
                    allChannels.clear()
                    allChannels.addAll(parsed)
                    updateCounters(sourceLabel)
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Hata: Liste yüklenemedi (${e.message})" }
            }
        }.start()
    }

    private fun fetchXtreamPlaylist(server: String, user: String, pass: String, label: String) {
        val fullM3u = "$server/get.php?username=$user&password=$pass&type=m3u_plus&output=ts"
        fetchM3uPlaylist(fullM3u, label)
    }

    private fun updateCounters(sourceName: String) {
        val live = allChannels.count { it.type == "LIVE" }
        val movie = allChannels.count { it.type == "MOVIE" }
        val series = allChannels.count { it.type == "SERIES" }
        tvStatus.text = "Aktif Hesap: $sourceName | Toplam ${allChannels.size} içerik"
        tvLiveCount.text = "$live Kanal"
        tvMovieCount.text = "$movie Film"
        tvSeriesCount.text = "$series Dizi"
    }

    private fun openChannelList(type: String, title: String) {
        val filtered = allChannels.filter { it.type == type }
        if (filtered.isEmpty()) {
            Toast.makeText(this, "Bu kategoride içerik bulunamadı!", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val searchBox = EditText(this).apply {
            hint = "İsim veya kategori ara..."
        }
        val listView = ListView(this)
        var currentList = filtered

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, currentList.map { "${it.name} [${it.category}]" })

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().lowercase()
                currentList = filtered.filter { it.name.lowercase().contains(q) || it.category.lowercase().contains(q) }
                listView.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, currentList.map { "${it.name} [${it.category}]" })
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = currentList[position]
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("STREAM_URL", selected.url)
                putExtra("STREAM_TITLE", selected.name)
            }
            startActivity(intent)
        }

        container.addView(searchBox)
        container.addView(listView)

        AlertDialog.Builder(this)
            .setTitle("$title (${filtered.size})")
            .setView(container)
            .setNegativeButton("Kapat", null)
            .show()
    }
                                             }
                                             
