package app.swifttv.player

import android.os.Build
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class TlsConfig(val sslSocketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

/**
 * Combines the platform's system root CAs with verified modern public Root CAs exported from JDK cacerts
 * so older Android TV firmware (Android 4.4–9) can validate HTTPS subscriptions and enable TLS 1.2.
 */
object TlsTrust {
    const val BUNDLED_ROOTS_RESOURCE = "/swifttv_extra_roots.pem"
    private val PREFERRED_TLS_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")

    fun loadBundledCertificates(): List<X509Certificate> {
        val stream = TlsTrust::class.java.getResourceAsStream(BUNDLED_ROOTS_RESOURCE) ?: return emptyList()
        return stream.use { input ->
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificates(input).filterIsInstance<X509Certificate>()
        }
    }

    internal fun enableModernTlsProtocols(supported: Array<String>, enabled: Array<String>): Array<String> {
        val supportedSet = supported.toSet()
        val combined = LinkedHashSet<String>()
        PREFERRED_TLS_PROTOCOLS.forEach { proto ->
            if (proto in supportedSet) combined.add(proto)
        }
        enabled.forEach { proto ->
            if (proto in supportedSet) combined.add(proto)
        }
        return if (combined.isEmpty()) enabled else combined.toTypedArray()
    }

    fun create(
        extraCertificates: List<X509Certificate> = loadBundledCertificates(),
        includeSystemDefaults: Boolean = true
    ): TlsConfig {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val seenHashes = HashSet<String>()

        fun addUnique(alias: String, cert: X509Certificate) {
            val digest = sha256Hex(cert.encoded)
            if (seenHashes.add(digest)) {
                keyStore.setCertificateEntry(alias, cert)
            }
        }

        if (includeSystemDefaults) {
            val defaultFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            defaultFactory.init(null as KeyStore?)
            val defaultManager = defaultFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            defaultManager?.acceptedIssuers?.forEachIndexed { index, cert ->
                addUnique("sys-$index", cert)
            }
        }

        extraCertificates.forEachIndexed { index, cert ->
            addUnique("extra-$index", cert)
        }

        val combinedFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        combinedFactory.init(keyStore)
        val combinedManager = combinedFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: error("Unable to initialize combined X509TrustManager")
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(combinedManager), null)
        }
        val factory: SSLSocketFactory = if (Build.VERSION.SDK_INT in 16..19) {
            Tls12EnablingSocketFactory(sslContext.socketFactory)
        } else {
            sslContext.socketFactory
        }
        return TlsConfig(factory, combinedManager)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private class Tls12EnablingSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = patch(delegate.createSocket())
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        patch(delegate.createSocket(s, host, port, autoClose))
    override fun createSocket(host: String, port: Int): Socket = patch(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        patch(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = patch(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        patch(delegate.createSocket(address, port, localAddress, localPort))

    private fun patch(socket: Socket): Socket {
        if (socket is SSLSocket) {
            socket.enabledProtocols = TlsTrust.enableModernTlsProtocols(socket.supportedProtocols, socket.enabledProtocols)
        }
        return socket
    }
}
