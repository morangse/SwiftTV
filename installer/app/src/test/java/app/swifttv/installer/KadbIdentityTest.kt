package app.swifttv.installer

import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KadbIdentityTest {

    @Test
    fun `Kadb generates and persists an adb identity`() {
        // Kadb 2.1.1 is compiled for Java 21. The normal Gradle build runs
        // tests on JDK 17, so skip this one there instead of failing the suite.
        val javaVersion = System.getProperty("java.version").orEmpty()
        val feature = if (javaVersion.startsWith("1.")) {
            javaVersion.split('.').getOrNull(1)?.toIntOrNull()
        } else {
            javaVersion.substringBefore('.').toIntOrNull()
        }
        assumeTrue((feature ?: 0) >= 21)

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
}
