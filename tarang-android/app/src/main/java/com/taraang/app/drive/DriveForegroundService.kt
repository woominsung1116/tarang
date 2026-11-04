package com.taraang.app.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.taraang.app.MainActivity
import com.taraang.app.R
import com.taraang.app.core.Logger

/**
 * 주행 동안만 도는 포그라운드 서비스(specialUse: 주행 중 안전운전 음성 피드백과 주행 기록).
 * 위치 권한이 없고(위치는 NaviHelper가 준다) location 타입은 Android 14에서 거부되므로 specialUse를 쓴다.
 * 하는 일은 알림을 띄워 프로세스를 붙잡는 것뿐이다. START_NOT_STICKY라 프로세스가 죽은 뒤 저절로 되살아나지 않는다
 * (그때 열린 세션은 다음 시작 때 "중단됨"으로 닫힌다). 알림 권한이 없어도 서비스는 돈다(알림만 안 보인다).
 */
class DriveForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 1001

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.drive_service_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }

        private fun notification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(R.string.drive_service_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}

/** [DriveKeepAlive]의 안드로이드 구현. 시작·중지 실패(배경 시작 금지 등)는 기록만 하고 주행을 막지 않는다. */
class ForegroundServiceKeepAlive(context: Context, private val logger: Logger) : DriveKeepAlive {
    private val appContext = context.applicationContext
    private val intent get() = Intent(appContext, DriveForegroundService::class.java)

    override fun start() {
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            logger.w(TAG, "주행 포그라운드 서비스 시작 실패, 서비스 없이 주행", e)
        }
    }

    override fun stop() {
        try {
            appContext.stopService(intent)
        } catch (e: Exception) {
            logger.w(TAG, "주행 포그라운드 서비스 중지 실패", e)
        }
    }

    private companion object {
        const val TAG = "DriveService"
    }
}
