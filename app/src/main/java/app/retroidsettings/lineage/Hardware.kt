package app.retroidsettings.lineage

import android.content.Context
import android.util.Log
import java.io.File

/** Temperature, fan and CPU/GPU limit control. Every read/write goes through the root shell. */
object Hardware {
    private const val TAG = "Hardware"

    data class Policy(val path: String, val maxKHz: Long, val freqsKHz: List<Long>)

    @Volatile var discovered = false; private set
    private var cpuZones = listOf<String>()
    private var gpuZones = listOf<String>()
    private var battZone: String? = null
    var fanPath: String? = null; private set
    var fanMax = 8; private set
    var policies = listOf<Policy>(); private set
    private var gpuFreqsHz = listOf<Long>()
    private const val GPU = "/sys/class/kgsl/kgsl-3d0"

    data class Reading(
        val cpuC: Double, val gpuC: Double, val battC: Double, val fanLevel: Int,
        val cpuMaxMHz: List<Int>, val gpuMaxMHz: Int
    )

    fun discover(ctx: Context): Boolean {
        val script = """
            for z in /sys/class/thermal/thermal_zone*; do echo "Z ${'$'}z ${'$'}(cat ${'$'}z/type 2>/dev/null)"; done
            for d in /sys/class/thermal/cooling_device*; do t=${'$'}(cat ${'$'}d/type 2>/dev/null); [ "${'$'}t" = "pwm-fan" ] && echo "F ${'$'}d ${'$'}(cat ${'$'}d/max_state)"; done
            for p in /sys/devices/system/cpu/cpufreq/policy*; do echo "P ${'$'}p ${'$'}(cat ${'$'}p/cpuinfo_max_freq) ${'$'}(cat ${'$'}p/scaling_available_frequencies 2>/dev/null)"; done
            echo "G ${'$'}(cat $GPU/gpu_available_frequencies 2>/dev/null || cat $GPU/devfreq/available_frequencies 2>/dev/null)"
        """.trimIndent()
        val out = RootShell.exec(ctx, script) ?: return false
        val cz = mutableListOf<String>(); val gz = mutableListOf<String>(); val pols = mutableListOf<Policy>()
        for (line in out.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            when (parts.firstOrNull()) {
                "Z" -> if (parts.size >= 3) {
                    val path = parts[1]; val type = parts[2]
                    when {
                        type.startsWith("cpu-") || type.startsWith("cpuss") -> cz += "$path/temp"
                        type.startsWith("gpuss") -> gz += "$path/temp"
                        type == "battery" -> battZone = "$path/temp"
                    }
                }
                "F" -> if (parts.size >= 3) { fanPath = "${parts[1]}/cur_state"; fanMax = parts[2].toIntOrNull() ?: 8 }
                "P" -> if (parts.size >= 3) {
                    val max = parts[2].toLongOrNull() ?: 0
                    val freqs = parts.drop(3).mapNotNull { it.toLongOrNull() }.sorted()
                    pols += Policy(parts[1], max, freqs.ifEmpty { listOf(max) })
                }
                "G" -> gpuFreqsHz = parts.drop(1).mapNotNull { it.toLongOrNull() }.sorted()
            }
        }
        cpuZones = cz; gpuZones = gz; policies = pols
        discovered = cz.isNotEmpty() || fanPath != null
        Log.i(TAG, "discover: cpuZones=${cz.size} gpuZones=${gz.size} fan=$fanPath policies=${pols.size} gpuFreqs=$gpuFreqsHz")
        return discovered
    }

    /**
     * Current readings: from the daemon's hs.status file when it runs (no adb needed), otherwise over
     * the root shell.
     */
    fun read(ctx: Context): Reading? = fromDaemon(ctx) ?: if (RootShell.isRoot) readRoot(ctx) else null

