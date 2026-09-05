package com.weekd.miracastreceiver.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.airplay.StreamStats
import com.weekd.miracastreceiver.miracast.RtpReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URL

/**
 * 投屏播放页面
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MEDIA_TITLE = "media_title"
        const val EXTRA_MEDIA_URIS = "media_uris"
        const val EXTRA_MEDIA_TITLES = "media_titles"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_IS_AIRPLAY_MIRROR = "is_airplay_mirror"

        // Miracast 额外参数
        const val EXTRA_SOURCE_TYPE = "SOURCE_TYPE"
        const val EXTRA_RTP_PORT = "RTP_PORT"
        const val EXTRA_SESSION_ID = "SESSION_ID"

        // 广播 Action
        const val ACTION_PLAY = "com.weekd.miracastreceiver.ACTION_PLAY"
        const val ACTION_PAUSE = "com.weekd.miracastreceiver.ACTION_PAUSE"
        const val ACTION_STOP = "com.weekd.miracastreceiver.ACTION_STOP"
        const val ACTION_SEEK = "com.weekd.miracastreceiver.ACTION_SEEK"
        const val ACTION_SET_VOLUME = "com.weekd.miracastreceiver.ACTION_SET_VOLUME"
        const val ACTION_SET_PLAYLIST = "com.weekd.miracastreceiver.ACTION_SET_PLAYLIST"
        const val ACTION_SET_SPEED = "com.weekd.miracastreceiver.ACTION_SET_SPEED"
        const val ACTION_SET_QUALITY_URL = "com.weekd.miracastreceiver.ACTION_SET_QUALITY_URL"

        const val EXTRA_SEEK_POSITION = "seek_position"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_QUALITY_URI = "quality_uri"

        @Volatile
        var mirrorSurface: Surface? = null
            private set

        private const val IMAGE_SLIDE_INTERVAL_MS = 5_000L
        private const val QUALITY_AUTO = -1
        // DLNA 切集时 Stop→SetURI 连发：这个窗口内收到新 SetURI 就复用本页无缝续播，
        // 窗口结束仍无新片源才真正退出，避免闪回主屏并重建播放器。
        private const val STOP_DEBOUNCE_MS = 1_200L
        // 向下方向键跳"末尾"时留的最后余量：跳到末尾前这段，让播放器走真实播完再切下一集
        private const val SEEK_TO_END_MARGIN_MS = 1_000L

        // Kodi 风格连续快进快退：短时间内连续按键会加大跳转步长
        private const val SEEK_ACCEL_WINDOW_MS = 1_500L
        private const val SEEK_COMMIT_DELAY_MS = 500L
        private val SEEK_STEP_TABLE_MS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L)

        private const val ACTION_PLAYBACK_STOPPED = "com.weekd.miracastreceiver.ACTION_PLAYBACK_STOPPED"
    }

    private lateinit var playerView: PlayerView
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var tvStreamInfo: TextView
    private lateinit var bufferingIndicator: ProgressBar

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var mediaUri: String? = null
    private var mediaTitle: String? = null
    private var playlist: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var currentIndex: Int = 0
    private var slideJob: Job? = null
    private var progressUpdateJob: Job? = null
    private var qualityHeight: Int = QUALITY_AUTO
    private var isMiracastSession = false
    private var isAirPlayMirrorSession = false
    private var mirrorAspectJob: Job? = null
    private var currentSpeed = 1f

    // 视频流信息面板
    private val streamInfoTracker = StreamInfoTracker()
    private var streamInfoJob: Job? = null
    private var isStreamInfoVisible = false
    private var bandwidthEstimateBps = 0L

    // Kodi 风格连续快进快退状态
    private var pendingSeekDeltaMs = 0L
    private var seekAccelerationStep = 0
    private var lastSeekWasForward = true
    private var lastSeekPressAt = 0L
    private var seekCommitJob: Job? = null
    private var isDialogShowing = false
    private var stopDebounceJob: Job? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY -> if (isCurrentImage()) startImageSlideShow() else player?.play()
                ACTION_PAUSE -> if (isCurrentImage()) stopImageSlideShow() else player?.pause()
                ACTION_STOP -> {
                    // 镜像/Miracast 断开：立即关播放页（它们用 mirrorSurface 直接送显，
                    // 没有可续接的片源，留着只会停在最后一帧）。
                    if (isMiracastSession || isAirPlayMirrorSession) {
                        stopPlayback()
                        finish()
                        return
                    }
                    // DLNA：抖音切集常是 Stop→SetURI 连发。立即 finish 会闪回主屏
                    // （“等待投屏连接”）并重建整个播放器（慢）。改为软停 + 短窗口：
                    // 窗口内收到新 SetURI 就无缝续播，没来才真正退出。
                    stopDebounceJob?.cancel()
                    player?.stop()
                    tvStatus.text = "已停止"
                    updateBufferingState(false)
                    stopDebounceJob = lifecycleScope.launch {
                        delay(STOP_DEBOUNCE_MS)
                        // 只在页面仍在前台时退出；切到后台等情况不强行 finish
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            stopPlayback()
                            finish()
                        }
                    }
                }
                ACTION_SEEK -> {
                    val position = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                    player?.seekTo(position)
                    reportPlaybackPosition()
                }
                ACTION_SET_VOLUME -> {
                    val volume = intent.getIntExtra(EXTRA_VOLUME, 50)
                    player?.volume = volume / 100f
                }
                ACTION_SET_PLAYLIST -> handleIntent(intent)
                ACTION_SET_SPEED -> {
                    val speed = intent.getFloatExtra(EXTRA_SPEED, 1f).coerceIn(0.25f, 4f)
                    player?.setPlaybackSpeed(speed)
                    currentSpeed = speed
                    tvStatus.text = "播放速度：${speed}x"
                    reportPlaybackPosition()
                }
                ACTION_SET_QUALITY_URL -> {
                    val uri = intent.getStringExtra(EXTRA_QUALITY_URI)
                    if (!uri.isNullOrBlank()) {
                        playMedia(uri)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        initViews()
        initPlayer()
        handleIntent(intent)
        registerControlReceiver()

        Timber.i("PlayerActivity created")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        mirrorSurfaceView = findViewById(R.id.airplay_mirror_surface)
        imageView = findViewById(R.id.image_view)
        tvStatus = findViewById(R.id.tv_status)
        tvTitle = findViewById(R.id.tv_title)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        tvStreamInfo = findViewById(R.id.tv_stream_info)
        bufferingIndicator = findViewById(R.id.buffering_indicator)
    }

    /**
     * @param lowLatency 实时投屏（Miracast）用。默认的缓冲策略是为点播设计的
     *   （起播要缓冲 2.5 秒、重缓冲后要 5 秒），而实时流的数据严格按实时速率到达，
     *   永远攒不出那么多缓冲，会陷入「解几帧 → 缓冲耗尽 → 转圈」的循环。
     */
    private fun initPlayer(lowLatency: Boolean = false) {
        player?.release()

        // 默认不限制分辨率（对应画质菜单的「自动」）。原先默认 setMaxVideoSizeSd() 会把所有
        // 播放压到标清，Miracast 的 1080p 视频轨会被直接排除，导致选不出轨道、
        // loader 停止加载，最终报 "stuck buffering and not loading"。
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().clearVideoSizeConstraints())
        }

        val loadControl = DefaultLoadControl.Builder()
            .apply {
                if (lowLatency) {
                    // maxBufferMs 不能压太小：到达上限后 ExoPlayer 会停止读取数据源，
                    // 而 RTP 仍按实时速率灌进管道，管道溢出丢数据就会把 TS 流打出空洞，
                    // 解码器拿不到完整 PES 直接黑屏。留出足够余量让它持续排空管道。
                    setBufferDurationsMs(
                        /* minBufferMs = */ 1_000,
                        /* maxBufferMs = */ 8_000,
                        /* bufferForPlaybackMs = */ 500,
                        /* bufferForPlaybackAfterRebufferMs = */ 1_000
                    )
                    setPrioritizeTimeOverSizeThresholds(true)
                }
            }
            .build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateBufferingState(playbackState == Player.STATE_BUFFERING)
                        when (playbackState) {
                            Player.STATE_IDLE -> Timber.d("Player state: IDLE")
                            Player.STATE_BUFFERING -> {
                                Timber.d("Player state: BUFFERING")
                                tvStatus.text = "正在缓冲..."
                            }
                            Player.STATE_READY -> {
                                Timber.d("Player state: READY")
                                tvError.visibility = View.GONE
                                tvStatus.text = getString(R.string.playing)
                                adaptOrientationToVideo()
                                startProgressUpdates()
                            }
                            Player.STATE_ENDED -> {
                                Timber.d("Player state: ENDED")
                                tvStatus.text = "播放完成"
                                reportPlaybackPosition()
                                playNextOrFinish()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // 缓冲中 isPlaying 也是 false，此时别把 onPlaybackStateChanged
                        // 刚写好的「正在缓冲...」覆盖成「已暂停」，那会让人以为是暂停了
                        tvStatus.text = when {
                            isPlaying -> getString(R.string.playing)
                            playbackState == Player.STATE_READY -> "已暂停"
                            else -> return
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Timber.e(error, "Player error")
                        updateBufferingState(false)
                        tvStatus.text = "播放错误"
                        tvError.text = "播放错误: ${error.message ?: "未知错误"}"
                        tvError.visibility = View.VISIBLE
                    }
                })
                // 视频信息面板的「网速」取自带宽估计（ExoPlayer 默认的 DefaultBandwidthMeter 采样）
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long
                    ) {
                        bandwidthEstimateBps = bitrateEstimate
                    }
                })
            }

        playerView.player = player
        // 控制条（含进度条）默认隐藏：切到下一个视频/开始播放时不再自动弹出来。
        // 需要时仍可通过按下确认键 / 点按画面手动呼出。
        playerView.setControllerAutoShow(false)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                findViewById<View?>(R.id.status_bar)?.visibility = visibility
                // 控制条显隐仅用于隐藏顶部状态栏；左右方向键现在始终用作 seek，不再依赖此标记
            })
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        setupControllerActions()
    }

    private fun setupControllerActions() {
        playerView.findViewById<View?>(R.id.btn_more)?.setOnClickListener { showMoreMenu() }
        playerView.findViewById<View?>(R.id.exo_ffwd)?.setOnClickListener { handleSeekPress(forward = true) }
        playerView.findViewById<View?>(R.id.exo_rew)?.setOnClickListener { handleSeekPress(forward = false) }
    }

    /** 「更多」弹窗：控制条精简后，低频功能都收到这里。 */
    private fun showMoreMenu() {
        val labels = arrayOf(
            if (isStreamInfoVisible) "关闭视频信息" else "视频信息",
            "画质",
            "播放速度（${formatSpeedLabel(currentSpeed)}）",
            "字幕",
            "屏幕方向"
        )
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(labels) { _, which ->
                // 本弹窗关闭后再打开下一级弹窗，否则 onDismiss 会把 isDialogShowing 误置回 false
                tvStatus.post {
                    when (which) {
                        0 -> toggleStreamInfo()
                        1 -> showQualityDialog()
                        2 -> showSpeedDialog()
                        3 -> Toast.makeText(this, "字幕切换将随媒体字幕轨自动支持", Toast.LENGTH_SHORT).show()
                        4 -> toggleOrientation()
                    }
                }
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun formatSpeedLabel(speed: Float): String =
        if (speed == 1f) "1.0x" else "${speed}x"

    /**
     * 参考 Kodi 的连续快进快退：短时间内连续按键会累加跳转步长并放大跨度，
     * 松开按键一段时间后再统一提交一次 seek，避免频繁 seek 造成反复缓冲。
     */
    private fun handleSeekPress(forward: Boolean) {
        if (isCurrentImage() || player == null) return
        val now = SystemClock.elapsedRealtime()
        val withinAccelWindow = now - lastSeekPressAt <= SEEK_ACCEL_WINDOW_MS
        seekAccelerationStep = if (withinAccelWindow && forward == lastSeekWasForward) {
            (seekAccelerationStep + 1).coerceAtMost(SEEK_STEP_TABLE_MS.lastIndex)
        } else {
            pendingSeekDeltaMs = 0L
            0
        }
        lastSeekWasForward = forward
        lastSeekPressAt = now

        val step = SEEK_STEP_TABLE_MS[seekAccelerationStep]
        pendingSeekDeltaMs += if (forward) step else -step

        previewPendingSeek()

        seekCommitJob?.cancel()
        seekCommitJob = lifecycleScope.launch {
            delay(SEEK_COMMIT_DELAY_MS)
            commitPendingSeek()
        }
    }

    private fun previewPendingSeek() {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (currentPlayer.currentPosition + pendingSeekDeltaMs).coerceIn(0L, duration)
        val sign = if (pendingSeekDeltaMs >= 0) "+" else "-"
        val deltaSeconds = kotlin.math.abs(pendingSeekDeltaMs) / 1000
        tvStatus.text = "${sign}${deltaSeconds}s  ${formatTimeMs(target)}"
        playerView.showController()
    }

    private fun commitPendingSeek() {
        val currentPlayer = player ?: return
        if (pendingSeekDeltaMs == 0L) return
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (currentPlayer.currentPosition + pendingSeekDeltaMs).coerceIn(0L, duration)
        currentPlayer.seekTo(target)
        pendingSeekDeltaMs = 0L
        seekAccelerationStep = 0
        reportPlaybackPosition()
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !isDialogShowing) {
            // 频道键切集：- 下一个、+ 上一个（用户定义的键位映射）
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    playNextVideo()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    playPrevVideo()
                    return true
                }
            }
            // DPAD 下/上：下 = 跳到当前视频末尾（自然播完进下一集），上 = 跳到开头
            if (!isCurrentImage()) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        seekToEnd()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        player?.seekTo(0)
                        return true
                    }
                }
            }
            // 镜像/Miracast 没有控制条，INFO / MENU 键是信息面板的唯一入口
            if (event.keyCode == KeyEvent.KEYCODE_INFO || event.keyCode == KeyEvent.KEYCODE_MENU) {
                toggleStreamInfo()
                return true
            }
            // 面板打开时返回键先关面板，不要直接结束播放
            if (event.keyCode == KeyEvent.KEYCODE_BACK && isStreamInfoVisible) {
                hideStreamInfo()
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && !isCurrentImage() && !isDialogShowing) {
            val isForwardKey = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            val isRewindKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            if (isForwardKey || isRewindKey) {
                // 左右方向键/快进快退键始终用作 seek：点按 = 单步跳，
                // 长按 = key repeat 自动连发，handleSeekPress 会累加步长并加速（Kodi 风格）。
                // 不再看是否控制条可见 —— 该遥控把 21/22 定位成独立的快进/快退键。
                handleSeekPress(forward = isForwardKey)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_SOURCE_TYPE) == "MIRACAST") {
            startMiracastPlayback(intent.getIntExtra(EXTRA_RTP_PORT, 0), intent.getStringExtra(EXTRA_SESSION_ID))
            return
        }

        if (intent?.getBooleanExtra(EXTRA_IS_AIRPLAY_MIRROR, false) == true) {
            startAirPlayMirrorPlayback()
            return
        }

        val list = intent?.getStringArrayListExtra(EXTRA_MEDIA_URIS)
            ?: intent?.getStringExtra(EXTRA_MEDIA_URI)?.let { arrayListOf(it) }
            ?: arrayListOf()
        val titles = intent?.getStringArrayListExtra(EXTRA_MEDIA_TITLES)
            ?: intent?.getStringExtra(EXTRA_MEDIA_TITLE)?.let { arrayListOf(it) }
            ?: arrayListOf()

        playlist = list.filter { it.isNotBlank() }
        playlistTitles = titles
        currentIndex = intent?.getIntExtra(EXTRA_START_INDEX, 0)?.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)) ?: 0

        if (playlist.isNotEmpty()) {
            playCurrent()
        }
    }

    private fun playCurrent() {
        // 新的 DLNA 片源来了：若正处于 Stop 软停窗口，取消到期的自动退出，无缝续播
        cancelStopDebounce()
        mediaUri = playlist.getOrNull(currentIndex)
        mediaTitle = playlistTitles.getOrNull(currentIndex) ?: "DLNA 投屏 ${currentIndex + 1}/${playlist.size}"
        tvTitle.text = mediaTitle
        val uri = mediaUri ?: return
        if (isImageUri(uri)) {
            showImage(uri)
        } else {
            playMedia(uri)
        }
    }

    private fun startAirPlayMirrorPlayback() {
        Timber.i("Starting AirPlay mirror playback")
        isAirPlayMirrorSession = true
        isMiracastSession = false
        streamInfoTracker.reset()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopImageSlideShow()
        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        tvTitle.text = "iPhone 屏幕镜像"
        tvStatus.text = "正在接收 iPhone 屏幕..."
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        publishMirrorSurface()
    }

    /**
     * 把镜像 SurfaceView 的 Surface 发布给解码器。AirPlay 镜像和 Miracast 共用这一块
     * Surface —— 两者都是「解码器直接送显」的实时镜像，不经过 ExoPlayer。
     *
     * 应用切后台时 SurfaceView 会销毁 Surface、回到前台再造一个新的，所以必须持续跟踪
     * 变化并置空，否则解码器会往失效的 Surface 上写，画面一直黑。
     */
    private fun publishMirrorSurface() {
        val waitingText = if (isMiracastSession) "等待 Windows 画面..." else "等待 AirPlay 显示画面..."
        val activeText = if (isMiracastSession) "正在接收 Windows 屏幕..." else "正在接收 iPhone 屏幕..."

        fun publish(holder: SurfaceHolder) {
            mirrorSurface = holder.surface.takeIf { it.isValid }
            Timber.i("Mirror surface ${if (mirrorSurface == null) "not ready" else "ready"}")
            tvStatus.text = if (mirrorSurface == null) waitingText else activeText
        }

        mirrorSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = publish(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = publish(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.i("Mirror surface destroyed")
                mirrorSurface = null
            }
        })
        mirrorSurfaceView.post { publish(mirrorSurfaceView.holder) }
        startMirrorAspectFitUpdates()
    }

    private fun startMirrorAspectFitUpdates() {
        mirrorAspectJob?.cancel()
        mirrorAspectJob = lifecycleScope.launch {
            var lastW = 0
            var lastH = 0
            while (isActive) {
                val videoW = StreamStats.videoWidth
                val videoH = StreamStats.videoHeight
                if (videoW > 0 && videoH > 0 && (videoW != lastW || videoH != lastH)) {
                    lastW = videoW
                    lastH = videoH
                    fitMirrorSurface(videoW, videoH)
                }
                delay(300)
            }
        }
    }

    private fun fitMirrorSurface(videoW: Int, videoH: Int) {
        val parent = mirrorSurfaceView.parent as? View ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return

        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()
        val (targetW, targetH) = if (videoAspect > parentAspect) {
            parentW to (parentW / videoAspect).toInt()
        } else {
            (parentH * videoAspect).toInt() to parentH
        }

        mirrorSurfaceView.layoutParams = mirrorSurfaceView.layoutParams.apply {
            width = targetW.coerceAtLeast(1)
            height = targetH.coerceAtLeast(1)
        }
        Timber.i("AirPlay mirror aspect-fit: video=${videoW}x$videoH view=${targetW}x$targetH parent=${parentW}x$parentH")
    }

    /**
     * Miracast 显示。RTP 接收、TS 解复用和解码都由
     * [com.weekd.miracastreceiver.miracast.WfdServer] 那条链路完成，这里只负责把镜像
     * Surface 交出去 —— 和 AirPlay 镜像完全同一套机制。
     *
     * 刻意不经过 ExoPlayer：播放器的缓冲和时钟同步会引入秒级延迟（实测超过 10 秒），
     * 而第二屏幕这种用途要的是「收到即解码、解完即送显」。
     */
    private fun startMiracastPlayback(rtpPort: Int, sessionId: String?) {
        Timber.i("Starting Miracast display: rtpPort=$rtpPort session=$sessionId")
        isMiracastSession = true
        isAirPlayMirrorSession = false
        streamInfoTracker.reset()
        stopImageSlideShow()

        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        imageView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE
        tvTitle.text = "Windows 无线显示器"
        tvStatus.text = "正在接收 Windows 屏幕..."
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        publishMirrorSurface()
    }

    private fun playMedia(uri: String) {
        Timber.i("Playing media: $uri")
        isMiracastSession = false
        isAirPlayMirrorSession = false
        streamInfoTracker.reset()
        stopImageSlideShow()
        imageView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        try {
            tvError.visibility = View.GONE
            updateBufferingState(true)

            // 根据 URI 判断媒体类型
            val mediaItem = createMediaItem(uri)

            val items = playlist.mapIndexedNotNull { index, itemUri ->
                if (!isImageUri(itemUri)) {
                    createMediaItem(itemUri).also { if (index == currentIndex) mediaUri = itemUri }
                } else null
            }
            if (playlist.size > 1 && items.isNotEmpty()) {
                val videoIndex = playlist.take(currentIndex + 1).count { !isImageUri(it) } - 1
                player?.setMediaItems(items, videoIndex.coerceAtLeast(0), 0L)
            } else {
                player?.setMediaItem(mediaItem)
            }
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            Timber.e(e, "Error playing media")
            tvStatus.text = "播放错误"
            tvError.text = "播放错误: ${e.message ?: "未知错误"}"
            tvError.visibility = View.VISIBLE
            updateBufferingState(false)
        }
    }

    private fun createMediaItem(uri: String): MediaItem {
        // 检测 HLS 流
        val isHls = uri.contains(".m3u8") || uri.contains("/playlist/m3u8")

        return if (isHls) {
            // HLS 流：明确指定 MIME 类型
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        } else {
            // 其他格式：让 ExoPlayer 自动检测
            MediaItem.fromUri(uri)
        }
    }

    private fun showImage(uri: String) {
        Timber.i("Showing image: $uri")
        player?.pause()
        playerView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        tvStatus.text = "正在显示图片"
        updateBufferingState(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    if (uri.startsWith("http://") || uri.startsWith("https://")) {
                        URL(uri).openStream().use { BitmapFactory.decodeStream(it) }
                    } else {
                        contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                    }
                }
                updateBufferingState(false)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    adaptOrientationToImage(bitmap.width, bitmap.height)
                    startImageSlideShow()
                } else {
                    throw IllegalArgumentException("无法加载图片")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error showing image")
                updateBufferingState(false)
                tvStatus.text = "图片加载错误"
                tvError.text = "图片加载错误: ${e.message ?: "未知错误"}"
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun startImageSlideShow() {
        if (playlist.count { isImageUri(it) } <= 1) return
        slideJob?.cancel()
        slideJob = lifecycleScope.launch {
            while (isActive && isCurrentImage()) {
                delay(IMAGE_SLIDE_INTERVAL_MS)
                playNextOrFinish(loopImages = true)
            }
        }
    }

    private fun stopImageSlideShow() {
        slideJob?.cancel()
        slideJob = null
        if (isCurrentImage()) tvStatus.text = "图片轮播已暂停"
    }

    private fun playNextOrFinish(loopImages: Boolean = false) {
        if (playlist.isEmpty()) return
        if (currentIndex < playlist.lastIndex) {
            currentIndex++
            playCurrent()
        } else if (loopImages) {
            currentIndex = 0
            playCurrent()
        } else {
            updateBufferingState(false)
            reportPlaybackStopped()
        }
    }

    /** 遥控遥控键切集（备用映射）：频道 - 下一个、+ 上一个。播放列表为空时是空操作。 */
    private fun playNextVideo() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playCurrent()
    }

    /**
     * 跳到当前视频末尾前 3 秒，让它自然播完并进入下一个视频。
     *
     * 故意不到精确末尾：seek 到 duration 时部分片源会直接进 ENDED 或停在末尾黑场，
     * 检查不到正常的"播完→切下一集"。留 3 秒 Play（SEEK_TO_END_MARGIN_MS）让播放器
     * 走真实的播完流程。未上报时长（如直播/流媒体）时跳过，避免误 seek 到最大值卡死。
     */
    private fun seekToEnd() {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: return
        currentPlayer.seekTo((duration - SEEK_TO_END_MARGIN_MS).coerceAtLeast(0L))
    }

    private fun playPrevVideo() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex - 1 + playlist.size) % playlist.size
        playCurrent()
    }

    private fun isCurrentImage(): Boolean = mediaUri?.let { isImageUri(it) } == true

    private fun isImageUri(uri: String): Boolean {
        val clean = uri.substringBefore('?').lowercase()
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".gif") || clean.endsWith(".webp") || clean.endsWith(".bmp") ||
            clean.startsWith("content://") && clean.contains("image")
    }

    private fun updateBufferingState(isBuffering: Boolean) {
        progressBar.visibility = if (isBuffering) View.VISIBLE else View.GONE
        bufferingIndicator.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    // ─── 视频流信息面板 ────────────────────────────────────────────────────
    /** 切换信息面板。镜像/Miracast 没有控制条，只能靠遥控 INFO / MENU 键触发。 */
    private fun toggleStreamInfo() {
        if (isStreamInfoVisible) hideStreamInfo() else showStreamInfo()
    }

    private fun showStreamInfo() {
        isStreamInfoVisible = true
        streamInfoTracker.reset()
        tvStreamInfo.text = buildStreamInfoText()
        tvStreamInfo.visibility = View.VISIBLE
        streamInfoJob?.cancel()
        streamInfoJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                tvStreamInfo.text = buildStreamInfoText()
            }
        }
    }

    private fun hideStreamInfo() {
        isStreamInfoVisible = false
        streamInfoJob?.cancel()
        streamInfoJob = null
        tvStreamInfo.visibility = View.GONE
    }

    private fun buildStreamInfoText(): String = when {
        isAirPlayMirrorSession -> buildAirPlayStreamInfo()
        isMiracastSession -> buildMiracastStreamInfo()
        else -> buildExoPlayerStreamInfo()
    }

    /** DLNA / 普通网络播放：分辨率、帧率、码率取自当前视频轨，网速取带宽估计。 */
    private fun buildExoPlayerStreamInfo(): String {
        val currentPlayer = player
        val format = currentPlayer?.videoFormat
        val videoSize = currentPlayer?.videoSize
        val bitrateBps = listOfNotNull(format?.bitrate, format?.averageBitrate, format?.peakBitrate)
            .firstOrNull { it != Format.NO_VALUE }?.toLong() ?: 0L
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(videoSize?.width ?: 0, videoSize?.height ?: 0),
            codec = StreamInfoTracker.formatCodec(format?.sampleMimeType),
            fps = StreamInfoTracker.formatFps(format?.frameRate ?: 0f),
            bitrate = StreamInfoTracker.formatBitrate(bitrateBps),
            speed = StreamInfoTracker.formatSpeed(bandwidthEstimateBps / 8)
        )
    }

    /** AirPlay 镜像：码率只算视频流，网速把音频流字节也算进去。 */
    private fun buildAirPlayStreamInfo(): String {
        val videoBytes = StreamStats.videoBytesTotal
        val totalBytes = videoBytes + StreamStats.audioBytesTotal
        val sample = streamInfoTracker.sample(totalBytes, StreamStats.videoFramesTotal)
        // 视频码率 = 总码率中扣掉音频部分，按本次采样的视频/总字节比例折算
        val videoShare = if (totalBytes > 0) videoBytes.toDouble() / totalBytes else 1.0
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(StreamStats.videoWidth, StreamStats.videoHeight),
            codec = StreamInfoTracker.formatCodec(StreamStats.videoCodec),
            fps = StreamInfoTracker.formatFps(sample.fps.toFloat()),
            bitrate = StreamInfoTracker.formatBitrate((sample.bitrateBps * videoShare).toLong()),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec)
        )
    }

    /**
     * Miracast：不经过 ExoPlayer，所以分辨率取解码器上报的值（[StreamStats]，由
     * VideoDecoder 写入），码率和网速取 [RtpReceiver] 的累计字节数。
     * 帧率暂不统计 —— 解码路径上没有帧计数器。
     */
    private fun buildMiracastStreamInfo(): String {
        val sample = streamInfoTracker.sample(RtpReceiver.active?.bytesReceived ?: 0L, 0L)
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(StreamStats.videoWidth, StreamStats.videoHeight),
            codec = StreamInfoTracker.formatCodec("video/avc"),
            fps = StreamInfoTracker.formatFps(0f),
            bitrate = StreamInfoTracker.formatBitrate(sample.bitrateBps),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec)
        )
    }

    private fun streamInfoLines(
        resolution: String,
        codec: String,
        fps: String,
        bitrate: String,
        speed: String
    ): String {
        val (displayW, displayH) = currentDisplaySize()
        val rows = listOf(
            "源分辨率" to resolution,
            "显示分辨率" to StreamInfoTracker.formatResolution(displayW, displayH),
            "视频编码" to codec,
            "帧率" to fps,
            "码率" to bitrate,
            "网速" to speed
        )
        return rows.joinToString("\n", prefix = "视频信息\n") { (label, value) ->
            StreamInfoTracker.padLabel(label) + value
        }
    }

    /**
     * 画面实际渲染到屏幕上的像素尺寸。
     * PlayerView 会按比例把内容框缩到片源宽高比，所以这里拿到的是去掉黑边后的真实显示尺寸；
     * 镜像模式则读 [fitMirrorSurface] 调整过的镜像 Surface。视图还没测量时退回屏幕分辨率。
     */
    private fun currentDisplaySize(): Pair<Int, Int> {
        val renderView = if (isAirPlayMirrorSession) mirrorSurfaceView else playerView.videoSurfaceView
        val width = renderView?.width ?: 0
        val height = renderView?.height ?: 0
        if (width > 0 && height > 0) return width to height
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun showQualityDialog() {
        val labels = arrayOf("自动", "流畅 480p", "高清 720p", "超清 1080p", "原画")
        val heights = intArrayOf(QUALITY_AUTO, 480, 720, 1080, Int.MAX_VALUE)
        val checked = heights.indexOf(qualityHeight).takeIf { it >= 0 } ?: 0
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("选择画质")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                qualityHeight = heights[which]
                applyQuality(qualityHeight)
                dialog.dismiss()
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun applyQuality(height: Int) {
        val builder = trackSelector?.buildUponParameters() ?: return
        when (height) {
            QUALITY_AUTO -> builder.clearVideoSizeConstraints()
            Int.MAX_VALUE -> builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            else -> builder.setMaxVideoSize(Int.MAX_VALUE, height)
        }
        trackSelector?.setParameters(builder)
        tvStatus.text = if (height == QUALITY_AUTO) "画质：自动" else "画质：${height}p"
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setItems(labels) { _, which ->
                player?.setPlaybackSpeed(speeds[which])
                currentSpeed = speeds[which]
                tvStatus.text = "播放速度：${labels[which]}"
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun adaptOrientationToVideo() {
        // TV 端固定横屏显示，竖屏视频由播放器按比例居中渲染。
        // 不再根据视频宽高切换 Activity 方向，避免 Surface 重建导致竖屏投屏反复缓冲/黑屏。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun adaptOrientationToImage(width: Int, height: Int) {
        requestedOrientation = if (width >= height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun registerControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_STOP)
            addAction(ACTION_SEEK)
            addAction(ACTION_SET_VOLUME)
            addAction(ACTION_SET_PLAYLIST)
            addAction(ACTION_SET_SPEED)
            addAction(ACTION_SET_QUALITY_URL)
        }
        // 同上：带 flags 的 registerReceiver 是 API 26 起才有的重载
        ContextCompat.registerReceiver(
            this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStart() {
        super.onStart()
        if (isCurrentImage()) startImageSlideShow() else {
            player?.play()
            startProgressUpdates()
        }
        if (isStreamInfoVisible) showStreamInfo()
    }

    override fun onStop() {
        super.onStop()
        if (isCurrentImage()) stopImageSlideShow() else player?.pause()
        stopProgressUpdates()
        // 保留 isStreamInfoVisible，回到前台时自动恢复刷新
        streamInfoJob?.cancel()
        streamInfoJob = null
        reportPlaybackPosition()
    }

    private fun stopPlayback() {
        cancelStopDebounce()
        stopImageSlideShow()
        stopProgressUpdates()
        mirrorAspectJob?.cancel()
        mirrorAspectJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player?.stop()
        reportPlaybackStopped()
    }

    /** 取消 DLNA Stop 软停窗口，继续复用本页（收到新片源时调用）。 */
    private fun cancelStopDebounce() {
        stopDebounceJob?.cancel()
        stopDebounceJob = null
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressUpdateJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                reportPlaybackPosition()
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    private fun reportPlaybackPosition() {
        val currentPlayer = player ?: return
        val position = currentPlayer.currentPosition
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: 0L

        // 发送广播以更新 DLNA renderer 状态
        val intent = Intent("com.weekd.miracastreceiver.ACTION_UPDATE_POSITION").apply {
            putExtra("position", position)
            putExtra("duration", duration)
            putExtra("is_playing", currentPlayer.isPlaying)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * TV 端本地退出/播放结束时通知 DLNA 渲染器状态归零，
     * 否则 Emby 会一直认为该设备处于播放中，无法取消投屏或切换到新文件。
     */
    private fun reportPlaybackStopped() {
        val intent = Intent(ACTION_PLAYBACK_STOPPED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering receiver")
        }
        stopImageSlideShow()
        mirrorAspectJob?.cancel()
        mirrorAspectJob = null
        streamInfoJob?.cancel()
        streamInfoJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // RTP 接收器归 WfdServer 所有（会话结束时由它停止），这里不要碰
        mirrorSurface = null
        player?.release()
        player = null
        reportPlaybackStopped()
        Timber.i("PlayerActivity destroyed")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        stopPlayback()
        super.onBackPressed()
    }
}
