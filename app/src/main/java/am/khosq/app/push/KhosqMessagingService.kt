package am.khosq.app.push

import am.khosq.app.AppState
import am.khosq.app.KeepAliveService
import am.khosq.app.MainActivity
import am.khosq.app.R
import am.khosq.app.call.CallManager
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
        KeepAliveService.createChannels(this)
        KeepAliveService.start(this)
        if (type == "call") {
            CallManager.init(applicationContext)
            FirebaseAuth.getInstance().currentUser?.uid?.let { CallManager.listen(it) }
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

    companion object {
        fun saveToken(token: String) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            FirebaseFirestore.getInstance().collection("tokens").document(uid)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
        }
    }
}