    private fun fromDaemon(ctx: Context): Reading? {
        if (!Daemon.isAlive(ctx)) return null
        val watch = File(ctx.filesDir, "hs.watch"); val status = File(ctx.filesDir, "hs.status")
        try { watch.createNewFile() } catch (_: Exception) {}
        val until = System.currentTimeMillis() + 800
        while (System.currentTimeMillis() - status.lastModified() > 4_000 && System.currentTimeMillis() < until) Thread.sleep(50)
        if (System.currentTimeMillis() - status.lastModified() > 4_000) return null
        val v = try { status.readText().trim().split(' ').map { it.toLongOrNull() ?: 0L } } catch (_: Exception) { return null }
        if (v.size < 5) return null
        val batt = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0) ?: 0.0
        val gpuRaw = v.last()
        return Reading(v[0] / 1000.0, v[1] / 1000.0, batt, v[2].toInt(),
            v.subList(3, v.size - 1).map { (it / 1000).toInt() },
            if (gpuRaw > 1_000_000) (gpuRaw / 1_000_000).toInt() else gpuRaw.toInt())
    }

    private fun readRoot(ctx: Context): Reading? {
        if (!discovered && !discover(ctx)) return null
        val files = cpuZones + gpuZones + listOfNotNull(battZone) + listOfNotNull(fanPath) +
            policies.map { "${it.path}/scaling_max_freq" }
        val cmd = files.joinToString("; ") { "cat $it 2>/dev/null || echo x" } +
            "; cat $GPU/max_gpuclk 2>/dev/null || cat $GPU/devfreq/max_freq 2>/dev/null || echo x"
        val out = RootShell.exec(ctx, cmd) ?: return null
        val v = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun at(i: Int) = v.getOrNull(i)?.toDoubleOrNull()
        var i = 0
        val cpu = cpuZones.mapNotNull { at(i++) }.maxOrNull()?.div(1000) ?: 0.0
        val gpu = gpuZones.mapNotNull { at(i++) }.maxOrNull()?.div(1000) ?: 0.0
        val batt = if (battZone != null) (at(i++) ?: 0.0) / 1000 else 0.0
        val fan = if (fanPath != null) (at(i++)?.toInt() ?: -1) else -1
        val cpuMax = policies.map { ((at(i++) ?: 0.0) / 1000).toInt() }
        val gpuMaxRaw = at(i) ?: 0.0
        val gpuMHz = if (gpuMaxRaw > 1e6) (gpuMaxRaw / 1e6).toInt() else gpuMaxRaw.toInt()
        return Reading(cpu, gpu, batt, fan, cpuMax, gpuMHz)
    }

    /** Temperature files the daemon watches (CPU and GPU). */
    fun controlZones(): List<String> = cpuZones + gpuZones

    /**
     * path:value pairs that cap CPU clusters and the GPU at a percentage of their maximum clock
     * (snapped to a supported frequency). 100 restores full speed.
     */
    fun limitPairs(cpuPct: Int, gpuPct: Int): List<String> {
        val pairs = mutableListOf<String>()
        for (pol in policies) {
            val target = pol.maxKHz * cpuPct.coerceIn(20, 100) / 100
            val f = if (cpuPct >= 100) pol.maxKHz else pol.freqsKHz.lastOrNull { it <= target } ?: pol.freqsKHz.first()
            pairs += "${pol.path}/scaling_max_freq:$f"
        }
        if (gpuFreqsHz.isNotEmpty()) {
            val maxHz = gpuFreqsHz.last()
            val target = maxHz * gpuPct.coerceIn(20, 100) / 100
            val f = if (gpuPct >= 100) maxHz else gpuFreqsHz.lastOrNull { it <= target } ?: gpuFreqsHz.first()
            pairs += "$GPU/max_gpuclk:$f"
            pairs += "$GPU/devfreq/max_freq:$f"
        }
        return pairs
    }
}

/**
 * The root control daemon. It is started once over adb root; after that the app only writes files:
 * hs.conf (settings, picked up right away) and hs.cmd (one-shot root commands, run within 0.2 s).
 * So quick settings taps and setting changes need no adb connection.
 */
object Daemon {
    private const val PATH = "/data/local/tmp/hs_daemon.sh"
    private const val PID = "/data/local/tmp/hs_daemon.pid"

    private fun q(v: String) = "'" + v.replace("'", "") + "'"

