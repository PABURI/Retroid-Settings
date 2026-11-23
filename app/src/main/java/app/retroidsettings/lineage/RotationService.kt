package app.retroidsettings.lineage

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Auto-rotate for the RP6. The panel is a portrait panel that the system turns 90° into landscape,
 * but the sensors still report in the panel's frame, so the system's own auto-rotate ends up 90° off.
 * This service works out the grip from the accelerometer itself and sets the rotation (the system's
 * auto-rotate stays off). It listens only while the screen is on. Started and stopped by the root
 * rotation command, so it also runs after boot when auto-rotate is the saved mode.
 */
class RotationService : Service(), SensorEventListener {
    private lateinit var sm: SensorManager
    private var accel: Sensor? = null
    private var listening = false
    private var applied = -1
    private var candidate = -1
    private var candidateSince = 0L
    private val g = FloatArray(2)

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = listen(i.action == Intent.ACTION_SCREEN_ON)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        sm = getSystemService(SensorManager::class.java)
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) { stopSelf(); return }
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        listen(getSystemService(PowerManager::class.java).isInteractive)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        listen(false)
        try { unregisterReceiver(screen) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) { candidate = -1; sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME) }
        else sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        // Light low-pass; the settle time below is what keeps a shake from counting as a turn.
        g[0] += (e.values[0] - g[0]) * 0.6f
        g[1] += (e.values[1] - g[1]) * 0.6f
        val x = g[0]; val y = g[1]
        if (sqrt(x * x + y * y) < 5f) return              // lying flat: keep the current rotation
        // Panel frame → display rotation: +x up is the normal landscape grip, -y up is rotation 1.
        val deg = Math.toDegrees(atan2(-y, x).toDouble())
        val q = (deg / 90).roundToInt()
        if (abs(deg - q * 90) > 30) return                 // between two grips: wait
        val rot = Math.floorMod(q, 4)
        val now = SystemClock.uptimeMillis()
        if (rot != candidate) { candidate = rot; candidateSince = now; return }
        if (rot == applied || now - candidateSince < SETTLE_MS) return
        applied = rot
        thread { Daemon.runRoot(this, "wm user-rotation lock $rot") }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, App.CH_ROTATION)
            .setSmallIcon(R.drawable.ic_tile_rotation)
            .setContentTitle(tr(R.string.rot_auto))
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val NOTIF_ID = 3
        private const val SETTLE_MS = 150L
        @Volatile var running = false
    }
}
