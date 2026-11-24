package app.swifttv.installer.net

import app.swifttv.installer.LocalNetwork
import app.swifttv.installer.Subnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

object LanScanner {

    suspend fun findOpenHosts(
        network: LocalNetwork,
        ports: List<Int> = listOf(5555),
        timeoutMs: Int = 220,
        concurrency: Int = 32,
        onProgress: (String) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val range = Subnet.localRange(network.ipAddress, network.prefixLength)
            ?: return@withContext emptyList()
        val hosts = range.hosts.filter { it != network.ipAddress }
        if (hosts.isEmpty()) return@withContext emptyList()

        val open = mutableListOf<String>()
        val done = AtomicInteger(0)
        val total = hosts.size
        val semaphore = Semaphore(concurrency)

        coroutineScope {
            hosts.forEach { host ->
                launch {
                    semaphore.withPermit {
                        val isOpen = ports.any { port -> canConnect(host, port, timeoutMs) }
                        if (isOpen) {
                            synchronized(open) { open += host }
                        }
                        val current = done.incrementAndGet()
                        if (current == total || current % 16 == 0) {
                            onProgress("已扫描 $current/$total")
                        }
                    }
                }
            }
        }
        onProgress("扫描完成，发现 ${open.size} 个开放端口")
        open.distinct()
    }

    private fun canConnect(host: String, port: Int, timeoutMs: Int): Boolean {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
