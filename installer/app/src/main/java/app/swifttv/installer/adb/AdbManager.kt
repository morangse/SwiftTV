package app.swifttv.installer.adb

import android.content.Context
import app.swifttv.installer.BrandHints
import app.swifttv.installer.ConnectedDevice
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.EOFException
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

enum class FailureKind {
    AUTHORIZATION,
    TIMEOUT,
    REFUSED,
    UNREACHABLE,
    PROTOCOL,
    UNKNOWN
}

sealed interface ConnectOutcome {
    data class Success(val device: ConnectedDevice) : ConnectOutcome
    data class Failure(
        val kind: FailureKind,
        val message: String,
        val detail: String? = null
    ) : ConnectOutcome
}

sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data class NeedsUninstall(val message: String) : InstallOutcome
    data class Failure(val message: String) : InstallOutcome
}

sealed interface PairOutcome {
    data object Success : PairOutcome
    data class Failure(val message: String, val detail: String? = null) : PairOutcome
}

/**
 * Thin, app-oriented wrapper around Kadb. Only one adbd transport is kept at a
 * time; scan probes deliberately use short-lived independent clients.
 */
class AdbManager(private val context: Context) {

    private val mutex = Mutex()
    private var client: Kadb? = null

    @Volatile
    var current: ConnectedDevice? = null
        private set

    init {
        configureIdentity()
    }

    private fun configureIdentity() {
        val keyFile = File(context.filesDir, "adb/adbkey.pem")
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
    }

