package com.controleco.app.ui.budget

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.PeriodMode
import com.controleco.app.data.budget.Budget
import com.controleco.app.data.budget.MonthBudgetPlan
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.PeriodPickerSheet
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

/**
 * 按月预算（功能5）：每个月的总预算与各一级分类预算都能单独设置。
 *
 * 没有单独设置的月份自动回落到全局月预算；「清除本月设置」把该月恢复成全局口径。
 */
@Composable
fun MonthBudgetScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val plans by vm.budgetPlans.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val globalMonthly by vm.globalMonthlyBudgetCents.collectAsStateWithLifecycle()
    val dailyMode by vm.dailyBudgetMode.collectAsStateWithLifecycle()
    val monthStartDay by vm.monthStartDay.collectAsStateWithLifecycle()

    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var showPicker by remember { mutableStateOf(false) }
    val plan = plans[month]
    val expenseTop = remember(categories) {
        categories.filter { it.type == TxType.EXPENSE && it.parentId == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }
    val globalFallback = if (plan?.hasTotal == true) 0L else globalMonthly

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "MONTH BUDGET / ${month.year}.${month.monthValue}",
            title = "按月预算",
            onBack = onBack,
            titleLeading = {
                // 标题旁的年月快捷入口：点开可选任意年/月查看该月预算
                Text(
                    "${month.year}年${month.monthValue}月",
                    style = EcoType.PageNote.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.eco.blueDeep,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showPicker = true }
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                )
            },
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EcoIconButton(
                        icon = EcoIcons.Back,
                        contentDescription = "上个月",
                        onClick = { month = month.minusMonths(1) },
                        size = 32.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    EcoIconButton(
                        icon = EcoIcons.Next,
                        contentDescription = "下个月",
                        onClick = { month = month.plusMonths(1) },
                        size = 32.dp,
                    )
                }
            },
        )

        // 换月时重建整块表单状态，避免把上个月的输入带过来
        key(month) {
            MonthBudgetForm(
                month = month,
                vm = vm,
                plan = plan,
                expenseTop = expenseTop.map { it.id to it.name },
                globalFallbackCents = globalFallback,
                globalMonthlyCents = globalMonthly,
                dailyMode = dailyMode,
                days = MonthPeriod.of(today, monthStartDay).days,
            )
        }
    }

    // 年/月快捷选择器：选完直接切到该月预算
    if (showPicker) {
        PeriodPickerSheet(
            mode = PeriodMode.MONTH,
            current = month,
            onSelectMonth = { month = it },
            onSelectYear = { month = month.withYear(it) },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun MonthBudgetForm(
    month: YearMonth,
    vm: LedgerViewModel,
    plan: MonthBudgetPlan?,
    expenseTop: List<Pair<Long, String>>,
    globalFallbackCents: Long,
    globalMonthlyCents: Long,
    dailyMode: Boolean,
    days: Int,
) {
    var totalText by remember { mutableStateOf(plan?.totalCents?.takeIf { it > 0L }?.let { Money.formatCents(it) } ?: "") }
    var globalText by remember {
        mutableStateOf(globalMonthlyCents.takeIf { it > 0L }?.let { Money.formatCents(it) } ?: "")
    }
    val categoryTexts = remember { mutableStateMapOf<Long, String>() }
    expenseTop.forEach { (id, _) ->
        if (!categoryTexts.containsKey(id)) {
            categoryTexts[id] = plan?.amountOf(id)?.takeIf { it > 0L }?.let { Money.formatCents(it) } ?: ""
        }
    }
    val c = MaterialTheme.eco
    // 实时小字：分类预算合计 + 总预算中还能分给分类的剩余
    val categoryTotalCents = expenseTop.sumOf { (id, _) ->
        categoryTexts[id].orEmpty().takeIf { it.isNotBlank() }?.let { Money.parseCents(it) } ?: 0L
    }
    val effectiveTotalCents = totalText.takeIf { it.isNotBlank() }?.let { Money.parseCents(it) } ?: globalFallbackCents
    val allocatableCents = effectiveTotalCents - categoryTotalCents

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = ecoBottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "global") {
            EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    EcoMoneyField(
                        label = "全局月预算",
                        value = globalText,
                        onValueChange = { globalText = it },
                        supporting = if (dailyMode) {
                            "日预算按本期 $days 天自动计算：约 " +
                                Money.formatYuanGroupedFixed(Budget.dailyEquivalent(globalMonthlyCents, days)) + " / 天"
                        } else {
                            "未单独设置的月份都沿用此金额"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    EcoGhostButton(
                        text = "保存全局预算",
                        onClick = {
                            val cents = globalText.takeIf { it.isNotBlank() }?.let { Money.parseCents(it) }
                            if (cents == null && globalText.isNotBlank()) {
                                EcoToast.show("全局预算格式不正确", EcoPillTone.Bad)
                                return@EcoGhostButton
                            }
                            vm.setMonthlyBudget(cents ?: 0L)
                            EcoToast.show("已保存全局预算")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "total") {
            EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    EcoMoneyField(
                        label = "${month.year} 年 ${month.monthValue} 月总预算",
                        value = totalText,
                        onValueChange = { totalText = it },
                        supporting = if (globalFallbackCents > 0L) {
                            "留空 = 使用全局月预算 ${Money.formatYuanGroupedFixed(globalFallbackCents)}"
                        } else {
                            "留空 = 沿用全局月预算（当前未设置）"
                        },
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "分类预算总额 ${Money.formatYuanGroupedFixed(categoryTotalCents)}",
                            style = EcoType.PageNote.copy(fontSize = 9.sp),
                            color = c.quiet,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "剩余可分配 ${Money.formatYuanGroupedFixed(allocatableCents)}",
                            style = EcoType.PageNote.copy(fontSize = 9.sp),
                            color = if (allocatableCents < 0L) c.clay else c.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        item(key = "head") {
            EcoSectionHead(title = "本月分类预算", aside = "CATEGORIES")
        }

        if (expenseTop.isEmpty()) {
            item(key = "empty") {
                EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                    EcoEmpty(title = "暂无支出分类", icon = EcoIcons.Bowl)
                }
            }
        } else {
            expenseTop.forEachIndexed { index, (id, name) ->
                item(key = "cat-$id") {
                    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp)) {
                            EcoMoneyField(
                                label = name,
                                value = categoryTexts[id].orEmpty(),
                                onValueChange = { categoryTexts[id] = it },
                                placeholder = "不设",
                            )
                        }
                    }
                }
                if (index != expenseTop.lastIndex) {
                    item(key = "gap-$id") { Spacer(Modifier.height(2.dp)) }
                }
            }
        }

        item(key = "note") {
            EcoPageNote(
                "每月预算各自保存，查账时自动显示对应月份；留空的月份沿用全局月预算。" +
                    "「清除本月设置」会让本月恢复为全局口径。",
            )
        }

        item(key = "save") {
            EcoPrimaryButton(
                text = "保存 ${month.monthValue} 月预算",
                onClick = {
                    val total = totalText.takeIf { it.isNotBlank() }?.let { Money.parseCents(it) }
                    if (total == null && totalText.isNotBlank()) {
                        EcoToast.show("总预算格式不正确", EcoPillTone.Bad)
                        return@EcoPrimaryButton
                    }
                    vm.setMonthlyBudgetFor(month, total ?: 0L)
                    expenseTop.forEach { (id, _) ->
                        val cents = categoryTexts[id].orEmpty().takeIf { it.isNotBlank() }?.let { Money.parseCents(it) }
                        vm.setCategoryBudgetFor(month, id, cents ?: 0L)
                    }
                    EcoToast.show("已保存")
                },
            )
        }
        item(key = "clear") {
            EcoGhostButton(
                text = "清除本月设置（回到全局）",
                onClick = {
                    vm.setMonthlyBudgetFor(month, 0L)
                    expenseTop.forEach { (id, _) -> vm.setCategoryBudgetFor(month, id, 0L) }
                    totalText = ""
                    categoryTexts.keys.toList().forEach { categoryTexts[it] = "" }
                    EcoToast.show("已回到全局预算")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
