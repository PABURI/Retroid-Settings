package app.retroidsettings.lineage

import android.content.Context

/**
 * Forced full-screen tools. Parts of this reset on reboot, so the service re-applies everything
 * once root is available after boot.
 */
object ScreenTools {
    val COMPAT_CHANGES = listOf(
        "OVERRIDE_ANY_ORIENTATION_TO_USER",
        "OVERRIDE_ANY_ORIENTATION",
        "FORCE_RESIZE_APP",
        "UNIVERSAL_RESIZABLE_BY_DEFAULT",
        "OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION"
    )
    /** PackageManager.USER_MIN_ASPECT_RATIO_* */
    const val ASPECT_UNSET = 0
    const val ASPECT_FULLSCREEN = 6

    /** Re-applies everything without restarting the selected apps (used after boot). */
    fun applyAll(ctx: Context, prefs: Prefs): Boolean {
        var ok = true
        if (prefs.lockLandscape) ok = lockLandscape(ctx, true) && ok
        if (prefs.fullscreenOption) ok = enableFullscreenOption(ctx) && ok
        if (prefs.lockDateTop) ok = lockScreenDate(ctx, true) && ok
        ok = applyDensityPref(ctx, prefs) && ok
        val apps = prefs.forcedAppsOrAll(ctx)
        if (prefs.forcedEnabled && apps.isNotEmpty()) ok = forceFullscreen(ctx, apps, on = true, restartApps = false) && ok
        return ok
    }

    fun lockLandscape(ctx: Context, on: Boolean): Boolean {
        val cmd = if (on)
            "wm user-rotation lock 0; wm fixed-to-user-rotation enabled; wm set-ignore-orientation-request true; settings put system accelerometer_rotation 0"
        else
            "wm fixed-to-user-rotation default; wm set-ignore-orientation-request false; wm user-rotation free"
        return RootShell.exec(ctx, cmd) != null
    }

    /** Makes the hidden "Full screen" choice appear in Settings > Apps > Aspect ratio (resets on reboot). */
    fun enableFullscreenOption(ctx: Context): Boolean {
        val cmd = listOf(
            "cmd overlay fabricate --target android --name RP6AspectSettings android:bool/config_appCompatUserAppAspectRatioSettingsIsEnabled 0x12 0xffffffff",
            "cmd overlay fabricate --target android --name RP6AspectFullscreen android:bool/config_appCompatUserAppAspectRatioFullscreenIsEnabled 0x12 0xffffffff",
            "cmd overlay enable com.android.shell:RP6AspectSettings",
            "cmd overlay enable com.android.shell:RP6AspectFullscreen",
            "device_config put window_manager enable_app_compat_aspect_ratio_user_settings true",
            "device_config put window_manager enable_app_compat_user_aspect_ratio_fullscreen true",
            "wm set-letterbox-style --isUserAppAspectRatioSettingsEnabled true --isUserAppAspectRatioFullscreenEnabled true"
        ).joinToString("; ") { "$it >/dev/null 2>&1" }
        return RootShell.exec(ctx, cmd) != null
    }

    /**
     * Per-app: compat overrides + user aspect ratio "Full screen".
     * The aspect ratio for all packages is set by one app_process call (it drops to the system uid).
     */
    fun forceFullscreen(ctx: Context, packages: Collection<String>, on: Boolean, restartApps: Boolean = true): Boolean {
        if (packages.isEmpty()) return true
        val out = RootShell.exec(ctx, forceFullscreenCommand(ctx, packages, on, restartApps), timeoutMs = 180_000) ?: return false
        return !out.contains("ERR")
    }

    fun forceFullscreenCommand(ctx: Context, packages: Collection<String>, on: Boolean, restartApps: Boolean): String {
        val apk = ctx.applicationInfo.sourceDir
        val action = if (on) "enable" else "reset"
        val ratio = if (on) ASPECT_FULLSCREEN else ASPECT_UNSET
        val cmds = mutableListOf<String>()
        for (pkg in packages) for (c in COMPAT_CHANGES) cmds += "am compat $action $c $pkg >/dev/null 2>&1"
        cmds += "CLASSPATH=$apk app_process /system/bin app.retroidsettings.lineage.RootHelper aspect $ratio ${packages.joinToString(" ")} 2>&1"
        if (restartApps) for (pkg in packages) cmds += "am force-stop $pkg"
        return cmds.joinToString("; ")
    }

    /**
     * Moves the lock screen date up into the top-left corner (landscape split-shade layout) with two
     * SystemUI dimension overlays: keyguard_split_shade_top_margin 68dp -> 0dp and
     * below_clock_padding_start 32dp -> 8dp. Root-made overlays vanish on reboot, so this re-creates them
     * when missing and restarts SystemUI so the change shows.
     */
    fun lockScreenDate(ctx: Context, on: Boolean): Boolean {
        val names = listOf("RP6DateTest", "RP6DateHorizontalTest")
        if (!on) {
            val cmd = names.joinToString("; ") { "cmd overlay disable --user 0 com.android.shell:$it >/dev/null 2>&1" } +
                "; pkill -f com.android.systemui"
            return RootShell.exec(ctx, cmd) != null
        }
        val present = RootShell.exec(ctx, "cmd overlay list com.android.systemui | grep RP6Date | grep -c x")
            ?.trim()?.toIntOrNull() ?: return false
        if (present >= names.size) return true
        val apk = ctx.applicationInfo.sourceDir
        val helper = "CLASSPATH=$apk app_process /system/bin app.retroidsettings.lineage.RootHelper dimen"
        val cmd = listOf(
            "$helper RP6DateTest com.android.systemui com.android.systemui:dimen/keyguard_split_shade_top_margin 0",
            "$helper RP6DateHorizontalTest com.android.systemui com.android.systemui:dimen/below_clock_padding_start 8",
            "pkill -f com.android.systemui"
        ).joinToString("; ")
        val out = RootShell.exec(ctx, cmd, timeoutMs = 30_000) ?: return false
        return !out.contains("ERR")
    }

