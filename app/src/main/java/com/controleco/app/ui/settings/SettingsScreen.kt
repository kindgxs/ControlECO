package com.controleco.app.ui.settings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.BuildConfig
import com.controleco.app.capture.CaptureAppCatalog
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.common.AnimationPrefs
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.SettingsDivider
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SettingsPane
import com.controleco.app.ui.common.SettingsRow
import com.controleco.app.ui.common.SettingsSection
import com.controleco.app.ui.common.SettingsSwitchRow
import com.controleco.app.ui.common.SettingsValueRow
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoHeroCard
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.theme.onHero
import com.controleco.app.ui.theme.onHeroChip
import com.controleco.app.ui.theme.onHeroMuted
import com.controleco.app.util.AppLog
import java.time.YearMonth

// 深色状态卡上的前景色：由主题推导（hero 渐变永远偏深，但色相跟主题走），
// 原来是固定的青绿常量，换主题后会出现「紫底绿字」。
private val HERO_TITLE_INK: Color
    @Composable get() = MaterialTheme.eco.onHero
private val HERO_MICRO_INK: Color
    @Composable get() = MaterialTheme.eco.onHeroMuted
private val HERO_SUB_INK: Color
    @Composable get() = MaterialTheme.eco.onHeroMuted
private val HERO_SIGNAL: Color
    @Composable get() = MaterialTheme.eco.onHeroChip

/**
 * 设置（一级页）：视觉与原型 5.12 对齐——深色运行状态卡 + 三组卡片
 * （自动记账 / 系统 / 关于），底部 `NO CLOUD · LOCAL ONLY`。
 *
 * 入口一个不少：三个入账方式开关、待确认账单、监听的 App、商户归类、保活与自启动、权限
 * （含通知监听 / 无障碍 / 悬浮窗 / 短信的汇总胶囊）、自动更新、检查更新、开发者功能（内含
 * 捕获日志、溯源）、关于与版本。账本（账户 / 账单导入）入口仍在个人页，不在此页重复。
 */
