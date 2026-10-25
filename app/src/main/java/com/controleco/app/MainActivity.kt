package com.controleco.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.capture.CapturePermissions
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.AccBookNav
import com.controleco.app.ui.ColdStartSplash
import com.controleco.app.ui.common.AnimationPrefs
import com.controleco.app.ui.eco.LocalAnimationsEnabled
import com.controleco.app.ui.theme.AccBookTheme
import com.controleco.app.ui.theme.AccentThemeStore

class MainActivity : ComponentActivity() {

    /** 通知点击跳转：route + 自增序号（同路由重复点击也能触发导航） */
    private var navEvent by mutableStateOf(0L to null as String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 纸面要铺到状态栏/手势条后面；系统栏图标颜色由 auto 按系统深浅色决定
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        consumeRoute(intent)
        // 权限自检只在"冷启动"提醒一次（重建/旋转不重弹），且从捕获通知进入时不打扰
        val coldStart = savedInstanceState == null
        val promptPermissions = coldStart && CapturePermissions.claimLaunchPrompt()
        setContent {
            // eco v5 是 390dp 固定栅格设计：字体跟随系统，但上限锁在 1.25，
            // 避免超大字体把 hero / 底排卡片撑开；dp 尺寸与系统显示大小仍照常生效。
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = base.density,
                    fontScale = base.fontScale.coerceAtMost(1.25f),
                ),
            ) {
                AccBookTheme(
                    accent = AccentThemeStore.current,
                    customBase = AccentThemeStore.customBase,
                ) {
                    val vm: LedgerViewModel = viewModel()
                    // 「显示动画」关掉后连开屏也不播，直接进内容
                    val animations = AnimationPrefs.enabled
                    // 冷启动才播开屏：旋转/重建不重播
                    var showSplash by rememberSaveable { mutableStateOf(coldStart && animations) }
                    // 先让开屏画出第一帧，再把真实内容挂上去在开屏后面完成组合/布局/首绘，
                    // 这样用户第一眼看到的是开屏而不是空屏，同时把首帧开销都预热掉。
                    var contentMounted by remember { mutableStateOf(!showSplash) }
                    LaunchedEffect(Unit) {
                        if (!contentMounted) {
                            withFrameNanos { }
                            contentMounted = true
                        }
                    }
                    androidx.compose.runtime.CompositionLocalProvider(
                        // 开屏期间也压住内容动画：内容已在后面布局/首绘完（这正是预热），
                        // 但金额/环/柱状图先定格在终值；开屏淡出的一瞬间再统一重播，用户才看得到入场。
                        LocalAnimationsEnabled provides (animations && !showSplash),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (contentMounted) {
                                AccBookNav(vm, navEvent, promptPermissions)
                            }
                            if (showSplash) {
                                ColdStartSplash(onFinished = { showSplash = false })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeRoute(intent)
    }

    // 前台状态上报：悬浮确认卡在本应用前台时立即收起（应用内用待确认列表，卡片悬在本应用上会挡操作）
    override fun onResume() {
        super.onResume()
        CaptureCenter.setAppForeground(true)
    }

    override fun onPause() {
        CaptureCenter.setAppForeground(false)
        super.onPause()
    }

    private fun consumeRoute(intent: Intent?) {
        val route = intent?.getStringExtra(CaptureCenter.EXTRA_ROUTE) ?: return
        CaptureCenter.onRouteOpened(route)
        navEvent = (navEvent.first + 1) to route
    }
}

