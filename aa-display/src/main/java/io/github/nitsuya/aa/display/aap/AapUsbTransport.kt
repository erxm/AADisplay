package io.github.nitsuya.aa.display.aap

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import net.prototux.aare.aap.Transport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * USB AOA（Android Open Accessory）上的 AAP 链路。
 *
 * 车机在 USB 上以 accessory 主机身份出现，手机侧 `openAccessory()` 拿到一对原始流，
 * 之后 AAP 的帧格式、TLS 握手都由 [net.prototux.aare.aap.AapSession] 负责，这里只当管道。
 */
class AapUsbTransport(
    private val usbManager: UsbManager,
    private val accessory: UsbAccessory,
) : Transport {

    private var pfd: ParcelFileDescriptor? = null

    override val input: InputStream
    override val output: OutputStream

    init {
        val fd = usbManager.openAccessory(accessory)
            ?: throw IllegalStateException("openAccessory 返回 null（没有授权，或 accessory 已被占用）")
        pfd = fd
        input = FileInputStream(fd.fileDescriptor)
        output = FileOutputStream(fd.fileDescriptor)
    }

    override val name: String
        get() = "usb ${accessory.manufacturer}/${accessory.model}"

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { pfd?.close() }
        pfd = null
    }
}
