package app.retroidsettings.lineage

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Battery status. Live values come from the system battery broadcast (no root); design/full capacity
 * are read from the power_supply node over root when the app is connected.
 */
object Battery {
    const val SPEC_MAH = 6000

    data class Info(
        val percent: Int, val status: String, val chargeType: String?, val watts: Double?,
        val tempC: Double, val voltage: Double, val cycles: Int?,
        val fullMah: Int?, val designMah: Int?
    ) {
        /** Full charge vs. the driver's design value, capped at 100 % (new cells often measure above it). */
        val healthPct: Int? get() = if (fullMah != null && designMah != null && designMah > 0) minOf(100, fullMah * 100 / designMah) else null
        /** Usable capacity against the rated 6,000 mAh of the RP6 (the driver's design value is 6,442). */
        val capacityMah: Int? get() = healthPct?.let { SPEC_MAH * it / 100 }
    }

    @Volatile private var capacity: Triple<Int, Int, String?>? = null   // full mAh, design mAh, charge type

    fun read(ctx: Context): Info? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val st = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> tr(R.string.bat_charging)
            BatteryManager.BATTERY_STATUS_FULL -> tr(R.string.bat_full)
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> tr(R.string.bat_not_charging)
            else -> tr(R.string.bat_discharging)
        }
        val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val cycles = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val watts = if (currentUa != Long.MIN_VALUE && currentUa != 0L) Math.abs(currentUa) / 1e6 * volt else null
        val cap = capacity
        return Info(level * 100 / scale, st, cap?.third, watts, temp, volt, cycles, cap?.first, cap?.second)
    }

    /** Reads full/design capacity and charge type (root). Called when the app connects. */
    fun loadCapacity(ctx: Context) {
        val out = RootShell.exec(ctx, "B=/sys/class/power_supply/battery; cat \$B/charge_full \$B/charge_full_design \$B/charge_type 2>/dev/null") ?: return
        val l = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val full = l.getOrNull(0)?.toLongOrNull()?.div(1000)?.toInt() ?: return
        val design = l.getOrNull(1)?.toLongOrNull()?.div(1000)?.toInt() ?: return
        capacity = Triple(full, design, l.getOrNull(2))
    }
}
