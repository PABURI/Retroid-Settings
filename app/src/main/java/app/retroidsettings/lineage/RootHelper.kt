package app.retroidsettings.lineage

import android.os.IBinder

/**
 * Small entry point run as root through app_process:
 *   CLASSPATH=<apk> app_process /system/bin app.retroidsettings.lineage.RootHelper aspect <pkg> <value>
 *   CLASSPATH=<apk> app_process /system/bin app.retroidsettings.lineage.RootHelper get <pkg>
 *   CLASSPATH=<apk> app_process /system/bin app.retroidsettings.lineage.RootHelper dimen <overlayName> <targetPkg> <resource> <dp>
 * aspect/get call IPackageManager.setUserMinAspectRatio, the same call Settings > Apps > Aspect ratio makes.
 * dimen registers and enables a fabricated overlay holding one dimension value
 * ("cmd overlay fabricate" cannot take dimensions on this build).
 */
object RootHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.getOrNull(0) == "dimen") { dimen(args); return }
        try {
            // PackageManager only lets the system uid (1000) change another app's aspect ratio, not root.
            // A root process may drop to 1000 before making the binder call.
            if (android.os.Process.myUid() == 0) {
                val proc = android.os.Process::class.java
                runCatching { proc.getMethod("setGid", Int::class.javaPrimitiveType).invoke(null, 1000) }
                runCatching { proc.getMethod("setUid", Int::class.javaPrimitiveType).invoke(null, 1000) }
            }
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "package") as IBinder
            val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val pm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            when (args.getOrNull(0)) {
                "aspect" -> {
                    val value = args[1].toInt()
                    val set = pm.javaClass.getMethod(
                        "setUserMinAspectRatio", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                    )
                    for (pkg in args.drop(2)) {
                        try { set.invoke(pm, pkg, 0, value); println("OK $pkg=$value") }
                        catch (t: Throwable) { println("ERR $pkg: ${((t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t).message}") }
                    }
                }
                "get" -> {
                    val get = pm.javaClass.getMethod("getUserMinAspectRatio", String::class.java, Int::class.javaPrimitiveType)
                    for (pkg in args.drop(1)) {
                        try { println("OK $pkg=${get.invoke(pm, pkg, 0)}") } catch (t: Throwable) { println("ERR $pkg") }
                    }
                }
                else -> println("ERR usage: aspect <value> <pkg>... | get <pkg>...")
            }
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            println("ERR ${cause.javaClass.name}: ${cause.message}")
        }
    }

    /** dimen <overlayName> <targetPkg> <resource> <dp> — runs as root, the overlay is owned by com.android.shell. */
    private fun dimen(args: Array<String>) {
        try {
            val (name, target, res) = Triple(args[1], args[2], args[3])
            val dp = args[4].toFloat()
            val foCls = Class.forName("android.content.om.FabricatedOverlay")
            val fo = foCls.getConstructor(String::class.java, String::class.java).newInstance(name, target)
            foCls.getMethod(
                "setResourceValue", String::class.java, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            ).invoke(fo, res, dp, 1 /* TypedValue.COMPLEX_UNIT_DIP */, null)
            // Normally OverlayManager fills in the owner from the app context; app_process has none.
            val owner = "com.android.shell"
            val setOwner = foCls.methods.firstOrNull { it.name == "setOwningPackage" && it.parameterTypes.size == 1 }
            if (setOwner != null) setOwner.invoke(fo, owner) else {
                val f = foCls.getDeclaredField("mOverlay").apply { isAccessible = true }
                val internal = f.get(fo)!!
                internal.javaClass.getField("packageName").set(internal, owner)
            }
            val id = foCls.getMethod("getIdentifier").invoke(fo)!!
            val bCls = Class.forName("android.content.om.OverlayManagerTransaction\$Builder")
            val b = bCls.getConstructor().newInstance()
            bCls.getMethod("registerFabricatedOverlay", foCls).invoke(b, fo)
            bCls.methods.first { it.name == "setEnabled" && it.parameterTypes.size == 2 }.invoke(b, id, true)
            val tx = bCls.getMethod("build").invoke(b)!!
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "overlay") as IBinder
            val om = Class.forName("android.content.om.IOverlayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            om.javaClass.methods.first { it.name == "commit" }.invoke(om, tx)
            println("OK dimen $name $res=${dp}dp")
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            println("ERR ${cause.javaClass.name}: ${cause.message}")
        }
    }
}
