package dev.pranav.applock.services

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.services.AppLockAccessibilityService.BiometricState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object AppLockConstants {
    val KNOWN_RECENTS_CLASSES = setOf(
        "com.android.systemui.recents.RecentsActivity",
        "com.android.quickstep.RecentsActivity",
        "com.android.systemui.recents.RecentsView",
        "com.android.systemui.recents.RecentsPanelView",
    )

    val EXCLUDED_APPS = setOf(
        "com.android.systemui",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "android.uid.system:1000",
        "com.google.android.googlequicksearchbox",
        "android",
        "com.google.android.gms",
        "com.google.android.webview"
    )

    val ACCESSIBILITY_SETTINGS_CLASSES = setOf(
        "com.android.settings.accessibility.AccessibilitySettings",
        "com.android.settings.accessibility.AccessibilityMenuActivity",
        "com.android.settings.accessibility.AccessibilityShortcutActivity",
        "com.android.settings.Settings\$AccessibilitySettingsActivity"
    )

}

fun Context.isDeviceLocked(): Boolean {
    val keyguardManager = getSystemService(KeyguardManager::class.java)
    return keyguardManager?.isKeyguardLocked ?: false
}

@Suppress("DEPRECATION")
fun Context.isServiceRunning(serviceClass: Class<*>): Boolean {
    val manager = getSystemService(ActivityManager::class.java) ?: return false
    return manager.getRunningServices(Int.MAX_VALUE)
        .any { serviceClass.name == it.service.className }
}

object AppLockManager {
    private const val TAG = "AppLockManager"

    /**
     * Durations (in minutes) at or above this value mean "keep unlocked until the screen turns off".
     */
    const val UNLOCK_UNTIL_SCREEN_OFF_THRESHOLD = 10_000

    @Volatile
    var temporarilyUnlockedApp: String = ""

    /**
     * Last time each app was unlocked or left while unlocked. The unlock duration is measured
     * from this timestamp.
     */
    val appUnlockTimes = ConcurrentHashMap<String, Long>()
    val isLockScreenShown = AtomicBoolean(false)
    var currentBiometricState: AppLockAccessibilityService.BiometricState? = null

    // Grace period tracking
    private var recentlyLeftApp: String = ""
    private var recentlyLeftTime: Long = 0L
    private const val GRACE_PERIOD_MS = 300L

    fun setRecentlyLeftApp(packageName: String) {
        recentlyLeftApp = packageName
        recentlyLeftTime = System.currentTimeMillis()
        LogUtils.d(TAG, "Left app $packageName at $recentlyLeftTime")
    }

    fun checkAndRestoreRecentlyLeftApp(packageName: String): Boolean {
        // If we are returning to the same app we just left within the grace period
        if (packageName == recentlyLeftApp && packageName.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - recentlyLeftTime
            if (elapsed <= GRACE_PERIOD_MS) {
                LogUtils.d(TAG, "Restoring unlock state for $packageName (elapsed: ${elapsed}ms)")
                temporarilyUnlockedApp = packageName
                // Clear the tracking so it doesn't trigger again inappropriately
                recentlyLeftApp = ""
                recentlyLeftTime = 0L
                return true
            } else {
                LogUtils.d(TAG, "Grace period expired for $packageName (elapsed: ${elapsed}ms)")
                recentlyLeftApp = "" // Expired
            }
        }
        return false
    }

    private val ALL_APP_LOCK_SERVICES = setOf(
        ShizukuAppLockService::class.java,
        UsageLockService::class.java
    )

    fun unlockApp(packageName: String) {
        temporarilyUnlockedApp = packageName
        appUnlockTimes[packageName] = System.currentTimeMillis()
        LogUtils.d(
            TAG,
            "App $packageName unlocked at timestamp: ${appUnlockTimes[packageName]}, current time: ${System.currentTimeMillis()}"
        )
    }

    fun temporarilyUnlockAppWithBiometrics(packageName: String) {
        unlockApp(packageName)
        reportBiometricAuthFinished()
    }

    fun reportBiometricAuthStarted() {
        currentBiometricState = BiometricState.AUTH_STARTED
    }

    fun reportBiometricAuthFinished() {
        currentBiometricState = BiometricState.IDLE
    }

    fun isAppTemporarilyUnlocked(packageName: String): Boolean =
        temporarilyUnlockedApp == packageName

    /**
     * Ends the current unlocked session. If the app has an unlock timestamp, it is refreshed to
     * "now" so the unlock duration counts from the moment the user left the app, rather than from
     * the moment it was first unlocked.
     */
    fun clearTemporarilyUnlockedApp() {
        val app = temporarilyUnlockedApp
        if (app.isNotEmpty()) {
            appUnlockTimes.computeIfPresent(app) { _, _ -> System.currentTimeMillis() }
        }
        temporarilyUnlockedApp = ""
    }

    /**
     * Forgets every unlock (e.g. when the screen turns off or the device gets locked).
     */
    fun resetAllUnlockState() {
        temporarilyUnlockedApp = ""
        appUnlockTimes.clear()
    }

    /**
     * Returns true if [packageName] is still inside its unlock duration window. When it is, the
     * app is marked as the temporarily unlocked app again so that the session continues and the
     * lock screen does not pop up while the user is actively using it.
     */
    fun isWithinUnlockDuration(
        packageName: String,
        durationMinutes: Int,
        currentTime: Long = System.currentTimeMillis()
    ): Boolean {
        if (durationMinutes <= 0) return false
        val unlockTimestamp = appUnlockTimes[packageName] ?: return false

        if (durationMinutes < UNLOCK_UNTIL_SCREEN_OFF_THRESHOLD) {
            val durationMillis = durationMinutes.toLong() * 60_000L
            val elapsedMillis = currentTime - unlockTimestamp
            LogUtils.d(
                TAG,
                "Unlock duration check for $packageName: elapsed=${elapsedMillis}ms, duration=${durationMillis}ms"
            )
            // A negative elapsed time means the clock was changed; treat it as expired.
            if (elapsedMillis < 0 || elapsedMillis >= durationMillis) {
                LogUtils.d(TAG, "Unlock duration expired for $packageName")
                appUnlockTimes.remove(packageName)
                return false
            }
        }

        temporarilyUnlockedApp = packageName
        return true
    }

    fun clearAppUnlockState(packageName: String) {
        if (temporarilyUnlockedApp == packageName) {
            temporarilyUnlockedApp = ""
        }
        appUnlockTimes.remove(packageName)
        if (packageName == recentlyLeftApp) {
            recentlyLeftApp = ""
            recentlyLeftTime = 0L
        }
        LogUtils.d(TAG, "Cleared stale unlock state for $packageName")
    }

    fun stopAllOtherServices(context: Context, excludeService: Class<*>) {
        ALL_APP_LOCK_SERVICES
            .filter { it != excludeService }
            .forEach {
                context.stopService(Intent(context, it))
            }
        LogUtils.d(TAG, "Stopped all main app lock services except ${excludeService.simpleName}.")
    }

}
