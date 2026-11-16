package app.retroidsettings.lineage

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/** Shared live state for the UI. */
object Status {
    @Volatile var reading: Hardware.Reading? = null
    @Volatile var note: String = ""
    @Volatile var daemonRunning = false
    @Volatile var running = false
}

/**
 * Sets things up after boot (or on request): gets root, re-applies the full-screen tools and starts
 * the root daemon that actually drives the fan and CPU/GPU limits. The daemon keeps running without
 * this service, so the service stops right away unless the status notification is switched on.
 */
class ControlService : Service() {
    private var worker: Thread? = null
    @Volatile private var stop = false
    @Volatile private var setupNeeded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        startForeground(NOTIF_ID, buildNotification("Applying settings…", prefs.showNotification), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (intent?.action == ACTION_SETUP) setupNeeded = true
        if (worker?.isAlive != true) {
            stop = false
            worker = Thread({ run(prefs) }, "hs-service").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stop = true
        worker?.interrupt()
        Status.running = false
        super.onDestroy()
    }

    private fun run(prefs: Prefs) {
        Status.running = true
        val started = System.currentTimeMillis()
        try {
            while (!stop) {
                // Android 15+ also sends BOOT_COMPLETED when a force-stopped app starts again. A daemon
                // that is still running means no reboot happened: everything is still applied.
                if (setupNeeded && Daemon.isCurrent(this)) {
                    setupNeeded = false; Status.daemonRunning = true
                }
                if (setupNeeded) {
                    if (RootShell.ensure(this)) {
                        Hardware.discover(this)
                        ScreenTools.applyAll(this, prefs)
                        Gamepad.applySaved(this)
                        Status.daemonRunning = Daemon.ensureCurrent(this)
                        setupNeeded = false
                        Status.note = if (Status.daemonRunning) tr(R.string.daemon_running) else tr(R.string.daemon_failed)
                    } else {
                        Status.note = RootShell.state
                        // Right after boot Wi-Fi may not be up yet: keep trying for up to 3 minutes.
                        if (System.currentTimeMillis() - started > 180_000) break
                        update("Waiting for Wi-Fi / wireless debugging…", prefs)
                        sleep(5_000)
                        continue
                    }
                }
                if (!prefs.showNotification) break
                val r = Hardware.read(this)
                if (r != null) {
                    Status.reading = r
                    val m = Memory.live(this)
                    val fanMode = mapOf(FanMode.AUTO to "Auto", FanMode.SMOOTH to "Smooth", FanMode.MANUAL to "Manual", FanMode.TARGET to "Target")[prefs.fanMode]
                    val tdp = mapOf(TdpProfile.NONE to "Max", TdpProfile.SAVE to "Save", TdpProfile.BALANCED to "Balanced",
                        TdpProfile.PERFORMANCE to "Perf", TdpProfile.CUSTOM to "Custom")[prefs.tdp]
                    update("CPU ${r.cpuC.toInt()}° GPU ${r.gpuC.toInt()}° · Fan ${r.fanLevel} $fanMode · TDP $tdp · " +
                        "RAM %.1f/%.1fG".format(m.usedKb / 1048576.0, m.totalKb / 1048576.0), prefs)
                }
                sleep(3_000)
            }
        } catch (e: Exception) {
            Log.w("ControlService", "service failed", e)
        }
        Status.running = false
        // The daemon keeps running; dropping adb clears the system notification (unless a screen is open).
        if (!UiVisibility.visible) RootShell.disconnect(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { stop = true } }

    private fun buildNotification(text: String, visible: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, if (visible) App.CH_SERVICE else App.CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_fan)
            .setContentTitle("Retroid Settings")
            .setContentText(text)
            .setOngoing(visible)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun update(text: String, prefs: Prefs) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, prefs.showNotification))
    }

    companion object {
        const val NOTIF_ID = 1
        const val ACTION_SETUP = "app.retroidsettings.lineage.SETUP"

        /** setup = reconnect, re-apply screen tools and restart the daemon. */
        fun start(ctx: Context, setup: Boolean = false) {
            val i = Intent(ctx, ControlService::class.java)
            if (setup) i.action = ACTION_SETUP
            try { ctx.startForegroundService(i) } catch (e: Exception) { Log.w("ControlService", "start failed", e) }
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ControlService::class.java))
    }
}
