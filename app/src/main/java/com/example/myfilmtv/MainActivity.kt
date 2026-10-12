package com.example.myfilmtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("myfilm", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private var config: Config? = null
    private var movies = listOf<Movie>()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, focus: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(9).toFloat()
        if (focus) setStroke(dp(3), Color.rgb(59, 183, 255))
    }
    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE)
        if (bold) setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(title: String, onClick: () -> Unit) = TextView(this).apply {
        text=title; textSize=18f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
        setPadding(dp(18),dp(11),dp(18),dp(11)); isFocusable=true; isFocusableInTouchMode=false
        background=shape(Color.rgb(44,53,72))
        setOnFocusChangeListener { _, focus -> background=shape(if(focus) Color.rgb(37,78,118) else Color.rgb(44,53,72), focus) }
        setOnClickListener { onClick() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(35),dp(22),dp(35),dp(18)); setBackgroundColor(Color.rgb(16,20,28)) }
        setContentView(root)
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        row.addView(label("MyFilm TV", 30f, true), LinearLayout.LayoutParams(0,dp(57),1f))
        row.addView(button("搜索") { askSearch() })
        row.addView(button("设置 URL") { editUrl() }.also { it.layoutParams = LinearLayout.LayoutParams(dp(155),dp(53)).apply { leftMargin=dp(12) } })
        row.addView(button("刷新") { loadConfig(true) }.also { it.layoutParams = LinearLayout.LayoutParams(dp(90),dp(53)).apply { leftMargin=dp(12) } })
        root.addView(row)
        heading = label("电影 / 电视剧", 20f, true).also { root.addView(it) }
        status = label("", 14f).also { root.addView(it) }
        val scroll = ScrollView(this).apply { isFillViewport=false; descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS }
        grid = GridLayout(this).apply { columnCount=5; alignmentMode=GridLayout.ALIGN_BOUNDS }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        if (prefs.getString("url", "").isNullOrEmpty()) editUrl() else loadConfig(false)
    }
    private fun editUrl() {
        val field=EditText(this).apply {
            setSingleLine(true); setText(prefs.getString("url", "")); hint="https://raw.githubusercontent.com/.../sources.json"
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val wrap=LinearLayout(this).apply { setPadding(dp(20),dp(6),dp(20),0); addView(field) }
        AlertDialog.Builder(this).setTitle("影视资源配置 URL").setMessage("填入 GitHub Raw / HTTPS JSON 文件地址，遥控器 OK 可输入。")
            .setView(wrap).setNegativeButton("取消",null)
            .setPositiveButton("保存并加载") { _,_ ->
                val url=field.text.toString().trim()
                if (!url.startsWith("https://")) { toast("请使用 HTTPS 地址"); return@setPositiveButton }
                prefs.edit().putString("url",url).apply(); loadConfig(true)
            }.show()
    }
    private fun loadConfig(force: Boolean) {
        val url=prefs.getString("url", "") ?: ""
        if(url.isEmpty()) { editUrl(); return }
        status.text="正在加载远程配置…"
        Network.pool.execute {
            val result=runCatching { val raw=Network.fetch(url); Config.parse(raw) to raw }
            Network.main.post {
                result.onSuccess { (conf,raw) ->
                    config=conf; prefs.edit().putString("cached_config", raw).apply()
                    loadMovies(conf, "")
                }.onFailure { e ->
                    val old=prefs.getString("cached_config", "") ?: ""
                    val fallback=runCatching { Config.parse(old) }.getOrNull()
                    if(fallback!=null) { config=fallback; toast("远程失败，使用缓存：${e.message}"); loadMovies(fallback, "") }
                    else { status.text="配置读取失败：${e.message}"; if(force) toast("请检查配置 URL") }
                }
            }
        }
    }
    private fun loadMovies(conf: Config, search: String) {
        heading.text = if(search.isBlank()) "最新影视" else "搜索：$search"
        status.text="正在从多个资源站读取内容…"
        grid.removeAllViews()
        val active=conf.sources.filter { it.enabled }
        Network.pool.execute {
            // Sequential coordination but each request is done off UI thread; avoids network work on main.
            val groups=mutableListOf<List<Movie>>()
            val errors=mutableListOf<String>()
            active.forEach { source ->
                runCatching { Network.page(source,search) }
                    .onSuccess { groups.add(it) }
                    .onFailure { errors.add("${source.name}: ${it.message}") }
            }
            val merged=Network.merge(groups)
            Network.main.post {
                movies=merged
                status.text="${merged.size} 部 · ${active.size-errors.size}/${active.size} 个资源站响应成功" + if(errors.isNotEmpty()) "（${errors.take(2).joinToString("；")}）" else ""
                renderMovies()
            }
        }
    }
    private fun renderMovies() {
        grid.removeAllViews()
        movies.forEachIndexed { index, movie ->
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(7),dp(7),dp(7),dp(8)); isFocusable=true; background=shape(Color.rgb(35,43,56)) }
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(49,62,79)) }
            card.addView(image, LinearLayout.LayoutParams(-1,dp(170)))
            Network.image(movie.image,image)
            card.addView(label(movie.name.take(28),15f,true), LinearLayout.LayoutParams(-1,dp(43)))
            card.addView(label("${movie.year} · ${movie.lines.size} 线路",12f))
            card.setOnClickListener { showMovie(movie) }
            card.setOnFocusChangeListener { _, f ->
                card.background=shape(if(f) Color.rgb(38,73,108) else Color.rgb(35,43,56), f)
                card.scaleX=if(f) 1.035f else 1f; card.scaleY=if(f) 1.035f else 1f
            }
            val metrics=resources.displayMetrics
            val width=((metrics.widthPixels-dp(100))/5).coerceAtLeast(dp(120))
            val p=GridLayout.LayoutParams(GridLayout.spec(index/5),GridLayout.spec(index%5)).apply {
                this.width=width; this.height=dp(255); setMargins(dp(5),dp(5),dp(5),dp(5))
            }
            grid.addView(card,p)
        }
        if(grid.childCount>0) grid.getChildAt(0).requestFocus()
    }
    private fun showMovie(movie: Movie) {
        val items=movie.lines.flatMap { line -> line.episodes.map { ep -> Triple(line,ep.first,ep.second) } }
        if(items.isEmpty()) { toast("此影片没有可用的直接播放地址"); return }
        val titles=items.map { (line,ep,_) -> "${line.source} · ${line.label} · $ep" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(movie.name).setItems(titles) { _, which ->
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("url",items[which].third))
        }.setNegativeButton("返回",null).show()
    }
    private fun askSearch() {
        val field=EditText(this).apply { setSingleLine(true); hint="输入电影/电视剧名称" }
        AlertDialog.Builder(this).setTitle("跨资源站搜索").setView(field)
            .setNegativeButton("取消", null).setNeutralButton("返回最新") { _,_-> config?.let { loadMovies(it, "") } }
            .setPositiveButton("搜索") { _,_-> config?.let { loadMovies(it,field.text.toString().trim()) } }.show()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if(keyCode == KeyEvent.KEYCODE_MENU) { editUrl(); return true }
        return super.onKeyDown(keyCode,event)
    }
    private fun toast(s: String) = Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
