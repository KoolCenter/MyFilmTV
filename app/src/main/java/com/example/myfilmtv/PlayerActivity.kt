package com.example.myfilmtv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.Locale
import androidx.media3.datasource.HttpDataSource

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private val store by lazy { CatalogStore(this) }
    private var movie: Movie? = null
    private var playbackUrl = ""
    private lateinit var status: TextView
    private lateinit var retry: Button
    private var resumePosition=0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackUrl = intent.getStringExtra("url") ?: run { finish(); return }
        movie = runCatching {
            val j = JSONObject(intent.getStringExtra("movieJson") ?: "{}")
            Movie(j.optString("name"), j.optString("year"), j.optString("image"), j.optString("description"), sourceId=j.optString("sourceId"), vodId=j.optString("vodId"))
        }.getOrNull()

        val frame = FrameLayout(this)
        val playerView = PlayerView(this).apply {
            useController = true
            isFocusable = true
            requestFocus()
        }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0xCC101820.toInt())
        }
        status = TextView(this).apply {
            text = "正在连接视频…"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }
        retry = Button(this).apply {
            text = "重试播放"
            visibility = View.GONE
            isFocusable = true
            setOnClickListener { startPlayback(playerView) }
        }
        overlay.addView(status)
        overlay.addView(retry)
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(frame)
        resumePosition=intent.getLongExtra("position",0L)
        startPlayback(playerView)
    }

    private fun startPlayback(view: PlayerView) {
        player?.let { if(it.currentPosition>0) resumePosition=it.currentPosition; it.release() }
        player = null
        status.visibility = View.VISIBLE
        status.text = "正在连接视频…"
        retry.visibility = View.GONE

        // Media3 cannot decode webpage URLs or some proprietary play-line formats.
        val uri = android.net.Uri.parse(playbackUrl)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val mime = when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            if (mime != null) setMimeType(mime)
        }.build()
        val headers = runCatching {
            val j=JSONObject(intent.getStringExtra("headers") ?: "{}")
            j.keys().asSequence().associateWith { j.getString(it) }
        }.getOrDefault(emptyMap())
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(20000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player = p
        view.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        status.visibility = View.VISIBLE
                        status.text = "正在缓冲视频…"
                    }
                    Player.STATE_READY -> status.visibility = View.GONE
                    Player.STATE_ENDED -> status.visibility = View.GONE
                    Player.STATE_IDLE -> if (p.playerError == null) {
                        status.visibility = View.VISIBLE
                        status.text = "等待视频数据…"
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val cause = generateSequence(error.cause) { it.cause }.take(6).lastOrNull()
                val detail = cause?.message ?: error.message ?: "未知错误"
                val httpError = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                val address = runCatching { uri.host.orEmpty() }.getOrDefault("")
                val reason = if (httpError != null) "HTTP ${httpError.responseCode}（$address）" else error.errorCodeName
                status.text = "播放失败：$reason\n${detail.take(180)}\n\n可更换线路或返回详情。"
                status.visibility = View.VISIBLE
                retry.visibility = View.VISIBLE
            }
        })
        p.setMediaItem(mediaItem)
        p.prepare()
        val position = resumePosition
        if (position > 0L) p.seekTo(position)
        p.playWhenReady = true
    }

    private fun saveProgress() {
        val m = movie ?: return
        val p = player ?: return
        runCatching { if(p.currentPosition>0) store.saveProgress(m, intent.getStringExtra("line").orEmpty(), playbackUrl, p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0)) }
    }
    override fun onPause() { saveProgress(); super.onPause() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        val p = player ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekTo((p.currentPosition + 10000).coerceAtMost(if (p.duration > 0) p.duration else Long.MAX_VALUE)); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (p.isPlaying) p.pause() else p.play(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
    override fun onStop() { saveProgress(); player?.release(); player = null; super.onStop() }
    override fun onDestroy() { store.close(); super.onDestroy() }
}
