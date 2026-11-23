package app.retroidsettings.lineage

import android.content.Context
import android.content.SharedPreferences

enum class FanMode { AUTO, SMOOTH, MANUAL, TARGET }
enum class BatteryLed(private val labelRes: Int) {
    OFF(R.string.led_off), ALWAYS(R.string.led_always), CHARGING(R.string.bat_charging);
    val label get() = tr(labelRes)
}
enum class TdpProfile(private val labelRes: Int, val cpuPct: Int, val gpuPct: Int) {
    NONE(R.string.tdp_none, 100, 100),
    SAVE(R.string.tdp_save, 55, 60),
    BALANCED(R.string.tdp_balanced, 75, 80),
    PERFORMANCE(R.string.tdp_perf, 100, 100),
    CUSTOM(R.string.tdp_custom, -1, -1);
    val label get() = tr(labelRes)
}

class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fanMode: FanMode
        get() = runCatching { FanMode.valueOf(p.getString("fan_mode", "SMOOTH")!!) }.getOrDefault(FanMode.SMOOTH)
        set(v) = p.edit().putString("fan_mode", v.name).apply()
    var fanLevel: Int
        get() = p.getInt("fan_level", 4)
        set(v) = p.edit().putInt("fan_level", v).apply()
    var targetTemp: Int
        get() = p.getInt("target_temp", 65)
        set(v) = p.edit().putInt("target_temp", v).apply()
    var safetyTemp: Int
        get() = p.getInt("safety_temp", 85)
        set(v) = p.edit().putInt("safety_temp", v).apply()

    /** 1..5: how calm the fan is (averaging window, wait before slowing down). */
    var smoothness: Int
        get() = p.getInt("smoothness", 3)
        set(v) = p.edit().putInt("smoothness", v).apply()

    /** Stick/trigger calibration written by this app (re-applied after boot), and the original value. */
    var calibration: String?
        get() = p.getString("calibration", null)
        set(v) = p.edit().putString("calibration", v).apply()
    var calibrationBackup: String?
        get() = p.getString("calibration_backup", null)
        set(v) = p.edit().putString("calibration_backup", v).apply()

    var tdp: TdpProfile
        get() = runCatching { TdpProfile.valueOf(p.getString("tdp", "NONE")!!) }.getOrDefault(TdpProfile.NONE)
        set(v) = p.edit().putString("tdp", v.name).apply()
    var cpuPct: Int
        get() = p.getInt("cpu_pct", 80)
        set(v) = p.edit().putInt("cpu_pct", v).apply()
    var gpuPct: Int
        get() = p.getInt("gpu_pct", 80)
        set(v) = p.edit().putInt("gpu_pct", v).apply()

    /** Saved rotation: ScreenTools.ROT_LANDSCAPE, ROT_AUTO or ROT_AUTO_FIX (re-applied after boot). */
    var rotationMode: Int
        get() = when {
            p.contains("rotation_mode") -> p.getInt("rotation_mode", ScreenTools.ROT_LANDSCAPE)
            !p.getBoolean("lock_landscape", true) -> ScreenTools.ROT_AUTO_FIX   // older "auto" setting
            else -> ScreenTools.ROT_LANDSCAPE
        }
        set(v) = p.edit().putInt("rotation_mode", v).remove("lock_landscape").apply()
    var fullscreenOption: Boolean
        get() = p.getBoolean("fullscreen_option", true)
        set(v) = p.edit().putBoolean("fullscreen_option", v).apply()
    /**
     * Apps forced to full screen. With auto-include on (default): every launcher app except the ones the
     * user unchecked, so newly installed apps are included. Otherwise the explicit selection.
     */
    fun forcedAppsOrAll(ctx: Context): Set<String> {
        val all = ScreenTools.launcherApps(ctx).map { it.first }.toSet()
        return when {
            autoNewApps -> all - excludedApps
            p.contains("forced_apps") -> forcedApps
            else -> all
        }
    }

    /** Master switch for forced full screen (quick settings tile). */
    var forcedEnabled: Boolean
        get() = p.getBoolean("forced_enabled", true)
        set(v) = p.edit().putBoolean("forced_enabled", v).apply()

    /** Stick LEDs show the battery level (over the stock lighting setting): off, always, or while plugged in. */
    var batteryLed: BatteryLed
        get() = runCatching { BatteryLed.valueOf(p.getString("battery_led", "OFF")!!) }.getOrDefault(BatteryLed.OFF)
        set(v) = p.edit().putString("battery_led", v.name).apply()

    var layoutXbox: Boolean
        get() = p.getBoolean("layout_xbox", false)
        set(v) = p.edit().putBoolean("layout_xbox", v).apply()

    /** Screen off: fan off (in the app's fan modes) and CPU/GPU capped at 50 %. On by default. */
    var sleepSave: Boolean
        get() = p.getBoolean("sleep_save", true)
        set(v) = p.edit().putBoolean("sleep_save", v).apply()

    /** Force full screen on apps installed later too. On by default. */
    var autoNewApps: Boolean
        get() = p.getBoolean("auto_new_apps", true)
        set(v) = p.edit().putBoolean("auto_new_apps", v).apply()

    /** Apps the user unchecked; never forced automatically. */
    var excludedApps: Set<String>
        get() = p.getStringSet("excluded_apps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("excluded_apps", v).apply()
    /** Keep the lock screen date in the top-left corner (SystemUI overlays re-created after boot). */
    var lockDateTop: Boolean
        get() = p.getBoolean("lock_date_top", true)
        set(v) = p.edit().putBoolean("lock_date_top", v).apply()

    /** Use the tablet density (288: smallest width 600dp on a 1080 px screen). On by default. */
    var tabletDensity: Boolean
        get() = p.getBoolean("tablet_density", true)
        set(v) = p.edit().putBoolean("tablet_density", v).apply()

    var forcedApps: Set<String>
        get() = p.getStringSet("forced_apps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("forced_apps", v).apply()

    /** Keep a status notification (temperatures, fan) in the shade. Off by default. */
    var showNotification: Boolean
        get() = p.getBoolean("show_notification", false)
        set(v) = p.edit().putBoolean("show_notification", v).apply()

    /** Switch Wireless debugging off again when the app is done (hides the system "connected" notification). */
    var wirelessAutoOff: Boolean
        get() = p.getBoolean("wireless_auto_off", true)
        set(v) = p.edit().putBoolean("wireless_auto_off", v).apply()

    var startOnBoot: Boolean
        get() = p.getBoolean("start_on_boot", true)
        set(v) = p.edit().putBoolean("start_on_boot", v).apply()
}
