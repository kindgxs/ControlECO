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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.deposit.DepositMath
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoProgressBar
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
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
    var goalText by remember(goal) {
        mutableStateOf(if (goal > 0L) Money.formatCents(goal) else "")
    }

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
                    goalCents = goal,
                )
            }
            item(key = "goal") {
                EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        EcoMoneyField(
                            label = "每月存款目标",
                            value = goalText,
                            onValueChange = { goalText = it },
                            placeholder = "0.00",
                            supporting = "目标用于计算每月的存款达成比例，留空 = 不设目标",
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
                )
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
                        MonthSavingCard(month = month, goalCents = goal)
                    }
                    if (index != trend.lastIndex) {
                        item(key = "gap-${month.month}") { Spacer(Modifier.height(2.dp)) }
                    }
                }
            }

            item(key = "note") {
                EcoPageNote(
                    "当月实际新增存款 = 当月收入 − 当月支出（已退款的支出不计入）。" +
                        "目标达成比例 = 当月新增存款 ÷ 存款目标。",
                )
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

/** 单月存款卡：月份 / 新增金额 / 达成比例 / 进度轨 */
@Composable
private fun MonthSavingCard(month: DepositMath.MonthSaving, goalCents: Long) {
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
                    Text(
                        "收入 ${Money.formatYuanGrouped(month.incomeCents)} · 支出 ${Money.formatYuanGrouped(month.expenseCents)}",
                        style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                        color = c.quiet,
                        maxLines = 1,
                    )
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
                    color = if (ratio == null) c.quiet else if (ratio >= 1f) c.jade else c.muted,
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
