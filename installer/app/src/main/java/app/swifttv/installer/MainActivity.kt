package app.swifttv.installer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import app.swifttv.installer.databinding.ActivityMainBinding
import app.swifttv.installer.databinding.DialogPairBinding
import app.swifttv.installer.ui.DeviceAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: InstallerViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter

    private var conflictDialogShowing = false
    private var lastLogSignature = ""
    private var lastHelpExpanded: Boolean? = null
    private var lastInstallState = InstallState.IDLE
    private var suppressAutoOpenCallback = false

    private val openApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.useApk(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        deviceAdapter = DeviceAdapter { device -> onDeviceAction(device) }
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = deviceAdapter

        binding.scanButton.setOnClickListener { viewModel.startScan() }
        binding.manualConnectButton.setOnClickListener { manualConnect() }
        binding.pairButton.setOnClickListener { showPairDialog() }
        binding.changeApkButton.setOnClickListener {
            openApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        binding.installButton.setOnClickListener { viewModel.install() }
        binding.openButton.setOnClickListener { viewModel.openOnTv() }
        binding.helpHeader.setOnClickListener { viewModel.toggleHelp() }

        binding.autoOpenCheck.setOnCheckedChangeListener { _, checked ->
            if (!suppressAutoOpenCallback) viewModel.setAutoOpen(checked)
        }

        binding.copyLogButton.setOnClickListener { copyLogs() }
        binding.clearLogButton.setOnClickListener { viewModel.clearLog() }

        binding.manualPortInput.setText("5555")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun applyWindowInsets() {
        val initialLeft = binding.root.paddingLeft
        val initialTop = binding.root.paddingTop
        val initialRight = binding.root.paddingRight
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right,
                bottom = initialBottom + maxOf(bars.bottom, keyboard.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun render(state: InstallerUiState) {
        renderNetwork(state)
        renderDevices(state)
        renderApk(state)
        renderInstall(state)
        renderHelp(state)
        renderLogs(state)
        maybeShowConflictDialog(state)
    }

    private fun renderNetwork(state: InstallerUiState) {
        val network = state.localNetwork
        if (network != null) {
            binding.networkValue.text = network.ipAddress
            binding.subnetValue.text = "${network.transport} · ${network.interfaceName} · ${state.subnetLabel}"
            setChip(binding.networkStateBadge, "在线", R.color.accent, R.color.accent_soft)
        } else {
            binding.networkValue.text = "未连接局域网"
            binding.subnetValue.text = state.subnetLabel.ifBlank { "请先连接与电视相同的 Wi‑Fi" }
            setChip(binding.networkStateBadge, "离线", R.color.warning, R.color.warning_soft)
        }

        binding.scanProgress.visibility = if (state.scanning) View.VISIBLE else View.GONE
        binding.scanButton.isEnabled = !state.isBusy
        binding.scanButton.text = if (state.scanning) "扫描中…" else getString(R.string.scan)

        val status = state.scanStatus
        binding.scanStatus.visibility = if (status.isNotBlank()) View.VISIBLE else View.GONE
        binding.scanStatus.text = status
    }

    private fun renderDevices(state: InstallerUiState) {
        binding.deviceCount.text = "${state.devices.size} 台"
        deviceAdapter.submitList(state.devices)

        val empty = state.devices.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.deviceList.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            binding.emptyState.text = if (state.scanning) {
                "正在扫描局域网，请稍候…"
            } else {
                getString(R.string.no_devices)
            }
        }

        binding.manualConnectButton.isEnabled = !state.isBusy
        binding.pairButton.isEnabled = !state.isBusy
        binding.manualHostInput.isEnabled = !state.isBusy
        binding.manualPortInput.isEnabled = !state.isBusy
    }

    private fun renderApk(state: InstallerUiState) {
        val apk = state.apk
        if (apk == null) {
            binding.apkName.text = "SwiftTV"
            binding.apkMeta.text = "安装包加载中…"
            binding.apkIcon.setImageResource(R.drawable.ic_launcher_foreground)
            return
        }
        binding.apkName.text = apk.label
        binding.apkMeta.text = buildString {
            append(apk.versionName ?: "未知版本")
            append(" · API ")
            append(apk.minSdk)
            append("+")
            append(" · ")
            append(apk.sizeLabel)
            append(" · ")
            append(if (apk.bundled) "内置" else "本地")
        }
        if (apk.icon != null) {
            binding.apkIcon.setImageDrawable(apk.icon)
        } else {
            binding.apkIcon.setImageResource(R.drawable.ic_launcher_foreground)
        }
        binding.changeApkButton.isEnabled = !state.isBusy
    }

    private fun renderInstall(state: InstallerUiState) {
        val badge = when (state.connectionState) {
            ConnectionState.CONNECTED -> Triple("已连接", R.color.success, R.color.success_soft)
            ConnectionState.CONNECTING -> Triple("连接中", R.color.accent, R.color.accent_soft)
            ConnectionState.WAITING_AUTH -> Triple("待授权", R.color.warning, R.color.warning_soft)
            ConnectionState.ERROR -> Triple("连接失败", R.color.error, R.color.surface_high)
            ConnectionState.DISCONNECTED -> Triple("未连接", R.color.text_tertiary, R.color.surface_high)
        }
        setChip(binding.connectionBadge, badge.first, badge.second, badge.third)

        val message = when {
            state.installState == InstallState.INSTALLING ||
                state.installState == InstallState.SUCCESS ||
                state.installState == InstallState.FAILED ||
                state.installState == InstallState.NEED_UNINSTALL -> state.installMessage
            state.connectionState == ConnectionState.WAITING_AUTH ||
                state.connectionState == ConnectionState.ERROR -> state.connectionMessage
            state.installMessage.isNotBlank() -> state.installMessage
            else -> getString(R.string.install_ready)
        }
        binding.installStatus.text = message
        binding.installStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (state.installState) {
                    InstallState.SUCCESS -> R.color.success
                    InstallState.FAILED -> R.color.error
                    InstallState.NEED_UNINSTALL -> R.color.warning
                    else -> if (state.connectionState == ConnectionState.ERROR) {
                        R.color.error
                    } else if (state.connectionState == ConnectionState.WAITING_AUTH) {
                        R.color.warning
                    } else {
                        R.color.text_secondary
                    }
                }
            )
        )

        binding.installProgress.visibility =
            if (state.busy == BusyKind.INSTALLING) View.VISIBLE else View.GONE
        binding.installButton.isEnabled =
            state.connected != null && state.apk != null && !state.isBusy
        binding.installButton.text = if (state.installState == InstallState.SUCCESS) {
            "重新安装 SwiftTV"
        } else {
            getString(R.string.install_button)
        }
        binding.openButton.visibility =
            if (state.installState == InstallState.SUCCESS || state.connected?.packageInstalled == true) {
                View.VISIBLE
            } else {
                View.GONE
            }
        binding.openButton.isEnabled = state.connected != null && !state.isBusy
        binding.changeApkButton.isEnabled = !state.isBusy

        if (binding.autoOpenCheck.isChecked != state.autoOpen) {
            suppressAutoOpenCallback = true
            binding.autoOpenCheck.isChecked = state.autoOpen
            suppressAutoOpenCallback = false
        }
        binding.autoOpenCheck.isEnabled = state.busy != BusyKind.INSTALLING

        if (state.installState == InstallState.SUCCESS && lastInstallState != InstallState.SUCCESS) {
            Toast.makeText(this, "SwiftTV 安装成功", Toast.LENGTH_SHORT).show()
        }
        if (state.installState == InstallState.FAILED && lastInstallState != InstallState.FAILED) {
            Toast.makeText(this, "安装失败，请查看日志", Toast.LENGTH_SHORT).show()
        }
        lastInstallState = state.installState
    }

    private fun renderHelp(state: InstallerUiState) {
        binding.helpBody.visibility = if (state.helpExpanded) View.VISIBLE else View.GONE
        if (lastHelpExpanded != state.helpExpanded) {
            binding.helpChevron.animate()
                .rotation(if (state.helpExpanded) 180f else 0f)
                .setDuration(160L)
                .start()
            lastHelpExpanded = state.helpExpanded
        }
    }

    private fun renderLogs(state: InstallerUiState) {
        val signature = "${state.logLines.size}:${state.logLines.lastOrNull()?.message.orEmpty()}"
        if (signature == lastLogSignature) return
        lastLogSignature = signature
        binding.logText.text = state.logLines.joinToString("\n") { "${it.time}  ${it.message}" }
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun maybeShowConflictDialog(state: InstallerUiState) {
        if (state.pendingConflictPath == null || conflictDialogShowing || isFinishing) return
        conflictDialogShowing = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.signature_conflict_title)
            .setMessage(R.string.signature_conflict_message)
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.dismissInstallConflict() }
            .setPositiveButton(R.string.uninstall_and_install) { _, _ ->
                viewModel.confirmUninstallAndInstall()
            }
            .setCancelable(false)
            .setOnDismissListener { conflictDialogShowing = false }
            .show()
    }

    private fun onDeviceAction(device: TvDevice) {
        if (device.status == DeviceStatus.NEED_PAIR || device.isPairingOnly) {
            showPairDialog(
                host = device.host,
                pairingPort = device.pairingPort ?: device.port,
                connectPort = findConnectPort(device.host)
            )
        } else {
            viewModel.connect(device)
        }
    }

    private fun manualConnect() {
        val host = binding.manualHostInput.text?.toString()?.trim().orEmpty()
        if (Subnet.ipv4ToLong(host) == null) {
            binding.manualHostLayout.error = "请输入正确的 IPv4 地址"
            binding.manualHostInput.requestFocus()
            return
        }
        val port = binding.manualPortInput.text?.toString()?.trim()?.toIntOrNull() ?: 5555
        if (port !in 1..65535) {
            binding.manualPortLayout.error = "端口应为 1–65535"
            return
        }
        binding.manualHostLayout.error = null
        binding.manualPortLayout.error = null
        viewModel.connect(
            TvDevice(
                host = host,
                port = port,
                source = DiscoverySource.MANUAL,
                status = DeviceStatus.READY
            )
        )
    }

    private fun showPairDialog(
        host: String? = null,
        pairingPort: Int? = null,
        connectPort: Int? = null
    ) {
        if (isFinishing) return
        val dialogBinding = DialogPairBinding.inflate(layoutInflater)
        val current = viewModel.state.value
        val suggestedHost = (
            host
                ?: binding.manualHostInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: current.connected?.host
                ?: current.devices.firstOrNull()?.host
            ).orEmpty()

        dialogBinding.pairHostInput.setText(suggestedHost)
        if (pairingPort != null && pairingPort in 1..65535) {
            dialogBinding.pairPortInput.setText(pairingPort.toString())
        }
        if (connectPort != null && connectPort in 1..65535) {
            dialogBinding.connectPortInput.setText(connectPort.toString())
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_dialog_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pair_and_connect, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val hostValue = dialogBinding.pairHostInput.text?.toString()?.trim().orEmpty()
                val pairingPortValue = dialogBinding.pairPortInput.text?.toString()?.trim()?.toIntOrNull()
                val code = dialogBinding.pairCodeInput.text?.toString()?.trim().orEmpty()
                val connectPortValue = dialogBinding.connectPortInput.text?.toString()?.trim()?.toIntOrNull()

                val error = when {
                    Subnet.ipv4ToLong(hostValue) == null -> "请输入正确的电视 IPv4 地址"
                    pairingPortValue == null || pairingPortValue !in 1..65535 -> "配对端口应为 1–65535"
                    !code.matches(Regex("\\d{6}")) -> "配对码应为 6 位数字"
                    dialogBinding.connectPortInput.text?.toString()?.trim()?.isNotEmpty() == true &&
                        (connectPortValue == null || connectPortValue !in 1..65535) ->
                        "连接端口应为 1–65535，或留空自动查找"
                    else -> null
                }

                if (error != null) {
                    dialogBinding.pairError.visibility = View.VISIBLE
                    dialogBinding.pairError.text = error
                    return@setOnClickListener
                }

                dialog.dismiss()
                viewModel.pairAndConnect(
                    host = hostValue,
                    pairingPort = pairingPortValue ?: return@setOnClickListener,
                    pairingCode = code,
                    connectPortText = connectPortValue?.toString()
                )
            }
        }
        dialog.show()
    }

    private fun findConnectPort(host: String): Int? =
        viewModel.state.value.devices.firstOrNull {
            it.host == host && it.source == DiscoverySource.MDNS_CONNECT
        }?.port

    private fun copyLogs() {
        val text = viewModel.logText()
        if (text.isBlank()) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SwiftTV Installer Log", text))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    private fun setChip(view: android.widget.TextView, text: String, textColor: Int, background: Int) {
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, textColor))
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, background))
    }
}
