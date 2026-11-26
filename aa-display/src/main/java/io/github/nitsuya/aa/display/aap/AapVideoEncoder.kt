package io.github.nitsuya.aa.display.aap

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Surface → H.264 / H.265 / VP9 / AV1 编码器。
 *
 * 输入面直接交给 system_server 里的虚拟屏渲染（[io.github.nitsuya.aa.display.xposed.CoreApi.onCreateDisplay]
 * 的 surface 参数），所以这里不需要 MediaProjection，也不需要再抓一次屏。
 *
 * 输出按 AAP 视频通道的约定：每帧 10 字节头部由 [net.prototux.aare.aap.channels.VideoChannel.sendFrame]
 * 自己写，调用方只要保证给的 ByteArray 在 offset 之前留出 10 字节即可。
 */
class AapVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val onCodecConfig: (ByteArray) -> Unit,
    private val onFrame: (buf: ByteArray, offset: Int, length: Int, ptsUs: Long, key: Boolean) -> Unit,
) {

    private companion object {
        const val TAG = AapConsts.TAG
        const val TIMEOUT_US = 10_000L
        const val HEADROOM = 10
    }

    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    /** 诊断计数：每秒打一条，用来判断 SF 挂死时编码器是否还在出帧。 */
    private val outFrames = java.util.concurrent.atomic.AtomicLong()
    private val outBytes = java.util.concurrent.atomic.AtomicLong()
    @Volatile
    private var lastStatsMs = 0L

    @Volatile
    private var running = false

    /** 编码器输入面：把它交给虚拟屏，虚拟屏画什么车机就看到什么。 */
    lateinit var inputSurface: Surface
        private set

    /** 单次 configure/start 的参数组合；失败后释放 codec 再换下一组。 */
    private data class Attempt(val level31: Boolean, val cbr: Boolean)

    fun start() {
        val isAvc = mime == MediaFormat.MIMETYPE_VIDEO_AVC
        // H.264 优先 Baseline + Level 3.1（车机最保守的口味）；Level 3.1 在
        // 高分辨率机型上可能 configure 失败，退到“不指定 level”（仍是 H.264），
        // 再失败则去掉 CBR（部分软件编码器不支持固定码率）。
        val attempts = if (isAvc) {
            listOf(Attempt(level31 = true, cbr = true), Attempt(false, true), Attempt(false, false))
        } else {
            listOf(Attempt(false, true), Attempt(false, false))
        }
        var lastError: Throwable? = null
        for ((i, attempt) in attempts.withIndex()) {
            val c = runCatching { MediaCodec.createEncoderByType(mime) }.getOrElse {
                // 找不到该编码器：换参数也没用，直接抛给上层回退。
                throw it
            }
            try {
                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (isAvc) {
                        // Baseline 不插 B 帧，避免部分车机花屏。
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        if (attempt.level31) {
                            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                        }
                    }
                    if (attempt.cbr) {
                        setInteger(
                            MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        )
                    }
                    setInteger("ts-schema", 0)
                }
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
                codec = c
                running = true
                thread = Thread(::drainLoop, "aap-video-encoder").apply {
                    isDaemon = true
                    start()
                }
                Log.i(
                    TAG,
                    "视频编码器启动 $mime ${width}x$height@$fps bitrate=$bitrate " +
                        "(level31=${attempt.level31}, cbr=${attempt.cbr})",
                )
                return
            } catch (t: Throwable) {
                lastError = t
                // [B1] configure/createInputSurface/start 中途抛异常时必须释放 codec，
                // 否则每次重试泄漏一个 MediaCodec（息屏唤醒自愈反复重试会放大）。
                runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
                runCatching { c.release() }
                Log.w(TAG, "编码器尝试 #${i + 1} 失败（$mime level31=${attempt.level31}）：${t.message}")
            }
        }
        throw lastError ?: IllegalStateException("no encoder attempt for $mime")
    }

    /** 已产出的帧数；息屏/唤醒后用来判断编码器是不是停了。 */
    fun framesEmitted(): Long = outFrames.get()

    /** 车机丢帧后会要求关键帧，这里强制下一条编码为 IDR。 */
    fun requestKeyFrame() {
        val c = codec ?: return
        runCatching {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { Log.w(TAG, "请求关键帧失败", it) }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        if (::inputSurface.isInitialized) runCatching { inputSurface.release() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break
            val index = try {
                c.dequeueOutputBuffer(info, TIMEOUT_US)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "编码器已失效", e)
                break
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = c.outputFormat
                    val csd = buildList {
                        for (name in listOf("csd-0", "csd-1")) {
                            val buf = out.getByteBuffer(name) ?: continue
                            add(buf.toByteArray())
                        }
                    }
                    if (csd.isNotEmpty()) onCodecConfig(csd.reduce { a, b -> a + b })
                }
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size + HEADROOM)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data, HEADROOM, info.size)
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        outFrames.incrementAndGet()
                        outBytes.addAndGet(info.size.toLong())
                        onFrame(data, HEADROOM, info.size, info.presentationTimeUs, key)
                    }
                    runCatching { c.releaseOutputBuffer(index, false) }
                    logStatsMaybe()
                }
                else -> logStatsMaybe()
            }
        }
    }

    /** 每秒一条：编码器还在出帧说明视频管线活着，卡住的那一套是 SF 侧的合成。 */
    private fun logStatsMaybe() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatsMs < 1_000L) return
        lastStatsMs = now
        Log.i(TAG, "enc stats out=${outFrames.get()} bytes=${outBytes.get()}")
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        position(0)
        val out = ByteArray(remaining())
        get(out)
        return out
    }
}
