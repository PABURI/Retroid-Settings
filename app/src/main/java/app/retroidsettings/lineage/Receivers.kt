package app.retroidsettings.lineage

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).startOnBoot) {
            try { ControlService.start(context, setup = true) } catch (_: Exception) {}
        }
    }
}

/**
 * Receives a pairing code either from the notification's inline reply, or from
 *   adb shell am broadcast -a app.retroidsettings.lineage.PAIR -n app.retroidsettings.lineage/.PairReceiver --es code 123456 --ei port 37000
 */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
            ?: intent.getStringExtra("code") ?: return
        val port = intent.getIntExtra("port", 0)
        val pending = goAsync()
        Thread {
            val result = RootShell.pair(context.applicationContext, port, code)
            Status.note = result
            showResult(context, result)
            if (result.startsWith(tr(R.string.rs_paired))) {
                try { ControlService.start(context.applicationContext, setup = true) } catch (_: Exception) {}
            }
            pending.finish()
        }.start()
    }

    companion object {
        const val KEY_CODE = "code"
        const val NOTIF_PAIR = 2

        /** Shows a notification with an inline reply box for the 6-digit code. */
        fun showPairPrompt(ctx: Context) {
            val reply = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, PairReceiver::class.java).setAction("app.retroidsettings.lineage.PAIR"),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val action = Notification.Action.Builder(null, "Enter code", pi).addRemoteInput(reply).build()
            val n = Notification.Builder(ctx, App.CH_PAIR)
                .setSmallIcon(R.drawable.ic_stat_fan)
                .setContentTitle("Retroid Settings pairing")
                .setContentText("Keep \"Pair device with pairing code\" open, then enter the code here")
                .addAction(action)
                .setAutoCancel(false)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_PAIR, n)
        }

        fun showResult(ctx: Context, text: String) {
            val n = Notification.Builder(ctx, App.CH_PAIR)
                .setSmallIcon(R.drawable.ic_stat_fan)
                .setContentTitle("Retroid Settings pairing")
                .setContentText(if (text.startsWith(tr(R.string.rs_paired))) "Paired" else "Pairing failed: open the app for details")
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_PAIR, n)
        }
    }
}
