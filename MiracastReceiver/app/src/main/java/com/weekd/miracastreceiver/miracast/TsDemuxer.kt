package com.weekd.miracastreceiver.miracast

import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * MPEG-2 传输流解复用器，从 Miracast 的 RTP 负载里抽出 H.264 访问单元。
 *
 * Miracast 的视频不是裸 H.264，而是封装成 TS 再走 RTP（负载类型 33），
 * 每个 RTP 包携带 7 个 188 字节 TS 包。这里只做「够用」的解析：
 *
 * ```
 * PID 0 → PAT → 找到 PMT 的 PID
 * PMT   → 找到 stream_type 0x1B (H.264) 的视频 PID
 * 视频 PID → PES → 负载即 Annex B 格式的 H.264，直接喂给 MediaCodec
 * ```
 *
 * 刻意不走 ExoPlayer：播放器带缓冲区和时钟同步，对第二屏幕这种实时用途会引入
 * 秒级延迟（实测超过 10 秒）。这里收到一个访问单元就立刻回调，由解码器直接送显。
 *
 * @param onAccessUnit 收到完整访问单元时回调（Annex B 字节 + PTS 微秒）
 * @param onDiscontinuity 检测到丢包时回调，接收方应丢弃到下一个关键帧
 */
class TsDemuxer(
    private val onAccessUnit: (data: ByteArray, ptsUs: Long) -> Unit,
    private val onDiscontinuity: () -> Unit = {}
) {

    private var pmtPid = -1
    private var videoPid = -1

    private val pes = ByteArrayOutputStream(256 * 1024)
    private var pendingPts = 0L
    private var hasPending = false

    /** 视频 PID 上一个包的连续计数器，用于发现 TS 层丢包 */
    private var lastContinuity = -1
    private var lossCount = 0

    companion object {
        private const val TS_PACKET_SIZE = 188
        private const val SYNC_BYTE = 0x47.toByte()
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_HEVC = 0x24
    }

    /** 喂入一段 TS 数据（必须是 188 字节包对齐的，RTP 负载天然满足）。 */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        var pos = offset
        val end = offset + length
        while (pos + TS_PACKET_SIZE <= end) {
            if (data[pos] != SYNC_BYTE) {
                pos++            // 失步了，逐字节找回同步字节
                continue
            }
            handlePacket(data, pos)
            pos += TS_PACKET_SIZE
        }
    }

    private fun handlePacket(data: ByteArray, start: Int) {
        val b1 = data[start + 1].toInt() and 0xFF
        val b2 = data[start + 2].toInt() and 0xFF
        val b3 = data[start + 3].toInt() and 0xFF

        if ((b1 and 0x80) != 0) return                       // transport_error_indicator
        val payloadUnitStart = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2
        val adaptationControl = (b3 shr 4) and 0x03

        if (adaptationControl == 0 || adaptationControl == 2) return   // 无负载

        var payloadStart = start + 4
        if (adaptationControl == 3) {
            val adaptationLength = data[start + 4].toInt() and 0xFF
            payloadStart = start + 5 + adaptationLength
        }
        val payloadEnd = start + TS_PACKET_SIZE
        if (payloadStart >= payloadEnd) return

        when (pid) {
            0 -> parsePat(data, payloadStart, payloadEnd, payloadUnitStart)
            pmtPid -> parsePmt(data, payloadStart, payloadEnd, payloadUnitStart)
            videoPid -> {
                // 连续计数器每包 +1（模 16），跳变即代表 TS 层丢包。
                // 丢了包的帧一定是残缺的，喂给解码器只会花屏，还会沿帧间预测扩散。
                val continuity = b3 and 0x0F
                if (lastContinuity >= 0 && continuity != (lastContinuity + 1) % 16) {
                    lossCount++
                    Timber.w("TS: continuity gap ($lastContinuity → $continuity), total $lossCount")
                    discardPending()
                    onDiscontinuity()
                }
                lastContinuity = continuity
                parseVideoPes(data, payloadStart, payloadEnd, payloadUnitStart)
            }
        }
    }

    /** 丢弃正在拼装的残缺帧。 */
    private fun discardPending() {
        pes.reset()
        hasPending = false
    }

    /** PAT：取第一个有效节目的 PMT PID。 */
    private fun parsePat(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
        if (pmtPid >= 0) return
        var pos = start
        if (payloadUnitStart) pos += 1 + (data[pos].toInt() and 0xFF)   // pointer_field
        if (pos + 12 > end) return
        if ((data[pos].toInt() and 0xFF) != 0x00) return                // table_id 必须是 PAT

        val sectionLength = ((data[pos + 1].toInt() and 0x0F) shl 8) or (data[pos + 2].toInt() and 0xFF)
        // 节目循环从 section 头之后开始，末尾 4 字节是 CRC
        var entry = pos + 8
        val loopEnd = minOf(pos + 3 + sectionLength - 4, end)
        while (entry + 4 <= loopEnd) {
            val programNumber = ((data[entry].toInt() and 0xFF) shl 8) or (data[entry + 1].toInt() and 0xFF)
            val pid = ((data[entry + 2].toInt() and 0x1F) shl 8) or (data[entry + 3].toInt() and 0xFF)
            if (programNumber != 0) {
                pmtPid = pid
                Timber.i("TS: PMT pid = $pmtPid (program $programNumber)")
                return
            }
            entry += 4
        }
    }

    /** PMT：找出 H.264（或 HEVC）基本流的 PID。 */
    private fun parsePmt(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
        if (videoPid >= 0) return
        var pos = start
        if (payloadUnitStart) pos += 1 + (data[pos].toInt() and 0xFF)
        if (pos + 12 > end) return
        if ((data[pos].toInt() and 0xFF) != 0x02) return                // table_id 必须是 PMT

        val sectionLength = ((data[pos + 1].toInt() and 0x0F) shl 8) or (data[pos + 2].toInt() and 0xFF)
        val programInfoLength = ((data[pos + 10].toInt() and 0x0F) shl 8) or (data[pos + 11].toInt() and 0xFF)

        var entry = pos + 12 + programInfoLength
        val loopEnd = minOf(pos + 3 + sectionLength - 4, end)
        while (entry + 5 <= loopEnd) {
            val streamType = data[entry].toInt() and 0xFF
            val pid = ((data[entry + 1].toInt() and 0x1F) shl 8) or (data[entry + 2].toInt() and 0xFF)
            val esInfoLength = ((data[entry + 3].toInt() and 0x0F) shl 8) or (data[entry + 4].toInt() and 0xFF)

            if (streamType == STREAM_TYPE_H264 || streamType == STREAM_TYPE_HEVC) {
                videoPid = pid
                Timber.i("TS: video pid = $videoPid (stream_type 0x%02X)".format(streamType))
                return
            }
            entry += 5 + esInfoLength
        }
    }

    /**
     * 视频 PES。视频 PES 的 packet_length 通常是 0（不定长），所以只能靠下一个
     * payload_unit_start 来判断上一帧结束 —— 这带来约一帧的固有延迟，无法避免。
     */
    private fun parseVideoPes(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
        var pos = start

        if (payloadUnitStart) {
            flushPes()                                       // 上一帧到此为止，先送出去

            if (pos + 9 > end) return
            // packet_start_code_prefix 必须是 00 00 01
            if (data[pos].toInt() != 0x00 || data[pos + 1].toInt() != 0x00 ||
                (data[pos + 2].toInt() and 0xFF) != 0x01
            ) return

            val flags2 = data[pos + 7].toInt() and 0xFF
            val headerDataLength = data[pos + 8].toInt() and 0xFF
            val ptsPresent = (flags2 and 0x80) != 0

            if (ptsPresent && pos + 14 <= end) {
                pendingPts = readPts(data, pos + 9)
            }
            hasPending = true
            pos += 9 + headerDataLength
            if (pos >= end) return
        }

        if (hasPending) pes.write(data, pos, end - pos)
    }

    /** PES 头里的 33 位 PTS，单位 90kHz，转成微秒。 */
    private fun readPts(data: ByteArray, p: Int): Long {
        val b0 = data[p].toLong() and 0xFF
        val b1 = data[p + 1].toLong() and 0xFF
        val b2 = data[p + 2].toLong() and 0xFF
        val b3 = data[p + 3].toLong() and 0xFF
        val b4 = data[p + 4].toLong() and 0xFF
        val pts90k = ((b0 and 0x0E) shl 29) or
            (b1 shl 22) or ((b2 and 0xFE) shl 14) or
            (b3 shl 7) or ((b4 and 0xFE) shr 1)
        return pts90k * 1000 / 90
    }

    private fun flushPes() {
        if (!hasPending || pes.size() == 0) {
            pes.reset()
            return
        }
        val unit = pes.toByteArray()
        pes.reset()
        onAccessUnit(unit, pendingPts)
    }

    fun reset() {
        pmtPid = -1
        videoPid = -1
        lastContinuity = -1
        discardPending()
    }
}
