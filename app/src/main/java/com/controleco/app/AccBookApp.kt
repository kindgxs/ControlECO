package com.controleco.app

import android.app.Application
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.DefaultData
import com.controleco.app.util.AppLog
import com.controleco.app.ui.theme.AccentThemeStore
import com.controleco.app.ui.common.AnimationPrefs
import com.controleco.app.ui.common.AppIconManager
import com.controleco.app.ui.transactions.CoverStyleStore
import com.controleco.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AccBookApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AccBookDatabase by lazy { AccBookDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        // 日志开关随构建类型：debug 全量输出（通知捕获排查用），release 静默
        AppLog.debugEnabled = BuildConfig.DEBUG
        // 货币符号在任何进程启动时就绪（服务/通知进程里格式化金额也要用对符号）
        Money.symbol = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("currency_symbol", Money.DEFAULT_SYMBOL) ?: Money.DEFAULT_SYMBOL
        // 强调色与结余封面底色（纯展示偏好）在任何 Compose 内容之前恢复
        AccentThemeStore.init(this)
        CoverStyleStore.init(this)
        AnimationPrefs.init(this)
        AppIconManager.init(this)
        CaptureCenter.init(this)
        // 覆盖安装后提示复查通知使用权（广播可能被系统吞掉，冷启动再兜一次）
        CaptureCenter.checkAppUpdated(BuildConfig.VERSION_CODE)
        applicationScope.launch { DefaultData.seedIfNeeded(db) }
    }
}