    suspend fun connect(host: String, port: Int): ConnectOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            disconnectInternal()
            val kadb = Kadb.create(
                host = host,
                port = port,
                connectTimeout = 4000,
                // Long enough for the TV's "Allow USB debugging?" prompt and
                // for package installation on slower TV hardware.
                socketTimeout = 45_000
            )
            try {
                val info = readDeviceInfo(kadb, host, port)
                client = kadb
                current = info
                ConnectOutcome.Success(info)
            } catch (error: Throwable) {
                runCatching { kadb.close() }
                val kind = classify(error)
                ConnectOutcome.Failure(kind, friendlyMessage(kind, error), error.message)
            }
        }
    }

    suspend fun identify(host: String, port: Int = 5555): ConnectOutcome =
        withContext(Dispatchers.IO) {
            val kadb = Kadb.create(
                host = host,
                port = port,
                connectTimeout = 2500,
                socketTimeout = 4500
            )
            try {
                val info = readDeviceInfo(kadb, host, port)
                ConnectOutcome.Success(info)
            } catch (error: Throwable) {
                val kind = classify(error)
                ConnectOutcome.Failure(kind, friendlyMessage(kind, error), error.message)
            } finally {
                runCatching { kadb.close() }
            }
        }

    suspend fun pair(host: String, pairingPort: Int, pairingCode: String): PairOutcome =
        withContext(Dispatchers.IO) {
            try {
                Kadb.pair(
                    host = host,
                    port = pairingPort,
                    pairingCode = pairingCode.trim(),
                    name = "SwiftTV Installer"
                )
                PairOutcome.Success
            } catch (error: Throwable) {
                PairOutcome.Failure(pairingFailureMessage(error), error.toString())
            }
        }

    suspend fun install(apkFile: File): InstallOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val kadb = client ?: return@withLock InstallOutcome.Failure("还没有连接电视")
            try {
                kadb.install(apkFile, "-r")
                refreshInstalledVersion(kadb)
                InstallOutcome.Success
            } catch (firstError: Throwable) {
                val firstMessage = firstError.message.orEmpty()
                if (firstMessage.contains("VERSION_DOWNGRADE", ignoreCase = true)) {
                    try {
                        kadb.install(apkFile, "-r", "-d")
                        refreshInstalledVersion(kadb)
                        return@withLock InstallOutcome.Success
                    } catch (secondError: Throwable) {
                        return@withLock outcomeForInstallError(secondError)
                    }
                }
                outcomeForInstallError(firstError)
            }
        }
    }

    suspend fun uninstallAndInstall(apkFile: File): InstallOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val kadb = client ?: return@withLock InstallOutcome.Failure("还没有连接电视")
            try {
                kadb.uninstall(PACKAGE_NAME)
                kadb.install(apkFile, "-r")
                refreshInstalledVersion(kadb)
                InstallOutcome.Success
            } catch (error: Throwable) {
                outcomeForInstallError(error)
            }
        }
    }

    suspend fun launchApp(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val kadb = client ?: return@withLock false
            val leanback = runCatching {
                kadb.shell(
                    "monkey -p $PACKAGE_NAME -c android.intent.category.LEANBACK_LAUNCHER 1"
                ).allOutput
            }.getOrNull().orEmpty()
            if (leanback.contains("Events injected: 1")) return@withLock true

            val main = runCatching {
                kadb.shell(
                    "am start -a android.intent.action.MAIN " +
                        "-c android.intent.category.LEANBACK_LAUNCHER -p $PACKAGE_NAME"
                ).allOutput
            }.getOrNull().orEmpty()
            if (main.contains("Starting:") || main.contains("Activity:")) return@withLock true

            val activity = runCatching {
                kadb.shell("am start -n $PACKAGE_NAME/.MainActivity").allOutput
            }.getOrNull().orEmpty()
            activity.contains("Starting:") || activity.contains("Activity:")
        }
    }

    fun disconnect() {
        runCatching { client?.close() }
        client = null
        current = null
    }

    private fun disconnectInternal() {
        runCatching { client?.close() }
        client = null
        current = null
    }

    private fun readDeviceInfo(kadb: Kadb, host: String, port: Int): ConnectedDevice {
        val propsOutput = kadb.shell(PROP_COMMAND).allOutput
        val props = propsOutput.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()

        val manufacturer = props["MANUFACTURER"].orEmpty().ifBlank { props["BRAND"].orEmpty() }
        val brand = props["BRAND"].orEmpty()
        val model = props["MODEL"].orEmpty().ifBlank { props["DEVICE"].orEmpty() }
        val androidVersion = props["RELEASE"].orEmpty()
        val sdk = props["SDK"].orEmpty().toIntOrNull() ?: 0

        val leanback = props["LEANBACK"].orEmpty().toIntOrNull() ?: 0
        val television = props["TELEVISION"].orEmpty().toIntOrNull() ?: 0
        val isTv = leanback > 0 || television > 0 ||
            BrandHints.isTvLike(props["CHARACTERISTICS"], model, brand)

        val packageInfo = readInstalledPackage(kadb)

        return ConnectedDevice(
            host = host,
            port = port,
            manufacturer = manufacturer.ifBlank { null },
            model = model.ifBlank { null },
            brand = brand.ifBlank { null },
            androidVersion = androidVersion.ifBlank { null },
            sdk = sdk,
            isTv = isTv,
            installedVersion = packageInfo.first,
            packageInstalled = packageInfo.second
        )
    }

    private fun readInstalledPackage(kadb: Kadb): Pair<String?, Boolean> {
        val path = runCatching { kadb.shell("pm path $PACKAGE_NAME").allOutput }.getOrNull().orEmpty()
        if (!path.contains("package:")) return null to false

        val version = runCatching {
            kadb.shell(
                "dumpsys package $PACKAGE_NAME | grep -E 'versionName|versionCode' | head -n 4"
            ).allOutput
        }.getOrNull().orEmpty()

        val versionName = Regex("versionName=([^\\s]+)").find(version)?.groupValues?.getOrNull(1)
        return versionName to true
    }

    private fun refreshInstalledVersion(kadb: Kadb) {
        val info = current ?: return
        val packageInfo = readInstalledPackage(kadb)
        val refreshed = info.copy(
            installedVersion = packageInfo.first,
            packageInstalled = packageInfo.second
        )
        current = refreshed
    }

    private fun outcomeForInstallError(error: Throwable): InstallOutcome {
        val message = error.message.orEmpty().ifBlank { error.toString() }
        val signatureMismatch = listOf(
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            "signatures do not match",
            "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE"
        ).any { message.contains(it, ignoreCase = true) }
        return if (signatureMismatch) {
            InstallOutcome.NeedsUninstall(message)
        } else {
            InstallOutcome.Failure(message)
        }
    }

    private fun classify(error: Throwable): FailureKind {
        val causes = generateSequence(error) { it.cause }.take(6).toList()
        val text = causes.joinToString(" ") {
            "${it.javaClass.simpleName} ${it.message.orEmpty()}"
        }.lowercase()
        val timeout = causes.filterIsInstance<SocketTimeoutException>().firstOrNull()
        return when {
            timeout != null -> {
                // "connect timed out" means the TCP handshake never completed.
                // "Read timed out" after CNXN/AUTH almost always means the TV's
                // "Allow USB debugging?" dialog is still waiting for the user.
                if (text.contains("connect timed out")) FailureKind.TIMEOUT
                else FailureKind.AUTHORIZATION
            }
            causes.any { it is ConnectException } -> FailureKind.REFUSED
            causes.any { it is NoRouteToHostException } -> FailureKind.UNREACHABLE
            causes.any { it is EOFException } -> FailureKind.AUTHORIZATION
            text.contains("unauthorized") ||
                text.contains("authoriz") ||
                text.contains("permission denied") ||
                text.contains("auth") -> FailureKind.AUTHORIZATION

            text.contains("refused") -> FailureKind.REFUSED
            text.contains("unreachable") || text.contains("no route") -> FailureKind.UNREACHABLE
            text.contains("timed out") || text.contains("timeout") -> FailureKind.TIMEOUT
            text.contains("connection failed") -> FailureKind.PROTOCOL
            else -> FailureKind.UNKNOWN
        }
    }

    private fun friendlyMessage(kind: FailureKind, error: Throwable): String = when (kind) {
        FailureKind.AUTHORIZATION -> "等待电视授权。请在电视上点“允许 USB 调试”，建议勾选“始终允许”。"
        FailureKind.TIMEOUT -> "连接超时。请确认电视与手机在同一 Wi‑Fi，且电视已开启网络 ADB 调试。"
        FailureKind.REFUSED -> "端口未开放。请在电视上开启网络调试/无线调试，或确认端口号。"
        FailureKind.UNREACHABLE -> "电视不可达。请检查 IP 地址和路由器是否开启了 AP 隔离。"
        FailureKind.PROTOCOL -> "对方端口不是 ADB 调试服务，请换一个端口或确认电视设置。"
        FailureKind.UNKNOWN -> "连接失败：${error.message ?: error.javaClass.simpleName}"
    }

    private fun pairingFailureMessage(error: Throwable): String {
        val text = error.toString().lowercase()
        return when {
            text.contains("tls") || text.contains("ssl") || text.contains("provider") ->
                "配对需要 TLS 1.3。当前手机系统可能不支持，请换用 Android 9 以上手机，或改用 5555 直连。"
            text.contains("timed out") || text.contains("timeout") ->
                "配对超时。请确认配对端口正确，并且电视上的配对对话框仍处于打开状态。"
            text.contains("wrong") || text.contains("password") || text.contains("code") ->
                "配对码不正确或已过期，请在电视上重新生成 6 位配对码。"
            else -> "配对失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    companion object {
        const val PACKAGE_NAME = "app.swifttv.player"

        private const val PROP_COMMAND =
            "echo MANUFACTURER=$(getprop ro.product.manufacturer); " +
                "echo MODEL=$(getprop ro.product.model); " +
                "echo BRAND=$(getprop ro.product.brand); " +
                "echo DEVICE=$(getprop ro.product.device); " +
                "echo RELEASE=$(getprop ro.build.version.release); " +
                "echo SDK=$(getprop ro.build.version.sdk); " +
                "echo CHARACTERISTICS=$(getprop ro.build.characteristics); " +
                "echo LEANBACK=$(pm list features | grep -c android.software.leanback); " +
                "echo TELEVISION=$(pm list features | grep -c android.hardware.type.television)"
    }
}
