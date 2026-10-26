package com.controleco.app.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.PeriodMode
import com.controleco.app.data.SubcategoryRollupMode
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.TxType
import com.controleco.app.data.deposit.DepositMath
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.PeriodPickerSheet
import com.controleco.app.ui.common.periodLabel
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoProgressBar
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.LocalAnimationsEnabled
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.transactions.BalanceCard
import com.controleco.app.ui.transactions.CoverStyleStore
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 统计页（ECO 版）：顶栏 → 周期工具条 → 结论卡 → 每日/月度支出柱状图 → 分类占比环 → 预算对比。
 *
 * 数据流保持原样（[LedgerViewModel.periodStatsRows] / [LedgerViewModel.periodTotals] /
 * [LedgerViewModel.periodCategorySums] / [LedgerViewModel.categoryBudgets]），
 * 月视角每日一柱、年视角每月一柱；柱状图与圆环都是自绘 Canvas，不引第三方图表库。
 *
 * 口径：`02` 分类预算与占比的「实际支出」= **未被标记「已退款」的支出之和**，
 * 与首页「本月支出」是同一个数（[LedgerViewModel.periodSpendingTotal]）。
 * 封面与支出节奏仍按账面（含已退款的原支出），两者用途不同。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(vm: LedgerViewModel, scrollToTopTick: Int = 0) {
    val c = MaterialTheme.eco
    val month by vm.month.collectAsStateWithLifecycle()
    val mode by vm.periodMode.collectAsStateWithLifecycle()
    val currentAccountingMonth by vm.currentAccountingMonth.collectAsStateWithLifecycle()
    val monthStartDayState by vm.monthStartDay.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val totals by vm.periodTotals.collectAsStateWithLifecycle()
    val byCategory by vm.periodCategorySums.collectAsStateWithLifecycle()
    val rows by vm.periodStatsRows.collectAsStateWithLifecycle()
    val spendingTotal by vm.periodSpendingTotal.collectAsStateWithLifecycle()
    val categoryBudgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rollupMode by vm.subcategoryRollupMode.collectAsStateWithLifecycle()
    val depositTrend by vm.depositTrend.collectAsStateWithLifecycle()
    val depositGoal by vm.depositGoalCents.collectAsStateWithLifecycle()
    val depositRangeMonths by vm.depositRangeMonths.collectAsStateWithLifecycle()
    val depositShowAll by vm.depositShowAll.collectAsStateWithLifecycle()
    val depositRangeStart by vm.depositRangeStart.collectAsStateWithLifecycle()
    val depositRangeEnd by vm.depositRangeEnd.collectAsStateWithLifecycle()
    val currentSaving by vm.currentMonthSaving.collectAsStateWithLifecycle()
    val cardImageUri by vm.cardImageUri.collectAsStateWithLifecycle()
    val cardTransform by vm.cardTransform.collectAsStateWithLifecycle()
    val cardImage by vm.cardImage.collectAsStateWithLifecycle()
    val cardImageColor by vm.cardImageColor.collectAsStateWithLifecycle()
    val cardImageLuminance by vm.cardImageLuminance.collectAsStateWithLifecycle()

    // 数据未到位前整页不画（避免先显示 0 / 空图再跳数）
    val rowsReady = rows ?: return
    val totalsReady = totals ?: return
    val byCategoryReady = byCategory ?: return
    val spendingReady = spendingTotal ?: return
    // 二级分类按当前口径折算到一级分类（预算只挂在一级分类上）
    val rolledCategory = remember(byCategoryReady, categories, rollupMode) {
        vm.rollupCategorySums(byCategoryReady, categories)
    }

    val expense = totalsReady.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totalsReady.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L
    val balance = income - expense
    val today = LocalDate.now()
    val monthStartDay = monthStartDayState
    val isCurrentPeriod = if (mode == PeriodMode.MONTH) {
        month == currentAccountingMonth
    } else {
        month.year == currentAccountingMonth.year
    }

    // 柱状图数据：月视角每日一根、年视角每月一根（月视角按会计月分桶）
    val bars = remember(rowsReady, month, mode, monthStartDay) {
        if (mode == PeriodMode.MONTH) {
            ChartMath.monthBars(rowsReady, month, monthStartDay)
        } else {
            ChartMath.yearBars(rowsReady, month)
        }
    }
    // 柱状图入场进度**由页面持有**：`ExpenseBarChart` 是 LazyColumn 的一个 item，滑出屏幕就被销毁，
    // 进度若 remember 在 item 内部，滑回来会从 0 重播（用户反馈的 bug：下滑再上滑动画重播）。
    // 提到页面级后：上下滑动时无论柱状图可不可见都不重播；只有切页（本页离开组合、重新进入时重建）
    // 或换周期（bars 变）才重播。读进度在 Canvas 的绘制阶段，每帧只重画、不重组。
    val barGrow = remember { Animatable(0f) }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(bars, animationsEnabled) {
        if (!animationsEnabled) {
            // 关闭美化动效：柱子直接到位
            barGrow.snapTo(1f)
            return@LaunchedEffect
        }
        barGrow.snapTo(0f)
        barGrow.animateTo(1f, tween(820, easing = FastOutSlowInEasing))
    }

    // 选中柱由页面持有：卡片头部的 readout 要跟着它走。
    // 原型默认选中"今天"对应的柱；看历史周期时默认选中支出最高的一天。
    val defaultBarIndex = remember(bars, isCurrentPeriod, mode, monthStartDay) {
        if (bars.isEmpty()) {
            0
        } else if (isCurrentPeriod) {
            val raw = if (mode == PeriodMode.MONTH) {
                MonthPeriod.of(month, monthStartDay).dayIndexOf(today) - 1
            } else {
                today.monthValue - 1
            }
            raw.coerceIn(0, bars.lastIndex)
        } else {
            bars.indices.maxByOrNull { bars[it].cents } ?: 0
        }
    }
    var selectedBar by remember(bars) { mutableIntStateOf(defaultBarIndex) }
    // 支出节奏图样式由页面持有（与入场进度同理：滑出屏幕不重置）
    var trendStyle by remember { mutableStateOf(TrendChartStyle.BAR) }
    // 存款趋势图样式 / 区间设置弹层
    var savingsStyle by remember { mutableStateOf(TrendChartStyle.BAR) }
    var showSavingsRange by remember { mutableStateOf(false) }

    // 分类切片：实际支出（本期，已排除被标「已退款」的支出）+ 分类预算（年视角按 12 个月合计）
    val slices = remember(rolledCategory, categoryBudgets, categories, mode) {
        val actual = rolledCategory.map { it.categoryName to it.totalCents }
        val budget = categoryBudgets.entries.mapNotNull { (id, cents) ->
            val category = categories.firstOrNull { it.id == id }
            if (category == null || category.type != TxType.EXPENSE || cents <= 0L) {
                null
            } else {
                category.name to if (mode == PeriodMode.YEAR) cents * 12 else cents
            }
        }
        ChartMath.mergeSlices(actual, budget)
    }
    // 圆环选中态与配色：**与分类图标同色**（图标自定义过颜色就用它；emoji/相册图片没有颜色，
    // 才由系统从调色板里挑"离已用色最远"的），选中态点空白处取消
    val selectedState = remember(slices) { mutableStateOf<String?>(null) }
    var selectedSlice by selectedState
    val palette = MaterialTheme.accBook.categoryPalette
    val sliceColors = remember(slices, palette, categories) {
        val iconByName = categories
            .filter { it.type == TxType.EXPENSE }
            .associate { it.name to it.icon }
        sliceColorsOf(palette, slices, iconByName)
    }

    // 预算对比：分类预算（年视角 ×12）与实际支出对齐，按实际降序
    val ratioRows = remember(categoryBudgets, categories, rolledCategory, mode) {
        buildRatioRows(categoryBudgets, categories, rolledCategory, mode)
    }
    val ratioBudget = ratioRows.sumOf { it.budgetCents }

    val monthEn = month.atDay(1)
        .format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))
        .uppercase(Locale.ENGLISH)
    val listState = rememberLazyListState()
    // 再次点击底部「统计」标签才回到顶部；从别的 tab 切回来保持原滚动位置
    var lastReenterTick by remember { mutableIntStateOf(scrollToTopTick) }
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > lastReenterTick) {
            lastReenterTick = scrollToTopTick
            listState.animateScrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "INSIGHTS / $monthEn",
            title = "统计",
            action = {
                Text(
                    "${month.year} / ${month.monthValue.toString().padStart(2, '0')}",
                    style = EcoType.Micro,
                    color = c.quiet,
                )
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 点页面上任意空白处 = 取消圆环选中（点环 / 点图例行会被它们自己接住）
                .pointerInput(selectedState) { detectTapGestures { selectedState.value = null } },
            // 底栏悬浮在内容之上：只留底栏高度的安全距离 + 一点余量，
            // 不加多余的滚动余量（否则最底部会有一段拉不到头的空白）
            contentPadding = PaddingValues(bottom = ecoBottomContentPadding(extra = 24.dp)),
        ) {
            // 与账单页共用同一个结余封面对象（同一张背景图 / 同一份 VM 状态），随内容滚动
            item(key = "cover") {
                Box(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                    BalanceCard(
                        mode = mode,
                        balanceCents = balance,
                        incomeCents = income,
                        expenseCents = expense,
                        imagePath = cardImageUri,
                        image = cardImage,
                        transform = cardTransform,
                        placeholderColor = cardImageColor,
                        placeholderLuminance = cardImageLuminance,
                        style = CoverStyleStore.current,
                        onModeChange = { vm.setPeriodMode(it) },
                        onOpenImageSettings = {},
                        showEdit = false,
                    )
                }
            }

            item(key = "toolbar") {
                StatsToolbar(
                    month = month,
                    mode = mode,
                    onPrev = { vm.prevPeriod() },
                    onNext = { vm.nextPeriod() },
                    onOpenPicker = { showPicker = true },
                    onMode = { vm.setPeriodMode(it) },
                )
            }

            // ---- 趋势卡：柱状图（可点按/滑动读数） ----
            item(key = "trendHead") {
                EcoSectionHead(
                    title = if (mode == PeriodMode.MONTH) "每日支出" else "月度支出",
                    aside = if (mode == PeriodMode.MONTH) "DAILY" else "MONTHLY",
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
            item(key = "trend") {
                TrendCard(
                    mode = mode,
                    bars = bars,
                    expenseCents = expense,
                    selected = selectedBar,
                    onSelect = { selectedBar = it },
                    grow = { barGrow.value },
                    style = trendStyle,
                    onStyleChange = { trendStyle = it },
                )
            }

            // ---- 唯一一个圆环：留在分类列表上方，向上滚动时吸顶 ----
            stickyHeader(key = "donutSticky") {
                Column(Modifier.fillMaxWidth().background(c.paper)) {
                    EcoSectionHead(
                        title = "分类预算与占比",
                        aside = "BUDGET / ACTUAL",
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                    // 二级分类统计口径（设置页已移到这里）：按一级分类 / 同名合并
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "二级分类口径",
                            style = EcoType.PageNote,
                            color = c.quiet,
                            modifier = Modifier.weight(1f),
                        )
                        EcoSegmented(
                            options = listOf(
                                SubcategoryRollupMode.BY_PARENT.label,
                                SubcategoryRollupMode.MERGE_SAME_NAME.label,
                            ),
                            selectedIndex = if (rollupMode == SubcategoryRollupMode.BY_PARENT) 0 else 1,
                            onSelect = {
                                vm.setSubcategoryRollupMode(
                                    if (it == 0) SubcategoryRollupMode.BY_PARENT
                                    else SubcategoryRollupMode.MERGE_SAME_NAME,
                                )
                            },
                            modifier = Modifier.width(150.dp),
                            height = 28.dp,
                            small = true,
                        )
                    }
                    DonutCard(
                        expenseCents = spendingReady,
                        slices = slices,
                        sliceColors = sliceColors,
                        selected = selectedSlice,
                        onSelect = { selectedSlice = it },
                    )
                }
            }

            // ---- 分类清单：只保留文字行，不再重复圆环 ----
            item(key = "ratio") {
                RatioCard(
                    rows = ratioRows,
                    actualTotal = spendingReady,
                    budgetTotal = ratioBudget,
                    colors = sliceColors,
                    selected = selectedSlice,
                    onSelect = { selectedSlice = it },
                )
            }

            // ---- 存款分析（功能7）：当月实际新增 = 当月收入 − 当月支出 ----
            val savingNow = currentSaving
            val trendNow = depositTrend
            if (savingNow != null && trendNow != null) {
                // 存款分析（含趋势图）吸顶：向上滚动时图表常驻，「每月存款」明细在下方滚动隐藏
                stickyHeader(key = "savingsSticky") {
                    Column(Modifier.fillMaxWidth().background(c.paper)) {
                        EcoSectionHead(
                            title = "存款分析",
                            aside = "SAVINGS",
                            modifier = Modifier.padding(horizontal = 22.dp),
                        )
                        SavingsCard(
                            current = savingNow,
                            trend = trendNow,
                            goalCents = depositGoal,
                            style = savingsStyle,
                            onStyleChange = { savingsStyle = it },
                            onOpenRange = { showSavingsRange = true },
                        )
                    }
                }
                // 逐月明细与趋势图同层级的两块内容，和「每日支出 / 分类预算与占比」的层次一致
                item(key = "savingsListHead") {
                    EcoSectionHead(
                        title = "每月存款",
                        aside = "MONTHLY",
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
                item(key = "savingsList") {
                    SavingsMonthlyCard(trend = trendNow, goalCents = depositGoal)
                }
            }
        }
    }

    if (showPicker) {
        PeriodPickerSheet(
            mode = mode,
            current = month,
            onSelectMonth = { vm.setMonth(it) },
            onSelectYear = { vm.setMonth(YearMonth.of(it, month.monthValue)) },
            onDismiss = { showPicker = false },
            currentMonth = currentAccountingMonth,
        )
    }

    if (showSavingsRange) {
        SavingsRangeSheet(
            showAll = depositShowAll,
            rangeMonths = depositRangeMonths,
            start = depositRangeStart,
            end = depositRangeEnd,
            trend = depositTrend.orEmpty(),
            onApply = { all, months, start, end ->
                vm.setDepositShowAll(all)
                vm.setDepositRangeMonths(months)
                vm.setDepositRangeBounds(start, end)
                showSavingsRange = false
            },
            onDismiss = { showSavingsRange = false },
        )
    }
}

/** 唯一保留的占比圆环：空心、圆心不写数字；实际/预算金额放在环两侧。 */
@Composable
private fun DonutCard(
    expenseCents: Long,
    slices: List<CategorySlice>,
    sliceColors: Map<String, Color>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val c = MaterialTheme.eco
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        if (slices.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyNote("暂无分类数据")
            }
        } else {
            val selectedData = slices.firstOrNull { it.name == selected }
            val actualShown = selectedData?.actualCents ?: expenseCents
            val budgetTotal = slices.sumOf { it.budgetCents }
            val actualShare = if (budgetTotal > 0) actualShown * 100f / budgetTotal else 0f
            val budgetShown = selectedData?.budgetCents ?: budgetTotal
            val budgetShare = if (selectedData == null) {
                if (budgetTotal > 0) 100f else 0f
            } else {
                ChartMath.sharePercent(budgetShown, budgetTotal)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        selectedData?.name ?: "实际支出",
                        style = EcoType.Micro.copy(fontSize = 8.sp),
                        color = c.quiet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    EcoAutoMoney(
                        text = Money.formatYuanGroupedFixed(actualShown),
                        style = EcoType.CatAmount.copy(fontSize = 16.sp),
                        color = c.ink,
                        widthLimit = 84.dp,
                        minSize = 10.sp,
                        textAlign = TextAlign.Start,
                    )
                    Text(
                        "${actualShare.roundToInt()}%",
                        style = EcoType.PageNote.copy(fontSize = 9.sp),
                        color = c.muted,
                    )
                }
                CategoryDonut(
                    slices = slices,
                    totalActualCents = expenseCents,
                    colors = sliceColors,
                    selected = selected,
                    onSelect = onSelect,
                    ringSize = 150.dp,
                    showCenterLabels = false,
                    singleRing = false,
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        "预算",
                        style = EcoType.Micro.copy(fontSize = 8.sp),
                        color = c.quiet,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    EcoAutoMoney(
                        text = Money.formatYuanGroupedFixed(budgetShown),
                        style = EcoType.CatAmount.copy(fontSize = 16.sp),
                        color = c.ink,
                        widthLimit = 84.dp,
                        minSize = 10.sp,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        "${budgetShare.roundToInt()}%",
                        style = EcoType.PageNote.copy(fontSize = 9.sp),
                        color = c.muted,
                    )
                }
            }
        }
    }
}

