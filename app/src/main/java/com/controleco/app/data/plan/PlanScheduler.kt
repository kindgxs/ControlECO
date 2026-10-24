package com.controleco.app.data.plan

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.controleco.app.AccBookApp
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.util.AppLog
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 分期 / 周期的到点唤醒：全局只挂一个闹钟，指向最近一次出账时间。
 *
 * - 有精确闹钟权限时用 `setExactAndAllowWhileIdle`，否则回落到 `setAndAllowWhileIdle`；
 * - 开机 / 覆盖安装后重新挂一次；
 * - 即使闹钟被系统吞掉，App 启动、回到前台、打开分期页面时都会再补记一次（见 [PlanAutoPoster]）。
 */
object PlanScheduler {

    const val ACTION_DUE = "com.controleco.app.action.PLAN_DUE"
    private const val REQUEST_CODE = 41

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PlanDueReceiver::class.java).setAction(ACTION_DUE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    suspend fun schedule(context: Context, db: AccBookDatabase) {
        val next = PlanAutoPoster.nextDueAt(db) ?: run {
            cancel(context)
            return
        }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
            }
            AppLog.i("plan", "next plan alarm at $next")
        } catch (e: Exception) {
            AppLog.e("plan", "schedule alarm failed", e)
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent) }
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context))
    }
}

/** 闹钟 / 开机 / 覆盖安装入口：补记到点的账单，再挂下一次闹钟 */
class PlanDueReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val app = context.applicationContext as? AccBookApp
        if (app == null) {
            pending.finish()
            return
        }
        app.applicationScope.launch {
            try {
                PlanAutoPoster.postDue(app.db, zone = ZoneId.systemDefault())
                PlanScheduler.schedule(context, app.db)
            } catch (e: Exception) {
                AppLog.e("plan", "plan receiver failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
