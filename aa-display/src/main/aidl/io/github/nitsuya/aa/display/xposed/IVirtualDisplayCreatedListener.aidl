package io.github.nitsuya.aa.display.xposed;

interface IVirtualDisplayCreatedListener {
    /**
     * 建屏结果回调。
     *
     * 必须是 oneway：调用方在 system_server 主线程，同步跨进程回调会阻塞主线程，
     * binder 还会打 `Outgoing transactions from this process must be FLAG_ONEWAY`。
     * 客户端本来就只是记下 displayId 再 runMain 处理，异步语义完全一致。
     */
    oneway void onAvailableDisplay(int displayId, boolean create);
}