// ---------- 工具条 ----------

/** 周期工具条：‹ 2026年10月 / MONTH › + 右侧月/年分段 */
@Composable
private fun StatsToolbar(
    month: YearMonth,
    mode: PeriodMode,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenPicker: () -> Unit,
    onMode: (PeriodMode) -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .height(45.dp)
            .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepChevron(icon = EcoIcons.Back, description = "上一${mode.label}", onClick = onPrev)
        Column(
            Modifier
                .widthIn(min = 86.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenPicker,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                periodLabel(month, mode),
                style = EcoType.RowTitle.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (mode == PeriodMode.MONTH) "ACCOUNTING MONTH" else "ACCOUNTING YEAR",
                style = EcoType.PageNote.copy(
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp,
                    lineHeight = 10.sp,
                ),
                color = c.quiet,
                maxLines = 1,
            )
        }
        StepChevron(icon = EcoIcons.Next, description = "下一${mode.label}", onClick = onNext)
        Spacer(Modifier.weight(1f))
        EcoSegmented(
            options = listOf("月", "年"),
            selectedIndex = if (mode == PeriodMode.MONTH) 0 else 1,
            onSelect = { index -> onMode(if (index == 0) PeriodMode.MONTH else PeriodMode.YEAR) },
            modifier = Modifier.width(88.dp),
            height = 30.dp,
            small = true,
        )
    }
}

