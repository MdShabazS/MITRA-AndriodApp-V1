package com.unique.visionmate

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads incoming message notifications aloud (WhatsApp, SMS, Telegram, etc.) so a visually impaired
 * user can hear their messages hands-free. Requires the user to grant "Notification access".
 *
 * Speaking is routed through [BackgroundService] (single TTS, coordinated with the mic). The most
 * recent message's reply action is captured for the voice "reply" command.
 */
class MitraNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MITRA_NOTIF"

        private val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging", // Google Messages (SMS)
            "com.android.mms",
            "com.coloros.mms",                   // ColorOS Messages
            "org.telegram.messenger",
            "com.instagram.android",
            "com.facebook.orca"                  // Messenger
        )

        // Most recent message — used by the "reply" voice command (BackgroundService reads these).
        @Volatile var lastReplyAction: Notification.Action? = null
        @Volatile var lastSender: String? = null
        @Volatile var lastAppLabel: String? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val pkg = sbn?.packageName ?: return
            if (pkg == packageName) return                 // ignore our own notifications
            if (pkg !in MESSAGING_PACKAGES) return

            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return   // skip ongoing (calls, etc.)
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return   // skip group summaries

            val extras = n.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            if (text.isNullOrBlank()) return

            val lower = text.lowercase()
            // Skip noise like "typing…", "new messages", missed-call summaries.
            if (lower.contains("typing") || lower.contains("new messages") ||
                lower.contains("missed call")) return

            val appLabel = when {
                pkg.startsWith("com.whatsapp") -> "WhatsApp"
                pkg.contains("messaging") || pkg.contains("mms") -> "S M S"
                pkg.contains("telegram") -> "Telegram"
                pkg.contains("instagram") -> "Instagram"
                pkg.contains("orca") -> "Messenger"
                else -> "Message"
            }
            val sender = title ?: "someone"

            captureReplyAction(n, sender, appLabel)

            Log.i(TAG, "reading $appLabel from $sender: $text")
            speak("$appLabel message from $sender. $text")
        } catch (e: Exception) {
            Log.w(TAG, "onNotificationPosted failed: ${e.message}")
        }
    }

    private fun captureReplyAction(n: Notification, sender: String, appLabel: String) {
        val actions = n.actions ?: return
        for (a in actions) {
            val inputs = a.remoteInputs
            if (inputs != null && inputs.isNotEmpty()) {
                lastReplyAction = a
                lastSender = sender
                lastAppLabel = appLabel
                return
            }
        }
    }

    private fun speak(text: String) {
        try {
            startService(Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_SPEAK_TEXT
                putExtra(BackgroundService.EXTRA_SPEAK_TEXT, text)
            })
        } catch (e: Exception) {
            Log.w(TAG, "speak routing failed: ${e.message}")
        }
    }
}
