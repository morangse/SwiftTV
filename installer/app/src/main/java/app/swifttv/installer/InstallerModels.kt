package app.swifttv.installer

import android.graphics.drawable.Drawable

enum class DeviceStatus {
    SCANNING,
    READY,
    NEED_AUTH,
    NEED_PAIR,
    CONNECTED,
    UNREACHABLE,
    UNKNOWN
}

enum class DiscoverySource {
    LAN_SCAN,
    MDNS_CONNECT,
    MDNS_PAIRING,
    MANUAL
}

data class TvDevice(
    val host: String,
    val port: Int,
    val source: DiscoverySource,
    val serviceName: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val brand: String? = null,
    val androidVersion: String? = null,
    val sdk: Int = 0,
    val isTv: Boolean = false,
    val status: DeviceStatus = DeviceStatus.READY,
    val pairingPort: Int? = null,
    val installedVersion: String? = null,
    val packageInstalled: Boolean = false
) {
    val key: String get() = "$host:$port"

    val displayName: String
        get() = model?.takeIf { it.isNotBlank() }
            ?: serviceName?.takeIf { it.isNotBlank() }
            ?: brand?.takeIf { it.isNotBlank() }
            ?: "Android 设备"

    val subtitle: String
        get() {
            val parts = mutableListOf("$host:$port")
            val maker = BrandHints.displayBrand(manufacturer ?: brand)
            if (maker.isNotBlank()) parts += maker
            if (androidVersion.orEmpty().isNotBlank()) parts += "Android $androidVersion"
            if (packageInstalled) {
                parts += "已装 ${installedVersion ?: "SwiftTV"}"
            }
            return parts.joinToString(" · ")
        }

    val isPairingOnly: Boolean get() = status == DeviceStatus.NEED_PAIR
}

data class LocalNetwork(
    val ipAddress: String,
    val prefixLength: Int,
    val interfaceName: String,
    val transport: String
) {
    val cidr: String get() = "$ipAddress/$prefixLength"
}

data class ApkInfo(
    val path: String,
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val sizeBytes: Long,
    val icon: Drawable?,
    val bundled: Boolean
) {
    val sizeLabel: String get() = formatBytes(sizeBytes)

    val versionLabel: String
        get() = buildString {
            append(versionName ?: "未知版本")
            if (versionCode > 0) append("  ·  ${versionCode}")
        }
}

data class ConnectedDevice(
    val host: String,
    val port: Int,
    val manufacturer: String? = null,
    val model: String? = null,
    val brand: String? = null,
    val androidVersion: String? = null,
    val sdk: Int = 0,
    val isTv: Boolean = false,
    val installedVersion: String? = null,
    val packageInstalled: Boolean = false
) {
    val displayName: String
        get() = model?.takeIf { it.isNotBlank() }
            ?: brand?.takeIf { it.isNotBlank() }
            ?: "$host:$port"

    val meta: String
        get() = buildString {
            append(host)
            append(":")
            append(port)
            val maker = BrandHints.displayBrand(manufacturer ?: brand)
            if (maker.isNotBlank()) append(" · $maker")
            if (androidVersion.orEmpty().isNotBlank()) append(" · Android $androidVersion")
            if (packageInstalled) append(" · 已装 ${installedVersion ?: "SwiftTV"}")
        }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    WAITING_AUTH,
    CONNECTED,
    ERROR
}

enum class BusyKind {
    NONE,
    SCANNING,
    CONNECTING,
    PAIRING,
    INSTALLING,
    PUSHING
}

enum class InstallState {
    IDLE,
    INSTALLING,
    SUCCESS,
    FAILED,
    NEED_UNINSTALL
}

enum class PushState {
    IDLE,
    PUSHING,
    SUCCESS,
    FAILED
}

data class LogLine(
    val time: String,
    val message: String
)

data class InstallerUiState(
    val localNetwork: LocalNetwork? = null,
    val networkLabel: String = "正在检测网络…",
    val subnetLabel: String = "",
    val devices: List<TvDevice> = emptyList(),
    val scanning: Boolean = false,
    val scanStatus: String = "",
    val connected: ConnectedDevice? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionMessage: String = "",
    val busy: BusyKind = BusyKind.NONE,
    val apk: ApkInfo? = null,
    val installState: InstallState = InstallState.IDLE,
    val installMessage: String = "连接电视后即可安装",
    val installProgressLabel: String = "",
    val autoOpen: Boolean = true,
    val pendingConflictPath: String? = null,
    val pushState: PushState = PushState.IDLE,
    val pushMessage: String = "",
    val logLines: List<LogLine> = emptyList(),
    val helpExpanded: Boolean = false
) {
    val isBusy: Boolean get() = busy != BusyKind.NONE
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB", mb)
}
