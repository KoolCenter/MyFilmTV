package com.example.myfilmtv

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

object Network {
    val pool = Executors.newFixedThreadPool(6)
    val main = Handler(Looper.getMainLooper())
    private val imageCache = object : android.util.LruCache<String, android.graphics.Bitmap>(50) {}
    data class Category(val id: String, val name: String, val parentId: String = "")
    data class Page(val items: List<Movie>, val hasNext: Boolean)
    fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6500; conn.readTimeout = 8500
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MyFilmTV/0.3")
        try {
            require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }
    fun endpoint(base: String, vararg params: Pair<String, String>): String {
        val glue = if (base.contains("?")) "&" else "?"
        return base + glue + params.joinToString("&") { (k,v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }
    fun categories(source: Source): List<Category> {
        val root = JSONObject(fetch(endpoint(source.api, "ac" to "list")))
        val arr = root.optJSONArray("class") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val j = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = j.optString("type_id")
            val name = j.optString("type_name")
            if (id.isBlank() || name.isBlank()) null else Category(id, name, j.optString("type_pid"))
        }
    }
    fun categoryGroup(name: String): String? {
        val n = name.lowercase()
        return when {
            listOf("电影", "電影", "片", "movie", "film").any { it in n } &&
                listOf("动漫", "動畫", "动画", "卡通", "纪录", "记录", "短剧").none { it in n } -> "movie"
            listOf("电视剧", "電視劇", "连续剧", "連續劇", "剧集", "電視", "tv", "韩剧", "美剧", "日剧", "国产剧", "港剧").any { it in n } -> "series"
            listOf("综艺", "綜藝", "variety").any { it in n } -> "variety"
            listOf("动漫", "動畫", "动画", "卡通", "anime").any { it in n } -> "anime"
            listOf("短剧", "短劇", "微剧", "微劇").any { it in n } -> "short"
            else -> null
        }
    }
    fun page(source: Source, search: String = "", page: Int = 1, typeId: String? = null): Page {
        val params = mutableListOf("ac" to "detail", "pg" to page.toString())
        if (search.isNotBlank()) params.add("wd" to search)
        if (!typeId.isNullOrBlank()) params.add("t" to typeId)
        val root = JSONObject(fetch(endpoint(source.api, *params.toTypedArray())))
        val list = root.optJSONArray("list") ?: return Page(emptyList(), false)
        val movies = (0 until list.length()).mapNotNull { i ->
            runCatching { parseMovie(source, list.getJSONObject(i)) }.getOrNull()
        }
        val current = root.optInt("page", page)
        val total = root.optInt("pagecount", current)
        return Page(movies, current < total)
    }
    private fun parseMovie(s: Source, j: JSONObject): Movie? {
        val name = j.optString("vod_name").trim()
        if (name.isEmpty()) return null
        val movie = Movie(name, j.optString("vod_year"), j.optString("vod_pic"),
            j.optString("vod_content").replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").trim(),
            j.optString("vod_class"), j.optString("vod_area"), j.optString("vod_actor"),
            j.optString("vod_director"), j.optString("vod_score"), j.optString("vod_time"),
            j.optLong("vod_hits"), j.optString("vod_remarks"))
        val names = j.optString("vod_play_from").split("\$\$\$")
        j.optString("vod_play_url").split("\$\$\$").forEachIndexed { i, section ->
            val episodes = section.split('#').mapNotNull { ep ->
                val at = ep.lastIndexOf('$')
                if (at <= 0) null else {
                    val u = ep.substring(at + 1).trim()
                    if (u.startsWith("https://") || u.startsWith("http://")) ep.substring(0, at) to u else null
                }
            }
            if (episodes.isNotEmpty()) movie.lines.add(PlayLine(s.name, names.getOrElse(i) { "线路 ${i + 1}" }, episodes))
        }
        return movie
    }
    fun merge(groups: List<List<Movie>>): List<Movie> {
        val map = linkedMapOf<String, Movie>()
        groups.flatten().forEach { m ->
            val key = m.name.trim().lowercase().replace(Regex("[\\s\\p{Punct}·]+"), "") + "|" + m.year
            val old = map[key]
            if (old == null) map[key] = m
            else {
                old.lines.addAll(m.lines)
                if (old.image.isBlank() && m.image.isNotBlank()) map[key] = old.copy(image = m.image)
            }
        }
        return map.values.toList()
    }
    fun image(url: String, view: ImageView) {
        view.tag = url
        view.setImageDrawable(null)
        if (!url.startsWith("https://")) return
        imageCache.get(url)?.let { view.setImageBitmap(it); return }
        pool.execute {
            val bmp = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 6000
                try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
            }.getOrNull()
            if (bmp != null) { imageCache.put(url, bmp); main.post { if (view.tag == url) view.setImageBitmap(bmp) } }
        }
    }
}
