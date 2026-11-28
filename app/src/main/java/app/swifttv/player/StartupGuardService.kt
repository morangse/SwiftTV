package app.swifttv.player

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent

/** Only the foreground package is observed: no node content, key filtering, or global Home action. */
class StartupGuardService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        StartupRuntime.guardConnected = true
        StartupRuntime.revived(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && StartupRuntime.enabled(this))
            event.packageName?.toString()?.let { StartupRuntime.windowChanged(this, it) }
    }
    override fun onInterrupt() {}
    override fun onDestroy() { StartupRuntime.guardConnected = false; super.onDestroy() }
}

/** Optional system rebind point; notification contents are never read, stored, or exported. */
class StartupRevivalListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    @android.annotation.SuppressLint("NewApi", "Override")
    override fun onListenerConnected() {
        if (Build.VERSION.SDK_INT >= 21) super.onListenerConnected()
        StartupRuntime.revived(this)
    }

    @android.annotation.SuppressLint("Override")
    override fun onListenerDisconnected() {
        if (Build.VERSION.SDK_INT >= 24) {
            super.onListenerDisconnected()
            if (StartupRuntime.enabled(this)) requestRebind(ComponentName(this, StartupRevivalListener::class.java))
        } else if (Build.VERSION.SDK_INT >= 21) {
            super.onListenerDisconnected()
        }
    }
}
