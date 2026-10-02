package com.godavin.vince

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * v2.4 - lets VINCE read the notifications on your phone (to read your latest
 * messages out) and use a notification's own Reply box to answer (more reliable
 * than tapping through WhatsApp). Notification text stays on the phone.
 */
class VinceNotificationListener : NotificationListenerService() {

    class Item(val key: String, val pkg: String, val title: String, val text: String, val time: Long, val replyable: Boolean)

    companion object {
        @Volatile var instance: VinceNotificationListener? = null
        val isRunning: Boolean get() = instance != null

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, VinceNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }

        private fun appLabel(pkg: String) = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging" -> "Messages"
            "org.telegram.messenger" -> "Telegram"
            "com.google.android.gm" -> "Gmail"
            else -> pkg.substringAfterLast('.')
        }

        /** Latest first. [onlyMessaging] limits to chat/SMS/email apps. */
        fun recent(max: Int = 8, onlyMessaging: Boolean = true): List<Item> {
            val svc = instance ?: return emptyList()
            val list = try { svc.activeNotifications?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
            val messaging = setOf(
                "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.android.mms",
                "com.samsung.android.messaging", "org.telegram.messenger", "com.google.android.gm"
            )
            return list.asSequence()
                .filter { it.packageName != svc.packageName }
                .filter { !onlyMessaging || it.packageName in messaging }
                .filter { !it.isOngoing }
                .map { sbn ->
                    val ex = sbn.notification.extras
                    val title = ex.getCharSequence("android.title")?.toString().orEmpty()
                    val text = (ex.getCharSequence("android.bigText") ?: ex.getCharSequence("android.text"))?.toString().orEmpty()
                    Item(sbn.key, sbn.packageName, title, text, sbn.postTime, hasReply(sbn))
                }
                .filter { it.title.isNotBlank() || it.text.isNotBlank() }
                .sortedByDescending { it.time }
                .take(max)
                .toList()
        }

        fun describe(item: Item) = "${appLabel(item.pkg)} - ${item.title}: ${item.text}".trim().take(220)

        private fun hasReply(sbn: StatusBarNotification): Boolean =
            sbn.notification.actions?.any { a -> a.remoteInputs?.isNotEmpty() == true } == true

        /** Sends [text] through the notification's own Reply action. */
        fun reply(context: Context, key: String, text: String): Boolean {
            val svc = instance ?: return false
            val sbn = try { svc.activeNotifications?.firstOrNull { it.key == key } } catch (e: Exception) { null } ?: return false
            val action = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true } ?: return false
            val ri = action.remoteInputs[0]
            val intent = Intent()
            val bundle = Bundle().apply { putCharSequence(ri.resultKey, text) }
            RemoteInput.addResultsToIntent(arrayOf(ri), intent, bundle)
            return try {
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
}
