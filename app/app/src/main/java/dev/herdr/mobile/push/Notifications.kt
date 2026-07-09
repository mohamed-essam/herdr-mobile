package dev.herdr.mobile.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.herdr.mobile.MainActivity
import dev.herdr.mobile.R

object Notifications {
    private const val CH_BLOCKED = "blocked"
    private const val CH_FINISHED = "finished"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BLOCKED, "Agent needs you", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FINISHED, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun cancel(ctx: Context, paneId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(paneId.hashCode())
    }

    fun post(ctx: Context, p: PushPayload) {
        if (p.kind == "clear") return
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("paneId", p.paneId)
        }
        val pi = PendingIntent.getActivity(ctx, p.paneId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (p.kind == "blocked") CH_BLOCKED else CH_FINISHED
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(p.title)
            .setContentText(p.body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (p.kind == "blocked") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(p.paneId.hashCode(), n)
    }
}
