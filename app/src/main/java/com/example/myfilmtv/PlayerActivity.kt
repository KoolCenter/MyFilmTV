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
    private lateinit var overlay: LinearLayout
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
            controllerAutoShow = false
            controllerShowTimeoutMs = 3000
            isFocusable = true
            requestFocus()
        }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0xCC101820.toInt())
            visibility = View.GONE
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
        frame.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(frame)
        resumePosition=intent.getLongExtra("position",0L)
        startPlayback(playerView)
    }

    // Probe only the ambiguous "feifan" line. HLS-labelled lines keep their
    // existing fast playback path; no global UA/Referer or codec changes.
    private fun inspectFeifan(view: PlayerView) {
        overlay.visibility = View.VISIBLE
        status.text = "正在检查非凡播放地址…"
        retry.visibility = View.GONE
        val target = playbackUrl
        val providedHeaders = runCatching {
            val obj = JSONObject(intent.getStringExtra("headers") ?: "{}")
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
        Thread {
            var detected: String? = null
            var problem: String? = null
            try {
                val connection = java.net.URL(target).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Range", "bytes=0-1023")
                providedHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        problem = "非凡线路服务器返回 HTTP $code\n域名：${connection.url.host.orEmpty()}"
                    } else {
                        val type = connection.contentType.orEmpty().lowercase(Locale.ROOT)
                        val responseHost = connection.url.host.orEmpty()
                        val buf = ByteArray(1024)
                        val size = connection.inputStream.use { stream -> stream.read(buf) }
                        val head = if (size > 0) String(buf, 0, size, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t') else ""
                        detected = when {
                            head.startsWith("#EXTM3U") || "mpegurl" in type -> MimeTypes.APPLICATION_M3U8
                            head.startsWith("<MPD") || head.startsWith("<?xml") && "<MPD" in head || "dash+xml" in type -> MimeTypes.APPLICATION_MPD
                            head.startsWith("<!DOCTYPE html", true) || head.startsWith("<html", true) || "text/html" in type -> {
                                val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                                    .find(head)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(70).orEmpty()
                                problem = "非凡线路返回 HTML 网页（HTTP $code）\n域名：$responseHost" +
                                    (if (title.isNotBlank()) "\n标题：$title" else "") +
                                    "\n响应类型：${type.take(65)}"
                                null
                            }
                            else -> null
                        }
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                problem = "检查非凡线路失败：${e.message.orEmpty().take(130)}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (problem != null) {
                    status.text = problem + "\n请返回选择其他可用线路。"
                    overlay.visibility = View.VISIBLE
                    retry.visibility = View.VISIBLE
                } else {
                    startPlayback(view, detected, skipProbe = true)
                }
            }
        }.start()
    }

    private fun startPlayback(view: PlayerView, probedMime: String? = null, skipProbe: Boolean = false) {
        if (!skipProbe && intent.getStringExtra("playFrom").orEmpty().equals("feifan", ignoreCase = true)) {
            inspectFeifan(view)
            return
        }
        player?.let { if(it.currentPosition>0) resumePosition=it.currentPosition; it.release() }
        player = null
        overlay.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "正在连接视频…"
        retry.visibility = View.GONE
        view.hideController()

        // Media3 cannot decode webpage URLs or some proprietary play-line formats.
        val originalUri = android.net.Uri.parse(playbackUrl)
        val prefs = getSharedPreferences("myfilm", MODE_PRIVATE)
        val defaults = runCatching { JSONObject(assets.open("gateway.json").bufferedReader().use { it.readText() }) }.getOrNull()
        val gateway = prefs.getString("hls_gateway_url", "").orEmpty().ifBlank { defaults?.optString("url").orEmpty() }.trim().trimEnd('/')
        val gatewayToken = prefs.getString("hls_gateway_token", "").orEmpty().ifBlank { defaults?.optString("token").orEmpty() }.trim()
        val useGateway = gateway.startsWith("https://") && gatewayToken.isNotBlank() &&
            Regex("v[0-9]+\\.rstu6\\.com", RegexOption.IGNORE_CASE).matches(originalUri.host.orEmpty()) &&
            originalUri.scheme == "https" && originalUri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
        val uri = if (useGateway) {
            android.net.Uri.parse("$gateway/hls").buildUpon()
                .appendQueryParameter("url", playbackUrl)
                .appendQueryParameter("token", gatewayToken).build()
        } else originalUri
        // Some MacCMS providers use extensionless HLS/DASH URLs.
        // Preserve their vod_play_from label as a media-type hint.
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val playFrom = intent.getStringExtra("playFrom").orEmpty().lowercase(Locale.ROOT)
        val mime = probedMime ?: when {
            useGateway || path.endsWith(".m3u8") || "m3u8" in playFrom || "hls" in playFrom -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || "dash" in playFrom -> MimeTypes.APPLICATION_MPD
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
                        overlay.visibility = View.VISIBLE
                        status.visibility = View.VISIBLE
                        status.text = "正在缓冲视频…"
                    }
                    Player.STATE_READY -> { overlay.visibility = View.GONE; view.hideController() }
                    Player.STATE_ENDED -> overlay.visibility = View.GONE
                    Player.STATE_IDLE -> if (p.playerError == null) {
                        overlay.visibility = View.VISIBLE
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
                val explanation = when {
                    httpError?.responseCode == 403 -> "视频服务器拒绝访问，请检查该线路的访问权限或网络出口。"
                    httpError?.responseCode == 404 || httpError?.responseCode == 410 -> "播放文件或视频分片不存在，请更换有效线路。"
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                        "服务器返回的数据无法识别为视频。可能是 HTML 页面、失效链接或未识别的播放协议。"
                    else -> "可重试或返回详情更换线路。"
                }
                status.text = "播放失败：$reason\n${detail.take(160)}\n\n$explanation"
                overlay.visibility = View.VISIBLE
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
