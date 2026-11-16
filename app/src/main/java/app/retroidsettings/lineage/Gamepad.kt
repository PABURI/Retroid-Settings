package app.retroidsettings.lineage

import android.content.Context

/**
 * Built-in controller (moorechip serial gamepad) settings in /sys/class/moorechip-joystick/joystick.
 *
 *  calibration  LX LY RX RY as min:max:center:deadzone, then L2 and R2 as full:start
 *               (raw value at full press : raw value where a press starts; raw drops when pressed)
 *  raw          buttons(hex):LX:LY:RX:RY:L2:R2, uncalibrated sensor values
 *  m0_function / m1_function   what the two back buttons send (e.g. l3, r3)
 */
object Gamepad {
    private const val DIR = "/sys/class/moorechip-joystick/joystick"

    data class Axis(var min: Int, var max: Int, var center: Int, var deadzone: Int)
    data class Trigger(var full: Int, var start: Int)
    data class Calibration(val sticks: List<Axis>, val triggers: List<Trigger>) {
        fun encode(): String =
            (sticks.flatMap { listOf(it.min, it.max, it.center, it.deadzone) } +
                triggers.flatMap { listOf(it.full, it.start) }).joinToString(":")

        companion object {
            fun parse(s: String?): Calibration? {
                val v = s?.trim()?.split(":")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
                if (v.size != 20) return null
                val sticks = (0 until 4).map { i -> Axis(v[i * 4], v[i * 4 + 1], v[i * 4 + 2], v[i * 4 + 3]) }
                val trig = (0 until 2).map { i -> Trigger(v[16 + i * 2], v[17 + i * 2]) }
                return Calibration(sticks, trig)
            }
        }
    }

    data class Raw(val buttons: String, val lx: Int, val ly: Int, val rx: Int, val ry: Int, val l2: Int, val r2: Int)

    fun readRaw(ctx: Context): Raw? {
        val s = RootShell.exec(ctx, "cat $DIR/raw", 3_000)?.trim() ?: return null
        val p = s.split(":")
        if (p.size < 7) return null
        val n = p.drop(1).map { it.trim().toIntOrNull() ?: return null }
        return Raw(p[0], n[0], n[1], n[2], n[3], n[4], n[5])
    }

    fun readCalibration(ctx: Context): String? = RootShell.exec(ctx, "cat $DIR/calibration", 3_000)?.trim()

    /** Writes a calibration string and returns what the driver reports back. */
    fun writeCalibration(ctx: Context, value: String): String? =
        RootShell.exec(ctx, "echo '$value' > $DIR/calibration; cat $DIR/calibration", 3_000)?.trim()

    /** m0/m1 back-button mapping, e.g. ("l3", "r3"). */
    fun backButtons(ctx: Context): Pair<String, String>? {
        val out = RootShell.exec(ctx, "cat $DIR/m0_function; cat $DIR/m1_function", 3_000) ?: return null
        val l = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (l.size >= 2) l[0] to l[1] else null
    }

    /**
     * Full calibration reset: the stock settings app's calibration goes back to the driver and this
     * app's saved calibration is dropped. The stock trigger end point is the raw value 0, which a real
     * press never reaches (it stops near 60), so the triggers would top out at 96-98 %; the end point is
     * pulled in by 6 % of the travel so a full press reads 100 %. Returns true when the driver took it.
     */
    fun resetCalibration(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        val stock = RootShell.exec(ctx, "sh ${Daemon.tools(ctx)} calib_stock", 5_000)?.trim()
        val c = Calibration.parse(stock) ?: Calibration.parse(prefs.calibrationBackup) ?: return false
        val trig = c.triggers.map { t ->
            val dir = if (t.full < t.start) 1 else -1
            Trigger(full = t.full + dir * Math.abs(t.start - t.full) * 6 / 100, start = t.start)
        }
        val value = Calibration(c.sticks, trig).encode()
        if (writeCalibration(ctx, value) != value) return false
        prefs.calibration = value; prefs.calibrationBackup = null
        return true
    }

    /** Controller button layout: "xbox" or "nintendo". */
    fun setLayout(ctx: Context, layout: String) = Daemon.runRoot(ctx, "sh ${Daemon.tools(ctx)} layout $layout")

    /** Re-applies this app's calibration after boot (the stock settings app may restore its own). */
    fun applySaved(ctx: Context) {
        val saved = Prefs(ctx).calibration ?: return
        if (Calibration.parse(saved) != null) writeCalibration(ctx, saved)
    }
}

/** Counts this app's visible screens so the adb link is only dropped when none is on screen. */
object UiVisibility {
    @Volatile private var count = 0
    val visible get() = count > 0

    fun started() { synchronized(this) { count++ } }

    fun stopped(ctx: Context) {
        val none = synchronized(this) { count = (count - 1).coerceAtLeast(0); count == 0 }
        if (!none || Status.running) return
        val app = ctx.applicationContext
        // Small delay: switching between our own screens briefly reports 0.
        Thread {
            Thread.sleep(1500)
            if (!visible && !Status.running) RootShell.disconnect(app)
        }.start()
    }
}
