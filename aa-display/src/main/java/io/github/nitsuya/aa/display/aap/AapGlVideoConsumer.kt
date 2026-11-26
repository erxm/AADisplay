package io.github.nitsuya.aa.display.aap

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 路线 A 实验：虚拟屏的 surface 不直接交给硬件编码器，而是先落进本进程的
 * [SurfaceTexture]（一个 GL 消费者），再由 GL 把它画进编码器输入面。
 *
 * 目的：原方案（AA 版）里虚拟屏的消费者是 gearhead 车机 UI 里的 TextureView
 * ——一个每帧主动拉取的 GL 消费者，模块从未让 system_server 的虚拟屏把 buffer
 * 直接交给 MediaCodec 的 GraphicBufferSource。今天 SurfaceFlinger 挂死的现场
 * 恰好都是“编码器一帧没出”。这里把消费路径改回与原方案同构（VD → SurfaceTexture
 * → GL → 编码器），用来判定挂死是否与“VD 直连编码器输入面”有关。
 *
 * 结构参考 screenrecord：EGL 上下文 + SurfaceTexture.updateTexImage + 全屏 shader
 * 画到编码器 surface（eglSwapBuffers 即投给编码器）。
 */
class AapGlVideoConsumer(
    private val width: Int,
    private val height: Int,
    private val encoderSurface: Surface,
) {

    private companion object {
        const val TAG = AapConsts.TAG

        private const val VERTEX_SHADER =
            "attribute vec2 aPos;\n" +
                "uniform mat4 uTexMat;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
                "  vTexCoord = (uTexMat * vec4((aPos + 1.0) / 2.0, 0.0, 1.0)).xy;\n" +
                "}"

        private const val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES uTex;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTex, vTexCoord);\n" +
                "}"

        private val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    }

    /** 交给虚拟屏的 surface：编码内容先落进它背后的 SurfaceTexture。 */
    var surface: Surface? = null
        private set

    private var thread: Thread? = null

    @Volatile
    private var stopped = false

    private val frameSignal = Semaphore(0)

    /** 诊断：实际画进编码器的帧数（drawFrame 计数），runLoop 每秒打一条。 */
    private val framesDrawn = java.util.concurrent.atomic.AtomicLong()
    private val framesSignaled = java.util.concurrent.atomic.AtomicLong()
    @Volatile
    private var lastGlStatsMs = 0L

    // GL 状态（仅 GL 线程访问）
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var texture: SurfaceTexture? = null
    private var program = 0
    private var aPosLoc = 0
    private var uTexMatLoc = 0
    private var uTexLoc = 0
    private val texMat = FloatArray(16)
    private val quadBuffer: java.nio.FloatBuffer =
        ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(QUAD); position(0) }

    /**
     * 启动 GL 线程并阻塞到初始化完成（或失败抛出）。
     * 成功后 [surface] 可用，可交给 system_server 的虚拟屏当输出面。
     */
    fun start() {
        val ready = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        val worker = Thread({
            try {
                initGl()
                ready.countDown()
                runLoop()
            } catch (t: Throwable) {
                failure[0] = t
                ready.countDown()
            } finally {
                cleanup()
            }
        }, "aap-gl-consumer")
        worker.isDaemon = true
        thread = worker
        worker.start()
        if (!ready.await(5, TimeUnit.SECONDS)) {
            stopped = true
            frameSignal.release()
            worker.join(2000)
            throw IllegalStateException("GL consumer start timeout")
        }
        failure[0]?.let {
            stopped = true
            throw it
        }
        Log.i(TAG, "GL 消费者就绪：${width}x$height -> 编码器 surface")
    }

    fun stop() {
        stopped = true
        frameSignal.release()
        thread?.join(2000)
        thread = null
    }

    // ------------------------------------------------------------ GL 线程内部

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val cfgAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, num, 0) && num[0] > 0
        ) { "eglChooseConfig failed" }
        val config = configs[0]!!

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) { "eglMakeCurrent(pbuffer) failed" }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(width, height)
        // 回调放主线程，只 release 一个信号量；GL 线程阻塞在 tryAcquire 上，
        // 不能让它自己收回调（同线程会被阻塞吞掉消息）。
        st.setOnFrameAvailableListener({
            framesSignaled.incrementAndGet()
            frameSignal.release()
        }, Handler(Looper.getMainLooper()))
        texture = st
        surface = Surface(st)

        program = buildProgram()
        encoderEgl = EGL14.eglCreateWindowSurface(
            eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(encoderEgl != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface(encoder) failed" }
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        aPosLoc = GLES20.glGetAttribLocation(p, "aPos")
        uTexMatLoc = GLES20.glGetUniformLocation(p, "uTexMat")
        uTexLoc = GLES20.glGetUniformLocation(p, "uTex")
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun runLoop() {
        while (!stopped) {
            if (!frameSignal.tryAcquire(500, TimeUnit.MILLISECONDS)) continue
            val t = texture ?: break
            drawFrame(t)
        }
    }

    private fun drawFrame(st: SurfaceTexture) {
        val n = framesDrawn.incrementAndGet()
        if (n == 1L) Log.i(TAG, "GL 消费者第一帧到达")
        st.updateTexImage()
        st.getTransformMatrix(texMat)
        EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uTexMatLoc, 1, false, texMat, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosLoc)
        // SurfaceTexture 的 timestamp（纳秒）作为编码器的 presentation time。
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEgl, st.timestamp)
        EGL14.eglSwapBuffers(eglDisplay, encoderEgl)
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
        logGlStatsMaybe()
    }

    /** 每秒一条：虚拟屏有没有帧进来、GL 有没有画出去，直接决定“无帧”卡在哪一级。 */
    private fun logGlStatsMaybe() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGlStatsMs < 1_000L) return
        lastGlStatsMs = now
        Log.i(TAG, "GL stats signaled=${framesSignaled.get()} drawn=${framesDrawn.get()}")
    }

    private fun cleanup() {
        val t = texture
        texture = null
        runCatching { t?.release() }
        val s = surface
        surface = null
        runCatching { s?.release() }
        if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
        program = 0
        if (encoderEgl != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, encoderEgl) }
        encoderEgl = EGL14.EGL_NO_SURFACE
        if (pbuffer != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, pbuffer) }
        pbuffer = EGL14.EGL_NO_SURFACE
        if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
        eglContext = EGL14.EGL_NO_CONTEXT
        // 不调 eglTerminate：这里持有的是进程级默认 EGLDisplay，
        // terminate 会波及同一进程里其它 EGL 使用者（HWUI 等）。
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }
}