    private fun confText(ctx: Context): String {
        val p = Prefs(ctx)
        val tdp = p.tdp
        val cpu = if (tdp == TdpProfile.CUSTOM) p.cpuPct else tdp.cpuPct
        val gpu = if (tdp == TdpProfile.CUSTOM) p.gpuPct else tdp.gpuPct
        return listOf(
            "MODE=${p.fanMode.name}", "LEVEL=${p.fanLevel}", "TARGET=${p.targetTemp}", "SAFETY=${p.safetyTemp}",
            "SMOOTH=${p.smoothness}", "CPU_PCT=$cpu", "GPU_PCT=$gpu", "REASSERT=${if (tdp == TdpProfile.NONE) 0 else 1}",
            "AUTOAPPS=${if (p.autoNewApps && p.forcedEnabled) 1 else 0}", "EXCLUDE=${q(p.excludedApps.joinToString(" "))}",
            "SLEEPSAVE=${if (p.sleepSave) 1 else 0}", "BATLED=${p.batteryLed.ordinal}",
            "SELF=${ctx.packageName}", "COMPAT=${q(ScreenTools.COMPAT_CHANGES.joinToString(" "))}"
        ).joinToString("\n", postfix = "\n")
    }

    /** Writes a file atomically (temp + rename) so the daemon never reads half of it. */
    private fun atomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text); tmp.renameTo(f)
    }

    fun writeConf(ctx: Context) = atomic(File(ctx.filesDir, "hs.conf"), confText(ctx))

    /** Hash of the bundled script; the daemon writes the one it was started with into hs.alive. */
    private fun script(ctx: Context) =
        ctx.assets.open("hs_daemon.sh").bufferedReader().use { it.readText() }.replace(13.toChar().toString(), "")
    private fun hash(ctx: Context) = Integer.toHexString(script(ctx).hashCode())

    /** True when the running daemon is alive and runs the bundled script (then no adb restart is needed). */
    fun isCurrent(ctx: Context): Boolean =
        isAlive(ctx) && runCatching { File(ctx.filesDir, "hs.alive").readText().trim() == hash(ctx) }.getOrDefault(false)

    /** Starts the daemon only when it is not running or runs an older script. */
    fun ensureCurrent(ctx: Context): Boolean { writeConf(ctx); return isCurrent(ctx) || restart(ctx) }

    /** The daemon touches hs.alive every 2 s. */
    fun isAlive(ctx: Context): Boolean {
        val f = File(ctx.filesDir, "hs.alive")
        return f.exists() && System.currentTimeMillis() - f.lastModified() < 8_000
    }

    /** Applies fan/TDP/auto-app settings: instant through the running daemon, else (re)starts it. */
    fun update(ctx: Context): Boolean {
        writeConf(ctx)
        return isAlive(ctx) || restart(ctx)
    }

    /** Runs a root command: through the daemon when it is running (fast), otherwise over adb root. */
    fun runRoot(ctx: Context, cmd: String): Boolean {
        if (!isAlive(ctx)) return RootShell.exec(ctx, cmd) != null
        val f = File(ctx.filesDir, "hs.cmd")
        synchronized(this) {
            if (f.exists()) f.appendText(cmd + "\n") else atomic(f, cmd + "\n")
        }
        val until = System.currentTimeMillis() + 5_000
        while (f.exists() && System.currentTimeMillis() < until) Thread.sleep(50)
        return true
    }

    /** Copies the controller helper script next to the app's files and returns its path. */
    fun tools(ctx: Context): String {
        val f = File(ctx.filesDir, "hs_tools.sh")
        val text = ctx.assets.open("hs_tools.sh").bufferedReader().use { it.readText() }.replace(13.toChar().toString(), "")
        if (!f.exists() || f.readText() != text) f.writeText(text)
        return f.absolutePath
    }

    /** (Re)starts the daemon over adb root; also used to pick up a new script version. */
    fun restart(ctx: Context): Boolean {
        writeConf(ctx)
        val script = script(ctx)
        // The heredoc terminator must sit alone at column 0, so the command is assembled line by line.
        val cmd = listOf(
            "[ -f $PID ] && kill \$(cat $PID) 2>/dev/null",
            "cat > $PATH <<'HSEOF'",
            script.trimEnd(),
            "HSEOF",
            "setsid sh $PATH ${ctx.filesDir.absolutePath} ${hash(ctx)} >/dev/null 2>&1 < /dev/null &",
            "sleep 1; [ -f $PID ] && kill -0 \$(cat $PID) 2>/dev/null && echo RUNNING"
        ).joinToString("\n")
        return RootShell.exec(ctx, cmd)?.contains("RUNNING") == true
    }

    fun isRunning(ctx: Context): Boolean =
        RootShell.exec(ctx, "[ -f $PID ] && kill -0 \$(cat $PID) 2>/dev/null && echo RUNNING")?.contains("RUNNING") == true
}
