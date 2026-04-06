package com.v2ray.ang.service

/**
 * JNI bindings for hev-socks5-tunnel. The native library exports symbols
 * under the Java package com/v2ray/ang/service, so this class must live
 * in exactly that package for JNI symbol resolution to work.
 */
object TProxyService {
    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyStopService()

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyGetStats(): LongArray?

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
