package am.khosq.app.push

import am.khosq.app.AppState
import am.khosq.app.KeepAliveService
import am.khosq.app.MainActivity
import am.khosq.app.R
import am.khosq.app.call.CallManager
import am.khosq.app.call.GroupCallManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class KhosqMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        saveToken(token)
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val type = m.data["type"] ?: return
        // when the last push came: shown in "Check the notifications", to tell a delivery problem from a display problem
        am.khosq.app.Prefs.putLong("fcm_last", System.currentTimeMillis())
        KeepAliveService.createChannels(this)
        KeepAliveService.start(this)
        if (type == "call") {
            CallManager.init(applicationContext)
            GroupCallManager.init(applicationContext) // a call in a group rings from the chat list that KeepAliveService listens to
            FirebaseAuth.getInstance().currentUser?.uid?.let { CallManager.listen(it) }
            return
        }
        if (type == "finder") {
            showFinder(m)
            return
        }
        if (type == "react") {
            showReaction(m)
            return
        }
        if (AppState.inForeground) return
        val chatId = m.data["chatId"] ?: return
        if (am.khosq.app.Prefs.isMuted(chatId)) return
        val title = m.data["title"] ?: "KHOSQ"
        var body = m.data["body"] ?: ""
        if (body.isEmpty()) {
            // an encrypted message comes without its text; the app writes the real one if it can open the message itself
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val shown = try {
                nm.activeNotifications.any { n -> n.id == chatId.hashCode() }
            } catch (e: Exception) {
                false
            }
            if (shown) return
            body = am.khosq.app.tr("Նոր նամակ")
        }
        // "hide the text in notifications" (an SOS stays readable: it holds only the word, never a place)
        body = am.khosq.app.data.NotifyPrivacy.text(am.khosq.app.AppLock.hideTextNow(), body, am.khosq.app.tr("Նոր նամակ"))
        val tap = PendingIntent.getActivity(
            this,
            chatId.hashCode(),
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("chat", chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, KeepAliveService.CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(chatId.hashCode(), n)
        } catch (e: SecurityException) {
            // notifications not allowed
        }
    }

    /**
     * Somebody put a reaction on one of my messages. The push holds only the emoji, the name and the group's title (never any text); the
     * notification says "👍 Reaction to your message" (in a group, with the name in front). Like a message it stays silent while the app is
     * on screen or the chat is muted, and "hide the text in notifications" turns it into "New reaction". Whatever goes wrong stays in here.
     */
    private fun showReaction(m: RemoteMessage) {
        try {
            if (AppState.inForeground) return
            val chatId = m.data["chatId"] ?: return
            if (am.khosq.app.Prefs.isMuted(chatId)) return
            val emoji = am.khosq.app.data.ReactNote.emojiOf(m.data["body"] ?: "")
            if (emoji.isEmpty()) return
            val title = m.data["title"] ?: "KHOSQ"
            val who = if (m.data["group"] == "1") (m.data["who"] ?: "") else ""
            val line = am.khosq.app.data.ReactNote.line(emoji, who, am.khosq.app.tr("Արձագանք քո նամակին"))
            val text = am.khosq.app.data.NotifyPrivacy.text(am.khosq.app.AppLock.hideTextNow(), line, am.khosq.app.tr("Նոր արձագանք"))
            val tap = PendingIntent.getActivity(
                this,
                am.khosq.app.data.ReactNote.notifId(chatId),
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("chat", chatId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(this, KeepAliveService.CH_MSG)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(this).notify(am.khosq.app.data.ReactNote.notifId(chatId), n)
        } catch (e: SecurityException) {
            // notifications not allowed
        } catch (e: Exception) {
            // a reaction must never break the delivery of messages
        }
    }

    /**
     * A new listing found by the Real Estate Finder (it has no chat). The app's own copy is refreshed; when the app is not on screen a
     * notification is shown, and a tap on it opens the bot screen. Whatever goes wrong in here stays in here.
     */
    private fun showFinder(m: RemoteMessage) {
        try {
            am.khosq.app.data.FinderHub.load()
            am.khosq.app.data.FinderHub.refresh()
            if (AppState.inForeground) return
            val title = m.data["title"] ?: "KHOSQ Finder"
            val fallback = am.khosq.app.tr("Նոր հայտարարություն")
            val body = am.khosq.app.data.NotifyPrivacy.text(am.khosq.app.AppLock.hideTextNow(), m.data["body"] ?: "", fallback)
            val id = "khosq-finder".hashCode()
            val tap = PendingIntent.getActivity(
                this,
                id,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("finder", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(this, KeepAliveService.CH_MSG)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body.ifEmpty { fallback })
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifEmpty { fallback }))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(this).notify(id, n)
        } catch (e: SecurityException) {
            // notifications not allowed
        } catch (e: Exception) {
            // a listing notification must never break the delivery of messages
        }
    }

    companion object {
        fun saveToken(token: String) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            FirebaseFirestore.getInstance().collection("tokens").document(uid)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
        }
    }
}
