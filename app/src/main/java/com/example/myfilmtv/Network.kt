package com.example.myfilmtv

import android.graphics.Bitmap
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
    fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6500; conn.readTimeout = 8500
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MyFilmTV/0.1")
        try {
            require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }
    fun endpoint(base: String, vararg params: Pair<String, String>): String {
        val glue = if (base.contains("?")) "&" else "?"
        return base + glue + params.joinToString("&") { (k,v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }
    fun page(source: Source, search: String): List<Movie> {
        val params = if (search.isBlank()) arrayOf("ac" to "detail", "pg" to "1")
                     else arrayOf("ac" to "detail", "wd" to search, "pg" to "1")
        val root = JSONObject(fetch(endpoint(source.api, *params)))
        val list = root.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            runCatching { parseMovie(source, list.getJSONObject(i)) }.getOrNull()
        }
    }
    private fun parseMovie(s: Source, j: JSONObject): Movie? {
        val name = j.optString("vod_name").trim()
        if (name.isEmpty()) return null
        val movie = Movie(name, j.optString("vod_year"), j.optString("vod_pic"),
            j.optString("vod_content").replace(Regex("<[^>]+>"), " "))
        val lineNames = j.optString("vod_play_from").split("$$$")
        val lineEntries = j.optString("vod_play_url").split("$$$")
        lineEntries.forEachIndexed { i, section ->
            val episodes = section.split('#').mapNotNull { ep ->
                val at = ep.lastIndexOf('$')
                if (at <= 0) null else {
                    val url = ep.substring(at + 1).trim()
                    if (url.startsWith("https://") || url.startsWith("http://")) ep.substring(0, at) to url else null
                }
            }
            if (episodes.isNotEmpty()) movie.lines.add(PlayLine(s.name, lineNames.getOrElse(i) { "线路 ${i+1}" }, episodes))
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
                // prefer an existing poster; exact same normalized name and year only
                if (old.image.isBlank() && m.image.isNotBlank()) map[key] = old.copy(image = m.image)
            }
        }
        return map.values.toList()
    }
    fun image(url: String, view: ImageView) {
        if (!url.startsWith("https://")) return
        view.tag = url
        pool.execute {
            val bmp: Bitmap? = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout=5000; c.readTimeout=6000
                try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
            }.getOrNull()
            if (bmp != null) main.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }
}
