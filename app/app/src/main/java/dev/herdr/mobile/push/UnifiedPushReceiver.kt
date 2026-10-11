package dev.herdr.mobile.push

import android.content.Context
import dev.herdr.mobile.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * One UnifiedPush instance per saved server (instance = server id): saves each
 * server's endpoint and turns its pushes into notifications tagged with it.
 * MainActivity forwards the active server's endpoint to its companion.
 */
class UnifiedPushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        CoroutineScope(Dispatchers.IO).launch { Settings(context).setPushEndpoint(instance, endpoint.url) }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val p = parsePush(message.content) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val settings = Settings(context)
            settings.migrate()   // an upgraded app may not have been opened yet
            val servers = settings.servers.first()
            val server = servers.firstOrNull { it.id == instance } ?: return@launch   // removed since
            if (p.kind == "clear") Notifications.cancel(context, server.id, p.paneId)
            else Notifications.post(context, server.id, server.name.takeIf { servers.size > 1 }, p)
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {}

    override fun onUnregistered(context: Context, instance: String) {
        CoroutineScope(Dispatchers.IO).launch { Settings(context).removePushEndpoint(instance) }
    }
}
