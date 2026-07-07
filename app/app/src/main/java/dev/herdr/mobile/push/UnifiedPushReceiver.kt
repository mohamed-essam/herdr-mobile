package dev.herdr.mobile.push

import android.content.Context
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Minimal stub. Fleshed out in later tasks (B4/B5/B6).
 */
class UnifiedPushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        // TODO(B4/B5): register endpoint with companion
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        // TODO(B4/B5): handle registration failure
    }

    override fun onUnregistered(context: Context, instance: String) {
        // TODO(B4/B5): handle unregistration
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        // TODO(B4/B5): handle incoming push message
    }
}
