package app.swifttv.player

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class TlsConfig(val sslSocketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

/**
 * Combines the platform's system root CAs with verified modern public Root CAs exported from JDK cacerts
 * so older Android TV firmware (Android 6–9) can validate HTTPS subscriptions such as GitHub Pages.
 */
object TlsTrust {
    const val BUNDLED_ROOTS_RESOURCE = "/swifttv_extra_roots.pem"

    fun loadBundledCertificates(): List<X509Certificate> {
        val stream = TlsTrust::class.java.getResourceAsStream(BUNDLED_ROOTS_RESOURCE) ?: return emptyList()
        return stream.use { input ->
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificates(input).filterIsInstance<X509Certificate>()
        }
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
        return TlsConfig(sslContext.socketFactory, combinedManager)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
