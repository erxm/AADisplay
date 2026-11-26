package io.github.nitsuya.aa.display.aap

import android.net.LocalSocket
import android.net.LocalSocketAddress
import net.prototux.aare.aap.Transport
import java.io.InputStream
import java.io.OutputStream

/**
 * AAP over localabstract UNIX domain socket：
 * `adb forward tcp:5277 localabstract:aadisplay_hu` 后 DHU 连 localhost:5277 即可，
 * 绕开 Windows 侧 TCP 端口被 Hyper-V/WinNAT 动态保留的问题（bind 报 10013）。
 * 与 AapTcpTransport 同构（Transport 三成员：name/input/output + close）。
 */
class AapLocalSocketTransport(private val socket: LocalSocket) : Transport {

    override val name: String =
        "localabstract:${socket.localSocketAddress?.name ?: "unknown"}"

    override val input: InputStream = socket.inputStream

    override val output: OutputStream = socket.outputStream

    override fun close() {
        runCatching { socket.close() }
    }
}