@Composable
private fun StepChevron(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.eco
    Box(
        Modifier
            .size(width = 27.dp, height = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .ecoPressable(scaleTo = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = c.muted, modifier = Modifier.size(13.dp))
    }
}

// ---------- 结论卡 ----------

/** 工具条下的一行统计摘要：替代原型里没有的大结论卡，信息不丢但不再占视觉主位。 */
@Composable
private fun StatsSummaryLine(
    expenseCents: Long,
    incomeCents: Long,
    balanceCents: Long,
    dailyBalanceCents: Long,
    count: Int,
    maxExpenseCents: Long,
    refundCents: Long,
    showRefund: Boolean,
) {
    val c = MaterialTheme.eco
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("支出 ${Money.formatYuanGrouped(expenseCents)}", style = EcoType.Micro.copy(fontSize = 9.sp), color = c.ink, maxLines = 1)
            Text("收入 ${Money.formatYuanGrouped(incomeCents)}", style = EcoType.Micro.copy(fontSize = 9.sp), color = c.blue, maxLines = 1)
            Text(
                "结余 ${Money.formatYuanGrouped(balanceCents)}",
                style = EcoType.Micro.copy(fontSize = 9.sp),
                color = if (balanceCents < 0) c.clay else c.jade,
                maxLines = 1,
            )
            Text("$count 笔", style = EcoType.Micro.copy(fontSize = 9.sp), color = c.muted, maxLines = 1)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append("日均结余 ${Money.formatYuanGrouped(dailyBalanceCents)}")
                if (maxExpenseCents > 0) append(" 最大单笔 ${Money.formatYuanGrouped(maxExpenseCents)}")
                if (showRefund && refundCents > 0) append(" 退款 ${Money.formatYuanGrouped(refundCents)}")
            },
            style = EcoType.Micro.copy(fontSize = 8.sp),
            color = c.quiet,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConclusionCard(
    mode: PeriodMode,
    expenseCents: Long,
    incomeCents: Long,
    balanceCents: Long,
    dailyBalanceCents: Long,
    count: Int,
    maxExpenseCents: Long,
    refundCents: Long,
    showRefund: Boolean,
) {
    val c = MaterialTheme.eco
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(top = 10.dp),
        shape = RoundedCornerShape(19.dp),
        elevation = 10.dp,
    ) {
        Box(Modifier.fillMaxWidth()) {
            // 右上装饰环（极淡，只让大片留白不空）
            Canvas(Modifier.matchParentSize()) {
                val ring = if (c.isDark) 0.4f else 0.75f
                drawCircle(
                    color = c.rule.copy(alpha = ring),
                    radius = size.width * 0.34f,
                    center = Offset(size.width * 1.04f, -size.height * 0.16f),
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(
                    color = c.rule.copy(alpha = ring * 0.7f),
                    radius = size.width * 0.22f,
                    center = Offset(size.width * 1.04f, -size.height * 0.16f),
                    style = Stroke(1.dp.toPx()),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 15.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (mode == PeriodMode.MONTH) "本月支出" else "本年支出",
                            style = EcoType.Micro.copy(fontSize = 9.5.sp),
                            color = c.muted,
                        )
                        Spacer(Modifier.height(1.dp))
                        EcoAutoMoney(
                            text = Money.formatYuanGrouped(expenseCents),
                            style = EcoType.ConclusionAmount,
                            color = c.ink,
                            widthLimit = 210.dp,
                            minSize = 18.sp,
                            textAlign = TextAlign.Start,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            "日均 ${Money.formatYuanGrouped(dailyBalanceCents)} / day",
                            style = EcoType.PageNote.copy(fontSize = 9.5.sp),
                            color = c.quiet,
                            maxLines = 1,
                        )
                        if (showRefund && refundCents > 0L) {
                            Text(
                                "退款到账 ${Money.formatYuanGrouped(refundCents)}",
                                style = EcoType.PageNote.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                color = c.jade,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        SideAmount(label = "收入", text = Money.formatYuanGrouped(incomeCents), color = c.jade)
                        Spacer(Modifier.height(6.dp))
                        SideAmount(
                            label = "结余",
                            text = Money.formatYuanGrouped(balanceCents),
                            color = if (balanceCents < 0L) c.clay else c.jade,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$count 笔",
                        style = EcoType.PageNote.copy(fontSize = 8.5.sp, letterSpacing = 0.2.sp),
                        color = c.quiet,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "最大单笔 ${Money.formatYuanGrouped(maxExpenseCents)}",
                        style = EcoType.PageNote.copy(fontSize = 8.5.sp, letterSpacing = 0.2.sp),
                        color = c.quiet,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SideAmount(label: String, text: String, color: Color) {
    val c = MaterialTheme.eco
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = EcoType.PageNote.copy(fontSize = 8.5.sp), color = c.quiet)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = EcoType.RowTrail.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
        )
    }
}

// ---------- 趋势卡 ----------

@Composable
private fun TrendCard(
    mode: PeriodMode,
    bars: List<BarPoint>,
    expenseCents: Long,
    selected: Int,
    onSelect: (Int) -> Unit,
    /** 柱状图入场进度 0..1，由页面持有（滑出屏幕不重播），见 `StatsScreen` 顶部注释 */
    grow: () -> Float,
    /** 支出节奏图样式：柱形 / 折线 */
    style: TrendChartStyle,
    onStyleChange: (TrendChartStyle) -> Unit,
) {
    val c = MaterialTheme.eco
    val selectedBar = bars.getOrNull(selected)
    // 读数：选中柱给"日期 / 金额"，没选中就是本期合计
    val readoutLabel = selectedBar?.tip
        ?.substringBefore("  ")
        ?.takeIf { it.isNotBlank() }
        ?: "合计"
    val readoutAmount = Money.formatYuanGrouped(selectedBar?.cents ?: expenseCents)
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "支出节奏",
                    style = EcoType.SectionTitle.copy(fontSize = 12.5.sp),
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                EcoSegmented(
                    options = listOf("柱形", "折线"),
                    selectedIndex = if (style == TrendChartStyle.BAR) 0 else 1,
                    onSelect = {
                        onStyleChange(if (it == 0) TrendChartStyle.BAR else TrendChartStyle.LINE)
                    },
                    modifier = Modifier.width(74.dp),
                    height = 24.dp,
                    small = true,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    readoutLabel,
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
                    color = c.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    readoutAmount,
                    style = EcoType.CatAmount.copy(fontSize = 15.sp),
                    color = c.ink,
                    maxLines = 1,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, end = 13.dp, top = 9.dp, bottom = 12.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
                Spacer(Modifier.height(11.dp))
                if (expenseCents == 0L) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(155.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (mode == PeriodMode.MONTH) "本月还没有支出" else "本年还没有支出",
                                style = EcoType.EmptyTitle.copy(fontSize = 12.sp),
                                color = c.muted,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "记一笔后这里会出现图表",
                                style = EcoType.PageNote.copy(fontSize = 9.sp),
                                color = c.quiet,
                            )
                        }
                    }
                } else {
                    ExpenseBarChart(
                        bars = bars,
                        selected = selected,
                        onSelect = onSelect,
                        grow = grow,
                        height = 200.dp,
                        style = style,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ---------- 预算对比卡 ----------

@Composable
private fun RatioCard(
    rows: List<BudgetRatioRow>,
    actualTotal: Long,
    budgetTotal: Long,
    colors: Map<String, Color>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val c = MaterialTheme.eco
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(top = 14.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 13.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("预算对比", style = EcoType.SectionTitle, color = c.ink)
                Spacer(Modifier.weight(1f))
                Text(
                    Money.formatYuanGrouped(actualTotal),
                    style = EcoType.CatAmount.copy(fontSize = 13.5.sp),
                    color = c.ink,
                    maxLines = 1,
                )
                Text(
                    " / ${Money.formatYuanGrouped(budgetTotal)}",
                    style = EcoType.RowTrail.copy(fontSize = 10.sp),
                    color = c.quiet,
                    maxLines = 1,
                )
            }
            if (rows.isEmpty()) {
                Text(
                    "暂无分类预算",
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
                    color = c.quiet,
                    modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
                )
            } else {
                Spacer(Modifier.height(10.dp))
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
                    }
                    RatioListRow(
                        row = row,
                        color = colors[row.name] ?: c.quiet,
                        actualShare = ratioPercent(row.actualCents, actualTotal),
                        budgetShare = ratioPercent(row.budgetCents, budgetTotal),
                        selected = row.name == selected,
                        dim = selected != null && row.name != selected,
                        onClick = { onSelect(row.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RatioSide(
    caption: String,
    amountCents: Long,
    percentText: String,
    percentColor: Color,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    Column(
        modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            caption,
            style = EcoType.PageNote.copy(fontSize = 8.sp, letterSpacing = 0.2.sp),
            color = c.muted,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        EcoAutoMoney(
            text = Money.formatYuanGrouped(amountCents),
            style = EcoType.CatAmount.copy(fontSize = 14.sp),
            color = c.ink,
            minSize = 9.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            percentText,
            style = EcoType.RowTrail.copy(fontSize = 11.5.sp),
            color = percentColor,
            maxLines = 1,
        )
    }
}

/** 预算对比清单的一行：左侧 9dp 竖色条 + 名称 / 实际 / 占比，第二行 预算 / 预算占比 */
@Composable
private fun RatioListRow(
    row: BudgetRatioRow,
    color: Color,
    actualShare: Float,
    budgetShare: Float,
    selected: Boolean,
    dim: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // 选中别的分类时其余行降到 46%（与原型 .dim 一致）
            .graphicsLayer { alpha = if (dim) 0.46f else 1f }
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.blueWash.copy(alpha = if (c.isDark) 0.5f else 0.85f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(9.dp)
                .fillMaxHeight()
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = EcoType.RowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    Money.formatYuanGrouped(row.actualCents),
                    style = EcoType.CatAmount.copy(fontSize = 14.5.sp),
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${actualShare.roundToInt()}%",
                    style = EcoType.RowTrail.copy(fontSize = 11.5.sp),
                    color = c.muted,
                    modifier = Modifier.width(34.dp),
                    textAlign = TextAlign.End,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    Money.formatYuanGrouped(row.budgetCents),
                    style = EcoType.RowTrail.copy(fontSize = 10.5.sp),
                    color = c.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${budgetShare.roundToInt()}%",
                    style = EcoType.PageNote.copy(fontSize = 10.sp),
                    color = c.quiet,
                    modifier = Modifier.width(34.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

// ---------- 存款分析 ----------

/**
 * 存款分析卡：当月实际新增存款（收入 − 支出）+ 目标达成比例 + 存款趋势图。
 *
 * 数据来自 [LedgerViewModel.currentMonthSaving] / [LedgerViewModel.depositTrend]，
 * 口径与首页、统计页一致：支出排除「已退款」，收入含退款到账。
 * 趋势图的月份范围与「存款」页共用同一份设置（月数 / 全部 / 手动年月），旁边可切柱形/折线。
 */
@Composable
private fun SavingsCard(
    current: DepositMath.MonthSaving,
    trend: List<DepositMath.MonthSaving>,
    goalCents: Long,
    style: TrendChartStyle,
    onStyleChange: (TrendChartStyle) -> Unit,
    onOpenRange: () -> Unit,
) {
    val c = MaterialTheme.eco
    val savingCents = current.savingCents
    val ratio = current.ratio(goalCents)
    val savingsColor = if (savingCents < 0L) c.clay else c.jade
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 15.dp, bottom = 13.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "本月实际新增存款",
                        style = EcoType.Micro.copy(fontSize = 8.5.sp),
                        color = c.quiet,
                    )
                    Spacer(Modifier.height(2.dp))
                    EcoAutoMoney(
                        text = Money.formatYuanGroupedFixed(savingCents),
                        style = EcoType.ConclusionAmount.copy(fontSize = 21.sp),
                        color = savingsColor,
                        minSize = 13.sp,
                        textAlign = TextAlign.Start,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (ratio == null) {
                    EcoPill(text = "未设目标", tone = EcoPillTone.Neutral)
                } else {
                    EcoPill(
                        text = "达成 ${(ratio * 100f).roundToInt()}%",
                        tone = when {
                            ratio >= 1f -> EcoPillTone.Good
                            ratio >= 0f -> EcoPillTone.Blue
                            else -> EcoPillTone.Bad
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (goalCents > 0L) {
                EcoProgressBar(
                    progress = savingCents.toFloat() / goalCents.toFloat(),
                    height = 7.dp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "目标 ${Money.formatYuanGrouped(goalCents)} / 月 · " +
                        "收入 ${Money.formatYuanGrouped(current.incomeCents)} · " +
                        "支出 ${Money.formatYuanGrouped(current.expenseCents)}",
                    style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "在「存款」页设置每月目标后，这里会显示达成比例",
                    style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
            Spacer(Modifier.height(10.dp))
            // 标题 + 柱形/折线切换 + 当前区间（点「区间」可改显示哪些月份）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "存款趋势",
                    style = EcoType.SectionTitle.copy(fontSize = 12.sp),
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                EcoSegmented(
                    options = listOf("柱形", "折线"),
                    selectedIndex = if (style == TrendChartStyle.BAR) 0 else 1,
                    onSelect = {
                        onStyleChange(if (it == 0) TrendChartStyle.BAR else TrendChartStyle.LINE)
                    },
                    modifier = Modifier.width(74.dp),
                    height = 24.dp,
                    small = true,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    savingsSpanLabel(trend),
                    style = EcoType.PageNote.copy(fontSize = 8.sp),
                    color = c.quiet,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                EcoTextAction(text = "区间", onClick = onOpenRange)
            }
            Spacer(Modifier.height(8.dp))
            SavingsChart(trend, style)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    trend.firstOrNull()?.let { "${it.month.year}.${it.month.monthValue}" } ?: "",
                    style = EcoType.PageNote.copy(fontSize = 8.sp),
                    color = c.quiet,
                )
                Text(
                    "正数 = 当月新增 · 负数 = 当月净流出",
                    style = EcoType.PageNote.copy(fontSize = 8.sp),
                    color = c.quiet,
                )
                Text(
                    trend.lastOrNull()?.let { "${it.month.year}.${it.month.monthValue}" } ?: "",
                    style = EcoType.PageNote.copy(fontSize = 8.sp),
                    color = c.quiet,
                )
            }
        }
    }
}

/** 逐月存款明细卡：与趋势图拆成两块，层次对齐「每日支出 / 分类预算与占比」 */
@Composable
private fun SavingsMonthlyCard(trend: List<DepositMath.MonthSaving>, goalCents: Long) {
    val c = MaterialTheme.eco
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (trend.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    EmptyNote("暂无存款记录")
                }
            } else {
                // 新的月份在上
                trend.reversed().forEachIndexed { index, month ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
                    }
                    SavingsMonthRow(month = month, goalCents = goalCents)
                }
            }
        }
    }
}

/** 单月存款行：月份 / 新增金额 / 达成比例（负数红显） */
@Composable
private fun SavingsMonthRow(month: DepositMath.MonthSaving, goalCents: Long) {
    val c = MaterialTheme.eco
    val saving = month.savingCents
    val ratio = month.ratio(goalCents)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${month.month.year}年${month.month.monthValue}月",
            style = EcoType.PageNote.copy(fontSize = 9.sp),
            color = c.muted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            Money.formatYuanGrouped(saving),
            style = EcoType.RowTrail.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = if (saving < 0L) c.clay else c.jade,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            ratio?.let { "${(it * 100f).roundToInt()}%" } ?: "—",
            style = EcoType.PageNote.copy(fontSize = 9.sp),
            // 达成率为负（净流出/没达标反向）用红色，正数达标才用玉色
            color = when {
                ratio == null -> c.quiet
                ratio < 0f -> c.clay
                ratio >= 1f -> c.jade
                else -> c.muted
            },
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** 存款趋势图：柱形（零线上下分正负）/ 折线（点色区分正负） */
@Composable
private fun SavingsChart(trend: List<DepositMath.MonthSaving>, style: TrendChartStyle) {
    val c = MaterialTheme.eco
    val values = trend.map { it.savingCents }
    if (values.isEmpty()) return
    val maxAbs = maxOf(1L, values.maxOfOrNull { kotlin.math.abs(it) } ?: 1L)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(132.dp),
    ) {
        val count = values.size
        val midY = size.height / 2f
        val half = size.height / 2f - 4.dp.toPx()
        drawLine(
            color = c.ruleStrong,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx(),
        )
        fun yAt(value: Long): Float =
            midY - (value.toFloat() / maxAbs).coerceIn(-1f, 1f) * half

        if (style == TrendChartStyle.BAR) {
            val slot = size.width / count
            val barWidth = slot * 0.52f
            values.forEachIndexed { index, value ->
                val height = (kotlin.math.abs(value).toFloat() / maxAbs) * half
                val x = index * slot + (slot - barWidth) / 2f
                val color = if (value >= 0L) c.jade else c.clay
                val h = if (value == 0L) 1.dp.toPx() else height.coerceAtLeast(1.5.dp.toPx())
                drawRoundRect(
                    color = color.copy(alpha = 0.82f),
                    topLeft = Offset(x, if (value >= 0L) midY - h else midY),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        } else {
            val pad = 5.dp.toPx()
            val step = if (count <= 1) 0f else (size.width - pad * 2f) / (count - 1)
            fun xAt(index: Int): Float = if (count <= 1) size.width / 2f else pad + index * step
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = xAt(index)
                val y = yAt(value)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = c.blue,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            values.forEachIndexed { index, value ->
                drawCircle(
                    color = if (value >= 0L) c.jade else c.clay,
                    radius = 2.6.dp.toPx(),
                    center = Offset(xAt(index), yAt(value)),
                )
            }
        }
    }
}

/** 趋势区间文案：2025/12 – 2026/10 */
private fun savingsSpanLabel(trend: List<DepositMath.MonthSaving>): String {
    val first = trend.firstOrNull()?.month ?: return ""
    val last = trend.lastOrNull()?.month ?: return ""
    return "${savingsMonthTag(first)} – ${savingsMonthTag(last)}"
}

private fun savingsMonthTag(month: YearMonth?): String =
    month?.let { "${it.year}/${it.monthValue.toString().padStart(2, '0')}" } ?: "最早"

/**
 * 存款趋势区间设置：指定月数（3/6/12/24/36）、全部历史，或手动起止年月。
 * 与「存款」页共用同一份设置，改完两边一致。
 */
@Composable
private fun SavingsRangeSheet(
    showAll: Boolean,
    rangeMonths: Int,
    start: YearMonth?,
    end: YearMonth?,
    trend: List<DepositMath.MonthSaving>,
    onApply: (all: Boolean, months: Int, start: YearMonth?, end: YearMonth?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    var all by remember(showAll) { mutableStateOf(showAll) }
    var months by remember(rangeMonths) { mutableIntStateOf(rangeMonths) }
    var draftStart by remember(start) { mutableStateOf(start) }
    var draftEnd by remember(end) { mutableStateOf(end) }
    var pickerOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableIntStateOf(-1) }

    if (!pickerOpen) {
        EcoSheet(
            onDismiss = onDismiss,
            title = "存款趋势区间",
            subtitle = "只影响存款趋势显示哪些月份；与「存款」页共用这份设置",
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "显示范围",
                        style = EcoType.RowTitle.copy(fontSize = 12.sp),
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    EcoSegmented(
                        options = listOf("指定月份", "全部"),
                        selectedIndex = if (all) 1 else 0,
                        onSelect = { all = it == 1 },
                        modifier = Modifier.width(124.dp),
                        height = 28.dp,
                        small = true,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (all) {
                    Text(
                        "显示从最早一笔账单所在月到本月的全部月份",
                        style = EcoType.PageNote.copy(fontSize = 9.sp),
                        color = c.muted,
                    )
                } else {
                    Text(
                        "显示月数",
                        style = EcoType.RowTitle.copy(fontSize = 12.sp),
                        color = c.ink,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(3, 6, 9, 12, 18, 24, 36, 48, 60).forEach { n ->
                            EcoChip(
                                text = "$n 个月",
                                selected = months == n,
                                onClick = { months = n },
                                height = 28.dp,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "起止年月",
                            style = EcoType.RowTitle.copy(fontSize = 12.sp),
                            color = c.ink,
                            modifier = Modifier.weight(1f),
                        )
                        if (draftStart != null || draftEnd != null) {
                            EcoTextAction(
                                text = "清除",
                                onClick = {
                                    draftStart = null
                                    draftEnd = null
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EcoChip(
                            text = savingsMonthTag(draftStart),
                            selected = draftStart != null,
                            onClick = {
                                picking = 0
                                pickerOpen = true
                            },
                            height = 30.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "–",
                            style = EcoType.PageNote.copy(fontSize = 11.sp),
                            color = c.quiet,
                        )
                        Spacer(Modifier.width(6.dp))
                        EcoChip(
                            text = draftEnd?.let { savingsMonthTag(it) } ?: "本月",
                            selected = draftEnd != null,
                            onClick = {
                                picking = 1
                                pickerOpen = true
                            },
                            height = 30.dp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "设了起止年月就按它显示；清除后回到最近 N 个月。",
                        style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                        color = c.quiet,
                    )
                }
                Spacer(Modifier.height(14.dp))
                EcoPrimaryButton(
                    text = "保存",
                    onClick = {
                        onApply(
                            all,
                            months,
                            if (all) null else draftStart,
                            if (all) null else draftEnd,
                        )
                    },
                )
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    if (pickerOpen) {
        val fallbackStart = start ?: YearMonth.now().minusMonths(rangeMonths.toLong() - 1L)
        PeriodPickerSheet(
            mode = PeriodMode.MONTH,
            current = if (picking == 0) (draftStart ?: trend.firstOrNull()?.month ?: fallbackStart) else (draftEnd ?: YearMonth.now()),
            onSelectMonth = { month ->
                if (picking == 0) draftStart = month else draftEnd = month
                pickerOpen = false
                picking = -1
            },
            onSelectYear = { year ->
                if (picking == 0) draftStart = (draftStart ?: fallbackStart).withYear(year)
                else draftEnd = (draftEnd ?: YearMonth.now()).withYear(year)
                pickerOpen = false
                picking = -1
            },
            onDismiss = {
                pickerOpen = false
                picking = -1
            },
        )
    }
}

// ---------- 小工具 ----------

/**
 * 预算对比的行数据 = **设了预算的支出分类** ∪ **本期有账单记录的支出分类**（按分类名去重）。
 *
 * - 只设了预算、本期没花的分也保留（预算 0 实际，点名"预算没用上"）；
 * - 本期有支出但**没设预算**的分类同样要出现（预算记 0、占比 0%），否则用户看不到这笔钱花在哪
 *   （用户反馈：清单原来只列了设过预算的分类）；
 * - 预算/实际都取支出侧：收入分类即便设了预算也不进清单；年视角预算 ×12；
 * - 排序：实际降序 → 预算降序 → 分类名升序（同额时有稳定顺序，不会随查询顺序抖动）。
 */
internal fun buildRatioRows(
    categoryBudgets: Map<Long, Long>,
    categories: List<CategoryEntity>,
    byCategory: List<CategorySumRow>,
    mode: PeriodMode,
): List<BudgetRatioRow> {
    val byId = categories.associateBy { it.id }
    val actualByName = byCategory.associate { it.categoryName to it.totalCents }
    val multiplier = if (mode == PeriodMode.YEAR) 12L else 1L
    val rows = LinkedHashMap<String, BudgetRatioRow>()
    // 1) 设了预算的支出分类（本期没花也要列出来）
    categoryBudgets.forEach { (id, cents) ->
        val category = byId[id] ?: return@forEach
        if (category.type != TxType.EXPENSE || cents <= 0L) return@forEach
        rows[category.name] = BudgetRatioRow(
            name = category.name,
            actualCents = actualByName[category.name] ?: 0L,
            budgetCents = cents * multiplier,
        )
    }
    // 2) 本期有账单记录的支出分类（可能没设预算 → 预算 0）
    byCategory.forEach { sum ->
        if (sum.totalCents <= 0L || sum.categoryName.isBlank()) return@forEach
        if (rows.containsKey(sum.categoryName)) return@forEach
        val category = byId[sum.categoryId]
        if (category != null && category.type != TxType.EXPENSE) return@forEach
        rows[sum.categoryName] = BudgetRatioRow(
            name = sum.categoryName,
            actualCents = sum.totalCents,
            budgetCents = 0L,
        )
    }
    return rows.values.sortedWith(
        compareByDescending<BudgetRatioRow> { it.actualCents }
            .thenByDescending { it.budgetCents }
            .thenBy { it.name },
    )
}

/** 占比（0..100）；分母 ≤ 0 时返回 0 */
private fun ratioPercent(part: Long, total: Long): Float =
    if (total <= 0L) 0f else part * 100f / total

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = EcoType.PageNote.copy(fontSize = 9.5.sp, lineHeight = 15.sp),
        color = MaterialTheme.eco.quiet,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 18.dp),
    )
}
