package app.retroidsettings.lineage

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // libadb uses the platform Conscrypt for TLS pairing, which sits behind hidden APIs.
        HiddenApiBypass.addHiddenApiExemptions("L")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PAIR, "Pairing", NotificationManager.IMPORTANCE_HIGH)
        )
        // Shown only for the few seconds of setup after boot when the status notification is off.
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "Boot setup", NotificationManager.IMPORTANCE_MIN)
        )
    }

    companion object {
        lateinit var instance: App
            private set
        const val CH_QUIET = "quiet"
        const val CH_SERVICE = "service"
        const val CH_PAIR = "pair"
    }
}

/**
 * UI text from string resources (res/values = English, res/values-ko = Korean). Usable from any thread
 * and outside Compose; the activity is recreated when the language changes, so screens pick it up.
 */
fun tr(@StringRes id: Int, vararg args: Any): String =
    if (args.isEmpty()) App.instance.getString(id) else App.instance.getString(id, *args)

/**
 * In-app language (Android's per-app language, also in Settings > Apps > Retroid Settings > Language).
 * "" follows the system: Korean devices get Korean, everything else English.
 * To add a language: add res/values-<tag>/strings.xml, the tag in res/xml/locales_config.xml and in [OPTIONS].
 */
object Lang {
    /** tag to the language's own name (not translated, so it can always be found). */
    val OPTIONS = listOf("ko" to "한국어", "en" to "English")

    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else ""

    fun set(ctx: Context, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        ctx.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
