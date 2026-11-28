package app.swifttv.installer

import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey

class KadbIdentityTest {

    private fun assumeJava21OrNewer() {
        // Kadb 2.1.x is compiled for Java 21. The normal Gradle build runs
        // tests on JDK 17, so skip this one there instead of failing the suite.
        val javaVersion = System.getProperty("java.version").orEmpty()
        val feature = if (javaVersion.startsWith("1.")) {
            javaVersion.split('.').getOrNull(1)?.toIntOrNull()
        } else {
            javaVersion.substringBefore('.').toIntOrNull()
        }
        assumeTrue((feature ?: 0) >= 21)
    }

    @Test
    fun `Kadb generates and persists an adb identity`() {
        assumeJava21OrNewer()

        val dir = Files.createTempDirectory("kadb-identity").toFile()
        val keyFile = File(dir, "adb/adbkey.pem")
        KadbCert.configure(
            store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()),
            policy = KadbCertPolicy(
                subject = KadbCertPolicy.Subject(
                    cn = "SwiftTV Installer",
                    o = "SwiftTV",
                    c = "CN"
                )
            )
        )

        val identity = KadbCert.ensureReady()

        assertTrue(identity.privateKeyPem.isNotEmpty())
        assertTrue(identity.certificatePem.isNotEmpty())
        assertTrue(identity.privateKeyPem.decodeToString().contains("PRIVATE KEY"))
        assertTrue(keyFile.isFile && keyFile.length() > 0)
    }

    @Test
    fun `Kadb derives public key from non-CRT RSAPrivateKey without Issue 3 failure`() {
        assumeJava21OrNewer()

        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val realPrivate = pair.private as RSAPrivateKey
        val nonCrtKey: PrivateKey = object : RSAPrivateKey {
            override fun getAlgorithm(): String = realPrivate.algorithm
            override fun getFormat(): String = realPrivate.format
            override fun getEncoded(): ByteArray = realPrivate.encoded
            override fun getModulus(): BigInteger = realPrivate.modulus
            override fun getPrivateExponent(): BigInteger = realPrivate.privateExponent
        }
        assertFalse(nonCrtKey is RSAPrivateCrtKey)

        val certUtilsClass = Class.forName("com.flyfishxu.kadb.cert.CertUtils")
        val instance = certUtilsClass.getField("INSTANCE").get(null)
        val deriveMethod = certUtilsClass.methods.first {
            it.name == "derivePublicKey" || it.name.startsWith("derivePublicKey$")
        }
        val derivedPublic = deriveMethod.invoke(instance, nonCrtKey) as PublicKey
        assertArrayEquals(pair.public.encoded, derivedPublic.encoded)
    }
}
