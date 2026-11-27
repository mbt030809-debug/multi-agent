package com.mbta.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mbta.app.MainActivity

object NotificationHelper {
    private const val CHANNEL_DONE = "mbta_done"
    private const val CHANNEL_ACTION = "mbta_action"

    const val EXTRA_SESSION_ID = "mbta_notif_session_id"
    const val EXTRA_QUICK_REPLY = "mbta_notif_quick_reply"
    const val EXTRA_NOTIF_ID = "mbta_notif_id"

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_DONE) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_DONE,
                        "MbtA 응답 완료",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            if (mgr.getNotificationChannel(CHANNEL_ACTION) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ACTION,
                        "MbtA 권한·질문·오류 알림",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }

    fun notifyDone(context: Context, title: String, body: String) {
        notifyEvent(context, sessionId = "", title = title, body = body, isUrgent = false, actions = emptyList())
    }

    fun notifyEvent(
        context: Context,
        sessionId: String,
        title: String,
        body: String,
        isUrgent: Boolean = false,
        actions: List<String> = emptyList()
    ) {
        ensureChannels(context)
        val notifId = ((System.currentTimeMillis() and 0x7FFFFFFF) % 100000).toInt().coerceAtLeast(100)
        val channelId = if (isUrgent) CHANNEL_ACTION else CHANNEL_DONE

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId.isNotBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        val openPi = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (isUrgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPi)

        if (sessionId.isNotBlank()) {
            actions.take(2).forEachIndexed { idx, actionLabel ->
                val actionIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_QUICK_REPLY, actionLabel)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                }
                val actionPi = PendingIntent.getActivity(
                    context,
                    notifId * 10 + idx + 1,
                    actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, actionLabel.take(18), actionPi)
            }
        }

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
            // Permission not granted
        }
    }

    fun cancel(context: Context, notifId: Int) {
        if (notifId > 0) {
            runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
        }
    }
}
