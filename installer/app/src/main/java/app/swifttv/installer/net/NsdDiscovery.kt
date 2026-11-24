package app.swifttv.installer.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

enum class NsdType {
    CONNECT,
    PAIRING,
    LEGACY
}

data class NsdEvent(
    val host: String,
    val port: Int,
    val type: NsdType,
    val serviceName: String,
    val lost: Boolean = false
)

/**
 * Discovers ADB mDNS advertisements:
 * - _adb-tls-connect._tcp  (paired wireless-debugging port)
 * - _adb-tls-pairing._tcp  (pairing port shown together with the 6-digit code)
 * - _adb._tcp              (legacy ADB-over-Wi-Fi advertisements)
 */
class NsdDiscovery(
    context: Context,
    private val onEvent: (NsdEvent) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val resolved = ConcurrentHashMap.newKeySet<String>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var started = false

    private data class ServiceType(val type: String, val kind: NsdType)

    private val serviceTypes = listOf(
        ServiceType("_adb-tls-connect._tcp", NsdType.CONNECT),
        ServiceType("_adb-tls-pairing._tcp", NsdType.PAIRING),
        ServiceType("_adb._tcp", NsdType.LEGACY)
    )

    fun start() {
        if (started) return
        val manager = nsdManager
        if (manager == null) {
            onLog("此设备不支持 mDNS 服务发现，将使用网段扫描")
            return
        }
        started = true
        acquireMulticastLock()
        serviceTypes.forEach { service ->
            val listener = createListener(service)
            listeners += listener
            runCatching {
                manager.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                onLog("mDNS 启动失败 ${service.type}: ${it.message}")
            }
        }
        onLog("开始 mDNS 发现（ADB 无线调试服务）")
    }

    fun stop() {
        if (!started) return
        started = false
        val manager = nsdManager ?: return
        listeners.forEach { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        listeners.clear()
        resolving.clear()
        resolved.clear()
        releaseMulticastLock()
    }

    private fun createListener(service: ServiceType): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                onLog("mDNS 已启动：$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val info = serviceInfo ?: return
                // Each listener is registered for one service type; avoid
                // rejecting a valid result over a trailing-dot difference.
                val key = "${service.type}|${info.serviceName}"
                if (!resolving.add(key)) return
                runCatching {
                    manager().resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            resolving.remove(key)
                            if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) {
                                onLog("mDNS 解析失败：${serviceInfo?.serviceName} ($errorCode)")
                            } else {
                                // Retry once after the previous resolution slot frees up.
                                handler.postDelayed({
                                    if (started) resolveRetry(info, service, key)
                                }, 1200)
                            }
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                            resolving.remove(key)
                            val resolvedInfo = serviceInfo ?: return
                            val host = resolvedInfo.host ?: return
                            val address = host.hostAddress ?: return
                            if (host !is Inet4Address || address.contains(":")) {
                                onLog("忽略 IPv6 mDNS 地址：$address")
                                return
                            }
                            val port = resolvedInfo.port
                            if (port <= 0) return
                            val dedupe = "${service.type}|$address|$port"
                            if (!resolved.add(dedupe)) return
                            onEvent(
                                NsdEvent(
                                    host = address,
                                    port = port,
                                    type = service.kind,
                                    serviceName = resolvedInfo.serviceName.orEmpty()
                                )
                            )
                            onLog("mDNS 发现 ${service.kind}: $address:$port (${resolvedInfo.serviceName})")
                        }
                    })
                }.onFailure {
                    resolving.remove(key)
                    onLog("mDNS 解析异常：${info.serviceName} ${it.message}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                val info = serviceInfo ?: return
                val host = info.host?.hostAddress
                if (host != null && info.port > 0) {
                    onEvent(NsdEvent(host, info.port, service.kind, info.serviceName.orEmpty(), lost = true))
                }
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                // No-op: stop() releases the multicast lock for all listeners.
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                onLog("mDNS 启动失败：$serviceType ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                onLog("mDNS 停止失败：$serviceType ($errorCode)")
            }
        }

    private fun resolveRetry(info: NsdServiceInfo, service: ServiceType, key: String) {
        val manager = nsdManager ?: return
        if (!resolving.add(key)) return
        runCatching {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    resolving.remove(key)
                    onLog("mDNS 重试解析失败：${serviceInfo?.serviceName} ($errorCode)")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    resolving.remove(key)
                    val resolvedInfo = serviceInfo ?: return
                    val address = resolvedInfo.host?.hostAddress ?: return
                    if (address.contains(":")) return
                    val dedupe = "${service.type}|$address|${resolvedInfo.port}"
                    if (!resolved.add(dedupe)) return
                    onEvent(
                        NsdEvent(
                            host = address,
                            port = resolvedInfo.port,
                            type = service.kind,
                            serviceName = resolvedInfo.serviceName.orEmpty()
                        )
                    )
                }
            })
        }.onFailure {
            resolving.remove(key)
        }
    }

    private fun manager(): NsdManager =
        nsdManager ?: throw IllegalStateException("NsdManager unavailable")

    private fun acquireMulticastLock() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        runCatching {
            multicastLock = wifi.createMulticastLock("swifttv-installer-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            onLog("多播锁获取失败：${it.message}")
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        }
        multicastLock = null
    }
}
