package app.swifttv.installer

import java.net.Inet4Address
import java.net.InetAddress

/**
 * IPv4 subnet helpers. A home LAN is usually /24; oversized ranges are narrowed
 * to the phone's own /24 so a scan never turns into a multi-thousand-host sweep.
 */
object Subnet {

    private const val MAX_HOSTS = 1022

    data class Range(
        val networkAddress: String,
        val prefixLength: Int,
        val hosts: List<String>,
        val narrowed: Boolean
    )

    fun localRange(ipAddress: String, prefixLength: Int): Range? {
        val ip = ipv4ToLong(ipAddress) ?: return null
        if (ip <= 0L) return null
        val prefix = normalizePrefix(prefixLength)
        var network = ip and maskFor(prefix)
        var effectivePrefix = prefix
        var narrowed = false

        // Decide from the host count before materialising the list. Building a
        // /8 range first would allocate ~16 million strings and OOM the phone.
        if (hostCount(prefix) > MAX_HOSTS) {
            effectivePrefix = 24
            network = ip and maskFor(effectivePrefix)
            narrowed = true
        }

        return Range(
            networkAddress = longToIp(network),
            prefixLength = effectivePrefix,
            hosts = hostList(network, effectivePrefix),
            narrowed = narrowed
        )
    }

    fun normalizePrefix(prefixLength: Int): Int = when {
        prefixLength !in 1..32 -> 24
        prefixLength > 30 -> 24
        prefixLength < 8 -> 24
        else -> prefixLength
    }

    fun ipv4ToLong(ipAddress: String): Long? {
        val parts = ipAddress.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun longToIp(value: Long): String {
        val normalized = value and 0xFFFFFFFFL
        return listOf(
            (normalized ushr 24) and 0xFF,
            (normalized ushr 16) and 0xFF,
            (normalized ushr 8) and 0xFF,
            normalized and 0xFF
        ).joinToString(".")
    }

    fun isIpv4Address(address: InetAddress): Boolean =
        address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress

    private fun maskFor(prefix: Int): Long =
        (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    private fun hostCount(prefix: Int): Long {
        if (prefix >= 31) return 0L
        return (1L shl (32 - prefix)) - 2L
    }

    private fun hostList(network: Long, prefix: Int): List<String> {
        val broadcast = network or (0xFFFFFFFFL xor maskFor(prefix))
        val first = network + 1
        val last = broadcast - 1
        if (last < first) return emptyList()
        val count = (last - first + 1).toInt()
        return (0 until count).map { longToIp(first + it) }
    }
}