    /**
     * Undoes everything this app changed so that uninstalling leaves a stock system: stops the daemon,
     * puts back fan trip points, CPU/GPU limits, stick LEDs and calibration, rotation, density, the
     * lock screen date and full-screen overlays, and resets every app it forced to full screen.
     * Settings that the stock Settings app also owns (controller layout, trigger mode, refresh rate,
     * auto-rotate) stay as they are.
     */
    fun restoreStock(ctx: Context, prefs: Prefs): Boolean {
        val apk = ctx.applicationInfo.sourceDir
        val apps = (launcherApps(ctx).map { it.first } + prefs.forcedApps + prefs.excludedApps).toSet()
        val cmd = listOf(
            "P=/data/local/tmp/hs_daemon.pid; [ -f ${'$'}P ] && kill ${'$'}(cat ${'$'}P); sleep 1",
            "T=/data/local/tmp/hs_fan_trips; [ -f ${'$'}T ] && while read -r pair; do echo ${'$'}{pair##*:} > ${'$'}{pair%:*}; done < ${'$'}T",
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do cat ${'$'}p/cpuinfo_max_freq > ${'$'}p/scaling_max_freq; done",
            "G=/sys/class/kgsl/kgsl-3d0; m=0; for f in ${'$'}(cat ${'$'}G/gpu_available_frequencies); do [ ${'$'}f -gt ${'$'}m ] && m=${'$'}f; done; [ ${'$'}m -gt 0 ] && { echo ${'$'}m > ${'$'}G/max_gpuclk; echo ${'$'}m > ${'$'}G/devfreq/max_freq; }",
            "sh ${Daemon.tools(ctx)} stock_led",
            "v=${'$'}(sh ${Daemon.tools(ctx)} calib_stock); [ -n \"${'$'}v\" ] && echo ${'$'}v > /sys/class/moorechip-joystick/joystick/calibration",
            "wm fixed-to-user-rotation default; wm set-ignore-orientation-request false; wm user-rotation free",
            "cmd overlay disable com.android.shell:RP6AspectSettings; cmd overlay disable com.android.shell:RP6AspectFullscreen",
            "device_config delete window_manager enable_app_compat_aspect_ratio_user_settings; device_config delete window_manager enable_app_compat_user_aspect_ratio_fullscreen; wm reset-letterbox-style",
            "for a in ${apps.joinToString(" ")}; do am compat reset-all --no-kill ${'$'}a; done",
            "CLASSPATH=$apk app_process /system/bin app.retroidsettings.lineage.RootHelper aspect $ASPECT_UNSET ${apps.joinToString(" ")}",
            "wm density reset",
            "rm -f /data/local/tmp/hs_*",
            "cmd overlay disable --user 0 com.android.shell:RP6DateTest; cmd overlay disable --user 0 com.android.shell:RP6DateHorizontalTest; pkill -f com.android.systemui"
        ).joinToString("\n") { "($it) >/dev/null 2>&1" } + "\necho DONE"
        return RootShell.exec(ctx, cmd, timeoutMs = 180_000)?.contains("DONE") == true
    }

    data class Density(val physical: Int, val current: Int, val shortSidePx: Int)

    /** Physical and current (override) display density plus the short side of the screen in pixels. */
    fun readDensity(ctx: Context): Density? {
        val out = RootShell.exec(ctx, "wm density; wm size") ?: return null
        val phys = Regex("Physical density: ([0-9]+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val over = Regex("Override density: ([0-9]+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val size = Regex("Physical size: ([0-9]+)x([0-9]+)").find(out)?.groupValues
        val short = if (size != null) minOf(size[1].toInt(), size[2].toInt()) else 1080
        return Density(phys, over ?: phys, short)
    }

    /** Sets the display density (null = back to the physical value). Kept by the system across reboots. */
    fun setDensity(ctx: Context, dpi: Int?): Boolean =
        RootShell.exec(ctx, if (dpi == null) "wm density reset" else "wm density $dpi") != null

    const val TABLET_DPI = 288

    /** Sets tablet (288) or the physical density according to the preference, if it is not already so. */
    fun applyDensityPref(ctx: Context, prefs: Prefs): Boolean {
        val d = readDensity(ctx) ?: return false
        val want = if (prefs.tabletDensity) TABLET_DPI else d.physical
        return if (d.current == want) true else setDensity(ctx, if (prefs.tabletDensity) TABLET_DPI else null)
    }

    /** Smallest width in dp; 600 or more is treated as a tablet. */
    fun smallestWidthDp(shortSidePx: Int, dpi: Int): Int = shortSidePx * 160 / dpi

    /** (package, label) of every app with a launcher icon, except this one. */
    fun launcherApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /** Current user aspect ratio per package (6 = full screen). */
    fun readAspects(ctx: Context, packages: Collection<String>): Map<String, Int> {
        if (packages.isEmpty()) return emptyMap()
        val apk = ctx.applicationInfo.sourceDir
        val out = RootShell.exec(
            ctx, "CLASSPATH=$apk app_process /system/bin app.retroidsettings.lineage.RootHelper get ${packages.joinToString(" ")} 2>&1",
            timeoutMs = 60_000
        ) ?: return emptyMap()
        return out.lines().mapNotNull { line ->
            val m = Regex("^OK (\\S+)=(-?\\d+)").find(line.trim()) ?: return@mapNotNull null
            m.groupValues[1] to m.groupValues[2].toInt()
        }.toMap()
    }
}
