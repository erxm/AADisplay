package io.github.nitsuya.aa.display.aap

import net.prototux.aare.aap.Transport
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * 调试用的 TCP 链路：让电脑上的 Desktop Head Unit 直接连上来。
 *
 * DHU 的 ADB 模式是“它主动连 localhost:5277，然后等手机先开口”（已实测：连上后一个字节都不发），
 * 正好符合我们作为 AAP 客户端（TLS client + VersionRequest）的角色，所以只要在隧道另一端
 * 接一个 socket，就能把整条 AAP 链路跑在桌面上，不必真车机。
 */
class AapTcpTransport(private val socket: Socket) : Transport {

    override val name: String
        get() = "tcp ${socket.remoteSocketAddress}"

    override val input: InputStream = socket.getInputStream()

    override val output: OutputStream = socket.getOutputStream()

    override fun close() {
        runCatching { socket.close() }
    }
}
