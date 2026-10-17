package com.example.myfilmtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("myfilm", MODE_PRIVATE) }
    private val store by lazy { CatalogStore(this) }
    private val loader by lazy { CatalogLoader(store) }
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private var config: Config? = null
    private var currentTab = "home"
    private var searchTerm = ""
    private var generation = 0
    private val sourceCats = mutableMapOf<String, List<Network.Category>>()
    private val sourcePages = mutableMapOf<String, Int>()
    private val exhausted = mutableSetOf<String>()
    private val displayed = mutableListOf<Movie>()
    private var busy = false
    private var lastErrors = listOf<String>()
    private var pageRequestRunning = false
    private var rendering = false
    private var lastRenderedCount = 0
    private var categoryComplete = false
    private var renderedLimit=50
    private var localSearchOffset=0
    private var selectedGenre = "全部"
    private var selectedYear = "全部"
    private var selectedSort = "最近更新"
    private val genreOptions = listOf("全部", "动作", "喜剧", "科幻", "剧情", "悬疑", "惊悚", "恐怖", "爱情", "动画", "犯罪", "纪录")
    private val yearOptions = listOf("全部", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2010年代", "更早")
    private val sortOptions = listOf("最近更新", "上映年份", "评分优先")
    private val tabs = listOf("home" to "首页", "movie" to "电影", "series" to "电视剧", "variety" to "综艺", "anime" to "动漫", "short" to "短剧")

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(c: Int, focused: Boolean = false) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(9).toFloat()
        if (focused) setStroke(dp(3), Color.rgb(66, 185, 255))
    }
    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(s: String, selected: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text=s; textSize=16f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
        setPadding(dp(14),dp(9),dp(14),dp(9)); isFocusable=true
        background=shape(if(selected) Color.rgb(35, 87, 132) else Color.rgb(42, 51, 69))
        setOnFocusChangeListener { _, focused ->
            background=shape(if(focused) Color.rgb(36, 106, 157) else if(selected) Color.rgb(35, 87, 132) else Color.rgb(42, 51, 69), focused)
        }
        setOnClickListener { click() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        root = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(28),dp(16),dp(28),dp(12)); setBackgroundColor(Color.rgb(15,19,28))
        }
        setContentView(root)
        loader.onPrefetched = { if(currentTab=="home" && ::content.isInitialized && !content.hasFocus()) drawHomeFromCache() }
        drawHeader()
        status=text("",13f).also { it.setTextColor(Color.rgb(175,190,207)); root.addView(it) }
        scroll=ScrollView(this).apply { isFillViewport=false; descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        if(prefs.getString("url","").isNullOrBlank()) editUrl() else { showCachedHome(); loadConfig() }
    }
    private fun drawHeader() {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        row.addView(text("MyFilm TV",28f,true),LinearLayout.LayoutParams(dp(225),dp(57)))
        tabs.forEach { (key,title) ->
            row.addView(button(title,key==currentTab) { openTab(key) },LinearLayout.LayoutParams(0,dp(48),1f).apply { rightMargin=dp(5) })
        }
        row.addView(button("搜索") { askSearch() })
        row.addView(button("设置") { editUrl() },LinearLayout.LayoutParams(dp(92),dp(48)).apply { leftMargin=dp(7) })
        root.addView(row,0)
    }
    private fun redrawHeader() { root.removeViewAt(0); drawHeader() }
    private fun openTab(key: String) {
        currentTab=key; searchTerm=""; selectedGenre="全部"; selectedYear="全部"; selectedSort="最近更新"; sourcePages.clear(); exhausted.clear(); displayed.clear()
        redrawHeader(); loadTab()
    }
    private fun loadConfig() {
        val url=prefs.getString("url", "").orEmpty()
        if(url.isBlank()) { editUrl(); return }
        status.text="读取片源配置…"
        Network.pool.execute {
            val result=runCatching { val json=Network.fetch(url); Config.parse(json) to json }
            Network.main.post {
                result.onSuccess { (c,json) ->
                    config=c; prefs.edit().putString("cached_config",json).apply(); discover(c)
                }.onFailure { e ->
                    val cached=runCatching { Config.parse(prefs.getString("cached_config","").orEmpty()) }.getOrNull()
                    if(cached!=null) { config=cached; Toast.makeText(this,"使用上次有效配置",Toast.LENGTH_SHORT).show(); discover(cached) }
                    else { status.text="无法读取配置：${e.message}"; editUrl() }
                }
            }
        }
    }
    private fun discover(conf: Config) {
        status.text="正在更新资源站分类，缓存可立即浏览…"
        val token=++generation
        sourceCats.clear()
        conf.sources.filter{it.enabled}.forEach { s -> sourceCats[s.id]=store.categories(s.id) }
        loader.configure(conf.sources,sourceCats)
        loadTab()
        Network.pool.execute {
            // Networks run concurrently; UI updates as each source becomes available.
            val futures=conf.sources.filter{it.enabled}.map { s ->
                Network.pool.submit<Pair<String,List<Network.Category>>> {
                    s.id to runCatching { Network.categories(s) }.getOrDefault(store.categories(s.id))
                }
            }
            futures.forEach { f ->
                val data=runCatching { f.get() }.getOrNull() ?: return@forEach
                store.saveCategories(data.first,data.second)
                Network.main.post {
                    if(token!=generation) return@post
                    sourceCats[data.first]=data.second
                    loader.configure(conf.sources,sourceCats)
                    if(currentTab=="home") drawHomeFromCache()
                }
            }
            Network.main.post { if(token==generation) { loader.prefetch(); if(currentTab!="home") loadNext() } }
        }
    }
    private fun showCachedHome() { currentTab="home"; drawHomeFromCache() }
    private fun loadTab() {
        loader.invalidate()
        content.removeAllViews(); displayed.clear(); pageRequestRunning=false; lastRenderedCount=0; categoryComplete=false; renderedLimit=50; localSearchOffset=0
        if(currentTab=="home") {
            drawHomeFromCache()
            loader.prefetch()
        } else {
            val title=if(currentTab=="search") "搜索：$searchTerm" else tabs.firstOrNull{it.first==currentTab}?.second.orEmpty()
            content.addView(text("$title · 全部资源",22f,true).apply { setPadding(0,dp(12),0,dp(8)) })
            if(currentTab!="search") addFilters()
            val grid=GridLayout(this).apply { columnCount=5; tag="moviegrid" }
            content.addView(grid)
            if(currentTab!="search") renderCatalog(store.movies(currentTab,renderedLimit)) else renderCatalog(store.search(searchTerm,renderedLimit))
            val indicator=text("正在获取更多影片…",14f).apply {tag="loading"}
            content.addView(indicator)
            val more=button("继续加载") { loadNext() }.apply {tag="more"}
            content.addView(more,LinearLayout.LayoutParams(-1,dp(53)))
            scroll.setOnScrollChangeListener { _:View, _:Int, y:Int, _:Int, _:Int ->
                if(currentTab!="home" && !pageRequestRunning && y+scroll.height >= content.height-dp(550)) loadNext()
            }
            loadNext()
        }
    }
    private fun drawHomeFromCache() {
        if(currentTab!="home") return
        content.removeAllViews()
        status.text="首页 · 缓存优先，后台同步片库"
        val recent=store.recent().filter { it.position>0 && (it.duration<=0 || it.position<it.duration*0.95) }
        if(recent.isNotEmpty()) {
            content.addView(text("继续观看",21f,true))
            val lane=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            content.addView(HorizontalScrollView(this).apply { addView(lane) })
            recent.take(12).forEach { p -> lane.addView(movieCard(p.movie,dp(165)) { play(p.movie,p.url,p.position) },LinearLayout.LayoutParams(dp(165),dp(236))) }
        }
        for ((group,label) in listOf("movie" to "最新电影","series" to "最新电视剧","anime" to "动漫精选","variety" to "综艺更新","short" to "短剧")) {
            val movies=Network.merge(listOf(store.movies(group,80))).take(14)
            if(movies.isEmpty()) continue
            content.addView(text(label,21f,true).apply {setPadding(0,dp(14),0,dp(6))})
            val lane=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
            content.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(lane)})
            movies.forEach { lane.addView(movieCard(it,dp(165)),LinearLayout.LayoutParams(dp(165),dp(236)).apply {rightMargin=dp(9)}) }
            content.addView(button("查看全部 $label") { openTab(group) })
        }
        if(content.childCount==0) content.addView(text("正在首次建立片库缓存，请稍候…",20f))
    }
    private fun addFilters() {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(6),0,dp(10)); tag="filters" }
        fun choice(label:String, options:List<String>, selected:()->String, change:(String)->Unit) {
            val b=button("$label：${selected()}") {
                AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(options.toTypedArray(), options.indexOf(selected())) { dialog, which ->
                    change(options[which]); dialog.dismiss(); updateFilters(); refreshCatalog()
                }.setNegativeButton("返回",null).show()
            }
            row.addView(b,LinearLayout.LayoutParams(0,dp(49),1f).apply { rightMargin=dp(12) })
        }
        choice("类型",genreOptions,{selectedGenre},{selectedGenre=it})
        choice("年份",yearOptions,{selectedYear},{selectedYear=it})
        choice("排序",sortOptions,{selectedSort},{selectedSort=it})
        content.addView(row)
    }
    private fun updateFilters() {
        val row=content.findViewWithTag<LinearLayout>("filters") ?: return
        val index=content.indexOfChild(row)
        content.removeView(row)
        addFiltersAt(index)
    }
    private fun addFiltersAt(index:Int) {
        // addFilters appends the view; move it back to its original position.
        addFilters()
        val row=content.findViewWithTag<View>("filters")
        content.removeView(row)
        content.addView(row,index)
    }
    private fun filtered(movies:List<Movie>):List<Movie> {
        val list=Network.merge(listOf(movies)).filter { m ->
            (selectedGenre=="全部" || m.genre.contains(selectedGenre,true)) &&
            (selectedYear=="全部" || when(selectedYear) {
                "2010年代" -> (m.year.toIntOrNull() ?: 0) in 2010..2019
                "更早" -> (m.year.toIntOrNull() ?: 9999)<2010
                else -> m.year==selectedYear
            })
        }
        return when(selectedSort) {
            "上映年份" -> list.sortedWith(compareByDescending<Movie> { it.year.toIntOrNull() ?: 0 }.thenBy { it.name })
            "评分优先" -> list.sortedByDescending { it.score.toDoubleOrNull() ?: -1.0 }
            "来源热度" -> list.sortedByDescending { it.hits }
            else -> list.sortedWith(compareByDescending<Movie> { it.updateTime }.thenByDescending { it.year })
        }
    }
    private fun refreshCatalog() {
        if(currentTab=="home" || currentTab=="search") return
        renderCatalog(store.movies(currentTab,renderedLimit))
    }
    private fun renderCatalog(movies:List<Movie>) {
        val grid=content.findViewWithTag<GridLayout>("moviegrid") ?: return
        if(rendering) return
        rendering=true
        val selected=filtered(movies)
        // Rebuild only if the ordered visible result set changed. Focus returns to the same cell.
        val focusBefore=grid.findFocus()
        val oldFocus=if(focusBefore!=null) grid.indexOfChild(focusBefore).coerceAtLeast(0) else -1
        val signatures=selected.map { it.sourceId+"|"+it.vodId+"|"+it.name }
        val oldSignatures=displayed.map { it.sourceId+"|"+it.vodId+"|"+it.name }
        if(signatures!=oldSignatures) {
            grid.removeAllViews(); displayed.clear();displayed.addAll(selected)
            selected.forEachIndexed { i,m ->
                grid.addView(movieCard(m,dp(168)),GridLayout.LayoutParams(GridLayout.spec(i/5),GridLayout.spec(i%5)).apply {
                    width=dp(168);height=dp(246);setMargins(dp(5),dp(5),dp(8),dp(7))
                })
            }
            if(oldFocus>=0 && grid.childCount>0) grid.getChildAt(oldFocus.coerceAtMost(grid.childCount-1)).requestFocus()
        }
        status.text="${tabs.firstOrNull{it.first==currentTab}?.second ?: "搜索"} · 当前筛选 ${displayed.size} 部（来自已缓存目录）· 后台仍在补充"
        rendering=false
    }
    private fun loadNext() {
        if(pageRequestRunning || categoryComplete || config==null) return
        if(currentTab=="search") { searchNext(); return }
        pageRequestRunning=true
        val group=currentTab
        if(store.count(group)>renderedLimit) {
            renderedLimit+=50
            renderCatalog(store.movies(group,renderedLimit))
            pageRequestRunning=false
            return
        }
        content.findViewWithTag<TextView>("loading")?.text="正在加载更多 $group …"
        loader.foreground(group) { movies, _, hasMore ->
            if(currentTab!=group) return@foreground
            renderCatalog(store.movies(group,renderedLimit))
            pageRequestRunning=false
            categoryComplete = !hasMore
            content.findViewWithTag<TextView>("loading")?.text=if(hasMore) "已加载 ${displayed.size} 部，继续滚动获取更多" else "当前没有可继续加载的页面"
        }
    }
    private fun searchNext() {
        if(pageRequestRunning) return
        pageRequestRunning=true
        renderedLimit+=50
        val query=searchTerm
        val tabToken=generation
        Network.pool.execute {
            val local=store.search(query,renderedLimit)
            Network.main.post {
                if(tabToken==generation && currentTab=="search") {
                    renderCatalog(local)
                    pageRequestRunning=false
                    content.findViewWithTag<TextView>("loading")?.text="本地搜索：已加载 ${local.size} 条；远程搜索结果不保证完整"
                }
            }
        }
    }
    private fun movieCard(m: Movie,width: Int, action: (() -> Unit)? = null): View {
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(6),dp(6),dp(6),dp(7)); isFocusable=true; background=shape(Color.rgb(35,43,57)) }
        val poster=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(48,58,72)) }
        card.addView(poster,LinearLayout.LayoutParams(-1,dp(158))); Network.image(m.image,poster)
        card.addView(text(m.name.take(19),14f,true),LinearLayout.LayoutParams(-1,dp(42)))
        card.addView(text("${m.year} · ${m.lines.size} 线路",11f))
        card.setOnClickListener { if(action==null) showMovie(m) else action() }
        card.setOnFocusChangeListener { _, focus ->
            card.background=shape(if(focus) Color.rgb(35,94,144) else Color.rgb(35,43,57),focus)
            card.scaleX=if(focus)1.035f else 1f; card.scaleY=if(focus)1.035f else 1f
        }
        return card
    }
    private fun showMovie(m: Movie) {
        val src=config?.sources?.firstOrNull { it.id==m.sourceId }
        if(src!=null && m.vodId.isNotBlank()) {
            Network.pool.execute {
                val detail=runCatching { Network.detail(src,m.vodId) }.getOrNull()
                if(detail!=null && detail.lines.isNotEmpty()) Network.main.post { showMovieDialog(detail) }
                else Network.main.post { showMovieDialog(m) }
            }
        } else showMovieDialog(m)
    }
    private fun showMovieDialog(m: Movie) {
        val episodes = m.lines.flatMap { line -> line.episodes.map { Triple(line, it.first, it.second) } }
        if (episodes.isEmpty()) { toast("没有可直接播放的线路"); return }
        val summary = listOfNotNull(
            listOf(m.year,m.area,m.genre).filter { it.isNotBlank() }.joinToString("  ·  ").takeIf { it.isNotBlank() },
            m.score.takeIf { it.isNotBlank() }?.let { "评分：$it（资源站）" },
            m.director.takeIf { it.isNotBlank() }?.let { "导演：$it" },
            m.actors.takeIf { it.isNotBlank() }?.let { "主演：$it" },
            m.remarks.takeIf { it.isNotBlank() }?.let { "状态：$it" },
            m.description.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")
        val labels = episodes.map { "${it.first.source} · ${it.first.label} · ${it.second}" }

        // Playback choices are always visible and receive initial D-pad focus.
        // The synopsis below scrolls independently and cannot push the lines off-screen.
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18),dp(8),dp(18),dp(8))
        }
        pane.addView(text("播放线路 / 选集（OK 播放）",18f,true))
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels)
            isFocusable = true
            isFocusableInTouchMode = true
            choiceMode = ListView.CHOICE_MODE_SINGLE
        }
        pane.addView(list, LinearLayout.LayoutParams(-1, dp(225)))
        if (summary.isNotBlank()) {
            pane.addView(text("影片资料（下方可滚动）",16f,true))
            val descScroll = ScrollView(this).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = true
                isFocusable = true
                addView(text(summary,15f).apply { setPadding(dp(6),dp(5),dp(6),dp(8)) })
            }
            pane.addView(descScroll, LinearLayout.LayoutParams(-1,dp(135)))
        }
        val dialog = AlertDialog.Builder(this).setTitle(m.name).setView(pane).setNegativeButton("返回",null).create()
        list.setOnItemClickListener { _,_,which,_ -> dialog.dismiss(); play(m,episodes[which].third,0L,episodes[which].first.label) }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.84).toInt(), -2)
        list.requestFocus()
        list.setSelection(0)
    }
    private fun play(m:Movie,url:String,position:Long,line:String="") {
        val intent=Intent(this,PlayerActivity::class.java).putExtra("url",url).putExtra("position",position)
            .putExtra("headers",org.json.JSONObject(config?.sources?.firstOrNull { it.id==m.sourceId }?.playbackHeaders ?: emptyMap<String,String>()).toString())
            .putExtra("line",line).putExtra("film",m.name).putExtra("year",m.year).putExtra("poster",m.image)
            .putExtra("movieJson", org.json.JSONObject().put("sourceId",m.sourceId).put("vodId",m.vodId).put("name",m.name).put("year",m.year).put("image",m.image).put("description",m.description).toString())
        startActivity(intent)
    }
    override fun onResume() { super.onResume(); if(::content.isInitialized && currentTab=="home") drawHomeFromCache(); if(config!=null) loader.resume() }
    override fun onStop() { loader.pause(); super.onStop() }
    override fun onDestroy() { loader.shutdown(); store.close(); super.onDestroy() }
    private fun editUrl() {
        val field=EditText(this).apply {
            setSingleLine(true); setText(prefs.getString("url","")); hint="https://.../sources.json"
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            selectAll()
        }
        val wrap=LinearLayout(this).apply { setPadding(dp(22),dp(8),dp(22),0); addView(field) }
        val dialog=AlertDialog.Builder(this).setTitle("影视资源配置 URL")
            .setView(wrap).setNegativeButton("取消",null)
            .setPositiveButton("保存并加载",null).create()
        dialog.setOnShowListener {
            val save=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.isFocusable=true
            save.setOnClickListener {
                val url=field.text.toString().trim()
                if(!url.startsWith("https://")) { field.error="需要 HTTPS URL"; return@setOnClickListener }
                prefs.edit().putString("url",url).apply()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken,0)
                dialog.dismiss(); loadConfig()
            }
            field.setOnEditorActionListener { _,_,_ -> save.requestFocus(); true }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isFocusable=true
            // Make actions reachable by TV D-pad; keyboard Back hides IME.
            if (save.id != View.NO_ID) field.nextFocusDownId=save.id
        }
        dialog.show()
    }
    private fun askSearch() {
        val field=EditText(this).apply { setSingleLine(true); hint="电影 / 电视剧名称" }
        val dialog=AlertDialog.Builder(this).setTitle("跨资源站搜索").setView(field)
            .setNegativeButton("取消",null).setPositiveButton("搜索",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val term=field.text.toString().trim()
                if(term.isBlank()) { field.error="输入关键词"; return@setOnClickListener }
                searchTerm=term; currentTab="search"; dialog.dismiss(); redrawHeader(); loadTab()
            }
        }
        dialog.show()
    }
    override fun onKeyDown(code:Int,event:KeyEvent?):Boolean {
        if(code==KeyEvent.KEYCODE_MENU) { editUrl(); return true }
        return super.onKeyDown(code,event)
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
