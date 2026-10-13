package com.example.myfilmtv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** SQLite persists catalog metadata, not video files. All functions are synchronized. */
class CatalogStore(context: Context) : SQLiteOpenHelper(context, "myfilm_catalog.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE films (grp TEXT NOT NULL, source TEXT NOT NULL, typeid TEXT NOT NULL, filmkey TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(grp, source, typeid, filmkey))")
        db.execSQL("CREATE INDEX films_group_idx ON films(grp)")
        db.execSQL("CREATE TABLE cursors (grp TEXT NOT NULL, source TEXT NOT NULL, typeid TEXT NOT NULL, nextpage INTEGER NOT NULL, finished INTEGER NOT NULL, PRIMARY KEY(grp,source,typeid))")
        db.execSQL("CREATE TABLE categories (source TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE history (filmkey TEXT PRIMARY KEY, payload TEXT NOT NULL, updated INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    private fun key(m: Movie) = m.name.trim().lowercase().replace(Regex("[\\s\\p{Punct}·]+"), "") + "|" + m.year.trim()
    private fun encode(m: Movie): String = JSONObject().apply {
        put("name", m.name); put("year", m.year); put("image", m.image); put("description", m.description)
        put("lines", JSONArray().apply { m.lines.forEach { l -> put(JSONObject().apply {
            put("source",l.source); put("label",l.label)
            put("episodes", JSONArray().apply { l.episodes.forEach { e -> put(JSONArray().put(e.first).put(e.second)) } })
        }) } })
    }.toString()
    private fun decode(raw: String): Movie {
        val j = JSONObject(raw)
        val m = Movie(j.optString("name"),j.optString("year"),j.optString("image"),j.optString("description"))
        val lines=j.optJSONArray("lines") ?: JSONArray()
        for(i in 0 until lines.length()) { val l=lines.getJSONObject(i); val eps=l.optJSONArray("episodes") ?: JSONArray()
            val pairs=(0 until eps.length()).mapNotNull { k -> runCatching { eps.getJSONArray(k).let { it.getString(0) to it.getString(1) } }.getOrNull() }
            m.lines.add(PlayLine(l.optString("source"),l.optString("label"),pairs))
        }
        return m
    }
    @Synchronized fun savePage(group:String, source:String, typeId:String, movies:List<Movie>, next:Int, finished:Boolean) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val sql="INSERT OR REPLACE INTO films(grp,source,typeid,filmkey,payload) VALUES(?,?,?,?,?)"
            movies.forEach { m -> db.execSQL(sql,arrayOf(group,source,typeId,key(m),encode(m))) }
            db.execSQL("INSERT OR REPLACE INTO cursors(grp,source,typeid,nextpage,finished) VALUES(?,?,?,?,?)",arrayOf(group,source,typeId,next,if(finished)1 else 0))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    data class Cursor(val nextPage:Int, val finished:Boolean)
    @Synchronized fun cursor(group:String,source:String,typeId:String):Cursor = readableDatabase.rawQuery(
        "SELECT nextpage,finished FROM cursors WHERE grp=? AND source=? AND typeid=?",arrayOf(group,source,typeId)).use {
        if(it.moveToFirst()) Cursor(it.getInt(0),it.getInt(1)!=0) else Cursor(1,false)
    }
    @Synchronized fun movies(group:String,limit:Int=100000):List<Movie> = readableDatabase.rawQuery(
        "SELECT payload FROM films WHERE grp=? ORDER BY rowid DESC LIMIT ?",arrayOf(group,limit.toString())).use { c ->
        val list=ArrayList<Movie>(); while(c.moveToNext()) runCatching { decode(c.getString(0)) }.getOrNull()?.let(list::add); list
    }
    @Synchronized fun saveCategories(source:String,categories:List<Network.Category>) {
        val arr=JSONArray().apply { categories.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("parentId",it.parentId)) } }
        writableDatabase.execSQL("INSERT OR REPLACE INTO categories(source,payload) VALUES(?,?)",arrayOf(source,arr.toString()))
    }
    @Synchronized fun categories(source:String):List<Network.Category> = readableDatabase.rawQuery(
        "SELECT payload FROM categories WHERE source=?",arrayOf(source)).use { c ->
        if(!c.moveToFirst()) return@use emptyList()
        val arr=JSONArray(c.getString(0)); (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Network.Category(it.getString("id"),it.getString("name"),it.optString("parentId")) } }
    }
    @Synchronized fun saveProgress(m:Movie,line:String,url:String,pos:Long,duration:Long) {
        val payload=JSONObject(encode(m)).put("progressUrl",url).put("progressLine",line).put("position",pos).put("duration",duration).toString()
        writableDatabase.execSQL("INSERT OR REPLACE INTO history(filmkey,payload,updated) VALUES(?,?,?)",arrayOf(key(m),payload,System.currentTimeMillis()))
    }
    data class Progress(val movie:Movie,val url:String,val position:Long,val duration:Long)
    @Synchronized fun recent(limit:Int=20):List<Progress> = readableDatabase.rawQuery(
        "SELECT payload FROM history ORDER BY updated DESC LIMIT ?",arrayOf(limit.toString())).use { c ->
        val list=ArrayList<Progress>(); while(c.moveToNext()) runCatching { val j=JSONObject(c.getString(0)); Progress(decode(j.toString()),j.optString("progressUrl"),j.optLong("position"),j.optLong("duration")) }.getOrNull()?.let(list::add); list
    }
}
