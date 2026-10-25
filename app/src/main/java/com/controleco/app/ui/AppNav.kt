package com.controleco.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.capture.CapturePermission
import com.controleco.app.capture.CapturePermissions
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.accounts.AccountsScreen
import com.controleco.app.ui.accounts.CategoriesScreen
import com.controleco.app.ui.backup.BackupScreen
import com.controleco.app.ui.budget.MonthBudgetScreen
import com.controleco.app.ui.calendar.CalendarScreen
import com.controleco.app.ui.plan.PlanScreen
import com.controleco.app.ui.common.PermissionReminderDialog
import com.controleco.app.ui.debug.CaptureLogScreen
import com.controleco.app.ui.debug.CaptureTraceScreen
import com.controleco.app.ui.deposit.DepositScreen
import com.controleco.app.ui.home.HomeScreen
import com.controleco.app.ui.imports.ImportScreen
import com.controleco.app.ui.keepalive.KeepAliveScreen
import com.controleco.app.ui.pending.PendingScreen
import com.controleco.app.ui.profile.ProfileScreen
import com.controleco.app.ui.profile.RewardScreen
import com.controleco.app.ui.refund.RefundLinkScreen
import com.controleco.app.ui.settings.SettingsAboutScreen
import com.controleco.app.ui.settings.SettingsAccountScreen
import com.controleco.app.ui.settings.SettingsDeveloperScreen
import com.controleco.app.ui.settings.SettingsImportScreen
import com.controleco.app.ui.settings.SettingsPermissionScreen
import com.controleco.app.ui.settings.SettingsMerchantRulesScreen
import com.controleco.app.ui.settings.SettingsScreen
import com.controleco.app.ui.settings.SettingsWatchedAppsScreen
import com.controleco.app.ui.eco.EcoBackdrop
import com.controleco.app.ui.eco.EcoBottomNav
import com.controleco.app.ui.eco.EcoFab
import com.controleco.app.ui.eco.EcoToastHost
import com.controleco.app.ui.eco.LocalAnimationsEnabled
import com.controleco.app.ui.eco.ecoFabBottomOffset
import com.controleco.app.ui.stats.StatsScreen
import com.controleco.app.ui.transactions.EditTransactionScreen
import com.controleco.app.ui.transactions.QuickAddScreen
import com.controleco.app.ui.transactions.TransactionListScreen

