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
        drawHeader()
        status=text("",13f).also { it.setTextColor(Color.rgb(175,190,207)); root.addView(it) }
        scroll=ScrollView(this).apply { isFillViewport=false; descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        if(prefs.getString("url","").isNullOrBlank()) editUrl() else loadConfig()
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
        currentTab=key; searchTerm=""; sourcePages.clear(); exhausted.clear(); displayed.clear()
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
        status.text="正在识别资源站分类…"
        val token=++generation
        Network.pool.execute {
            val results=mutableMapOf<String,List<Network.Category>>()
            conf.sources.filter{it.enabled}.forEach { source ->
                results[source.id]=runCatching { Network.categories(source) }.getOrDefault(emptyList())
            }
            Network.main.post {
                if(token!=generation) return@post
                sourceCats.clear(); sourceCats.putAll(results)
                loadTab()
            }
        }
    }
    private fun groupSourceIds(source: Source, key: String): List<String> =
        sourceCats[source.id].orEmpty().filter { Network.categoryGroup(it.name)==key }.map { it.id }.distinct()
    private fun loadTab() {
        val conf=config ?: return
        val token=++generation
        content.removeAllViews(); displayed.clear(); sourcePages.clear(); exhausted.clear(); busy=false
        if(currentTab=="home") {
            status.text="首页 · 分类栏目"
            // A real category-scoped request per lane, not just relabelled latest results.
            listOf("movie" to "最新电影", "series" to "最新电视剧", "anime" to "动漫精选", "variety" to "综艺更新").forEach { (key,title) ->
                val header=text(title,21f,true).apply { setPadding(0,dp(14),0,dp(6)) }
                content.addView(header)
                val lane=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(lane) })
                Network.pool.execute {
                    val groups=conf.sources.filter{it.enabled}.take(4).map { s ->
                        val id=groupSourceIds(s,key).firstOrNull()
                        if (id==null) emptyList() else runCatching { Network.page(s,typeId=id).items }.getOrDefault(emptyList())
                    }
                    val list=Network.merge(groups).take(14)
                    Network.main.post { if(token==generation) {
                        if(list.isEmpty()) { content.removeView(header); (lane.parent as? ViewGroup)?.let { content.removeView(it) } }
                        else list.forEach { lane.addView(movieCard(it, dp(165)),LinearLayout.LayoutParams(dp(165),dp(236)).apply{rightMargin=dp(9)}) }
                    } }
                }
            }
        } else {
            val title = if (currentTab=="search") "搜索：$searchTerm" else tabs.firstOrNull{it.first==currentTab}?.second.orEmpty()
            content.addView(text(title,22f,true).apply { setPadding(0,dp(12),0,dp(8)) })
            val grid=GridLayout(this).apply { columnCount=5; tag="moviegrid" }
            content.addView(grid)
            content.addView(button("加载更多") { loadNext() }.apply { tag="more" },LinearLayout.LayoutParams(-1,dp(53)).apply { topMargin=dp(15) })
            loadNext()
        }
    }
    private fun loadNext() {
        val conf=config ?: return
        if (busy) return
        busy=true
        val token=generation
        status.text="正在加载 ${if(currentTab=="search") "搜索结果" else "分类影片"}…"
        val active=conf.sources.filter{it.enabled}
        Network.pool.execute {
            val groups=mutableListOf<List<Movie>>()
            val errors=mutableListOf<String>()
            var attempted=0
            active.forEach { s ->
                val ids = if(currentTab=="search") listOf("") else groupSourceIds(s,currentTab)
                ids.forEach { id ->
                    val key="${s.id}:$id"
                    if(key in exhausted) return@forEach
                    attempted++
                    val page=(sourcePages[key]?:0)+1
                    runCatching { Network.page(s,search=searchTerm,page=page,typeId=id.ifBlank{null}) }
                        .onSuccess { result ->
                            groups.add(result.items)
                            sourcePages[key]=page
                            if(!result.hasNext || result.items.isEmpty()) exhausted.add(key)
                        }.onFailure { errors.add(s.name); exhausted.add(key) }
                }
            }
            val newItems=Network.merge(groups)
            Network.main.post {
                if (token!=generation) return@post
                busy=false; lastErrors=errors.distinct()
                val existing=displayed.map { it.name.trim().lowercase()+"|"+it.year }.toHashSet()
                val additional=newItems.filter { existing.add(it.name.trim().lowercase()+"|"+it.year) }
                displayed.addAll(additional)
                val grid=content.findViewWithTag<GridLayout>("moviegrid")
                additional.forEach { movie ->
                    val i=grid.childCount
                    grid.addView(movieCard(movie,dp(168)),GridLayout.LayoutParams(GridLayout.spec(i/5),GridLayout.spec(i%5)).apply {
                        width=dp(168); height=dp(246); setMargins(dp(5),dp(5),dp(8),dp(7))
                    })
                }
                status.text="共 ${displayed.size} 部 · ${active.size-lastErrors.size}/${active.size} 个资源站正常" + if(lastErrors.isNotEmpty()) " · ${lastErrors.size} 个站点请求异常" else ""
                content.findViewWithTag<View>("more")?.visibility=if(attempted==0 || (additional.isEmpty() && exhausted.size>=attempted)) View.GONE else View.VISIBLE
                if(grid.childCount>0 && grid.findFocus()==null && currentFocus==null) grid.getChildAt(0).requestFocus()
                if(displayed.isEmpty()) status.text="暂无匹配内容；可切换分类或资源站"
            }
        }
    }
    private fun movieCard(m: Movie,width: Int): View {
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(6),dp(6),dp(6),dp(7)); isFocusable=true; background=shape(Color.rgb(35,43,57)) }
        val poster=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(48,58,72)) }
        card.addView(poster,LinearLayout.LayoutParams(-1,dp(158))); Network.image(m.image,poster)
        card.addView(text(m.name.take(19),14f,true),LinearLayout.LayoutParams(-1,dp(42)))
        card.addView(text("${m.year} · ${m.lines.size} 线路",11f))
        card.setOnClickListener { showMovie(m) }
        card.setOnFocusChangeListener { _, focus ->
            card.background=shape(if(focus) Color.rgb(35,94,144) else Color.rgb(35,43,57),focus)
            card.scaleX=if(focus)1.035f else 1f; card.scaleY=if(focus)1.035f else 1f
        }
        return card
    }
    private fun showMovie(m: Movie) {
        val episodes=m.lines.flatMap { l -> l.episodes.map { Triple(l,it.first,it.second) } }
        if(episodes.isEmpty()) { toast("没有可直接播放的线路"); return }
        AlertDialog.Builder(this).setTitle(m.name)
            .setItems(episodes.map { "${it.first.source} · ${it.first.label} · ${it.second}" }.toTypedArray()) { _,which ->
                startActivity(Intent(this,PlayerActivity::class.java).putExtra("url",episodes[which].third))
            }.setNegativeButton("返回",null).show()
    }
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
