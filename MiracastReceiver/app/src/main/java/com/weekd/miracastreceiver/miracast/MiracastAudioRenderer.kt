package com.weekd.miracastreceiver.miracast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import timber.log.Timber
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Miracast AAC 音频播放器。
 *
 * WFD 采用 RTP/TS 封装：Windows 把 AAC（ADTS）文本于独立的 TS PID 上发来，
 * 由 [TsDemuxer] 抽出负载后，直接以任意切片喂给本类。它把切片交给 MediaCodec
 * 的 AAC 解码器（解码器对输入块边界自愈），解出的 PCM 经 AudioTrack 连续播放。
 *
 * 刻意自持独立的消费线程 + 浅队列：解码慢时只丢最旧的切片，绝不反压到视频路径。
 * 视频已经过这条"收到即解码、解完即送显"的实时路径，音频保持同样低延迟即可，
 * 音画大体同步靠两侧都不做缓冲来保证（和 VideoDecoder 同源策略）。
 */
class MiracastAudioRenderer {

    private val queue = ArrayBlockingQueue<ByteArray>(64)

    @Volatile
    private var running = false

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var trackStarted = false
    private var thread: Thread? = null

    @Volatile
    private var feedError = false

    /** 从解复用线程喂入一段 AAC(ADTS) 负载；队列满时丢最旧的以保实时。 */
    fun enqueue(data: ByteArray) {
        if (!running) return
        if (!queue.offer(data)) {
            queue.poll()
            queue.offer(data)
        }
    }

    fun start() {
        if (running) return
        running = true
        feedError = false
        thread = Thread({ loop() }, "MiracastAudio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun createCodec() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 0, 0)
            .apply {
                // 音频同样要低延迟：未知的 key 会被忽略，各家平台取各自能认的
                setInteger("low-latency", 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun loop() {
        while (running) {
            // 队列积压超过阈值时，一次性丢弃最旧数据跳到最新（实时优先）
            while (running && queue.size > 48) queue.poll()
            val data = runCatching { queue.poll(100, TimeUnit.MILLISECONDS) }
                .getOrNull() ?: continue
            try {
                if (codec == null) createCodec()
                feedAudio(data)
                drainOutput()
            } catch (e: Exception) {
                if (running) {
                    feedError = true
                    Timber.e(e, "Miracast AAC decode error, dropping this chunk")
                    // 解码器进错误态后自愈无望，重建以恢复
                    runCatching { codec?.stop(); codec?.release() }
                    codec = null
                }
            }
        }
        releaseResources()
        Timber.i("MiracastAudio thread exited")
    }

    private fun feedAudio(data: ByteArray) {
        val c = codec ?: return
        val index = c.dequeueInputBuffer(10_000)
        if (index < 0) return                      // 来不及喂就丢这一片，保实时
        val buf = c.getInputBuffer(index) ?: return
        buf.clear()
        buf.put(data)
        c.queueInputBuffer(index, 0, data.size, 0, 0)
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        // 每次入队后把当前已解出的 PCM 全部倒进 AudioTrack（一般就 1~2 帧）
        var index = c.dequeueOutputBuffer(info, 0)
        while (index >= 0) {
            if (info.size > 0) {
                val out = c.getOutputBuffer(index)
                if (out != null) {
                    val pcm = ByteArray(info.size)
                    out.position(0)
                    out.get(pcm)
                    writeToTrack(pcm)
                }
            }
            c.releaseOutputBuffer(index, false)
            index = c.dequeueOutputBuffer(info, 0)
        }
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            initTrack(c.outputFormat)
        }
    }

    private fun initTrack(format: MediaFormat) {
        val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48000)
        val channelCount = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
        val channelMask = if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        if (sampleRate <= 0 || sampleRate > 192000) return

        val minBuf = runCatching {
            AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(48 * 1024)
        val bufferSize = (minBuf * 2).coerceAtLeast(16 * 1024)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
        trackStarted = true
        Timber.i("Miracast AAC → AudioTrack %dHz %dch".format(sampleRate, channelCount))
    }

    private fun writeToTrack(pcm: ByteArray) {
        val t = track ?: return
        // 摆脱阻塞写：AudioTrack 内部缓冲写满时同步等待，天然控制节奏
        runCatching { t.write(pcm, 0, pcm.size) }
            .onFailure { if (running) Timber.e(it, "AudioTrack write failed") }
    }

    private fun releaseResources() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        if (trackStarted) runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        trackStarted = false
    }

    /** 必须在 [start] 的消费线程退出后再调用，避免 dequeue 与 release 竞争导致 native 崩溃。 */
    fun release() {
        running = false
        thread?.let {
            runCatching { it.join(2000) }
            if (it.isAlive) Timber.w("MiracastAudio thread did not exit in time")
        }
        thread = null
    }
}