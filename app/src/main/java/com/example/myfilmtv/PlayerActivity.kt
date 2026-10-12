package com.example.myfilmtv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val view = PlayerView(this).apply { useController = true; requestFocus() }
        setContentView(view)
        player = ExoPlayer.Builder(this).build().also {
            view.player = it
            it.setMediaItem(MediaItem.fromUri(url)); it.prepare(); it.playWhenReady = true
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        return when(keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekTo((p.currentPosition + 10000).coerceAtMost(if(p.duration > 0) p.duration else Long.MAX_VALUE)); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> { if(p.isPlaying) p.pause() else p.play(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
    override fun onStop() { player?.release(); player = null; super.onStop() }
}
