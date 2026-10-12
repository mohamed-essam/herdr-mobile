package dev.herdr.mobile.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.herdr.mobile.MainActivity
import dev.herdr.mobile.R

const val EXTRA_SERVER_ID = "serverId"
const val EXTRA_PANE_ID = "paneId"

/** [title], prefixed with the server's name when there's one to tell apart. */
fun notificationTitle(title: String, serverName: String?): String =
    if (serverName == null) title else "$serverName · $title"

/** Distinct per server and pane, so two servers' taps don't overwrite each other's intent. */
fun openRequestCode(serverId: String, paneId: String): Int = "$serverId/$paneId".hashCode()

object Notifications {
    private const val CH_BLOCKED = "blocked"
    private const val CH_FINISHED = "finished"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BLOCKED, "Agent needs you", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FINISHED, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    // Tagged with the server, so equal pane ids on two servers stay apart.
    fun cancel(ctx: Context, serverId: String, paneId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(serverId, paneId.hashCode())
    }

    /** Withdraws everything a (removed) server posted. */
    fun cancelServer(ctx: Context, serverId: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.tag == serverId }.forEach { nm.cancel(it.tag, it.id) }
    }

    /** [serverName] is prefixed to the title when non-null (more than one server saved). */
    fun post(ctx: Context, serverId: String, serverName: String?, p: PushPayload) {
        if (p.kind == "clear") return
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SERVER_ID, serverId)
            putExtra(EXTRA_PANE_ID, p.paneId)
        }
        val pi = PendingIntent.getActivity(ctx, openRequestCode(serverId, p.paneId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (p.kind == "blocked") CH_BLOCKED else CH_FINISHED
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notificationTitle(p.title, serverName))
            .setContentText(p.body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (p.kind == "blocked") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(serverId, p.paneId.hashCode(), n)
    }
}
