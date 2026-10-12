package com.termagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.termagent.MainActivity
import com.termagent.R

/**
 * Keeps the process alive — and the CPU awake — for as long as a turn is running.
 *
 * ## Why this is needed
 *
 * A turn is not a short request. It can spend minutes inside a single `run_shell`: `apt install`,
 * a build, a test suite. While the app is in the foreground, the capture path is fine, but once the
 * user switches away two things go wrong:
 *
 *  1. **Cached-app freezer.** Android may freeze the process, which suspends the very timers and
 *     threads the command capture depends on. The command keeps running in the PTY (a child process
 *     is not frozen with the app) while the parent stops reading it — the visible symptom is a
 *     command the user *knows* has finished, apparently hanging. The [com.termagent.shell.ShellManager]
 *     KDoc records exactly this happening for 55 minutes.
 *  2. **Process death.** A backgrounded process with no foreground component is the first thing the
 *     low-memory killer takes, which loses the turn: the terminal session dies with it.
 *
 * A foreground service is the supported way to declare "this process is doing user-visible work".
 * The notification is not a nuisance here — it is the *point*: it tells the user, in the shade, that
 * a command is still executing and that the app should not be swiped away. It is also what makes the
 * service immune to being started from the background on Android 12+.
 *
 * ## What it does *not* do
 *
 * It does not own the turn, the `AgentEngine` or the `ShellManager`. Those live in the application
 * scoped container (`AppContainer`) precisely so that a rotation — or this service stopping — cannot
 * interrupt a running command. The service is a *lifetime* marker, nothing more; ownership of a
 * running turn stays with the `ChatViewModel`, which stops the service in the turn's `finally` block.
 *
 * ## Wake lock
 *
 * A `PARTIAL_WAKE_LOCK` keeps the CPU running with the screen off. Without it, a long `apt install`
 * with the phone in the user's pocket can be suspended by Doze even though the PTY child is still
 * alive — and a suspended parent does not reap the child's output. The lock is held only for the
 * duration of the turn and released in [onDestroy] and [stop], so it cannot leak.
 */
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        startInForeground()

        // Not sticky: the service exists only for as long as a turn does, and a turn cannot be
        // resumed across process death anyway (the PTY and the SSE stream are both gone). Restarting
        // it would show a notification for work that is not happening.
        return START_NOT_STICKY
    }

    /**
     * Called when the turn ends ([stop]) or the system tears the service down.
     *
     * `stopService` does not run this synchronously; the wake lock release is also mirrored in
     * [stop] so a rapid turn-then-turn sequence cannot accumulate locks.
     */
    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Importance LOW: it is a progress notice, not something that should buzz.
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.foreground_channel_description)
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.foreground_title))
            .setContentText(getString(R.string.foreground_text))
            .setContentIntent(open)
            // Ongoing: it cannot be dismissed while a command is running, which is honest — the work
            // really is still going. `setSilent` keeps it out of the sound/vibration path.
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            // Held only for the length of one turn; the timeout is a safety net for the case where
            // the process is killed before `stop` runs, not the expected release path.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
                .onFailure { Log.w(TAG, "Failed to release wake lock", it) }
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AgentForegroundService"

        private const val CHANNEL_ID = "termagent-agent-turn"
        private const val NOTIFICATION_ID = 0x7E41

        private const val WAKE_LOCK_TAG = "termagent:agent-turn"

        /** Safety net slightly longer than the shell's own maximum command budget. */
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L

        /**
         * Bring the foreground service up.
         *
         * Safe to call repeatedly: a second `startForegroundService` on a running service only
         * delivers a new `onStartCommand`, which re-acquires a wake lock it already holds.
         */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, intent(context)) }
                .onFailure {
                    // On Android 12+ this throws if the app is in the background. That is not fatal:
                    // the turn still runs, it just loses the freezer protection.
                    Log.w(TAG, "Could not start foreground service", it)
                }
        }

        /** Tear it down; called from the turn's `finally`, so it always runs. */
        fun stop(context: Context) {
            runCatching { context.stopService(intent(context)) }
                .onFailure { Log.w(TAG, "Could not stop foreground service", it) }
        }

        private fun intent(context: Context) =
            Intent(context, AgentForegroundService::class.java)
    }
}
