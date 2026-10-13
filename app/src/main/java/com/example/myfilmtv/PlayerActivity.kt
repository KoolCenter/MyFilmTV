package com.example.myfilmtv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject

class PlayerActivity : Activity() {
    private var player:ExoPlayer? = null
    private val store by lazy { CatalogStore(this) }
    private var movie:Movie? = null
    private var playbackUrl=""
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackUrl=intent.getStringExtra("url") ?: run {finish();return}
        movie=runCatching { val j=JSONObject(intent.getStringExtra("movieJson") ?: "{}"); Movie(j.optString("name"),j.optString("year"),j.optString("image"),j.optString("description")) }.getOrNull()
        val view=PlayerView(this).apply { useController=true; requestFocus() }
        setContentView(view)
        player=ExoPlayer.Builder(this).build().also { p ->
            view.player=p
            p.setMediaItem(MediaItem.fromUri(playbackUrl))
            p.prepare()
            val position=intent.getLongExtra("position",0L)
            if(position>0) p.seekTo(position)
            p.playWhenReady=true
        }
    }
    private fun saveProgress() {
        val m=movie ?: return
        val p=player ?: return
        runCatching { store.saveProgress(m,"",playbackUrl,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0)) }
    }
    override fun onPause() { saveProgress(); super.onPause() }
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean {
        val p=player ?: return super.onKeyDown(keyCode,event)
        return when(keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {p.seekTo((p.currentPosition+10000).coerceAtMost(if(p.duration>0)p.duration else Long.MAX_VALUE));true}
            KeyEvent.KEYCODE_DPAD_LEFT -> {p.seekTo((p.currentPosition-10000).coerceAtLeast(0));true}
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {if(p.isPlaying)p.pause() else p.play();true}
            else -> super.onKeyDown(keyCode,event)
        }
    }
    override fun onStop() {saveProgress();player?.release();player=null;super.onStop()}
    override fun onDestroy() {store.close();super.onDestroy()}
}
