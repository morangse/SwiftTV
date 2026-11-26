package app.swifttv.player

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONObject
import java.util.UUID

class SwiftTvApplication : Application() {
    override fun onCreate() { super.onCreate(); StartupRuntime.attach(this) }
}

/** All Android integration is here; launch decisions stay in StartupPolicy for regression tests. */
object StartupRuntime {
    const val EXTRA_REQUEST = "app.swifttv.player.STARTUP_REQUEST"
    internal const val ACTION_RETRY = "app.swifttv.player.STARTUP_RETRY"
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var bootBroadcastSeen = false
    private var bootInitializedInProcess = false
    @Volatile var activityVisible = false
        private set
    @Volatile var guardConnected = false
    @Volatile private var internalFlowUntil = 0L
    private var lastPackage = ""
    @Volatile private var homePackages = setOf("app.orchard.tv", "com.mitv.tvhome", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx")
    private var tick: Runnable? = null
    private fun prefs(c: Context) = c.getSharedPreferences("startup", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", StartupPolicy.DEFAULT_ENABLED)
    private fun now() = SystemClock.elapsedRealtime()
    private fun count(c: Context) = runCatching { Settings.Global.getInt(c.contentResolver, "boot_count", -1) }.getOrDefault(-1)
    private fun epoch() = System.currentTimeMillis() - now()
    private fun read(c: Context): StartupState {
        val p = prefs(c)
        val x = runCatching { JSONObject(p.getString("state", "{}")!!) }.getOrElse { JSONObject() }
        return StartupState(x.optInt("bootCount", -1), x.optLong("bootEpoch"), x.optLong("eventAt"),
            x.optString("requestId"), x.optString("reason"), x.optLong("until"), x.optInt("attempts"),
            x.optLong("lastAttemptAt", -30000), x.optBoolean("watching"), x.optBoolean("suppressed"),
            x.optBoolean("screenOff"), x.optLong("reviveAt"), x.optInt("reviveCount"))
    }
    @SuppressLint("ApplySharedPref")
    private fun write(c: Context, s: StartupState) {
        val x = JSONObject().put("bootCount", s.bootCount).put("bootEpoch", s.bootEpoch).put("eventAt", s.eventAt)
            .put("requestId", s.requestId).put("reason", s.reason).put("until", s.until).put("attempts", s.attempts)
            .put("lastAttemptAt", s.lastAttemptAt).put("watching", s.watching).put("suppressed", s.suppressed)
            .put("screenOff", s.screenOff).put("reviveAt", s.reviveAt).put("reviveCount", s.reviveCount)
        // The alarm can outlive this process: persist its token synchronously before scheduling it.
        prefs(c).edit(commit = true) { putString("state", x.toString()) }
    }
    private fun status(c: Context, value: String) {
        prefs(c).edit { putString("result", value); putLong("resultAt", System.currentTimeMillis()) }
        Log.i("SwiftTVStartup", value)
    }

    /** Marks a deliberate in-app jump to system permission settings or document picker so onUserLeaveHint does not suppress watching state. */
    fun beginInternalSystemFlow() {
        internalFlowUntil = now() + 5_000L
    }
    fun consumeInternalSystemFlow(): Boolean {
        val active = now() <= internalFlowUntil
        internalFlowUntil = 0L
        return active
    }

    @Synchronized fun attach(c: Context) {
        if (attached) return
        attached = true
        val app = c.applicationContext
        // Some old TV firmware stalls in package/foreground probes. Never block playback or keys.
        Thread({
            runCatching { app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }.toSet() }.onSuccess { homePackages = homePackages + it }
        }, "SwiftTV-home-probe").apply { isDaemon = true; start() }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOff(context)
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> wake(context)
                }
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun retryIntent(c: Context) = Intent(c, StartupRetryReceiver::class.java).setAction(ACTION_RETRY)
    private fun cancel(c: Context) {
        tick?.let { handler.removeCallbacks(it) }; tick = null
        val pi = PendingIntent.getBroadcast(c, 410, retryIntent(c), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi); pi.cancel() }
    }
    private fun schedule(c: Context, delay: Long = 3000) {
        cancel(c)
        val s = read(c)
        if (!enabled(c) || s.requestId.isEmpty() || s.suppressed || s.screenOff || now() > s.until) return
        val token = s.requestId
        val intent = retryIntent(c).putExtra(EXTRA_REQUEST, token)
        val pi = PendingIntent.getBroadcast(c, 410, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Inexact, non-wakeup alarm: no exact-alarm permission and never powers on a sleeping TV.
        runCatching { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.ELAPSED_REALTIME, now() + delay, pi) }
            .onFailure { status(c, "定时恢复不可用：" + it.javaClass.simpleName) }
        tick = Runnable { attempt(c.applicationContext, token) }.also { handler.postDelayed(it, delay) }
    }
    @SuppressLint("ApplySharedPref")
    @Synchronized fun setEnabled(c: Context, value: Boolean) {
        prefs(c).edit(commit = true) { putBoolean("enabled", value) }
        cancel(c)
        write(c, StartupPolicy.left(read(c), now()))
        status(c, if (value) "已开启，下一次开机或唤醒生效" else "已关闭：不会自动打开或恢复")
    }
    @Synchronized fun boot(c: Context, fromBroadcast: Boolean = true) {
        if (!enabled(c)) return
        if (fromBroadcast && bootBroadcastSeen) return
        if (fromBroadcast) bootBroadcastSeen = true
        if (bootInitializedInProcess) return
        bootInitializedInProcess = true
        val time = now()
        val base = StartupState(bootCount = count(c), bootEpoch = epoch())
        write(c, StartupPolicy.begin(base, "boot", time, UUID.randomUUID().toString()))
        status(c, "等待开机打开直播"); schedule(c)
    }
    @Synchronized private fun screenOff(c: Context) {
        if (!enabled(c)) return
        write(c, StartupPolicy.screenOff(read(c), now())); cancel(c)
    }
    @Synchronized private fun wake(c: Context) {
        if (!enabled(c)) return
        val old = read(c); val next = StartupPolicy.wake(old, now(), UUID.randomUUID().toString())
        if (next == old) {
            if (!old.suppressed && old.requestId.isNotEmpty() && now() <= old.until) schedule(c)
            return
        }
        write(c, next); status(c, "等待唤醒后打开直播"); schedule(c)
    }
    @Synchronized fun revived(c: Context) {
        attach(c)
        if (!enabled(c)) return
        val old = read(c)
        if (StartupPolicy.provenNewBoot(old, count(c), now())) { boot(c, false); return }
        if (activityVisible) return
        val next = StartupPolicy.begin(old, "revive", now(), UUID.randomUUID().toString())
        if (next != old) write(c, next)
        if (next.requestId.isNotEmpty() && !next.suppressed) schedule(c)
    }
    @Synchronized fun acceptsEntry(c: Context, intent: Intent): Boolean {
        val token = intent.getStringExtra(EXTRA_REQUEST) ?: return true
        return StartupPolicy.acceptsAutomaticEntry(read(c), enabled(c), token, now())
    }
    @Synchronized fun visible(c: Context, intent: Intent) {
        activityVisible = true; lastPackage = c.packageName; internalFlowUntil = 0L
        val old = read(c); val time = now()
        val normalized = if (StartupPolicy.sameBoot(old, count(c), epoch(), time)) old
            else StartupState(bootCount = count(c), bootEpoch = epoch())
        write(c, StartupPolicy.visible(normalized, time))
        cancel(c)
        if (intent.getStringExtra(EXTRA_REQUEST) == normalized.requestId && normalized.requestId.isNotEmpty())
            status(c, "已确认自动进入直播界面")
        intent.removeExtra(EXTRA_REQUEST)
    }
    fun hidden() { activityVisible = false }
    @Synchronized fun userLeft(c: Context) {
        if (consumeInternalSystemFlow()) return
        write(c, StartupPolicy.left(read(c), now())); cancel(c)
        status(c, "已主动离开，本次不再自动拉回；下次开机或唤醒恢复")
    }
    @Synchronized fun windowChanged(c: Context, packageName: String) {
        lastPackage = packageName
        if (!enabled(c) || activityVisible) return
        val s = read(c)
        if (s.requestId.isNotEmpty() && !s.suppressed && s.watching && now() <= s.until && isHome(packageName)) schedule(c, 1500)
    }
    private fun isHome(name: String): Boolean = name in homePackages
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Synchronized fun attempt(c: Context, token: String) {
        val s = read(c); val time = now()
        val power = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val locked = (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        if (!StartupPolicy.canAttempt(s, enabled(c), power.isInteractive, locked, activityVisible, time, token)) {
            if (StartupPolicy.acceptsAutomaticEntry(s, enabled(c), token, time) && !activityVisible &&
                s.attempts < StartupPolicy.MAX_ATTEMPTS) schedule(c, 5000)
            return
        }
        if (s.watching && lastPackage.isNotEmpty() && lastPackage != c.packageName &&
            !isHome(lastPackage) && (s.reason == "revive" || s.attempts > 0)) return
        write(c, StartupPolicy.attempted(s, time))
        try {
            status(c, "已请求自动打开，尚未确认；被系统拦截时需开启开机助手或后台弹出权限")
            c.startActivity(Intent(c, MainActivity::class.java).putExtra(EXTRA_REQUEST, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: RuntimeException) { status(c, "自动打开被拦截：" + e.javaClass.simpleName + "；请检查开机助手与后台权限") }
        if (!activityVisible && read(c).attempts < StartupPolicy.MAX_ATTEMPTS) schedule(c, StartupPolicy.RETRY_MS)
    }
    fun overlayAllowed(c: Context) = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c)
    fun guardEnabled(c: Context): Boolean {
        val own = ComponentName(c, StartupGuardService::class.java)
        return Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == own }
    }
    fun listenerEnabled(c: Context): Boolean {
        val own = ComponentName(c, StartupRevivalListener::class.java)
        return Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == own }
    }
    fun settingsIntents(c: Context, kind: String): List<Intent> {
        val pkgUri = ("package:" + c.packageName).toUri()
        val first = when (kind) {
            "guard" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }
        return listOf(first, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri), Intent(Settings.ACTION_SETTINGS))
    }
    fun diagnostics(c: Context): JSONObject = JSONObject().put("enabled", enabled(c)).put("defaultEnabled", true)
        .put("guardEnabled", guardEnabled(c)).put("guardConnected", guardConnected).put("revivalAccess", listenerEnabled(c))
        .put("backgroundWindowPermission", overlayAllowed(c)).put("attempts", read(c).attempts)
        .put("lastResult", prefs(c).getString("result", "尚无自动启动记录"))
        .put("lastResultAt", prefs(c).getLong("resultAt", 0)).put("physicalDeviceValidation", "untested")
    fun settingLabel(c: Context): String = if (!enabled(c)) "已关闭"
        else if (Build.VERSION.SDK_INT >= 29 && !guardEnabled(c) && !overlayAllowed(c)) "已开启 · 待系统授权"
        else "已开启"
    fun summary(c: Context): String = "自动启动：" + (if (enabled(c)) "开启（默认）" else "关闭") +
        "\n开机助手：" + (if (guardEnabled(c)) "已授权" else "待授权") +
        "\n后台弹出界面：" + (if (overlayAllowed(c)) "无需额外授权或已允许" else "待允许（Android 10 及以上可能需要）") +
        "\n恢复辅助：" + (if (listenerEnabled(c)) "已授权" else "未开启，可选") +
        "\n\n最近结果：" + prefs(c).getString("result", "尚无自动启动记录")
}

/** Exported system broadcast receiver restricted strictly to the protected BOOT_COMPLETED action. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            StartupRuntime.boot(context)
        }
    }
}

/** Non-exported internal receiver exclusively for AlarmManager retry PendingIntents. */
class StartupRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == StartupRuntime.ACTION_RETRY) {
            StartupRuntime.attempt(context, intent.getStringExtra(StartupRuntime.EXTRA_REQUEST).orEmpty())
        }
    }
}
