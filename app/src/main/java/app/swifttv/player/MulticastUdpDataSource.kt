package app.swifttv.player

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketException

/** Strips RFC 3550 RTP headers from UDP datagrams when encapsulated over RTP so TsExtractor receives clean MPEG-TS packets. */
internal object RtpPacket {
    fun extractPayload(packet: ByteArray, length: Int, scheme: String): ByteArray {
        if (length <= 0) return ByteArray(0)
        // Raw MPEG-TS packet always starts with sync byte 0x47; pass straight through even if labeled rtp://.
        if (packet[0] == 0x47.toByte()) return packet.copyOf(length)

        val version = (packet[0].toInt() ushr 6) and 0x03
        if (version != 2 || length < 12) {
            return packet.copyOf(length)
        }

        val csrcCount = packet[0].toInt() and 0x0F
        var headerLen = 12 + csrcCount * 4
        if (headerLen >= length) return packet.copyOf(length)

        val hasExtension = (packet[0].toInt() and 0x10) != 0
        if (hasExtension) {
            if (headerLen + 4 > length) return packet.copyOf(length)
            val extWords = ((packet[headerLen + 2].toInt() and 0xFF) shl 8) or (packet[headerLen + 3].toInt() and 0xFF)
            headerLen += 4 + extWords * 4
        }

        var end = length
        val hasPadding = (packet[0].toInt() and 0x20) != 0
        if (hasPadding && end > headerLen) {
            val padCount = packet[end - 1].toInt() and 0xFF
            if (padCount in 1..(end - headerLen)) {
                end -= padCount
            }
        }

        if (headerLen >= end) return ByteArray(0)
        val isRtpScheme = scheme.equals("rtp", ignoreCase = true)
        val payloadStartsWithTs = packet[headerLen] == 0x47.toByte()
        return if (isRtpScheme || payloadStartsWithTs) {
            packet.copyOfRange(headerLen, end)
        } else {
            packet.copyOf(length)
        }
    }
}

/**
 * DataSource for udp:// and rtp:// multicast/unicast live streams with WifiManager.MulticastLock,
 * udp://@host:port normalization, large kernel receive buffers, and transparent RTP header stripping.
 */
@UnstableApi
class MulticastUdpDataSource(
    context: Context,
    private val maxPacketSize: Int = 8192,
    private val socketTimeoutMillis: Int = 8000,
    private val receiveBufferBytes: Int = 512 * 1024
) : BaseDataSource(/* isNetwork = */ true) {

    private val appContext = context.applicationContext
    private val packetBuffer = ByteArray(maxPacketSize)
    private var socket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var multicastAddress: InetAddress? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var currentScheme: String = "udp"
    private var pending: ByteArray = ByteArray(0)
    private var pendingOffset = 0

    override fun open(dataSpec: DataSpec): Long {
        val rawUri = dataSpec.uri
        currentUri = rawUri
        currentScheme = rawUri.scheme.orEmpty().lowercase()
        val (host, port) = resolveHostPort(rawUri)
        if (port !in 1..65535) {
            throw IOException("UDP/RTP 地址缺少有效端口")
        }

        transferInitializing(dataSpec)
        try {
            val address = if (host.isNotEmpty()) InetAddress.getByName(host) else null
            if (address != null && address.isMulticastAddress) {
                acquireMulticastLock()
                val ms = MulticastSocket(null).apply {
                    reuseAddress = true
                    soTimeout = socketTimeoutMillis
                    runCatching { receiveBufferSize = receiveBufferBytes }
                    bind(InetSocketAddress(port))
                    @Suppress("DEPRECATION")
                    joinGroup(address)
                }
                multicastAddress = address
                multicastSocket = ms
                socket = ms
            } else {
                val ds = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = socketTimeoutMillis
                    runCatching { receiveBufferSize = receiveBufferBytes }
                    bind(if (address != null && !address.isAnyLocalAddress) InetSocketAddress(address, port) else InetSocketAddress(port))
                }
                socket = ds
            }
            opened = true
            pending = ByteArray(0)
            pendingOffset = 0
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        } catch (e: IOException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw IOException("无法打开 UDP/RTP 组播流：${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (pendingOffset >= pending.size) {
            val activeSocket = socket ?: return C.RESULT_END_OF_INPUT
            val datagram = DatagramPacket(packetBuffer, packetBuffer.size)
            try {
                activeSocket.receive(datagram)
            } catch (e: SocketException) {
                if (!opened) return C.RESULT_END_OF_INPUT
                throw e
            }
            val payload = RtpPacket.extractPayload(packetBuffer, datagram.length, currentScheme)
            if (payload.isNotEmpty()) {
                pending = payload
                pendingOffset = 0
            }
        }
        val available = pending.size - pendingOffset
        val count = minOf(available, length)
        System.arraycopy(pending, pendingOffset, buffer, offset, count)
        pendingOffset += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        pending = ByteArray(0)
        pendingOffset = 0
        val ms = multicastSocket
        val addr = multicastAddress
        if (ms != null && addr != null) {
            @Suppress("DEPRECATION")
            runCatching { ms.leaveGroup(addr) }
        }
        multicastSocket = null
        multicastAddress = null
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
        multicastLock = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun acquireMulticastLock() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wifi.createMulticastLock("SwiftTV-Multicast").apply {
            setReferenceCounted(false)
        }
        runCatching { lock.acquire() }
        multicastLock = lock
    }

    companion object {
        internal fun resolveHostPort(uri: Uri): Pair<String, Int> {
            val clean = uri.toString().replaceFirst(Regex("^(?i)(udp|rtp)://@"), "$1://")
            val parsed = Uri.parse(clean)
            val host = parsed.host.orEmpty().trim()
            return host to parsed.port
        }
    }
}
