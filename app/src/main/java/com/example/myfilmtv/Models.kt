package com.example.myfilmtv

import org.json.JSONObject

data class Source(val id: String, val name: String, val api: String, val enabled: Boolean)
data class PlayLine(val source: String, val label: String, val episodes: List<Pair<String, String>>)
data class Movie(val name: String, val year: String, val image: String, val description: String, val genre: String = "", val area: String = "", val actors: String = "", val director: String = "", val score: String = "", val updateTime: String = "", val hits: Long = 0L, val remarks: String = "", val lines: MutableList<PlayLine> = mutableListOf())

data class Config(val sources: List<Source>, val refreshMinutes: Int) {
    companion object {
        fun parse(json: String): Config {
            val root = JSONObject(json)
            require(root.optInt("schemaVersion", -1) == 1) { "JSON 必须含 schemaVersion: 1" }
            val arr = root.getJSONArray("sources")
            val list = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                Source(a.getString("id"), a.getString("name"), a.getString("api"), a.optBoolean("enabled", true))
            }
            require(list.any { it.enabled }) { "至少启用一个资源站" }
            require(list.filter { it.enabled }.all { it.api.startsWith("https://") }) { "首版仅允许 HTTPS API" }
            return Config(list, root.optInt("refreshMinutes", 60).coerceIn(5, 1440))
        }
    }
}
