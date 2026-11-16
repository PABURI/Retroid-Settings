package app.retroidsettings.lineage

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** Memory usage: a cheap live summary (no root) and a detailed breakdown from `dumpsys meminfo` (root). */
object Memory {
    /** installedKb = RAM the device is sold with (0 if unknown); the rest of it is reserved by firmware/hardware. */
    data class Live(val totalKb: Long, val availKb: Long, val swapTotalKb: Long, val swapUsedKb: Long, val installedKb: Long) {
        val usedKb get() = totalKb - availKb
        val reservedKb get() = if (installedKb > totalKb) installedKb - totalKb else 0L
    }

    data class Proc(val name: String, val label: String, val pssKb: Long)
    data class Detail(
        val usedPssKb: Long, val kernelKb: Long, val cachedKb: Long, val freeKb: Long,
        val zramPhysKb: Long, val top: List<Proc>
    )

    fun live(ctx: Context): Live {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        var swapTotal = 0L; var swapFree = 0L
        runCatching {
            File("/proc/meminfo").forEachLine { l ->
                val v = l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: return@forEachLine
                when {
                    l.startsWith("SwapTotal:") -> swapTotal = v
                    l.startsWith("SwapFree:") -> swapFree = v
                }
            }
        }
        // Installed RAM = smallest standard module size that holds what the kernel sees
        // (advertisedMem is not usable here: this ROM reports RAM + zRAM in it).
        val totalKb = mi.totalMem / 1024
        val installedKb = listOf(2, 3, 4, 6, 8, 12, 16, 18, 24, 32, 64)
            .map { it * 1024L * 1024L }.firstOrNull { it >= totalKb } ?: 0L
        return Live(totalKb, mi.availMem / 1024, swapTotal, swapTotal - swapFree, installedKb)
    }

    /** Takes a few seconds: runs dumpsys meminfo as root. */
    fun detail(ctx: Context): Detail? {
        val out = RootShell.exec(ctx, "dumpsys meminfo", timeoutMs = 45_000) ?: return null
        fun kb(s: String) = s.replace(",", "").removeSuffix("K").trim().toLongOrNull() ?: 0L
        fun grab(regex: String) = Regex(regex).find(out)?.groupValues
        val used = grab("Used RAM: *([0-9,]+)K \\(([0-9,]+)K used pss \\+ *([0-9,]+)K kernel")
        val free = grab("Free RAM: *([0-9,]+)K \\(([0-9,]+)K cached pss \\+ *([0-9,]+)K cached kernel \\+ *([0-9,]+)K free")
        val zram = grab("ZRAM: *([0-9,]+)K physical used")
        val pm = ctx.packageManager
        val top = out.substringAfter("Total PSS by process:", "").substringBefore("\n\n").lines()
            .mapNotNull { Regex("^ *([0-9,]+)K: (\\S+)").find(it)?.groupValues }
            .take(10)
            .map { g ->
                val name = g[2]
                val pkg = name.substringBefore(':')
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(name)
                Proc(name, if (name.contains(':')) "$label (${name.substringAfter(':')})" else label, kb(g[1]))
            }
        return Detail(
            usedPssKb = kb(used?.get(2) ?: "0"), kernelKb = kb(used?.get(3) ?: "0"),
            cachedKb = kb(free?.get(2) ?: "0") + kb(free?.get(3) ?: "0"), freeKb = kb(free?.get(4) ?: "0"),
            zramPhysKb = kb(zram?.get(1) ?: "0"), top = top
        )
    }

    fun gb(kb: Long) = "%.1f GB".format(kb / 1024.0 / 1024.0)
    fun mb(kb: Long) = if (kb >= 1024 * 1024) gb(kb) else "${kb / 1024} MB"
}
