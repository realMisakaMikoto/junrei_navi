package cn.anitabi.navigator.navigation

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.R
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NavigationRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                restoreControlEntry(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun restoreControlEntry(context: Context) {
        val application = context as? AnitabiApplication ?: return
        val storedTourId = ActiveNavigationStore.get(context)
        val saved = try {
            loadNavigationRecoveryCandidate(storedTourId, application.container.tourRepository)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return
        }
        when (navigationBootRestoreAction(saved)) {
            NavigationBootRestoreAction.CLEAR_STALE_POINTER -> {
                if (storedTourId != null) {
                    ActiveNavigationStore.replaceIfCurrent(context, storedTourId, null)
                }
                return
            }
            NavigationBootRestoreAction.IGNORE_NON_EXTERNAL -> return
            NavigationBootRestoreAction.SHOW_EXTERNAL_JAPAN_CONTROL -> Unit
        }
        val externalTour = checkNotNull(saved)
        if (!ActiveNavigationStore.replaceIfCurrent(context, storedTourId, externalTour.plan.id)) return
        if (!NavigationControlAvailability.notificationsVisible(context)) return
        val openIntent = userVisibleActivityPendingIntent(
            context,
            RESTORE_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_RESTORE_TOUR_ID, externalTour.plan.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val notification = NotificationCompat.Builder(context, NavigationService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation_notification)
            .setContentTitle("巡礼手帳 · 行程可恢复")
            .setContentText(externalRestoreNotificationText(externalTour.plan.executionStrategy))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(navigationNotificationCategory())
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(RESTORE_NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_RESTORE_TOUR_ID = "restore_tour_id"
        private const val RESTORE_REQUEST_CODE = 2002
        private const val RESTORE_NOTIFICATION_ID = 2002
    }
}

internal fun externalRestoreNotificationText(strategy: TransitExecutionStrategy): String = when (strategy) {
    TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
        "点按返回应用恢复控制；不会自动打开 Google 地图"
    TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND ->
        "点按返回应用恢复控制；不会自动打开高德地图"
    TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES ->
        "点按返回应用恢复导航控制"
}
