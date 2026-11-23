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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ChannelItem(
    val name: String,
    val url: String,
    val category: String,
    val type: String
)

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val allChannels = mutableListOf<ChannelItem>()
    private lateinit var tvStatus: TextView
    private lateinit var tvLiveCount: TextView
    private lateinit var tvMovieCount: TextView
    private lateinit var tvSeriesCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDashboardUI()
        loadSavedAccount()
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
            ).apply { bottomMargin = 32 }
        }

        val title = TextView(this).apply {
            text = "TITAN PLAYER"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        topBar.addView(title)

        val btnAdd = Button(this).apply {
            text = "+ Hesap / M3U Ekle"
            setBackgroundColor(Color.parseColor("#1A2235"))
            setTextColor(Color.WHITE)
            setPadding(32, 12, 32, 12)
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }
            setOnClickListener { showAccountDialog() }
        }
        topBar.addView(btnAdd)
        root.addView(topBar)

        tvStatus = TextView(this).apply {
            text = "Aktif Liste: Henüz liste eklenmedi (Sağ üstten ekleyin)"
            setTextColor(Color.parseColor("#A0AEC0"))
            setBackgroundColor(Color.parseColor("#131B2E"))
            setPadding(28, 20, 28, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 36 }
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

    private fun showAccountDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 12)
        }

        val etServer = EditText(this).apply { hint = "Xtream Sunucu URL (http://...)" }
        val etUser = EditText(this).apply { hint = "Kullanıcı Adı" }
        val etPass = EditText(this).apply { hint = "Şifre" }
        val divider = TextView(this).apply {
            text = "\n— VEYA DOĞRUDAN M3U LİNKİ —\n"
            gravity = Gravity.CENTER
        }
        val etM3u = EditText(this).apply { hint = "M3U / M3U8 Playlist URL" }

        layout.addView(etServer)
        layout.addView(etUser)
        layout.addView(etPass)
        layout.addView(divider)
        layout.addView(etM3u)

        AlertDialog.Builder(this)
            .setTitle("IPTV Hesabı / Listesi Ekle")
            .setView(layout)
            .setPositiveButton("Bağlan ve Yükle") { _, _ ->
                val prefs = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE).edit()
                val m3u = etM3u.text.toString().trim()
                val srv = etServer.text.toString().trim().removeSuffix("/")
                val usr = etUser.text.toString().trim()
                val pwd = etPass.text.toString().trim()

                if (m3u.isNotEmpty()) {
                    prefs.putString("MODE", "M3U").putString("M3U_URL", m3u).apply()
                    fetchM3uPlaylist(m3u)
                } else if (srv.isNotEmpty() && usr.isNotEmpty()) {
                    prefs.putString("MODE", "XTREAM")
                        .putString("SERVER", srv)
                        .putString("USER", usr)
                        .putString("PASS", pwd)
                        .apply()
                    fetchXtreamPlaylist(srv, usr, pwd)
                } else {
                    Toast.makeText(this, "Lütfen geçerli bilgi girin", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun loadSavedAccount() {
        val prefs = getSharedPreferences("TitanPrefs", Context.MODE_PRIVATE)
        when (prefs.getString("MODE", "")) {
            "M3U" -> prefs.getString("M3U_URL", "")?.let { if (it.isNotEmpty()) fetchM3uPlaylist(it) }
            "XTREAM" -> {
                val s = prefs.getString("SERVER", "") ?: ""
                val u = prefs.getString("USER", "") ?: ""
                val p = prefs.getString("PASS", "") ?: ""
                if (s.isNotEmpty()) fetchXtreamPlaylist(s, u, p)
            }
        }
    }

    private fun fetchM3uPlaylist(url: String) {
        tvStatus.text = "M3U listesi indiriliyor, lütfen bekleyin..."
        Thread {
            try {
                val req = Request.Builder().url(url).build()
                val body = client.newCall(req).execute().body?.string() ?: ""
                val parsed = mutableListOf<ChannelItem>()
                var currentName = "Bilinmeyen Kanal"
                var currentGroup = "Genel"

                for (line in body.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXTINF:")) {
                        currentName = trimmed.substringAfterLast(",").trim()
                        val grpMatch = Regex("""group-title="([^"]*)"""").find(trimmed)
                        currentGroup = grpMatch?.groupValues?.get(1) ?: "Genel"
                    } else if (trimmed.startsWith("http")) {
                        val lowerUrl = trimmed.lowercase()
                        val type = when {
                            lowerUrl.contains("/movie/") || lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".mkv") -> "MOVIE"
                            lowerUrl.contains("/series/") -> "SERIES"
                            else -> "LIVE"
                        }
                        parsed.add(ChannelItem(currentName, trimmed, currentGroup, type))
                    }
                }

                runOnUiThread {
                    allChannels.clear()
                    allChannels.addAll(parsed)
                    updateCounters("M3U Playlist")
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Hata: Liste yüklenemedi (${e.message})" }
            }
        }.start()
    }

    private fun fetchXtreamPlaylist(server: String, user: String, pass: String) {
        val fullM3u = "$server/get.php?username=$user&password=$pass&type=m3u_plus&output=ts"
        fetchM3uPlaylist(fullM3u)
    }

    private fun updateCounters(sourceName: String) {
        val live = allChannels.count { it.type == "LIVE" }
        val movie = allChannels.count { it.type == "MOVIE" }
        val series = allChannels.count { it.type == "SERIES" }
        tvStatus.text = "Aktif Kaynak: $sourceName | Toplam ${allChannels.size} içerik yüklendi"
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
            hint = "Kanal veya içerik ara..."
        }
        val listView = ListView(this)
        var currentList = filtered

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, currentList.map { "${it.name} [${it.category}]" })
        listView.adapter = adapter

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
            }
            startActivity(intent)
        }

        container.addView(searchBox)
        container.addView(listView)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("Kapat", null)
            .show()
    }
                                             }
                                             
