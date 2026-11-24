package app.swifttv.installer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import app.swifttv.installer.LocalNetwork
import app.swifttv.installer.Subnet
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    fun localNetwork(context: Context): LocalNetwork? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivity != null) {
            // Prefer a real Wi-Fi/Ethernet interface over activeNetwork: when a
            // VPN is active, activeNetwork is the VPN and its link addresses
            // cannot reach the TV's LAN.
            val candidates = runCatching { connectivity.allNetworks.toList() }
                .getOrElse { emptyList() }
                .mapNotNull { network -> networkCandidate(connectivity, network) }
                .sortedBy { candidate ->
                    when (candidate.transport) {
                        "Wi-Fi" -> 0
                        "以太网" -> 1
                        else -> 2
                    }
                }
            candidates.firstOrNull()?.let { return it }
        }
        return fallbackInterface()
    }

    private fun networkCandidate(
        connectivity: ConnectivityManager,
        network: android.net.Network
    ): LocalNetwork? {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> return null
        }
        val link = connectivity.getLinkProperties(network) ?: return null
        val linkAddress = link.linkAddresses.firstOrNull {
            it.address is Inet4Address &&
                !it.address.isLoopbackAddress &&
                !it.address.isLinkLocalAddress
        } ?: return null
        val address = linkAddress.address as Inet4Address
        return LocalNetwork(
            ipAddress = address.hostAddress ?: return null,
            prefixLength = linkAddress.prefixLength,
            interfaceName = link.interfaceName.orEmpty(),
            transport = transport
        )
    }

    @Suppress("DEPRECATION")
    fun isWifiConnected(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val info: NetworkInfo = connectivity.activeNetworkInfo ?: return false
        return info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
    }

    private fun fallbackInterface(): LocalNetwork? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrElse { return null }
            .filter { !it.isLoopback && it.isUp }
            .sortedBy { iface ->
                val name = iface.name.lowercase()
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") -> 2
                    else -> 3
                }
            }

        for (iface in candidates) {
            val address = iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?: continue
            val prefix = iface.interfaceAddresses
                .firstOrNull { it.address == address }
                ?.networkPrefixLength
                ?.toInt()
                ?: 24
            val transport = when {
                iface.name.startsWith("wlan") -> "Wi-Fi"
                iface.name.startsWith("eth") -> "以太网"
                else -> iface.name
            }
            return LocalNetwork(
                ipAddress = address.hostAddress ?: continue,
                prefixLength = prefix,
                interfaceName = iface.name,
                transport = transport
            )
        }
        return null
    }

    fun rangeFor(network: LocalNetwork): Subnet.Range? =
        Subnet.localRange(network.ipAddress, network.prefixLength)
}
