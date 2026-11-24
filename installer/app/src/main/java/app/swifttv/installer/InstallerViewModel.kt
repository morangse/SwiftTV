package app.swifttv.installer

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.swifttv.installer.adb.AdbManager
import app.swifttv.installer.adb.ConnectOutcome
import app.swifttv.installer.adb.FailureKind
import app.swifttv.installer.adb.InstallOutcome
import app.swifttv.installer.adb.PairOutcome
import app.swifttv.installer.net.LanScanner
import app.swifttv.installer.net.NsdDiscovery
import app.swifttv.installer.net.NsdEvent
import app.swifttv.installer.net.NsdType
import app.swifttv.installer.net.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InstallerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val adb = AdbManager(application)
    private val nsd = NsdDiscovery(application, ::onNsdEvent, ::log)

    private val _state = MutableStateFlow(InstallerUiState())
    val state: StateFlow<InstallerUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var installJob: Job? = null
    private var initialized = false

    init {
        viewModelScope.launch {
            loadBundledApk()
            refreshNetwork()
            startScan()
        }
    }

    fun refreshNetwork() {
        val network = runCatching { NetworkUtils.localNetwork(app) }.getOrNull()
        val range = network?.let { Subnet.localRange(it.ipAddress, it.prefixLength) }
        _state.update {
            it.copy(
                localNetwork = network,
                networkLabel = when {
                    network == null -> "未连接局域网"
                    else -> "${network.transport}  ${network.cidr}"
                },
                subnetLabel = when {
                    range == null -> "请先连接与电视相同的 Wi‑Fi"
                    range.narrowed -> "扫描 ${range.networkAddress}/${range.prefixLength}（已收窄到本机 /24）"
                    else -> "扫描 ${range.networkAddress}/${range.prefixLength}"
                }
            )
        }
        if (network == null) {
            log("没有找到可用的局域网 IPv4 地址，请连接 Wi‑Fi 或以太网")
        } else {
            log("本机网络：${network.transport} ${network.ipAddress}/${network.prefixLength}（${network.interfaceName}）")
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        if (_state.value.busy == BusyKind.INSTALLING) return
        scanJob = viewModelScope.launch {
            scanInternal()
        }
    }

    private suspend fun scanInternal() {
        val network = NetworkUtils.localNetwork(app)
        if (network == null) {
            _state.update {
                it.copy(
                    scanning = false,
                    busy = BusyKind.NONE,
                    scanStatus = "未连接局域网",
                    localNetwork = null,
                    networkLabel = "未连接局域网",
                    subnetLabel = "请先连接与电视相同的 Wi‑Fi"
                )
            }
            log("扫描取消：没有局域网地址")
            return
        }

        val range = Subnet.localRange(network.ipAddress, network.prefixLength) ?: run {
            _state.update { it.copy(scanning = false, busy = BusyKind.NONE, scanStatus = "无法计算扫描范围") }
            return
        }

        _state.update {
            val connectedRow = it.connected?.let { device -> deviceFromConnected(device, it.devices) }
            it.copy(
                localNetwork = network,
                networkLabel = "${network.transport}  ${network.cidr}",
                subnetLabel = "扫描 ${range.networkAddress}/${range.prefixLength}",
                scanning = true,
                busy = BusyKind.SCANNING,
                scanStatus = "正在扫描 ${range.networkAddress}/${range.prefixLength}…",
                devices = connectedRow?.let { row -> listOf(row) } ?: emptyList()
            )
        }
        log("开始扫描 ${range.networkAddress}/${range.prefixLength}，端口 5555")

        nsd.start()

        val openHosts = LanScanner.findOpenHosts(
            network = network,
            ports = listOf(5555),
            timeoutMs = 220,
            concurrency = 32
        ) { progress ->
            _state.update { it.copy(scanStatus = progress) }
        }

        if (openHosts.isEmpty()) {
            log("网段内没有发现开放 5555 端口的设备")
        } else {
            log("发现 ${openHosts.size} 个开放 5555 的设备，开始识别…")
            openHosts.forEach { host ->
                mergeDevice(
                    TvDevice(
                        host = host,
                        port = 5555,
                        source = DiscoverySource.LAN_SCAN,
                        status = DeviceStatus.SCANNING
                    )
                )
            }

            coroutineScope {
                val semaphore = Semaphore(4)
                openHosts.map { host ->
                    async {
                        semaphore.withPermit { identifyHost(host) }
                    }
                }.awaitAll()
            }
        }

        // Give mDNS advertisements a moment to arrive after the TCP sweep.
        delay(3500)
        nsd.stop()
        val discovered = _state.value.devices.size
        _state.update {
            it.copy(
                scanning = false,
                // A connect/pair started while the sweep was running owns the
                // busy flag now; do not clear it from underneath that job.
                busy = if (it.busy == BusyKind.SCANNING) BusyKind.NONE else it.busy,
                scanStatus = "扫描完成，共发现 $discovered 台设备"
            )
        }
        log("扫描完成，当前列表 $discovered 台设备")
    }

    private suspend fun identifyHost(host: String) {
        val outcome = adb.identify(host, 5555)
        when (outcome) {
            is ConnectOutcome.Success -> {
                val info = outcome.device
                log("识别成功 ${host}: ${info.displayName}")
                mergeDevice(
                    TvDevice(
                        host = host,
                        port = 5555,
                        source = DiscoverySource.LAN_SCAN,
                        manufacturer = info.manufacturer,
                        model = info.model,
                        brand = info.brand,
                        androidVersion = info.androidVersion,
                        sdk = info.sdk,
                        isTv = info.isTv,
                        status = DeviceStatus.READY,
                        installedVersion = info.installedVersion,
                        packageInstalled = info.packageInstalled
                    )
                )
            }

            is ConnectOutcome.Failure -> {
                val status = when (outcome.kind) {
                    FailureKind.AUTHORIZATION -> DeviceStatus.NEED_AUTH
                    FailureKind.REFUSED, FailureKind.UNREACHABLE -> DeviceStatus.UNREACHABLE
                    FailureKind.PROTOCOL, FailureKind.UNKNOWN -> DeviceStatus.UNKNOWN
                    FailureKind.TIMEOUT -> DeviceStatus.UNREACHABLE
                }
                log("识别 ${host}：${outcome.message}")
                mergeDevice(
                    TvDevice(
                        host = host,
                        port = 5555,
                        source = DiscoverySource.LAN_SCAN,
                        status = status
                    )
                )
            }
        }
    }

    fun connect(device: TvDevice) {
        if (_state.value.busy == BusyKind.INSTALLING) return
        if (device.isPairingOnly) {
            log("${device.host} 是配对服务，请使用无线调试配对")
            return
        }
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = BusyKind.CONNECTING,
                    connectionState = ConnectionState.CONNECTING,
                    connectionMessage = "正在连接 ${device.host}:${device.port}…",
                    installState = InstallState.IDLE
                )
            }
            log("连接 ${device.host}:${device.port}")

            var lastFailure: ConnectOutcome.Failure? = null
            var info: ConnectedDevice? = null
            for (attempt in 1..3) {
                when (val outcome = adb.connect(device.host, device.port)) {
                    is ConnectOutcome.Success -> {
                        info = outcome.device
                        break
                    }

                    is ConnectOutcome.Failure -> {
                        lastFailure = outcome
                        if (outcome.kind == FailureKind.AUTHORIZATION) {
                            _state.update {
                                it.copy(
                                    connectionState = ConnectionState.WAITING_AUTH,
                                    connectionMessage = "请在电视上点“允许 USB 调试”（第 $attempt/3 次等待）"
                                )
                            }
                            log("等待电视授权（$attempt/3）")
                            delay(2600)
                        } else {
                            break
                        }
                    }
                }
            }

            if (info != null) {
                applyConnectionSuccess(info, device)
            } else {
                applyConnectionFailure(lastFailure, device)
            }
        }
    }

    private fun applyConnectionSuccess(info: ConnectedDevice, requested: TvDevice) {
        val existing = _state.value.devices.firstOrNull { it.host == info.host }
        val row = TvDevice(
            host = info.host,
            port = info.port,
            source = existing?.source ?: requested.source,
            serviceName = existing?.serviceName ?: requested.serviceName,
            manufacturer = info.manufacturer,
            model = info.model,
            brand = info.brand,
            androidVersion = info.androidVersion,
            sdk = info.sdk,
            isTv = info.isTv,
            status = DeviceStatus.CONNECTED,
            pairingPort = existing?.pairingPort ?: requested.pairingPort,
            installedVersion = info.installedVersion,
            packageInstalled = info.packageInstalled
        )
        _state.update { state ->
            state.copy(
                devices = replaceDevice(state.devices, row),
                connected = info,
                connectionState = ConnectionState.CONNECTED,
                connectionMessage = "已连接 ${info.displayName}",
                busy = BusyKind.NONE,
                installState = InstallState.IDLE,
                installMessage = if (info.packageInstalled) {
                    "电视已安装 ${info.installedVersion ?: "SwiftTV"}，可以覆盖安装"
                } else {
                    "电视尚未安装 SwiftTV"
                }
            )
        }
        log("已连接 ${info.displayName} (${info.host}:${info.port})")
        if (info.installedVersion != null) {
            log("电视上已有 SwiftTV ${info.installedVersion}")
        }
        if (!info.isTv) {
            log("注意：对方未声明电视/Leanback 特征，仍可尝试安装")
        }
    }

    private fun applyConnectionFailure(failure: ConnectOutcome.Failure?, requested: TvDevice) {
        val message = failure?.message ?: "连接失败"
        val status = when (failure?.kind) {
            FailureKind.AUTHORIZATION -> DeviceStatus.NEED_AUTH
            FailureKind.REFUSED, FailureKind.UNREACHABLE, FailureKind.TIMEOUT -> DeviceStatus.UNREACHABLE
            FailureKind.PROTOCOL, FailureKind.UNKNOWN -> DeviceStatus.UNKNOWN
            null -> DeviceStatus.UNKNOWN
        }
        val row = requested.copy(status = status)
        _state.update {
            it.copy(
                devices = replaceDevice(it.devices, row),
                connectionState = if (status == DeviceStatus.NEED_AUTH) {
                    ConnectionState.WAITING_AUTH
                } else {
                    ConnectionState.ERROR
                },
                connectionMessage = message,
                busy = BusyKind.NONE
            )
        }
        log("连接失败：$message")
    }

    fun pairAndConnect(
        host: String,
        pairingPort: Int,
        pairingCode: String,
        connectPortText: String?
    ) {
        if (_state.value.busy == BusyKind.INSTALLING) return
        if (host.isBlank() || pairingPort !in 1..65535) {
            _state.update { it.copy(connectionMessage = "请填写正确的电视 IP 和配对端口") }
            return
        }
        if (!pairingCode.trim().matches(Regex("\\d{6}"))) {
            _state.update { it.copy(connectionMessage = "配对码应为电视上显示的 6 位数字") }
            return
        }

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = BusyKind.PAIRING,
                    connectionState = ConnectionState.CONNECTING,
                    connectionMessage = "正在与 $host:$pairingPort 配对…"
                )
            }
            log("开始无线调试配对 $host:$pairingPort")

            when (val pairOutcome = adb.pair(host, pairingPort, pairingCode)) {
                PairOutcome.Success -> {
                    log("配对成功，准备连接")
                    val port = connectPortText?.trim()?.toIntOrNull()
                        ?: _state.value.devices.firstOrNull {
                            it.host == host && it.source == DiscoverySource.MDNS_CONNECT
                        }?.port
                        ?: 5555
                    _state.update {
                        it.copy(
                            connectionMessage = "配对成功，正在连接 $host:$port…",
                            busy = BusyKind.CONNECTING
                        )
                    }
                    when (val outcome = adb.connect(host, port)) {
                        is ConnectOutcome.Success -> {
                            val requested = TvDevice(
                                host = host,
                                port = port,
                                source = DiscoverySource.MDNS_CONNECT,
                                status = DeviceStatus.READY,
                                pairingPort = pairingPort
                            )
                            applyConnectionSuccess(outcome.device, requested)
                        }

                        is ConnectOutcome.Failure -> {
                            applyConnectionFailure(
                                outcome,
                                TvDevice(host, port, DiscoverySource.MDNS_CONNECT, status = DeviceStatus.NEED_PAIR)
                            )
                        }
                    }
                }

                is PairOutcome.Failure -> {
                    _state.update {
                        it.copy(
                            busy = BusyKind.NONE,
                            connectionState = ConnectionState.ERROR,
                            connectionMessage = pairOutcome.message
                        )
                    }
                    log("配对失败：${pairOutcome.message}")
                }
            }
        }
    }

    fun install() {
        val apk = _state.value.apk ?: return
        val connected = _state.value.connected ?: return
        if (_state.value.busy != BusyKind.NONE) return
        if (connected.sdk > 0 && apk.minSdk > 0 && connected.sdk < apk.minSdk) {
            val message = "电视 Android API ${connected.sdk} 低于安装包要求的 API ${apk.minSdk}，无法安装"
            _state.update {
                it.copy(
                    installState = InstallState.FAILED,
                    installMessage = message
                )
            }
            log(message)
            return
        }
        installJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = BusyKind.INSTALLING,
                    installState = InstallState.INSTALLING,
                    installMessage = "正在推送到 ${connected.displayName}…",
                    installProgressLabel = "上传安装包",
                    pendingConflictPath = null
                )
            }
            log("开始安装 ${apk.label} ${apk.versionLabel} 到 ${connected.host}")

            when (val outcome = adb.install(File(apk.path))) {
                InstallOutcome.Success -> finishInstallSuccess(apk)
                is InstallOutcome.NeedsUninstall -> {
                    _state.update {
                        it.copy(
                            busy = BusyKind.NONE,
                            installState = InstallState.NEED_UNINSTALL,
                            installMessage = "电视上的 SwiftTV 签名不同，需要卸载旧版本后重装",
                            installProgressLabel = "",
                            pendingConflictPath = apk.path
                        )
                    }
                    log("安装被阻止：${outcome.message}")
                }

                is InstallOutcome.Failure -> finishInstallFailure(outcome.message)
            }
        }
    }

    fun confirmUninstallAndInstall() {
        val path = _state.value.pendingConflictPath ?: _state.value.apk?.path ?: return
        val connected = _state.value.connected ?: return
        if (_state.value.busy != BusyKind.NONE) return
        installJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = BusyKind.INSTALLING,
                    installState = InstallState.INSTALLING,
                    installMessage = "正在卸载旧版本并安装…",
                    installProgressLabel = "卸载旧版本",
                    pendingConflictPath = null
                )
            }
            log("用户确认：卸载旧版 SwiftTV 后重装")
            when (val outcome = adb.uninstallAndInstall(File(path))) {
                InstallOutcome.Success -> finishInstallSuccess(_state.value.apk)
                is InstallOutcome.NeedsUninstall -> finishInstallFailure("卸载后仍无法安装：${outcome.message}")
                is InstallOutcome.Failure -> finishInstallFailure(outcome.message)
            }
        }
    }

    fun dismissInstallConflict() {
        _state.update {
            it.copy(
                installState = InstallState.IDLE,
                installMessage = "已取消卸载旧版本",
                pendingConflictPath = null
            )
        }
    }

    private suspend fun finishInstallSuccess(apk: ApkInfo?) {
        val info = adb.current
        val version = info?.installedVersion ?: apk?.versionName
        _state.update { state ->
            state.copy(
                busy = BusyKind.NONE,
                installState = InstallState.SUCCESS,
                installMessage = "安装成功 · ${version ?: "SwiftTV"}",
                installProgressLabel = "",
                pendingConflictPath = null,
                connected = info ?: state.connected
            )
        }
        log("安装成功：${version ?: apk?.label}")

        if (_state.value.autoOpen) {
            val launched = adb.launchApp()
            log(if (launched) "已在电视上打开 SwiftTV" else "安装完成，但自动打开失败；请用电视遥控器打开 SwiftTV")
        }
    }

    private fun finishInstallFailure(message: String) {
        _state.update {
            it.copy(
                busy = BusyKind.NONE,
                installState = InstallState.FAILED,
                installMessage = "安装失败：$message",
                installProgressLabel = "",
                pendingConflictPath = null
            )
        }
        log("安装失败：$message")
    }

    fun openOnTv() {
        val connected = _state.value.connected ?: return
        viewModelScope.launch {
            val launched = adb.launchApp()
            log(if (launched) "已发送打开指令到 ${connected.displayName}" else "打开失败，请用遥控器手动打开")
            _state.update {
                it.copy(installMessage = if (launched) "已发送打开指令" else "打开失败，请用遥控器手动打开")
            }
        }
    }

    fun useApk(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(installState = InstallState.IDLE, installMessage = "正在读取安装包…") }
            val file = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "picked").apply { mkdirs() }
                val out = File(dir, "picked.apk")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选文件")
                out
            }
            val info = readApkInfo(file, bundled = false)
            if (info == null) {
                log("所选文件不是有效的 APK")
                _state.update { it.copy(installMessage = "所选文件不是有效的 APK") }
                return@launch
            }
            if (info.packageName != AdbManager.PACKAGE_NAME) {
                log("注意：所选安装包是 ${info.packageName}，不是 SwiftTV")
            }
            _state.update {
                it.copy(
                    apk = info,
                    installState = InstallState.IDLE,
                    installMessage = "已选择 ${info.label} ${info.versionName ?: ""}".trim()
                )
            }
            log("已选择安装包 ${info.label} ${info.versionLabel} (${info.sizeLabel})")
        }
    }

    fun setAutoOpen(enabled: Boolean) {
        _state.update { it.copy(autoOpen = enabled) }
    }

    fun toggleHelp() {
        _state.update { it.copy(helpExpanded = !it.helpExpanded) }
    }

    fun clearLog() {
        _state.update { it.copy(logLines = emptyList()) }
    }

    fun logText(): String = _state.value.logLines.joinToString("\n") { "${it.time}  ${it.message}" }

    private suspend fun loadBundledApk() {
        if (initialized) return
        initialized = true
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(app.filesDir, "bundled").apply { mkdirs() }
                val file = File(dir, BUNDLED_APK_NAME)
                // The asset is stored uncompressed, so openFd gives its exact
                // length. Recopy when a future installer build ships a new APK.
                val assetLength = app.assets.openFd(BUNDLED_APK_NAME).use { it.length }
                if (!file.exists() || file.length() != assetLength) {
                    app.assets.open(BUNDLED_APK_NAME).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                readApkInfo(file, bundled = true)
            }.getOrNull()
        }
        if (info == null) {
            log("内置 SwiftTV 安装包加载失败")
            _state.update {
                it.copy(installState = InstallState.FAILED, installMessage = "内置安装包加载失败")
            }
        } else {
            _state.update { it.copy(apk = info) }
            log("已加载内置 ${info.label} ${info.versionLabel}（${info.sizeLabel}）")
        }
    }

    private fun readApkInfo(file: File, bundled: Boolean): ApkInfo? {
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_ACTIVITIES)
            ?: return null
        val appInfo = archive.applicationInfo ?: return null
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath

        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrElse { file.nameWithoutExtension }
        val icon = runCatching { appInfo.loadIcon(pm) }.getOrNull()
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }

        return ApkInfo(
            path = file.absolutePath,
            label = label,
            packageName = archive.packageName,
            versionName = archive.versionName,
            versionCode = code,
            minSdk = appInfo.minSdkVersion,
            sizeBytes = file.length(),
            icon = icon,
            bundled = bundled
        )
    }

    private fun onNsdEvent(event: NsdEvent) {
        if (event.lost) {
            if (_state.value.connected?.host == event.host) return
            val existing = _state.value.devices.firstOrNull { it.host == event.host && it.port == event.port }
            if (existing != null && existing.status != DeviceStatus.CONNECTED) {
                mergeDevice(existing.copy(status = DeviceStatus.UNREACHABLE))
            }
            return
        }

        when (event.type) {
            NsdType.PAIRING -> {
                // Pairing advertisements must not downgrade a row that already
                // has a usable connect endpoint (5555 sweep, manual IP, or
                // _adb-tls-connect). Just enrich it with the pairing port.
                val connectDevice = _state.value.devices.firstOrNull {
                    it.host == event.host && it.source != DiscoverySource.MDNS_PAIRING &&
                        it.status in setOf(
                            DeviceStatus.READY,
                            DeviceStatus.NEED_AUTH,
                            DeviceStatus.CONNECTED
                        )
                }
                if (connectDevice != null) {
                    mergeDevice(connectDevice.copy(pairingPort = event.port, serviceName = connectDevice.serviceName ?: event.serviceName))
                } else {
                    mergeDevice(
                        TvDevice(
                            host = event.host,
                            port = event.port,
                            source = DiscoverySource.MDNS_PAIRING,
                            serviceName = event.serviceName,
                            status = DeviceStatus.NEED_PAIR,
                            pairingPort = event.port
                        )
                    )
                }
            }

            NsdType.CONNECT, NsdType.LEGACY -> {
                val existing = _state.value.devices.firstOrNull { it.host == event.host }
                mergeDevice(
                    TvDevice(
                        host = event.host,
                        port = event.port,
                        source = DiscoverySource.MDNS_CONNECT,
                        serviceName = event.serviceName,
                        status = DeviceStatus.READY,
                        pairingPort = existing?.pairingPort
                    )
                )
            }
        }
    }

    private fun mergeDevice(candidate: TvDevice) {
        _state.update { state ->
            val existing = state.devices.firstOrNull { it.host == candidate.host }
            val merged = if (existing == null) candidate else mergeTwo(existing, candidate)
            state.copy(devices = replaceDevice(state.devices, merged))
        }
    }

    private fun mergeTwo(existing: TvDevice, candidate: TvDevice): TvDevice {
        val existingConnected = existing.status == DeviceStatus.CONNECTED
        val bestSource = if (sourceRank(candidate.source) > sourceRank(existing.source)) {
            candidate.source
        } else {
            existing.source
        }
        val bestPort = if (sourceRank(candidate.source) > sourceRank(existing.source)) {
            candidate.port
        } else {
            existing.port
        }
        return existing.copy(
            port = bestPort,
            source = bestSource,
            serviceName = candidate.serviceName?.takeIf { it.isNotBlank() } ?: existing.serviceName,
            manufacturer = candidate.manufacturer ?: existing.manufacturer,
            model = candidate.model ?: existing.model,
            brand = candidate.brand ?: existing.brand,
            androidVersion = candidate.androidVersion ?: existing.androidVersion,
            sdk = if (candidate.sdk > 0) candidate.sdk else existing.sdk,
            isTv = existing.isTv || candidate.isTv,
            status = when {
                existingConnected -> DeviceStatus.CONNECTED
                candidate.status == DeviceStatus.CONNECTED -> DeviceStatus.CONNECTED
                existing.status == DeviceStatus.CONNECTED -> DeviceStatus.CONNECTED
                candidate.status == DeviceStatus.NEED_PAIR -> DeviceStatus.NEED_PAIR
                candidate.status == DeviceStatus.NEED_AUTH -> DeviceStatus.NEED_AUTH
                else -> candidate.status
            },
            pairingPort = candidate.pairingPort ?: existing.pairingPort,
            installedVersion = candidate.installedVersion ?: existing.installedVersion,
            packageInstalled = existing.packageInstalled || candidate.packageInstalled
        )
    }

    private fun replaceDevice(devices: List<TvDevice>, device: TvDevice): List<TvDevice> {
        val filtered = devices.filterNot { it.host == device.host }
        return (filtered + device).sortedWith(
            compareBy<TvDevice> { if (it.status == DeviceStatus.CONNECTED) 0 else 1 }
                .thenBy { if (it.source != DiscoverySource.LAN_SCAN) 0 else 1 }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun sourceRank(source: DiscoverySource): Int = when (source) {
        DiscoverySource.MDNS_CONNECT -> 4
        DiscoverySource.MANUAL -> 3
        // A pairing advertisement carries a pairing port, not a connect port.
        // Prefer a real 5555 sweep result over it so the row stays connectable.
        DiscoverySource.LAN_SCAN -> 2
        DiscoverySource.MDNS_PAIRING -> 1
    }

    private fun deviceFromConnected(info: ConnectedDevice, devices: List<TvDevice>): TvDevice {
        val existing = devices.firstOrNull { it.host == info.host }
        return TvDevice(
            host = info.host,
            port = info.port,
            source = existing?.source ?: DiscoverySource.MANUAL,
            serviceName = existing?.serviceName,
            manufacturer = info.manufacturer,
            model = info.model,
            brand = info.brand,
            androidVersion = info.androidVersion,
            sdk = info.sdk,
            isTv = info.isTv,
            status = DeviceStatus.CONNECTED,
            pairingPort = existing?.pairingPort,
            installedVersion = info.installedVersion,
            packageInstalled = info.packageInstalled
        )
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update {
            it.copy(logLines = (it.logLines + LogLine(time, message)).takeLast(180))
        }
    }

    override fun onCleared() {
        nsd.stop()
        adb.disconnect()
        super.onCleared()
    }

    companion object {
        private const val BUNDLED_APK_NAME = "swifttv-0.3.3.apk"
    }
}
