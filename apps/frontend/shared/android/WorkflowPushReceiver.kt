package kr.allcheck.flutter.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Display the backend's data-only reception and dispatch messages when Flutter is in the background. */
class WorkflowPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("eventType") ?: return
        val label = when (type) {
            "DISPATCH_OFFER" -> "새 배차 요청이 도착했습니다."
            "FOLLOW_UP_REQUIRED" -> "후속 조치가 필요한 의뢰가 있습니다."
            "RECEPTION_ANALYSIS_SUCCEEDED", "RECEPTION_ANALYSIS_FAILED" -> "접수 분석 상태를 확인해 주세요."
            else -> return
        }
        if (appVisible) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.action = ACTION
        for (key in keys) intent.getStringExtra(key)?.let { launch.putExtra(key, it) }
        val id = listOf(type, intent.getStringExtra("eventId"), intent.getStringExtra("offerId"), intent.getStringExtra("requestId")).hashCode()
        val tap = PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("workflow", "업무 알림", NotificationManager.IMPORTANCE_HIGH))
            Notification.Builder(context, "workflow")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        manager.notify(id, builder.setSmallIcon(context.applicationInfo.icon).setContentTitle("올체크")
            .setContentText(label).setContentIntent(tap).setAutoCancel(true).build())
    }

    companion object {
        var appVisible = false
        private const val ACTION = "kr.allcheck.flutter.OPEN_WORKFLOW"
        private val keys = listOf("eventType", "offerId", "requestId", "eventId", "receptionId")
        fun takeEvent(intent: Intent): Map<String, String>? {
            if (intent.action != ACTION) return null
            intent.action = null
            return keys.mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()
        }
    }
}
