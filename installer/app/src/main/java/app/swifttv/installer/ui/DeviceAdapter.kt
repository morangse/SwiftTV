package app.swifttv.installer.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.swifttv.installer.DeviceStatus
import app.swifttv.installer.R
import app.swifttv.installer.TvDevice
import app.swifttv.installer.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onAction: (TvDevice) -> Unit
) : ListAdapter<TvDevice, DeviceAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: TvDevice) {
            val context = binding.root.context
            binding.deviceName.text = device.displayName
            binding.deviceMeta.text = device.subtitle

            val status = statusAppearance(device.status)
            binding.deviceStatus.text = status.label
            binding.deviceStatus.setTextColor(ContextCompat.getColor(context, status.textColor))
            binding.deviceStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, status.background)
            )

            val iconColor = if (device.status == DeviceStatus.CONNECTED || device.isTv) {
                R.color.accent
            } else {
                R.color.text_tertiary
            }
            binding.deviceIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, iconColor)
            )

            val actionLabel = when (device.status) {
                DeviceStatus.CONNECTED -> "已连接"
                DeviceStatus.NEED_PAIR -> "配对"
                DeviceStatus.SCANNING -> "识别中"
                else -> "连接"
            }
            binding.deviceAction.text = actionLabel
            binding.deviceAction.isEnabled = device.status != DeviceStatus.CONNECTED &&
                device.status != DeviceStatus.SCANNING

            binding.deviceAction.setOnClickListener {
                if (binding.deviceAction.isEnabled) onAction(device)
            }
            binding.root.setOnClickListener {
                if (binding.deviceAction.isEnabled) onAction(device)
            }
        }

        private fun statusAppearance(status: DeviceStatus): StatusAppearance = when (status) {
            DeviceStatus.READY -> StatusAppearance("可连接", R.color.accent, R.color.accent_soft)
            DeviceStatus.NEED_AUTH -> StatusAppearance("等待授权", R.color.warning, R.color.warning_soft)
            DeviceStatus.NEED_PAIR -> StatusAppearance("需要配对", R.color.warning, R.color.warning_soft)
            DeviceStatus.CONNECTED -> StatusAppearance("已连接", R.color.success, R.color.success_soft)
            DeviceStatus.UNREACHABLE -> StatusAppearance("不可达", R.color.text_tertiary, R.color.surface_high)
            DeviceStatus.UNKNOWN -> StatusAppearance("未识别", R.color.text_tertiary, R.color.surface_high)
            DeviceStatus.SCANNING -> StatusAppearance("识别中", R.color.text_secondary, R.color.surface_high)
        }
    }

    private data class StatusAppearance(
        val label: String,
        val textColor: Int,
        val background: Int
    )

    private object Diff : DiffUtil.ItemCallback<TvDevice>() {
        override fun areItemsTheSame(oldItem: TvDevice, newItem: TvDevice): Boolean =
            oldItem.host == newItem.host

        override fun areContentsTheSame(oldItem: TvDevice, newItem: TvDevice): Boolean =
            oldItem == newItem
    }
}
