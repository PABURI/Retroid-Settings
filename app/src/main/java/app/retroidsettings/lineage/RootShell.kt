package app.retroidsettings.lineage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs shell commands as root without Magisk:
 * this app connects to the device's own adbd over Wireless debugging (TLS),
 * then asks adbd to restart as root ("Rooted debugging" in Developer options must be on).
 */
object RootShell {
    private const val TAG = "RootShell"
    private val lock = Any()

    @Volatile var state: String = tr(R.string.rs_not_connected)
        private set
    @Volatile var isRoot: Boolean = false
        private set
    @Volatile var needsPairing: Boolean = false
        private set

    /** Turns Wireless debugging on if the app was granted WRITE_SECURE_SETTINGS. */
    fun enableWirelessDebugging(ctx: Context): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            if (Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) != 1) {
                Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
                Thread.sleep(1500)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "enable wireless adb failed", e); false
        }
    }

    /** Connects and makes sure the shell is root. Returns true when commands will run as uid 0. */
    fun ensure(ctx: Context): Boolean = synchronized(lock) {
        val m = AdbManager.get(ctx)
        try {
            if (m.isConnected && isRoot) return true
            if (!m.isConnected) {
                state = tr(R.string.rs_connecting)
                enableWirelessDebugging(ctx)
                if (!connect(ctx)) {
                    state = tr(R.string.rs_cant_connect)
                    return false
                }
            }
            needsPairing = false
            if (rawExec(m, "id -u").trim() == "0") {
                isRoot = true; state = tr(R.string.st_root); selfGrant(ctx, m); return true
            }
            state = tr(R.string.rs_switching)
            // adbd answers and then restarts, which can leave the stream hanging: read with a time limit.
            val reply = try { readAll(m.openStream("root:"), 4_000) } catch (e: Exception) { "" }
            Log.i(TAG, "root: reply=$reply")
            if (reply.contains("cannot", true) || reply.contains("disabled", true)) {
                isRoot = false
                state = tr(R.string.rs_root_denied)
                return false
            }
            try { m.disconnect() } catch (_: Exception) {}
            var ok = false
            for (i in 0 until 12) {
                Thread.sleep(1500)
                if (try { connect(ctx) } catch (_: Exception) { false }) { ok = true; break }
            }
            if (!ok) { state = tr(R.string.rs_reconnect_failed); return false }
            val uid = rawExec(m, "id -u").trim()
            isRoot = uid == "0"
            state = if (isRoot) tr(R.string.st_root) else tr(R.string.rs_not_root, uid)
            if (isRoot) selfGrant(ctx, m)
            isRoot
        } catch (e: AdbPairingRequiredException) {
            needsPairing = true; isRoot = false
            state = tr(R.string.rs_needs_pairing)
            false
        } catch (e: Exception) {
            Log.w(TAG, "ensure failed", e)
            isRoot = false
            state = tr(R.string.rs_error, "${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            try { m.disconnect() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Grants this app the permissions that normally need "adb shell pm grant" from a PC:
     * WRITE_SECURE_SETTINGS (to switch Wireless debugging on after boot) and POST_NOTIFICATIONS.
     */
    private fun selfGrant(ctx: Context, m: AdbManager) {
        val perms = listOf(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (perms.isEmpty()) return
        try {
            rawExec(m, perms.joinToString("; ") { "pm grant ${ctx.packageName} $it" })
        } catch (e: Exception) {
            Log.w(TAG, "self grant failed", e)
        }
    }

    private fun connect(ctx: Context): Boolean {
        val m = AdbManager.get(ctx)
        if (m.connectTls(ctx, 8_000)) return true
        // Fallback: resolve the TLS port ourselves and connect to localhost and the Wi-Fi address.
        val port = discoverPort(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 6_000) ?: return false
        for (host in listOf("127.0.0.1", AndroidUtils.getHostIpAddress(ctx))) {
            try { if (m.connect(host, port)) return true } catch (e: AdbPairingRequiredException) { throw e } catch (_: Exception) {}
        }
        return false
    }

    fun discoverPort(ctx: Context, type: String, timeoutMs: Long): Int? {
        var found: Int? = null
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(ctx, type) { _: InetAddress?, port: Int ->
            if (port > 0) { found = port; latch.countDown() }
        }
        mdns.start()
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } finally { mdns.stop() }
        return found
    }

    /** Pairs this app with Wireless debugging. Port may be 0 to discover it automatically. */
    fun pair(ctx: Context, portIn: Int, code: String): String = synchronized(lock) {
        return try {
            val port = if (portIn > 0) portIn else
                discoverPort(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 15_000)
                    ?: return tr(R.string.rs_no_pair_port)
            val m = AdbManager.get(ctx)
            var ok = false
            for (host in listOf("127.0.0.1", AndroidUtils.getHostIpAddress(ctx))) {
                ok = try { m.pair(host, port, code.trim()) } catch (e: Exception) { Log.w(TAG, "pair $host", e); false }
                if (ok) break
            }
            if (ok) { needsPairing = false; state = tr(R.string.rs_paired); tr(R.string.rs_paired) } else tr(R.string.rs_pair_failed)
        } catch (e: Exception) {
            tr(R.string.rs_pair_error, e.message ?: "")
        }
    }

    /** Runs a command as root. Returns null when root is not available. */
    fun exec(ctx: Context, cmd: String, timeoutMs: Long = 15_000): String? {
        if (!ensure(ctx)) return null
        synchronized(lock) {
            val m = AdbManager.get(ctx)
            return try {
                rawExec(m, cmd, timeoutMs)
            } catch (e: Exception) {
                Log.w(TAG, "exec failed: $cmd", e)
                isRoot = false
                try { m.disconnect() } catch (_: Exception) {}
                null
            }
        }
    }

    /**
     * Drops the adb link. If enabled in settings, also switches Wireless debugging off, which removes the
     * system's "Wireless debugging connected" notification. It is switched back on automatically next time.
     */
    fun disconnect(ctx: Context) = synchronized(lock) {
        try { AdbManager.get(ctx).disconnect() } catch (_: Exception) {}
        isRoot = false
        state = tr(R.string.rs_idle)
        if (Prefs(ctx).wirelessAutoOff &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        ) {
            try { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 0) } catch (_: Exception) {}
        }
    }

    private const val END = "__RP6END__"

    /**
     * Runs one command and returns its output. An end marker is echoed after the command and reading
     * stops there, because libadb's blocking read can miss the stream's close and wait forever.
     */
    private fun rawExec(m: AdbManager, cmd: String, timeoutMs: Long = 15_000): String {
        val s = m.openStream("shell:($cmd\n); echo $END")
        val text = readAll(s, timeoutMs, END)
        val idx = text.lastIndexOf(END)
        if (idx < 0) throw java.io.IOException(tr(R.string.rs_timeout))
        return text.substring(0, idx)
    }

    /** Polls the stream (never blocks in read) until [marker] appears, the stream closes, or time runs out. */
    private fun readAll(s: AdbStream, timeoutMs: Long = 4_000, marker: String? = null): String {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            val input = s.openInputStream()
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val avail = try { input.available() } catch (_: java.io.IOException) { 0 }
                if (avail > 0) {
                    val n = try { input.read(buf, 0, minOf(avail, buf.size)) } catch (_: java.io.IOException) { -1 }
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (marker != null && out.toString(Charsets.UTF_8.name()).contains(marker)) break
                } else {
                    if (s.isClosed) break
                    Thread.sleep(10)
                }
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
