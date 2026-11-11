package com.arc.nupcar

import com.arc.nupcar.session.GeneratedRestAudio
import com.arc.nupcar.session.PleosVehicleGateway
import com.arc.nupcar.session.RestProfile
import com.arc.nupcar.session.RestSessionController
import com.arc.nupcar.session.SimulatedVehicleGateway
import com.arc.nupcar.session.VehicleGateway
import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NupcarApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controller: RestSessionController by lazy {
        RestSessionController(::createVehicle, GeneratedRestAudio(), scope, notifyWake = ::notifyQuietWake)
    }

    private fun createVehicle(): VehicleGateway =
        if (BuildConfig.SIMULATED_VEHICLE) SimulatedVehicleGateway() else PleosVehicleGateway(this)

    /**
     * Posts a heads-up notice when a video session ends, since the user is usually in a video
     * app. Without the notification permission it is skipped; the light and seat still say so.
     */
    private fun notifyQuietWake(profile: RestProfile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.video_end_title, profile.mode.title))
            .setContentText(getString(R.string.video_end_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private companion object {
        const val ALERT_CHANNEL_ID = "rest_alert"
        const val ALERT_NOTIFICATION_ID = 2
    }
}
