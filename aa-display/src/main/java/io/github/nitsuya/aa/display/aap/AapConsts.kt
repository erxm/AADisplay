package io.github.nitsuya.aa.display.aap

/**
 * AAP 出口（本地自用分支）用到的常量。
 *
 * 这套通道的目的是把“车机画面”从 Google gearhead 的私有通道换成 AARE 的开源 AAP 实现：
 * 手机侧仍然是本模块创建虚拟屏喂内容，只是把编码后的 H.264 直接说给车机听。
 */
object AapConsts {
    const val TAG = "AADisplay_Aap"

    /** 证书与私钥落盘目录（相对 filesDir）。 */
    const val IDENTITY_DIR = "aap"

    /** 车机侧会记住手机证书；自签证书必须跨会话稳定，否则每次连接都要重新配对。 */
    const val CERT_FILE = "phone.crt"
    const val KEY_FILE = "phone.key"

    /** 投屏路线（AADisplayConfig.AapRoute）：aap = 自研 AAP 通道；legacy = 原版（hook gearhead）。 */
    const val ROUTE_AAP = "aap"
    const val ROUTE_LEGACY = "legacy"

    /** 调试用：adb 广播拉起/停止 AAP 出口（不对外暴露能力，只用于本地验证）。 */
    const val ACTION_START = "io.github.nitsuya.aa.display.local.aap.action.START"
    const val ACTION_STOP = "io.github.nitsuya.aa.display.local.aap.action.STOP"

    /**
     * 调试用：起一个本地 TCP 监听，等 Desktop Head Unit 连进来（DHU 连上后等手机先说话，
     * 与本模块作为 AAP 客户端的角色一致）。仅在 debug 构建里可用。
     */
    const val ACTION_START_TCP = "io.github.nitsuya.aa.display.local.aap.action.START_TCP"
    /** 无线 AAP：蓝牙发现车机后自动连接（或在设置里手动触发）。 */
    const val ACTION_START_WIRELESS = "io.github.nitsuya.aa.display.local.aap.action.START_WIRELESS"
    const val EXTRA_TCP_PORT = "aap_tcp_port"
    const val DEFAULT_TCP_PORT = 5278

    /** 车机给的视频配置里没有 800x480 时的兜底尺寸。 */
    const val FALLBACK_WIDTH = 800
    const val FALLBACK_HEIGHT = 480
    const val FALLBACK_DPI = 170
    const val FALLBACK_FPS = 30
}
