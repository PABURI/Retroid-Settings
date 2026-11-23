package app.retroidsettings.lineage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tiles. Each tap changes one setting; work that needs root runs on a background
 * thread (connect -> apply -> drop the adb link again when no app screen is open).
 */
abstract class BaseTile : TileService() {
    protected val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    abstract fun label(): String
    abstract fun subtitle(): String
    abstract fun active(): Boolean
    /** Called on tap (main thread): change prefs and return the root work to run, or null. */
    abstract fun onTap(): ((Context) -> Unit)?

    override fun onStartListening() { refresh() }

    override fun onClick() {
        if (busy) return
        val work = onTap()
        refresh()
        if (work == null) return
        busy = true
        val app = applicationContext
        Thread {
            try {
                work(app)   // through the running daemon; falls back to adb root only if it is not running
            } catch (e: Exception) {
                android.util.Log.w("Tile", "tile action failed", e)
            } finally {
                if (RootShell.isRoot && !UiVisibility.visible && !Status.running) RootShell.disconnect(app)
                busy = false
                main.post { refresh() }
            }
        }.start()
    }

    protected fun refresh() {
        val t = qsTile ?: return
        t.label = label()
        t.subtitle = if (busy) tr(R.string.applying) else subtitle()
        t.state = if (active()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}

class TdpTile : BaseTile() {
    private val cycle = listOf(TdpProfile.NONE, TdpProfile.SAVE, TdpProfile.BALANCED, TdpProfile.PERFORMANCE)
    override fun label() = "TDP"
    override fun subtitle() = prefs.tdp.label
    override fun active() = prefs.tdp != TdpProfile.NONE
    override fun onTap(): (Context) -> Unit {
        val i = cycle.indexOf(prefs.tdp)
        prefs.tdp = cycle[(i + 1) % cycle.size]
        return { Daemon.update(it) }
    }
}

class FanTile : BaseTile() {
    private val names = mapOf(FanMode.AUTO to tr(R.string.fan_system), FanMode.SMOOTH to tr(R.string.fan_smooth), FanMode.MANUAL to tr(R.string.fan_manual), FanMode.TARGET to tr(R.string.fan_target))
    override fun label() = tr(R.string.fan)
    override fun subtitle() = names[prefs.fanMode] ?: ""
    override fun active() = prefs.fanMode != FanMode.AUTO
    override fun onTap(): (Context) -> Unit {
        val all = FanMode.entries
        prefs.fanMode = all[(all.indexOf(prefs.fanMode) + 1) % all.size]
        return { Daemon.update(it) }
    }
}

class FullscreenTile : BaseTile() {
    override fun label() = tr(R.string.fullscreen)
    override fun subtitle() = if (prefs.forcedEnabled) tr(R.string.on) else tr(R.string.off)
    override fun active() = prefs.forcedEnabled
    override fun onTap(): (Context) -> Unit {
        val on = !prefs.forcedEnabled
        prefs.forcedEnabled = on
        return { ctx ->
            Daemon.runRoot(ctx, ScreenTools.forceFullscreenCommand(ctx, prefs.forcedAppsOrAll(ctx), on, restartApps = false))
            Daemon.update(ctx)   // new-app watcher follows the switch
        }
    }
}

class DensityTile : BaseTile() {
    override fun label() = tr(R.string.density)
    override fun subtitle() = if (prefs.tabletDensity) tr(R.string.density_tablet) else tr(R.string.density_default)
    override fun active() = prefs.tabletDensity
    override fun onTap(): (Context) -> Unit {
        prefs.tabletDensity = !prefs.tabletDensity
        return { Daemon.runRoot(it, if (prefs.tabletDensity) "wm density ${ScreenTools.TABLET_DPI}" else "wm density reset") }
    }
}

/** Fixed rotations only (no sensor): the RP6 orientation sensor is off by 90 degrees on this build. */
class RotationTile : BaseTile() {
    private val cycle = listOf(ScreenTools.ROT_LANDSCAPE, ScreenTools.ROT_AUTO, ScreenTools.ROT_AUTO_FIX,
        ScreenTools.ROT_PORTRAIT, ScreenTools.ROT_PORTRAIT_REV)
    private val kept = setOf(ScreenTools.ROT_LANDSCAPE, ScreenTools.ROT_AUTO, ScreenTools.ROT_AUTO_FIX)
    private fun names(m: Int) = when (m) {
        ScreenTools.ROT_AUTO -> tr(R.string.rot_auto)
        ScreenTools.ROT_AUTO_FIX -> tr(R.string.rot_auto_fix)
        ScreenTools.ROT_PORTRAIT -> tr(R.string.portrait)
        ScreenTools.ROT_PORTRAIT_REV -> tr(R.string.portrait_rev)
        else -> tr(R.string.landscape)
    }
    private fun current(): Int = when {
        RotationService.running -> ScreenTools.ROT_AUTO_FIX
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 -> ScreenTools.ROT_AUTO
        else -> Settings.System.getInt(contentResolver, Settings.System.USER_ROTATION, 0).let { if (it in cycle) it else 0 }
    }
    override fun label() = tr(R.string.rotation)
    override fun subtitle() = names(current())
    override fun active() = current() != ScreenTools.ROT_LANDSCAPE
    override fun onTap(): (Context) -> Unit {
        val next = cycle[(cycle.indexOf(current()) + 1) % cycle.size]
        // Landscape and the two auto modes are kept (re-applied after boot); portrait is a one-off.
        if (next in kept) prefs.rotationMode = next
        return { Daemon.runRoot(it, ScreenTools.rotationCommand(next)) }
    }
}

class LayoutTile : BaseTile() {
    override fun label() = tr(R.string.ctrl_layout)
    override fun subtitle() = if (prefs.layoutXbox) "Xbox" else "Nintendo"
    override fun active() = true
    override fun onTap(): (Context) -> Unit {
        val xbox = !prefs.layoutXbox
        prefs.layoutXbox = xbox
        return { Gamepad.setLayout(it, if (xbox) "xbox" else "nintendo") }
    }
}

/** 60 Hz <-> 120 Hz (Settings.System peak_refresh_rate; min stays 0 so the system may still drop when idle). */
class RefreshTile : BaseTile() {
    private fun peak() = Settings.System.getFloat(contentResolver, "peak_refresh_rate", 120f)
    private fun is120() = peak() > 61f
    override fun label() = tr(R.string.refresh_rate)
    override fun subtitle() = if (is120()) "120Hz" else "60Hz"
    override fun active() = is120()
    override fun onTap(): ((Context) -> Unit)? {
        // Apps may not write this system key directly, so it goes through the root daemon.
        val next = if (is120()) 60 else 120
        return { Daemon.runRoot(it, "settings put system peak_refresh_rate $next.0; settings put system min_refresh_rate 0.0") }
    }
}
