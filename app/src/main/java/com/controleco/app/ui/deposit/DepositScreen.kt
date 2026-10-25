package com.controleco.app.ui.deposit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.deposit.DepositMath
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoProgressBar
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.YearMonth
import kotlin.math.roundToInt

/**
 * 存款（功能7）：设置每月存款目标，显示每月实际新增存款（收入 − 支出）与达成情况。
 *
 * 口径与统计页「存款分析」一致：支出排除「已退款」，收入含退款到账；数据源是近 12 个月的账单。
 */
@Composable
fun DepositScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val trendState by vm.depositTrend.collectAsStateWithLifecycle()
    val trend = trendState ?: return
    val current by vm.currentMonthSaving.collectAsStateWithLifecycle()
    val goal by vm.depositGoalCents.collectAsStateWithLifecycle()
    val monthGoals by vm.depositMonthGoals.collectAsStateWithLifecycle()
    val planStart by vm.depositPlanStartMonth.collectAsStateWithLifecycle()
    val rangeMonths by vm.depositRangeMonths.collectAsStateWithLifecycle()
    val showAll by vm.depositShowAll.collectAsStateWithLifecycle()
    var showRangeSheet by remember { mutableStateOf(false) }
    var rangeDraft by remember { mutableStateOf(12) }
    var goalText by remember(goal) {
        mutableStateOf(if (goal > 0L) Money.formatCents(goal) else "")
    }
    var editingMonth by remember { mutableStateOf<YearMonth?>(null) }
    var monthGoalText by remember { mutableStateOf("") }
    fun goalOf(month: YearMonth): Long = monthGoals[month] ?: goal

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "SAVINGS / TARGET",
            title = "存款",
            onBack = onBack,
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 22.dp,
                end = 22.dp,
                top = 6.dp,
                bottom = ecoBottomContentPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "current") {
                CurrentSavingCard(
                    current = current,
                    goalCents = current?.month?.let { goalOf(it) } ?: goal,
                )
            }
            item(key = "goal") {
                EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        EcoMoneyField(
                            label = "默认每月存款目标",
                            value = goalText,
                            onValueChange = { goalText = it },
                            placeholder = "0.00",
                            supporting = "没单独设置的月份都用这个目标；在下方每月卡片里可以设不同金额，留空 = 不设目标",
                        )
                        Spacer(Modifier.height(10.dp))
                        EcoPrimaryButton(
                            text = "保存目标",
                            onClick = {
                                val text = goalText.trim()
                                val cents = text.takeIf { it.isNotBlank() }?.let { Money.parseCents(it) }
                                if (text.isNotBlank() && cents == null) {
                                    EcoToast.show("目标金额格式不正确", EcoPillTone.Bad)
                                    return@EcoPrimaryButton
                                }
                                vm.setDepositGoal(cents ?: 0L)
                                EcoToast.show("已保存存款目标")
                            },
                        )
                    }
                }
            }

            item(key = "head") {
                EcoSectionHead(
                    title = "每月存款",
                    aside = "MONTHLY / TARGET",
                    trailing = {
                        Spacer(Modifier.width(10.dp))
                        EcoSegmented(
                            options = listOf("区间", "全部"),
                            selectedIndex = if (showAll) 1 else 0,
                            onSelect = { vm.setDepositShowAll(it == 1) },
                            modifier = Modifier.width(94.dp),
                            height = 26.dp,
                            small = true,
                        )
                    },
                )
            }

            item(key = "range") {
                // 区间模式：月份数可手动调整（默认 12）；「全部」模式从最早一笔账单开始
                val startMonth = trend.firstOrNull()?.month
                val note = when {
                    showAll -> "当前显示全部月份：从最早一笔账单所在月到本月"
                    startMonth != null -> "当前显示最近 $rangeMonths 个月：${startMonth.year} 年 " +
                        "${startMonth.monthValue} 月起至本月"
                    else -> "当前显示最近 $rangeMonths 个月"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        EcoPageNote(
                            if (!showAll && planStart != null) {
                                "$note（存款计划开始时间可在设置里修改）"
                            } else {
                                note
                            },
                        )
                    }
                    if (!showAll) {
                        EcoTextAction(
                            text = "调整月份数",
                            onClick = {
                                rangeDraft = rangeMonths
                                showRangeSheet = true
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            if (trend.none { it.incomeCents != 0L || it.expenseCents != 0L }) {
                item(key = "empty") {
                    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                        EcoEmpty(
                            title = "还没有存款记录",
                            body = "记一笔收入或支出后，这里会显示每月实际新增存款",
                            icon = EcoIcons.Wallet,
                        )
                    }
                }
            } else {
                // 新的月份在上
                trend.reversed().forEachIndexed { index, month ->
                    item(key = "month-${month.month}") {
                        MonthSavingCard(
                            month = month,
                            goalCents = goalOf(month.month),
                            custom = monthGoals.containsKey(month.month),
                            onSetGoal = {
                                editingMonth = month.month
                                // 只回填「单独设置过」的月份（0 显示为 0）；未单独设置则留空
                                monthGoalText = monthGoals[month.month]
                                    ?.let { Money.formatCents(it) }
                                    .orEmpty()
                            },
                        )
                    }
                    if (index != trend.lastIndex) {
                        item(key = "gap-${month.month}") { Spacer(Modifier.height(2.dp)) }
                    }
                }
            }

            item(key = "note") {
                EcoPageNote(
                    "当月实际新增存款 = 当月收入 − 当月支出（已退款的支出不计入）。" +
                        "目标达成比例 = 当月新增存款 ÷ 存款目标；每月可设不同目标，未设置的月份用默认目标。",
                )
            }
        }
    }

    // 单月目标编辑（每月存款可以设不同金额）
    editingMonth?.let { month ->
        EcoSheet(
            onDismiss = { editingMonth = null },
            title = "${month.year} 年 ${month.monthValue} 月存款目标",
            subtitle = "只影响这一个月的达成比例",
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
                EcoMoneyField(
                    label = "本月目标",
                    value = monthGoalText,
                    onValueChange = { monthGoalText = it },
                    placeholder = "0.00",
                    supporting = "填 0 = 本月不设存款目标；留空 = 不做单独设置，使用默认目标" +
                        if (goal > 0L) " ${Money.formatYuanGroupedFixed(goal)}" else "",
                )
                Spacer(Modifier.height(12.dp))
                EcoPrimaryButton(
                    text = "保存本月目标",
                    onClick = {
                        val text = monthGoalText.trim()
                        val cents = text.takeIf { it.isNotBlank() }?.let { Money.parseCents(it) }
                        if (text.isNotBlank() && cents == null) {
                            EcoToast.show("目标金额格式不正确", EcoPillTone.Bad)
                            return@EcoPrimaryButton
                        }
                        if (cents == null) {
                            vm.setDepositGoalFor(month, null)
                            editingMonth = null
                            EcoToast.show("已恢复默认目标")
                        } else {
                            vm.setDepositGoalFor(month, cents)
                            editingMonth = null
                            EcoToast.show("已保存 ${month.monthValue} 月目标")
                        }
                    },
                )
                if (monthGoals.containsKey(month)) {
                    Spacer(Modifier.height(8.dp))
                    EcoGhostButton(
                        text = "用默认目标（清除本月设置）",
                        onClick = {
                            vm.setDepositGoalFor(month, null)
                            editingMonth = null
                            EcoToast.show("已恢复默认目标")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    // 区间模式：手动设置显示月数
    if (showRangeSheet) {
        val c = MaterialTheme.eco
        EcoSheet(
            onDismiss = { showRangeSheet = false },
            title = "存款显示月份区间",
            subtitle = "只影响「每月存款」列表显示多少个月，不影响统计与备份",
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "显示月数",
                        style = EcoType.RowTitle.copy(fontSize = 12.sp),
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    EcoIconButton(
                        icon = EcoIcons.Back,
                        contentDescription = "减少一个月",
                        onClick = { if (rangeDraft > 1) rangeDraft-- },
                        size = 30.dp,
                        enabled = rangeDraft > 1,
                    )
                    Text(
                        "$rangeDraft 个月",
                        style = EcoType.RowTitle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        color = c.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(90.dp),
                    )
                    EcoIconButton(
                        icon = EcoIcons.Next,
                        contentDescription = "增加一个月",
                        onClick = { if (rangeDraft < 60) rangeDraft++ },
                        size = 30.dp,
                        enabled = rangeDraft < 60,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 6, 12, 24, 36).forEach { n ->
                        EcoChip(
                            text = "$n",
                            selected = rangeDraft == n,
                            onClick = { rangeDraft = n },
                            height = 28.dp,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                EcoPrimaryButton(
                    text = "保存显示区间",
                    onClick = {
                        vm.setDepositRangeMonths(rangeDraft)
                        showRangeSheet = false
                        EcoToast.show("已保存：每月存款显示最近 $rangeDraft 个月")
                    },
                )
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/** 当月存款卡：新增金额 + 达成比例 + 目标进度 */
@Composable
private fun CurrentSavingCard(current: DepositMath.MonthSaving?, goalCents: Long) {
    val c = MaterialTheme.eco
    val saving = current?.savingCents ?: 0L
    val ratio = current?.ratio(goalCents)
    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "本月实际新增存款",
                        style = EcoType.Micro.copy(fontSize = 9.sp),
                        color = c.quiet,
                    )
                    Spacer(Modifier.height(3.dp))
                    EcoAutoMoney(
                        text = Money.formatYuanGroupedFixed(saving),
                        style = EcoType.ConclusionAmount.copy(fontSize = 26.sp),
                        color = if (saving < 0L) c.clay else c.jade,
                        minSize = 14.sp,
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
            if (goalCents > 0L) {
                Spacer(Modifier.height(10.dp))
                EcoProgressBar(
                    progress = saving.toFloat() / goalCents.toFloat(),
                    height = 8.dp,
                )
            }
        }
    }
}

/** 单月存款卡：月份 / 新增金额 / 达成比例 / 目标进度（点「目标」可改这个月的金额） */
@Composable
private fun MonthSavingCard(
    month: DepositMath.MonthSaving,
    goalCents: Long,
    custom: Boolean,
    onSetGoal: () -> Unit,
) {
    val c = MaterialTheme.eco
    val saving = month.savingCents
    val ratio = month.ratio(goalCents)
    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${month.month.year} 年 ${month.month.monthValue} 月",
                        style = EcoType.RowTitle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                        color = c.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "收入 ${Money.formatYuanGrouped(month.incomeCents)} · 支出 ${Money.formatYuanGrouped(month.expenseCents)}",
                            style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                            color = c.quiet,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                goalCents > 0L && custom -> "目标 ${Money.formatYuanGrouped(goalCents)}"
                                goalCents > 0L -> "目标 ${Money.formatYuanGrouped(goalCents)}（默认）"
                                // 单独把某月目标设成 0 = 这个月不设存款目标
                                custom -> "目标 0（不设）"
                                else -> "设目标"
                            },
                            style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                            color = c.blueDeep,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .ecoPressable(scaleTo = 0.96f, onClick = onSetGoal)
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    Money.formatYuanGrouped(saving),
                    style = EcoType.CatAmount.copy(fontSize = 14.sp),
                    color = if (saving < 0L) c.clay else c.jade,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    ratio?.let { "${(it * 100f).roundToInt()}%" } ?: "—",
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
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
            if (goalCents > 0L) {
                Spacer(Modifier.height(8.dp))
                EcoProgressBar(
                    progress = saving.toFloat() / goalCents.toFloat(),
                    height = 6.dp,
                )
            }
        }
    }
}