@Composable
fun SettingsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenWatchedApps: () -> Unit,
    onOpenMerchantRules: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenKeepAlive: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    LaunchedEffect(Unit) { AppLog.i("settings", "hub opened") }

    val context = LocalContext.current

    // 从系统设置页 / 子页返回时刷新权限与监听范围（NavBackStackEntry 会走 ON_RESUME）
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val captureNotify by vm.captureNotifyEnabled.collectAsStateWithLifecycle()
    val overlayConfirm by vm.overlayConfirmEnabled.collectAsStateWithLifecycle()
    val autoEntry by vm.autoEntryEnabled.collectAsStateWithLifecycle()
    val autoUpdate by vm.autoUpdateEnabled.collectAsStateWithLifecycle()
    val captureEnabled by vm.captureEnabled.collectAsStateWithLifecycle()
    val lastCaptureAt by vm.lastCaptureAt.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val rules by vm.merchantRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val homeQuickAdd by vm.homeQuickAddEnabled.collectAsStateWithLifecycle()
    val depositStart by vm.depositPlanStartMonth.collectAsStateWithLifecycle()

    var showDepositStart by remember { mutableStateOf(false) }
    var depositStartDraft by remember { mutableStateOf(YearMonth.now()) }

    val listenerGranted = remember(resumeTick) { vm.isListenerGranted() }
    val a11yGranted = remember(resumeTick) { vm.isA11yEnabled() }
    val overlayGranted = remember(resumeTick) { vm.isOverlayGranted() }
    val smsGranted = remember(resumeTick) { vm.isSmsPermissionGranted() }
    val watchedCount = remember(resumeTick) { CaptureAppCatalog.watched(context).size }
    val appTotal = remember { CaptureAppCatalog.apps.size }

    val running = captureEnabled && (listenerGranted || a11yGranted)
    val grantedCount = listOf(listenerGranted, a11yGranted, overlayGranted, smsGranted).count { it }
    val permissionsReady = grantedCount == 4

    SettingsPage(micro = "PREFERENCES", title = "设置", onBack = onBack) {
        AutomationStatusCard(running = running, lastCaptureAt = lastCaptureAt)

        SettingsSection(title = "自动记账", aside = "CAPTURE / 监听")
        SettingsPane {
            SettingsSwitchRow(
                title = "入账提醒",
                checked = captureNotify,
                onCheckedChange = { vm.setCaptureNotifyEnabled(it) },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "悬浮窗入账",
                checked = overlayConfirm,
                onCheckedChange = { vm.setOverlayConfirmEnabled(it) },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "自动入账",
                checked = autoEntry,
                onCheckedChange = { vm.setAutoEntryEnabled(it) },
            )
            SettingsDivider()
            // 待确认账单：自动记账的产出队列，有笔数时副标直接显示待处理条数
            SettingsRow(
                title = "待确认账单",
                subtitle = if (pendingCount == 0) "没有等待处理的条目" else "$pendingCount 笔等待处理",
                icon = EcoIcons.Receipt,
                iconTint = MaterialTheme.eco.amber,
                trail = "查看",
                trailColor = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = onOpenPending,
            )
            SettingsDivider()
            SettingsRow(
                title = "监听的 App",
                icon = EcoIcons.App,
                iconTint = MaterialTheme.eco.blue,
                trail = "$watchedCount 个",
                showChevron = true,
                onClick = onOpenWatchedApps,
            )
            SettingsDivider()
            SettingsRow(
                title = "商户归类",
                icon = EcoIcons.Tag,
                iconTint = MaterialTheme.eco.amber,
                trail = "${rules.size} 条",
                showChevron = true,
                onClick = onOpenMerchantRules,
            )
            SettingsDivider()
            SettingsRow(
                title = "保活与自启动",
                icon = EcoIcons.Battery,
                iconTint = MaterialTheme.eco.jade,
                trail = "查看",
                trailColor = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = onOpenKeepAlive,
            )
            SettingsDivider()
            SettingsRow(
                title = "权限",
                icon = EcoIcons.Shield,
                iconTint = MaterialTheme.eco.jade,
                pillText = if (permissionsReady) "已就绪" else "待设置",
                pillTone = if (permissionsReady) EcoPillTone.Good else EcoPillTone.Warn,
                showChevron = true,
                onClick = onOpenPermissions,
            )
        }

        SettingsSection(title = "记账", aside = "LEDGER / 记账")
        SettingsPane {
            // 只关闭首页的「记一笔」，账单页的 + 不受影响
            SettingsSwitchRow(
                title = "首页「记一笔」按钮",
                subtitle = "关闭后只隐藏首页的快捷记账，账单页仍可记账",
                checked = homeQuickAdd,
                onCheckedChange = { vm.setHomeQuickAddEnabled(it) },
            )
            SettingsDivider()
            // 存款页「每月存款」的起始月份：未设置时默认显示近 12 个月
            SettingsRow(
                title = "存款计划开始时间",
                subtitle = "每月存款从这一月起显示；未设置时默认显示近 12 个月",
                icon = EcoIcons.Calendar,
                iconTint = MaterialTheme.eco.jade,
                trail = depositStart?.let { "${it.year} 年 ${it.monthValue} 月" } ?: "未设置",
                showChevron = true,
                onClick = {
                    depositStartDraft = depositStart ?: YearMonth.now()
                    showDepositStart = true
                },
            )
        }

        SettingsSection(title = "系统", aside = "SYSTEM / 系统")
        SettingsPane {
            // 美化动效总开关：关掉后圆环填充、金额滚动、柱状图升起、卡片弹入等都不播
            SettingsSwitchRow(
                title = "显示动画",
                checked = AnimationPrefs.enabled,
                onCheckedChange = { AnimationPrefs.set(context, it) },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "自动更新",
                checked = autoUpdate,
                onCheckedChange = { vm.setAutoUpdateEnabled(it) },
            )
            SettingsDivider()
            SettingsRow(
                title = "检查更新",
                icon = EcoIcons.Download,
                trail = "v${BuildConfig.VERSION_NAME}",
                showChevron = true,
                onClick = {
                    EcoToast.show("正在检查更新…", EcoPillTone.Blue)
                    vm.checkUpdateNow { message ->
                        if (message == null) {
                            EcoToast.show("发现新版本，正在打开更新…", EcoPillTone.Blue)
                        } else {
                            EcoToast.show(
                                message,
                                if (message.startsWith("检查失败")) EcoPillTone.Warn else EcoPillTone.Good,
                            )
                        }
                    }
                },
            )
            SettingsDivider()
            SettingsRow(
                title = "开发者功能",
                icon = EcoIcons.Gear,
                iconTint = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = onOpenDeveloper,
            )
        }

        SettingsSection(title = "关于", aside = "ABOUT / 关于")
        SettingsPane {
            SettingsRow(
                title = "关于易控钱包",
                icon = EcoIcons.Info,
                iconTint = MaterialTheme.eco.jade,
                trail = "v${BuildConfig.VERSION_NAME}",
                showChevron = true,
                onClick = onOpenAbout,
            )
            SettingsDivider()
            SettingsValueRow("版本", "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            SettingsDivider()
            SettingsValueRow("监听范围", "$watchedCount / $appTotal 个应用")
        }

        Text(
            "NO CLOUD / LOCAL ONLY",
            style = EcoType.MicroQuiet.copy(fontSize = 8.sp),
            color = MaterialTheme.eco.quiet,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 17.dp, bottom = 8.dp),
        )
    }

    if (showDepositStart) {
        val draft = depositStartDraft
        val now = YearMonth.now()
        EcoSheet(
            onDismiss = { showDepositStart = false },
            title = "存款计划开始时间",
            subtitle = "存款页「每月存款」默认从这里显示到本月",
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
                MonthStepper(
                    label = "年份",
                    value = "${draft.year} 年",
                    canPrev = draft.year > 2000,
                    canNext = draft.year < now.year,
                    onPrev = { depositStartDraft = draft.minusYears(1) },
                    onNext = { depositStartDraft = draft.plusYears(1) },
                )
                Spacer(Modifier.height(8.dp))
                MonthStepper(
                    label = "月份",
                    value = "${draft.monthValue} 月",
                    canPrev = draft > YearMonth.of(2000, 1),
                    canNext = draft < now,
                    onPrev = { depositStartDraft = draft.minusMonths(1) },
                    onNext = { depositStartDraft = draft.plusMonths(1) },
                )
                Spacer(Modifier.height(14.dp))
                EcoPrimaryButton(
                    text = "保存开始月份",
                    onClick = {
                        // 防御：步进器允许跨年，可能落到未来月份；保存时收回到本月
                        vm.setDepositPlanStartMonth(if (draft.isAfter(now)) now else draft)
                        showDepositStart = false
                        EcoToast.show("已保存存款计划开始时间")
                    },
                )
                if (depositStart != null) {
                    Spacer(Modifier.height(8.dp))
                    EcoGhostButton(
                        text = "清除（不设置开始时间）",
                        onClick = {
                            vm.setDepositPlanStartMonth(null)
                            showDepositStart = false
                            EcoToast.show("已清除存款计划开始时间")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/** 年 / 月步进器：左右圆形按钮 + 中间数值 */
@Composable
private fun MonthStepper(
    label: String,
    value: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = EcoType.RowTitle.copy(fontSize = 12.sp), color = c.ink, modifier = Modifier.weight(1f))
        EcoIconButton(
            icon = EcoIcons.Back,
            contentDescription = "上一步",
            onClick = onPrev,
            size = 30.dp,
            enabled = canPrev,
        )
        Text(
            value,
            style = EcoType.RowTitle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = c.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(90.dp),
        )
        EcoIconButton(
            icon = EcoIcons.Next,
            contentDescription = "下一步",
            onClick = onNext,
            size = 30.dp,
            enabled = canNext,
        )
    }
}

/** 顶部深色状态卡：自动记账是否在运行 + 最近一次捕获时间 + 呼吸信号条 */
@Composable
private fun AutomationStatusCard(running: Boolean, lastCaptureAt: Long) {
    EcoHeroCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(7.dp, 23.dp, 7.dp, 23.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 14.dp),
        ) {
            Text(
                "AUTOMATION / NOW",
                style = EcoType.Micro.copy(letterSpacing = 1.6.sp),
                color = HERO_MICRO_INK,
                maxLines = 1,
            )
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "自动记账（运行中）" else "自动记账（已暂停）",
                        style = EcoType.RowTitle.copy(
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.W800,
                        ),
                        color = HERO_TITLE_INK,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (lastCaptureAt == 0L) "还没有捕获记录" else "最近捕获 ${formatTimestamp(lastCaptureAt)}",
                        style = EcoType.RowSub.copy(fontSize = 8.5.sp),
                        color = HERO_SUB_INK,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(10.dp))
                SignalBars(active = running)
            }
        }
    }
}

/** 四根竖条信号：运行时最后一根呼吸，暂停时整组变暗 */
@Composable
private fun SignalBars(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "auto-signal")
    val breathe by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "auto-signal-alpha",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(5.dp, 9.dp, 13.dp, 17.dp).forEachIndexed { index, height ->
            val alpha = when {
                !active -> 0.28f
                index == 3 -> breathe
                else -> 0.92f
            }
            Box(
                Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(HERO_SIGNAL.copy(alpha = alpha)),
            )
        }
    }
}
