package dev.herdr.mobile.push

import android.content.Context
import dev.herdr.mobile.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Persists the UnifiedPush endpoint and turns incoming push messages into notifications.
 * Forwarding the endpoint to the companion (client.registerPush) is wired in B6.
 */
class UnifiedPushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        CoroutineScope(Dispatchers.IO).launch { Settings(context).setPushEndpoint(endpoint.url) }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        parsePush(message.content)?.let { p ->
            if (p.kind == "clear") Notifications.cancel(context, p.paneId)
            else Notifications.post(context, p)
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {}

    override fun onUnregistered(context: Context, instance: String) {}
}