@Composable
fun AccBookNav(
    vm: LedgerViewModel,
    navEvent: Pair<Long, String?> = 0L to null,
    /** 本次冷启动是否做权限自检（MainActivity 传入；每次启动只提醒一次） */
    promptPermissions: Boolean = false,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // 打开 App 时权限自检：只列"功能开着但没授权"的项；从捕获通知直接进入时不打断处理动线
    var permissionMissing by remember {
        mutableStateOf(
            if (promptPermissions && navEvent.second == null) CapturePermissions.missing(context) else emptyList(),
        )
    }
    // 去系统设置授权后返回：重查一次，补齐即自动收起
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionMissing.isNotEmpty()) {
                permissionMissing = CapturePermissions.missing(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统设置页直达（权限页 / 保活页共用）
    val openNotificationAccess: () -> Unit = {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        Unit
    }
    val openAccessibilitySettings: () -> Unit = {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Unit
    }
    val openOverlayPermission: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        Unit
    }

    // 系统通知点击跳转（MainActivity 传入 route 事件；既有路由名是捕获层的对外契约，勿改）
    LaunchedEffect(navEvent.first) {
        val route = navEvent.second
        when {
            route == null -> Unit
            route == CaptureCenter.ROUTE_PENDING -> navController.navigate("pending")
            route == CaptureCenter.ROUTE_REFUND_LINK -> navController.navigate("refundLink")
            // 「已记入一笔账」通知：直达该笔记录的编辑页（追加路由：edit/{txId}）
            route.startsWith(CaptureCenter.ROUTE_EDIT_PREFIX) -> navController.navigate(route)
            // 快捷方式/磁贴：直接进快速记账
            route == "quickAdd" -> navController.navigate("quickAdd")
            // 设置子页直达（调试/客服引导用：开发者功能、权限页）
            route == "settings/developer" || route == "settings/permissions" ->
                navController.navigate(route)
            // 悬浮确认卡样例（等价开发者功能里的「悬浮确认卡（立即）」；调试路由，不落库）
            route == CaptureCenter.ROUTE_OVERLAY_TEST -> CaptureCenter.showOverlayTest(0L) { }
            // 分享导入：文件已在缓存里，跳到导入页并预解析
            route.startsWith(CaptureCenter.ROUTE_IMPORT_FILE_PREFIX) -> {
                val fileUri = route.removePrefix(CaptureCenter.ROUTE_IMPORT_FILE_PREFIX)
                vm.parseBillFile(fileUri)
                navController.navigate("import")
            }
        }
    }
    // 转场：旧页先淡出、退干净后新页再淡入。两页都有透明区域，同时处在中途 alpha 会互相透出
    // （“上一页还在、下一页已经出现”），所以改成串行：exit 结束后 enter 才启动。
    Box(Modifier.fillMaxSize()) {
        EcoBackdrop()
        NavHost(
        navController = navController,
        startDestination = "main",
        modifier = Modifier.fillMaxSize(),
        // 原型 .page-enter：淡入 + 8px 下移；返回走横向位移
        enterTransition = {
            fadeIn(tween(260, delayMillis = 150, easing = FastOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = tween(260, delayMillis = 150, easing = FastOutSlowInEasing),
                    initialOffsetY = { (it * 0.0095f).roundToInt() },
                )
        },
        // 旧页先走完（150ms）再让新页入场，避免两页同时可见
        exitTransition = { fadeOut(tween(150, easing = FastOutSlowInEasing)) },
        popEnterTransition = {
            fadeIn(tween(240, delayMillis = 140, easing = FastOutSlowInEasing)) +
                slideInHorizontally(
                    animationSpec = tween(240, delayMillis = 140, easing = FastOutSlowInEasing),
                    initialOffsetX = { it / 32 },
                )
        },
        popExitTransition = {
            fadeOut(tween(140, easing = FastOutSlowInEasing)) +
                slideOutHorizontally(
                    animationSpec = tween(140, easing = FastOutSlowInEasing),
                    targetOffsetX = { it / 32 },
                )
        },
    ) {
        composable("main") {
            MainScaffold(
                vm = vm,
                onQuickAdd = { navController.navigate("quickAdd") },
                onEditTransaction = { id -> navController.navigate("edit/$id") },
                onOpenPending = { navController.navigate("pending") },
                onOpenAccount = { navController.navigate("settings/account") },
                onOpenImport = { navController.navigate("backup") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenCategories = { navController.navigate("categories") },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenReward = { navController.navigate("reward") },
                onOpenCalendar = { navController.navigate("calendar") },
                onOpenPlan = { navController.navigate("plan") },
                onOpenBudget = { navController.navigate("budget") },
                onOpenDeposit = { navController.navigate("deposit") },
            )
        }
        composable("calendar") { CalendarScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("plan") { PlanScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("backup") { BackupScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("budget") { MonthBudgetScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("deposit") { DepositScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("quickAdd") { QuickAddScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("edit/{txId}") { entry ->
            val txId = entry.arguments?.getString("txId")?.toLongOrNull() ?: -1L
            EditTransactionScreen(vm = vm, txId = txId, onBack = { navController.popBackStack() })
        }
        composable("accounts") { AccountsScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("categories") { CategoriesScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("import") { ImportScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("pending") { PendingScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("refundLink") { RefundLinkScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("captureLog") { CaptureLogScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("captureTrace") {
            CaptureTraceScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenTransaction = { id -> navController.navigate("edit/$id") },
            )
        }

        // ---- 个人页 → 打赏：收款码独立页（全屏预览 / 保存到相册） ----
        composable("reward") { RewardScreen(onBack = { navController.popBackStack() }) }

        // ---- 设置体系：设置 →（权限 / 开发者功能 / 关于）；账户/账单导入在个人页 ----
        composable("settings") {
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenPermissions = { navController.navigate("settings/permissions") },
                onOpenWatchedApps = { navController.navigate("settings/watchedApps") },
                onOpenMerchantRules = { navController.navigate("settings/merchantRules") },
                onOpenPending = { navController.navigate("pending") },
                onOpenKeepAlive = { navController.navigate("keepAlive") },
                onOpenDeveloper = { navController.navigate("settings/developer") },
                onOpenAbout = { navController.navigate("settings/about") },
            )
        }
        composable("settings/account") {
            SettingsAccountScreen(
                onBack = { navController.popBackStack() },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenCategories = { navController.navigate("categories") },
            )
        }
        composable("settings/import") {
            SettingsImportScreen(
                onBack = { navController.popBackStack() },
                onImport = { navController.navigate("import") },
            )
        }
        composable("settings/permissions") {
            SettingsPermissionScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenNotificationAccess = openNotificationAccess,
                onOpenAccessibilitySettings = openAccessibilitySettings,
                onOpenOverlayPermission = openOverlayPermission,
            )
        }
        composable("settings/watchedApps") {
            SettingsWatchedAppsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings/merchantRules") {
            SettingsMerchantRulesScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable("settings/developer") {
            SettingsDeveloperScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenCaptureLog = { navController.navigate("captureLog") },
                onOpenCaptureTrace = { navController.navigate("captureTrace") },
            )
        }
        composable("settings/about") {
            SettingsAboutScreen(onBack = { navController.popBackStack() })
        }

        composable("keepAlive") {
            KeepAliveScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenNotificationAccess = openNotificationAccess,
                onOpenAccessibilitySettings = openAccessibilitySettings,
            )
        }
        }
        EcoToastHost(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 112.dp),
        )
    }

    // 冷启动权限提醒（与 NavHost 并列；调用方 Surface 内容即 Box，弹窗自带独立系统窗口）
    if (permissionMissing.isNotEmpty()) {
        PermissionReminderDialog(
            missing = permissionMissing,
            onOpenPermissions = {
                permissionMissing = emptyList()
                navController.navigate("settings/permissions")
            },
            onDismiss = { permissionMissing = emptyList() },
        )
    }
}

/** tab 切换的位移动画时长：只做整体上移，不做两页交叉淡入 */
private const val TAB_SWITCH_MS = 220
/** 新页进入时上移的距离 */
private const val TAB_RISE_DP = 10

@Composable
private fun MainScaffold(
    vm: LedgerViewModel,
    onQuickAdd: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenPending: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenReward: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenDeposit: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 再次点击当前标签 → 计数 +1，由页面自己"回到顶部"（列表/滚动页通用）
    var reenterTick by rememberSaveable { mutableIntStateOf(0) }
    val tabStates = rememberSaveableStateHolder()
    // 首页「记一笔」可在设置里关闭；这里只管首页的 FAB，账单页不受影响
    val homeQuickAdd by vm.homeQuickAddEnabled.collectAsStateWithLifecycle()

    // 切 tab 只保留当前页：旧页立即移除，新页整体上移 10dp 就位。
    // 不做两页叠加/整页 alpha：那会让旧页透出来（重影），还要多一层全屏离屏缓冲，明显掉帧。
    var hasSwitched by remember { mutableStateOf(false) }
    // 设置里关掉「显示动画」后，切 tab 直接换页，不做上移
    val animationsEnabled = LocalAnimationsEnabled.current
    // 首帧即目标值：避免先按 1f 画一帧再归零的闪；启动首帧/关闭动效时不播位移。
    val pageIn = remember(tab) { Animatable(if (hasSwitched && animationsEnabled) 0f else 1f) }
    LaunchedEffect(tab, animationsEnabled) {
        if (!animationsEnabled) {
            pageIn.snapTo(1f)
        } else if (pageIn.value < 1f) {
            pageIn.animateTo(1f, tween(TAB_SWITCH_MS, easing = FastOutSlowInEasing))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 只保留当前页：SaveableStateProvider 按 tab 保存滚动/局部状态，切走再回来仍在。
        // translationY 在 layer 阶段读取，每帧只改变换矩阵：不重组、不重新测量整页，也不产生离屏 alpha 缓冲。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = (1f - pageIn.value) * TAB_RISE_DP.dp.toPx() },
        ) {
            tabStates.SaveableStateProvider(tab) {
                TabPage(
                    tab = tab,
                    vm = vm,
                    reenterTick = reenterTick,
                    onEditTransaction = onEditTransaction,
                    onOpenAccount = onOpenAccount,
                    onOpenImport = onOpenImport,
                    onOpenPending = onOpenPending,
                    onOpenSettings = onOpenSettings,
                    onOpenCategories = onOpenCategories,
                    onOpenAccounts = onOpenAccounts,
                    onOpenReward = onOpenReward,
                    onOpenCalendar = onOpenCalendar,
                    onOpenPlan = onOpenPlan,
                    onOpenBudget = onOpenBudget,
                    onOpenDeposit = onOpenDeposit,
                )
            }
        }
        EcoBottomNav(
            selected = tab,
            onSelect = { index ->
                if (tab == index) {
                    reenterTick++
                } else {
                    hasSwitched = true
                    tab = index
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // FAB 只属于首页/账单：跟当前页走，切换时淡出+缩小，不要突然消失
        AnimatedVisibility(
            visible = (tab == 0 && homeQuickAdd) || tab == 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 19.dp, bottom = ecoFabBottomOffset()),
            enter = fadeIn(tween(180)) +
                scaleIn(initialScale = 0.86f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(140)) +
                scaleOut(targetScale = 0.86f, animationSpec = tween(160, easing = FastOutSlowInEasing)),
        ) {
            EcoFab(onClick = onQuickAdd)
        }
    }
}

/** 当前页与滑动中的旧页共用同一份渲染，保证两页内容一致 */
@Composable
private fun TabPage(
    tab: Int,
    vm: LedgerViewModel,
    reenterTick: Int,
    onEditTransaction: (Long) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenReward: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenDeposit: () -> Unit,
) {
    when (tab) {
        0 -> HomeScreen(
            vm = vm,
            onOpenCategories = onOpenCategories,
            onOpenAccounts = onOpenAccounts,
        )
        1 -> TransactionListScreen(
            vm = vm,
            onEditTransaction = onEditTransaction,
            onOpenCalendar = onOpenCalendar,
            scrollToTopTick = reenterTick,
        )
        2 -> StatsScreen(vm = vm, scrollToTopTick = reenterTick)
        3 -> ProfileScreen(
            vm = vm,
            onOpenAccount = onOpenAccount,
            onOpenImport = onOpenImport,
            onOpenPending = onOpenPending,
            onOpenSettings = onOpenSettings,
            onOpenCategories = onOpenCategories,
            onOpenPlan = onOpenPlan,
            onOpenMonthlyBudget = onOpenBudget,
            onOpenDeposit = onOpenDeposit,
        )
    }
}
