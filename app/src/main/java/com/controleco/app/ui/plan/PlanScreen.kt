package com.controleco.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.CategoryTree
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.InstallmentPlanEntity
import com.controleco.app.data.db.RecurringPlanEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.data.plan.InstallmentMath
import com.controleco.app.data.plan.RecurringEndMode
import com.controleco.app.data.plan.RecurringFrequency
import com.controleco.app.data.plan.RecurringMath
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoDangerButton
import com.controleco.app.ui.eco.EcoDialog
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoSwitchRow
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.transactions.TxSelectField
import com.controleco.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

private enum class PlanTab(val label: String) {
    INSTALLMENT("信用卡分期"),
    RECURRING("周期记账"),
}

/** 分期编辑卡提交的数据 */
private data class InstallmentDraft(
    val id: Long,
    val title: String,
    val accountId: Long,
    val categoryId: Long,
    val principalCents: Long,
    val interestCents: Long,
    val periods: Int,
    val firstDate: LocalDate,
    val postTimeMinutes: Int,
    val deductAsset: Boolean,
    val excludeAssetPeriods: String,
    /** 空 = 每期都计入账单和收支统计 */
    val excludeStatsPeriods: String,
    val note: String,
)

/** 周期编辑卡提交的数据 */
private data class RecurringDraft(
    val id: Long,
    val title: String,
    val type: TxType,
    val amountCents: Long,
    val categoryId: Long,
    val accountId: Long,
    val frequency: RecurringFrequency,
    val intervalCount: Int,
    val firstDate: LocalDate,
    val postTimeMinutes: Int,
    val deductAsset: Boolean,
    val excludeAssetPeriods: String,
    /** 空 = 每次都计入账单和收支统计 */
    val excludeStatsPeriods: String,
    val endMode: RecurringEndMode,
    val endCount: Int,
    val endDate: LocalDate?,
    val note: String,
)

/**
 * 分期 · 周期（功能2）：信用卡分期与固定周期账单。
 *
 * 分期每期 = 本金份额 + 利息份额；总利息留空时按「每期 0.60% × 期数」自动算。
 * 两种计划都支持「只记账、不扣资产」，并且都可以设置出账时间：
 * 到「计划日期 + 出账时间」才自动生成账单（早于该时刻不会提前入账）。
 */
@Composable
fun PlanScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val installments by vm.installmentPlans.collectAsStateWithLifecycle()
    val recurring by vm.recurringPlans.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showInstallmentEditor by remember { mutableStateOf(false) }
    var showRecurringEditor by remember { mutableStateOf(false) }
    var editingInstallment by remember { mutableStateOf<InstallmentPlanEntity?>(null) }
    var editingRecurring by remember { mutableStateOf<RecurringPlanEntity?>(null) }

    val accountName = remember(accounts) { accounts.associate { it.id to it.name } }
    val categoryPath = remember(categories) {
        val byId = categories.associateBy { it.id }
        categories.associate { it.id to (it.parentId?.let { p -> byId[p]?.name }?.let { n -> "$n / ${it.name}" } ?: it.name) }
    }

    fun openInstallment(plan: InstallmentPlanEntity?) {
        editingInstallment = plan
        showInstallmentEditor = true
    }

    fun openRecurring(plan: RecurringPlanEntity?) {
        editingRecurring = plan
        showRecurringEditor = true
    }

    fun postInstallment(plan: InstallmentPlanEntity) {
        vm.postInstallment(plan.id) { err ->
            EcoToast.show(err ?: "已记下第 ${plan.postedPeriods + 1} 期", if (err == null) EcoPillTone.Good else EcoPillTone.Bad)
        }
    }

    fun postRecurring(plan: RecurringPlanEntity) {
        vm.postRecurring(plan.id) { err ->
            EcoToast.show(err ?: "已记一笔", if (err == null) EcoPillTone.Good else EcoPillTone.Bad)
        }
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(micro = "INSTALMENT / RECURRING", title = "分期 · 周期", onBack = onBack)

        EcoSegmented(
            options = PlanTab.entries.map { it.label },
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = ecoBottomContentPadding(extra = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tab == 0) {
                if (installments.isEmpty()) {
                    item(key = "empty") {
                        EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                            EcoEmpty(
                                title = "还没有分期计划",
                                body = "建立信用卡分期后，到出账时间会自动记一期",
                                icon = EcoIcons.Calendar,
                            )
                        }
                    }
                } else {
                    items(installments.size, key = { installments[it].id }) { index ->
                        val plan = installments[index]
                        InstallmentCard(
                            plan = plan,
                            accountName = accountName[plan.accountId] ?: "账户",
                            categoryName = categoryPath[plan.categoryId] ?: "分类",
                            onClick = { openInstallment(plan) },
                            onPost = { postInstallment(plan) },
                        )
                    }
                }
            } else {
                if (recurring.isEmpty()) {
                    item(key = "empty") {
                        EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                            EcoEmpty(
                                title = "还没有周期计划",
                                body = "建立周期账单后，到出账时间会自动记一笔",
                                icon = EcoIcons.Clock,
                            )
                        }
                    }
                } else {
                    items(recurring.size, key = { recurring[it].id }) { index ->
                        val plan = recurring[index]
                        RecurringCard(
                            plan = plan,
                            accountName = accountName[plan.accountId] ?: "账户",
                            categoryName = categoryPath[plan.categoryId] ?: "分类",
                            onClick = { openRecurring(plan) },
                            onPost = { postRecurring(plan) },
                        )
                    }
                }
            }
            item(key = "note") {
                EcoPageNote(
                    if (tab == 0) {
                        "每期金额 = 该期本金 + 该期利息；总利息留空时按每期 0.60% × 期数自动计算。" +
                            "账单只在「计划日期 + 出账时间」到点后生成，未出账的期数不计入流水；" +
                            "开启「只记账不扣资产」后，入账记录不会改变账户余额。"
                    } else {
                        "周期账单到「计划日期 + 出账时间」才生成并顺延到下一个周期；" +
                            "可设置永不结束、按次数结束或按日期结束。" +
                            "开启「只记账不扣资产」后，入账记录不会改变账户余额。"
                    },
                )
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
            EcoGhostButton(
                text = if (tab == 0) "新增分期计划" else "新增周期账单",
                onClick = { if (tab == 0) openInstallment(null) else openRecurring(null) },
                modifier = Modifier.fillMaxWidth(),
                height = 42.dp,
            )
        }
    }

    if (showInstallmentEditor) {
        InstallmentEditorSheet(
            initial = editingInstallment,
            accounts = accounts,
            categories = categories,
            onSave = { draft, onResult ->
                vm.saveInstallment(
                    id = draft.id,
                    title = draft.title,
                    accountId = draft.accountId,
                    categoryId = draft.categoryId,
                    totalPrincipalCents = draft.principalCents,
                    totalInterestCents = draft.interestCents,
                    periods = draft.periods,
                    firstDateMillis = RecurringMath.millisAt(draft.firstDate),
                    deductAsset = draft.deductAsset,
                    excludeAssetPeriods = draft.excludeAssetPeriods,
                    excludeStatsPeriods = draft.excludeStatsPeriods,
                    postTimeMinutes = draft.postTimeMinutes,
                    note = draft.note,
                    onResult = onResult,
                )
            },
            onDelete = editingInstallment?.let { plan ->
                val action: (Boolean, (String?) -> Unit) -> Unit = { rollback, onDone ->
                    vm.deleteInstallment(plan.id, rollback, onDone)
                }
                action
            },
            onClose = { showInstallmentEditor = false },
        )
    }

    if (showRecurringEditor) {
        RecurringEditorSheet(
            initial = editingRecurring,
            accounts = accounts,
            categories = categories,
            onSave = { draft, onResult ->
                vm.saveRecurring(
                    id = draft.id,
                    title = draft.title,
                    type = draft.type,
                    amountCents = draft.amountCents,
                    categoryId = draft.categoryId,
                    accountId = draft.accountId,
                    frequency = draft.frequency,
                    firstDateMillis = RecurringMath.millisAt(draft.firstDate),
                    deductAsset = draft.deductAsset,
                    intervalCount = draft.intervalCount,
                    postTimeMinutes = draft.postTimeMinutes,
                    endMode = draft.endMode,
                    endCount = draft.endCount,
                    endDateMillis = draft.endDate?.let { RecurringMath.millisAt(it) } ?: 0L,
                    excludeAssetPeriods = draft.excludeAssetPeriods,
                    excludeStatsPeriods = draft.excludeStatsPeriods,
                    note = draft.note,
                    onResult = onResult,
                )
            },
            onDelete = editingRecurring?.let { plan ->
                val action: (Boolean, (String?) -> Unit) -> Unit = { rollback, onDone ->
                    vm.deleteRecurring(plan.id, rollback, onDone)
                }
                action
            },
            onClose = { showRecurringEditor = false },
        )
    }
}

// ---------- 列表卡 ----------

@Composable
private fun InstallmentCard(
    plan: InstallmentPlanEntity,
    accountName: String,
    categoryName: String,
    onClick: () -> Unit,
    onPost: () -> Unit,
) {
    val c = MaterialTheme.eco
    val today = LocalDate.now()
    val firstDate = RecurringMath.dateOf(plan.firstDateMillis)
    val currentIndex = InstallmentMath.currentPeriod(firstDate, today, plan.periods)
    val done = plan.postedPeriods >= plan.periods
    val nextIndex = (plan.postedPeriods + 1).coerceAtMost(plan.periods)
    val nextDate = InstallmentMath.periodDate(firstDate, nextIndex)
    val timeText = InstallmentMath.timeText(plan.postTimeMinutes)
    // 只有到了「当期日期 + 出账时间」才允许手动记账，避免提前把未出账的期数记进流水
    val canPost = !done && System.currentTimeMillis() >=
        InstallmentMath.periodMillis(firstDate, nextIndex, plan.postTimeMinutes)
    val perCents = InstallmentMath.periodTotal(
        plan.totalPrincipalCents, plan.totalInterestCents, plan.periods, nextIndex,
    )
    val unposted = InstallmentMath.unpostedTotal(
        plan.totalPrincipalCents, plan.totalInterestCents, plan.periods, plan.postedPeriods,
    )
    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .ecoPressable(scaleTo = 0.99f, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plan.title,
                        style = EcoType.RowTitle,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (done) "已记完" else "当前第 ${currentIndex}/${plan.periods} 期",
                        style = EcoType.RowSub,
                        color = if (done) c.jade else c.muted,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(accountName).append(" · ").append(categoryName)
                        if (!plan.deductAsset) {
                            append(" · 全部只记账不扣资产")
                        } else {
                            val excluded = InstallmentMath.parseExcludedPeriods(plan.excludeAssetPeriods, plan.periods)
                            if (excluded.isNotEmpty()) {
                                append(" · 第 ")
                                append(InstallmentMath.encodeExcludedPeriods(excluded, plan.periods))
                                append(" 期不扣资产")
                            }
                        }
                    },
                    style = EcoType.RowSub,
                    color = c.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (done) {
                        "已全部记完 · 总还款 ${Money.formatYuanGroupedFixed(InstallmentMath.totalRepayment(plan.totalPrincipalCents, plan.totalInterestCents))}"
                    } else {
                        "已记 ${plan.postedPeriods} 期 · 下期 ${nextDate.monthValue}/${nextDate.dayOfMonth} $timeText · 每期 " +
                            Money.formatYuanGroupedFixed(perCents) +
                            " （本金 ${Money.formatYuanGroupedFixed(InstallmentMath.principalOf(plan.totalPrincipalCents, plan.periods, nextIndex))}" +
                            " + 利息 ${Money.formatYuanGroupedFixed(InstallmentMath.interestOf(plan.totalInterestCents, plan.periods, nextIndex))}）"
                    },
                    style = EcoType.PageNote,
                    color = c.quiet,
                    maxLines = 2,
                )
            }
            EcoRowDivider(inset = 16.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "未出账 ${Money.formatYuanGroupedFixed(unposted)}",
                    style = EcoType.PageNote,
                    color = if (unposted > 0L) c.amber else c.quiet,
                )
                Spacer(Modifier.weight(1f))
                EcoTextAction(
                    text = when {
                        done -> "已记完"
                        canPost -> "记一期"
                        else -> "未到出账"
                    },
                    onClick = onPost,
                    enabled = canPost,
                )
            }
        }
    }
}

@Composable
private fun RecurringCard(
    plan: RecurringPlanEntity,
    accountName: String,
    categoryName: String,
    onClick: () -> Unit,
    onPost: () -> Unit,
) {
    val c = MaterialTheme.eco
    val frequency = RecurringFrequency.of(plan.frequency)
    val endMode = RecurringEndMode.of(plan.endMode)
    val ended = plan.nextDateMillis >= RecurringMath.NO_NEXT
    val nextDate = if (ended) null else RecurringMath.dateOf(plan.nextDateMillis)
    val timeText = InstallmentMath.timeText(plan.postTimeMinutes)
    // 未到「下次日期 + 出账时间」不允许提前入账
    val canPost = !ended && plan.nextDateMillis <= System.currentTimeMillis()
    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .ecoPressable(scaleTo = 0.99f, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plan.title,
                        style = EcoType.RowTitle,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        (if (plan.type == TxType.EXPENSE) "-" else "+") + Money.formatYuanGroupedFixed(plan.amountCents),
                        style = EcoType.RowTitle,
                        color = if (plan.type == TxType.EXPENSE) c.clay else c.jade,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(accountName).append(" · ").append(categoryName)
                        append(" · ").append(RecurringMath.describe(frequency, plan.intervalCount))
                        if (!plan.deductAsset) append(" · 不扣资产")
                    },
                    style = EcoType.RowSub,
                    color = c.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (nextDate == null) {
                            "已结束"
                        } else {
                            "下次 ${nextDate.year}/${nextDate.monthValue}/${nextDate.dayOfMonth} $timeText"
                        },
                        style = EcoType.PageNote,
                        color = c.quiet,
                    )
                    if (endMode != RecurringEndMode.NEVER && !ended) {
                        Spacer(Modifier.weight(1f))
                        EcoPill(
                            if (endMode == RecurringEndMode.COUNT) {
                                "共 ${plan.endCount} 次"
                            } else {
                                "至 ${RecurringMath.dateOf(plan.endDateMillis)}"
                            },
                            EcoPillTone.Neutral,
                        )
                    }
                }
            }
            EcoRowDivider(inset = 16.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "首期 ${RecurringMath.dateOf(plan.firstDateMillis)}",
                    style = EcoType.PageNote,
                    color = c.quiet,
                )
                Spacer(Modifier.weight(1f))
                EcoTextAction(
                    text = when {
                        ended -> "已结束"
                        canPost -> "记一笔"
                        else -> "未到出账"
                    },
                    onClick = onPost,
                    enabled = canPost,
                )
            }
        }
    }
}

// ---------- 编辑卡 ----------

private fun yearOptions(): List<Pair<Long, String>> {
    val now = LocalDate.now().year
    return (now - 5..now + 5).map { it.toLong() to "$it 年" }
}

private fun monthOptions(): List<Pair<Long, String>> = (1..12).map { it.toLong() to "$it 月" }

private fun dayOptions(month: YearMonth): List<Pair<Long, String>> =
    (1..month.lengthOfMonth()).map { it.toLong() to "$it 日" }

/** 年 / 月 / 日三段下拉（首期 / 结束日期共用） */
@Composable
private fun DateDropdowns(
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
    yearLabel: String = "首期年份",
) {
    val month = YearMonth.of(date.year, date.monthValue)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TxSelectField(
            value = "${date.year} 年",
            options = yearOptions(),
            onSelect = { year ->
                val target = YearMonth.of(year.toInt(), date.monthValue)
                onChange(target.atDay(date.dayOfMonth.coerceAtMost(target.lengthOfMonth())))
            },
            label = yearLabel,
            modifier = Modifier.weight(1f),
        )
        TxSelectField(
            value = "${date.monthValue} 月",
            options = monthOptions(),
            onSelect = { m ->
                val target = YearMonth.of(date.year, m.toInt())
                onChange(target.atDay(date.dayOfMonth.coerceAtMost(target.lengthOfMonth())))
            },
            label = "月份",
            modifier = Modifier.weight(1f),
        )
        TxSelectField(
            value = "${date.dayOfMonth} 日",
            options = dayOptions(month),
            onSelect = { d -> onChange(month.atDay(d.toInt())) },
            label = "日期",
            modifier = Modifier.weight(1f),
        )
    }
}

/** 出账时间：小时 + 分钟（当天 0 点起的分钟数） */
@Composable
private fun TimeDropdowns(
    minutes: Int,
    onChange: (Int) -> Unit,
) {
    val hour = (minutes / 60).coerceIn(0, 23)
    val minute = (minutes % 60).coerceIn(0, 59)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TxSelectField(
            value = "%02d 时".format(hour),
            options = (0..23).map { it.toLong() to "%02d 时".format(it) },
            onSelect = { h -> onChange((h.toInt() * 60 + minute).coerceIn(0, 1439)) },
            label = "出账小时",
            modifier = Modifier.weight(1f),
        )
        TxSelectField(
            value = "%02d 分".format(minute),
            options = (0..59).map { it.toLong() to "%02d 分".format(it) },
            onSelect = { m -> onChange((hour * 60 + m.toInt()).coerceIn(0, 1439)) },
            label = "出账分钟",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 分类选择：先选一级分类，再选二级分类（二级可不选）。
 *
 * 只选一级时账单落在该一级分类上；选了二级时落在二级分类上，
 * 预算/统计仍按所属一级分类汇总。
 */
@Composable
private fun AccountCategoryFields(
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    accountId: Long,
    categoryId: Long,
    type: TxType,
    onAccount: (Long) -> Unit,
    onCategory: (Long) -> Unit,
) {
    val topCategories = remember(categories, type) { CategoryTree.topLevel(categories, type) }
    val selectedTop = remember(categories, categoryId) {
        categories.firstOrNull { it.id == categoryId }?.let { cat ->
            if (cat.parentId == null) cat else categories.firstOrNull { it.id == cat.parentId }
        }
    }
    val children = remember(categories, selectedTop) {
        selectedTop?.let { CategoryTree.childrenOf(categories, it.id) }.orEmpty()
    }
    val childLabel = remember(categories, categoryId, selectedTop) {
        val cat = categories.firstOrNull { it.id == categoryId }
        if (cat?.parentId != null && cat.parentId == selectedTop?.id) cat.name else "不指定"
    }

    TxSelectField(
        value = accounts.firstOrNull { it.id == accountId }?.name.orEmpty(),
        options = accounts.map { it.id to it.name },
        onSelect = onAccount,
        label = "账户",
        placeholder = "选择账户",
    )
    Spacer(Modifier.height(12.dp))
    TxSelectField(
        value = selectedTop?.name.orEmpty(),
        options = topCategories.map { it.id to it.name },
        onSelect = { id -> onCategory(id) },
        label = "一级分类",
        placeholder = "选择一级分类",
    )
    Spacer(Modifier.height(12.dp))
    TxSelectField(
        value = childLabel,
        options = listOf(0L to "不指定（只记一级）") + children.map { it.id to it.name },
        onSelect = { id ->
            if (id <= 0L) {
                selectedTop?.let { onCategory(it.id) }
            } else {
                onCategory(id)
            }
        },
        label = "二级分类（可选）",
        placeholder = if (selectedTop == null) "请先选择一级分类" else "选择二级分类",
    )
    Spacer(Modifier.height(5.dp))
    Text(
        "二级分类可以不选；只选一级时按一级分类记账与统计",
        style = EcoType.PageNote,
        color = MaterialTheme.eco.quiet,
    )
}

@Composable
private fun InstallmentEditorSheet(
    initial: InstallmentPlanEntity?,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onSave: (InstallmentDraft, (String?) -> Unit) -> Unit,
    onDelete: ((rollbackPosted: Boolean, onResult: (String?) -> Unit) -> Unit)?,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.eco
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var principalText by remember(initial?.id) {
        mutableStateOf(initial?.let { Money.formatCents(it.totalPrincipalCents) } ?: "")
    }
    var interestText by remember(initial?.id) {
        mutableStateOf(initial?.totalInterestCents?.takeIf { it > 0L }?.let { Money.formatCents(it) } ?: "")
    }
    var periodsText by remember(initial?.id) { mutableStateOf(initial?.periods?.toString() ?: "12") }
    var firstDate by remember(initial?.id) {
        mutableStateOf(initial?.let { RecurringMath.dateOf(it.firstDateMillis) } ?: LocalDate.now())
    }
    var postTimeMinutes by remember(initial?.id) { mutableIntStateOf(initial?.postTimeMinutes ?: 540) }
    var accountId by remember(initial?.id) { mutableStateOf(initial?.accountId ?: (accounts.firstOrNull()?.id ?: 0L)) }
    var categoryId by remember(initial?.id) { mutableStateOf(initial?.categoryId ?: 0L) }
    var deduct by remember(initial?.id) { mutableStateOf(initial?.deductAsset ?: true) }
    var excludePeriodsText by remember(initial?.id) { mutableStateOf(initial?.excludeAssetPeriods ?: "") }
    var includeStats by remember(initial?.id) {
        mutableStateOf(initial?.excludeStatsPeriods.isNullOrBlank())
    }
    var note by remember(initial?.id) { mutableStateOf(initial?.note ?: "") }
    var localError by remember(initial?.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val principal = Money.parseCents(principalText) ?: 0L
    val periods = periodsText.toIntOrNull() ?: 0
    val typedInterest = Money.parseCents(interestText)
    val interest = when {
        typedInterest != null -> typedInterest.coerceAtLeast(0L)
        principal > 0L && periods > 0 -> InstallmentMath.autoInterestCents(principal, periods)
        else -> 0L
    }
    val perPeriod = if (periods > 0) InstallmentMath.periodTotal(principal, interest, periods, 1) else 0L
    // 下一期待出账日期（首期按已有进度顺延，仅用于提示文案）
    val nextDue = InstallmentMath.periodDate(
        firstDate,
        ((initial?.postedPeriods ?: 0) + 1).coerceAtMost(if (periods > 0) periods else 1),
    )

    EcoSheet(onDismiss = onClose, title = if (initial == null) "新增分期" else "编辑分期") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EcoField(
                label = "名称",
                value = title,
                onValueChange = { title = it; localError = null },
                placeholder = "例如：招行手机分期",
            )
            EcoMoneyField(
                label = "总本金",
                value = principalText,
                onValueChange = { principalText = it; localError = null },
                supporting = "分期商品 / 账单的总金额",
            )
            EcoMoneyField(
                label = "总利息",
                value = interestText,
                onValueChange = { interestText = it },
                supporting = "留空 = 按每期 0.60% × 期数自动计算（当前 ${Money.formatYuanGroupedFixed(interest)}）",
            )
            EcoField(
                label = "期数",
                value = periodsText,
                onValueChange = { periodsText = it.filter { ch -> ch.isDigit() }.take(2); localError = null },
                placeholder = "12",
                keyboardType = KeyboardType.Number,
                supporting = "1 ~ ${InstallmentMath.MAX_PERIODS} 期",
            )
            DateDropdowns(date = firstDate, onChange = { firstDate = it })
            Text("出账时间", style = EcoType.PageNote, color = c.muted)
            TimeDropdowns(minutes = postTimeMinutes, onChange = { postTimeMinutes = it })
            Text(
                "下一期将在「${nextDue.monthValue} 月 ${nextDue.dayOfMonth} 日 ${InstallmentMath.timeText(postTimeMinutes)}」" +
                    "自动生成账单；提前不入账，账户里只显示未出账金额",
                style = EcoType.PageNote,
                color = c.quiet,
            )
            AccountCategoryFields(
                accounts = accounts,
                categories = categories,
                accountId = accountId,
                categoryId = categoryId,
                type = TxType.EXPENSE,
                onAccount = { accountId = it },
                onCategory = { categoryId = it },
            )
            EcoSwitchRow(
                title = "只记账，不扣资产",
                checked = !deduct,
                onCheckedChange = { deduct = !it },
                subtitle = "开启后每期入账不会改变账户余额",
            )
            if (deduct) {
                EcoField(
                    label = "指定不扣资产的期数",
                    value = excludePeriodsText,
                    onValueChange = {
                        excludePeriodsText = it.filter { ch ->
                            ch.isDigit() || ch == ',' || ch == '-' || ch == '，' ||
                                ch == '－' || ch == '~'
                        }
                        localError = null
                    },
                    placeholder = "例如 1-3,6（留空 = 每期都扣资产）",
                    supporting = "支持单期与区间：1,3,12 或 1-3,6",
                )
            }
            EcoSwitchRow(
                title = "计入账单和收支统计",
                checked = includeStats,
                onCheckedChange = { includeStats = it },
                subtitle = "关闭后每期只在账单列表显示一条记录，不计入收支统计",
            )

            Text(
                "每期约 ${Money.formatYuanGroupedFixed(perPeriod)} · 总还款 " +
                    Money.formatYuanGroupedFixed(InstallmentMath.totalRepayment(principal, interest)),
                style = EcoType.PageNote,
                color = c.muted,
            )

            EcoField(
                label = "备注",
                value = note,
                onValueChange = { note = it },
                placeholder = "可选",
            )

            val shownError = localError
            if (shownError != null) Text(shownError, style = EcoType.PageNote, color = c.clay)

            EcoGhostButton(
                text = "保存",
                onClick = {
                    val excludeSet = InstallmentMath.parseExcludedPeriods(excludePeriodsText, periods)
                    val excludeInvalid = InstallmentMath.hasInvalidExcludedPeriods(excludePeriodsText, periods)
                    when {
                        title.isBlank() -> localError = "请输入名称"
                        principal <= 0L -> localError = "请输入总本金"
                        periods !in InstallmentMath.MIN_PERIODS..InstallmentMath.MAX_PERIODS ->
                            localError = "期数需在 1~${InstallmentMath.MAX_PERIODS} 之间"
                        deduct && excludeInvalid ->
                            localError = "不扣资产的期数需在 1~$periods 之间，可写 1-3,6"
                        accountId <= 0L -> localError = "请选择账户"
                        categoryId <= 0L -> localError = "请选择一级分类"
                        else -> onSave(
                            InstallmentDraft(
                                id = initial?.id ?: 0L,
                                title = title.trim(),
                                accountId = accountId,
                                categoryId = categoryId,
                                principalCents = principal,
                                interestCents = interest,
                                periods = periods,
                                firstDate = firstDate,
                                postTimeMinutes = postTimeMinutes,
                                deductAsset = deduct,
                                excludeAssetPeriods = if (deduct) {
                                    InstallmentMath.encodeExcludedPeriods(excludeSet, periods)
                                } else {
                                    ""
                                },
                                excludeStatsPeriods = if (includeStats) "" else "1-$periods",
                                note = note.trim(),
                            ),
                        ) { err ->
                            if (err == null) {
                                EcoToast.show("已保存")
                                onClose()
                            } else {
                                localError = err
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(text = "取消", onClick = onClose, modifier = Modifier.weight(1f))
                if (onDelete != null) {
                    EcoDangerButton(
                        text = "删除计划",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        DeletePlanConfirm(
            title = "删除分期计划",
            body = "删除后剩余期数不再自动入账。请选择已入账账单的处理方式。",
            rollbackHint = "该计划已入账的每一期账单都会被删除，账户余额与统计随之变化，不可恢复。",
            onConfirm = { rollback ->
                confirmDelete = false
                onDelete(rollback) { err ->
                    if (err == null) {
                        EcoToast.show(if (rollback) "已删除（含已入账账单）" else "已删除")
                        onClose()
                    } else {
                        localError = err
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RecurringEditorSheet(
    initial: RecurringPlanEntity?,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onSave: (RecurringDraft, (String?) -> Unit) -> Unit,
    onDelete: ((rollbackPosted: Boolean, onResult: (String?) -> Unit) -> Unit)?,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.eco
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: TxType.EXPENSE) }
    var amountText by remember(initial?.id) {
        mutableStateOf(initial?.let { Money.formatCents(it.amountCents) } ?: "")
    }
    var frequency by remember(initial?.id) { mutableStateOf(RecurringFrequency.of(initial?.frequency)) }
    var intervalText by remember(initial?.id) {
        mutableStateOf(initial?.intervalCount?.takeIf { it > 1 }?.toString() ?: "2")
    }
    var firstDate by remember(initial?.id) {
        mutableStateOf(initial?.let { RecurringMath.dateOf(it.firstDateMillis) } ?: LocalDate.now())
    }
    var postTimeMinutes by remember(initial?.id) { mutableIntStateOf(initial?.postTimeMinutes ?: 540) }
    var endMode by remember(initial?.id) { mutableStateOf(RecurringEndMode.of(initial?.endMode)) }
    var endCountText by remember(initial?.id) {
        mutableStateOf(initial?.endCount?.takeIf { it > 0 }?.toString() ?: "12")
    }
    var endDate by remember(initial?.id) {
        mutableStateOf(
            initial?.endDateMillis?.takeIf { it > 0L }?.let { RecurringMath.dateOf(it) }
                ?: LocalDate.now().plusMonths(1),
        )
    }
    var accountId by remember(initial?.id) { mutableStateOf(initial?.accountId ?: (accounts.firstOrNull()?.id ?: 0L)) }
    var categoryId by remember(initial?.id) { mutableStateOf(initial?.categoryId ?: 0L) }
    var deduct by remember(initial?.id) { mutableStateOf(initial?.deductAsset ?: true) }
    var excludePeriodsText by remember(initial?.id) { mutableStateOf(initial?.excludeAssetPeriods ?: "") }
    var includeStats by remember(initial?.id) {
        mutableStateOf(initial?.excludeStatsPeriods.isNullOrBlank())
    }
    var note by remember(initial?.id) { mutableStateOf(initial?.note ?: "") }
    var localError by remember(initial?.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val interval = intervalText.toIntOrNull() ?: 1
    // 下一笔预览：还没记过就按首期，记过则沿用计划里的下一期
    val nextPreview = initial?.let { plan ->
        if (plan.postedCount == 0 || plan.nextDateMillis >= RecurringMath.NO_NEXT) {
            firstDate
        } else {
            RecurringMath.dateOf(plan.nextDateMillis)
        }
    } ?: firstDate

    EcoSheet(onDismiss = onClose, title = if (initial == null) "新增周期" else "编辑周期") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EcoField(
                label = "名称",
                value = title,
                onValueChange = { title = it; localError = null },
                placeholder = "例如：房租 / 会员订阅",
            )
            EcoSegmented(
                options = listOf("支出", "收入"),
                selectedIndex = if (type == TxType.EXPENSE) 0 else 1,
                onSelect = {
                    type = if (it == 0) TxType.EXPENSE else TxType.INCOME
                    categoryId = 0L
                },
            )
            EcoMoneyField(
                label = "金额",
                value = amountText,
                onValueChange = { amountText = it; localError = null },
            )
            Text("重复周期", style = EcoType.PageNote, color = c.muted)
            EcoSegmented(
                options = RecurringFrequency.entries.map { it.label },
                selectedIndex = frequency.ordinal,
                onSelect = { frequency = RecurringFrequency.entries[it] },
                small = true,
            )
            if (frequency == RecurringFrequency.EVERY_N_DAYS) {
                EcoField(
                    label = "间隔天数",
                    value = intervalText,
                    onValueChange = { intervalText = it.filter { ch -> ch.isDigit() }.take(4); localError = null },
                    placeholder = "2",
                    keyboardType = KeyboardType.Number,
                    supporting = "每 1 ~ 3650 天记一笔",
                )
            }
            DateDropdowns(date = firstDate, onChange = { firstDate = it })
            Text("出账时间", style = EcoType.PageNote, color = c.muted)
            TimeDropdowns(minutes = postTimeMinutes, onChange = { postTimeMinutes = it })
            Text(
                "下一笔将在「${nextPreview.monthValue} 月 ${nextPreview.dayOfMonth} 日 " +
                    "${InstallmentMath.timeText(postTimeMinutes)}」自动生成账单；提前不入账",
                style = EcoType.PageNote,
                color = c.quiet,
            )
            Text("结束方式", style = EcoType.PageNote, color = c.muted)
            EcoSegmented(
                options = RecurringEndMode.entries.map { it.label },
                selectedIndex = endMode.ordinal,
                onSelect = { endMode = RecurringEndMode.entries[it] },
                small = true,
            )
            when (endMode) {
                RecurringEndMode.NEVER -> Unit
                RecurringEndMode.COUNT -> EcoField(
                    label = "共记多少次",
                    value = endCountText,
                    onValueChange = { endCountText = it.filter { ch -> ch.isDigit() }.take(5); localError = null },
                    placeholder = "12",
                    keyboardType = KeyboardType.Number,
                    supporting = "含首次，1 ~ ${InstallmentMath.MAX_PERIOD_INDEX} 次",
                )
                RecurringEndMode.DATE -> {
                    DateDropdowns(
                        date = endDate,
                        onChange = { endDate = it },
                        yearLabel = "结束年份",
                    )
                    Text(
                        "结束日期当天及之前仍会照常出账",
                        style = EcoType.PageNote,
                        color = c.quiet,
                    )
                }
            }
            AccountCategoryFields(
                accounts = accounts,
                categories = categories,
                accountId = accountId,
                categoryId = categoryId,
                type = type,
                onAccount = { accountId = it },
                onCategory = { categoryId = it },
            )
            EcoSwitchRow(
                title = "只记账，不扣资产",
                checked = !deduct,
                onCheckedChange = { deduct = !it },
                subtitle = "开启后入账不会改变账户余额",
            )
            if (deduct) {
                EcoField(
                    label = "指定不扣资产的次数",
                    value = excludePeriodsText,
                    onValueChange = {
                        excludePeriodsText = it.filter { ch ->
                            ch.isDigit() || ch == ',' || ch == '-' || ch == '，' ||
                                ch == '－' || ch == '~'
                        }
                        localError = null
                    },
                    placeholder = "例如 1-3,6（留空 = 每次都扣资产）",
                    supporting = "按第几次入账计算，支持 1,3,12 或 1-3,6",
                )
            }
            EcoSwitchRow(
                title = "计入账单和收支统计",
                checked = includeStats,
                onCheckedChange = { includeStats = it },
                subtitle = "关闭后每次只在账单列表显示一条记录，不计入收支统计",
            )
            EcoField(
                label = "备注",
                value = note,
                onValueChange = { note = it },
                placeholder = "可选",
            )

            val shownError = localError
            if (shownError != null) Text(shownError, style = EcoType.PageNote, color = c.clay)

            EcoGhostButton(
                text = "保存",
                onClick = {
                    val amount = Money.parseCents(amountText) ?: 0L
                    val excludeSet = InstallmentMath.parseExcludedIndexes(excludePeriodsText)
                    val excludeInvalid = InstallmentMath.hasInvalidExcludedPeriods(
                        excludePeriodsText, InstallmentMath.MAX_PERIOD_INDEX,
                    )
                    when {
                        title.isBlank() -> localError = "请输入名称"
                        amount <= 0L -> localError = "请输入金额"
                        frequency == RecurringFrequency.EVERY_N_DAYS && interval !in 1..3650 ->
                            localError = "间隔天数需在 1~3650 之间"
                        accountId <= 0L -> localError = "请选择账户"
                        categoryId <= 0L -> localError = "请选择一级分类"
                        endMode == RecurringEndMode.COUNT &&
                            (endCountText.toIntOrNull() ?: 0) !in 1..InstallmentMath.MAX_PERIOD_INDEX ->
                            localError = "结束次数需在 1~${InstallmentMath.MAX_PERIOD_INDEX} 之间"
                        deduct && excludeInvalid ->
                            localError = "不扣资产的次数格式不正确，可写 1-3,6"
                        else -> onSave(
                            RecurringDraft(
                                id = initial?.id ?: 0L,
                                title = title.trim(),
                                type = type,
                                amountCents = amount,
                                categoryId = categoryId,
                                accountId = accountId,
                                frequency = frequency,
                                intervalCount = if (frequency == RecurringFrequency.EVERY_N_DAYS) interval else 1,
                                firstDate = firstDate,
                                postTimeMinutes = postTimeMinutes,
                                deductAsset = deduct,
                                excludeAssetPeriods = if (deduct) {
                                    InstallmentMath.encodeExcludedPeriods(
                                        excludeSet, InstallmentMath.MAX_PERIOD_INDEX,
                                    )
                                } else {
                                    ""
                                },
                                excludeStatsPeriods = if (includeStats) {
                                    ""
                                } else {
                                    "1-${InstallmentMath.MAX_PERIOD_INDEX}"
                                },
                                endMode = endMode,
                                endCount = if (endMode == RecurringEndMode.COUNT) {
                                    endCountText.toIntOrNull() ?: 0
                                } else {
                                    0
                                },
                                endDate = if (endMode == RecurringEndMode.DATE) endDate else null,
                                note = note.trim(),
                            ),
                        ) { err ->
                            if (err == null) {
                                EcoToast.show("已保存")
                                onClose()
                            } else {
                                localError = err
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(text = "取消", onClick = onClose, modifier = Modifier.weight(1f))
                if (onDelete != null) {
                    EcoDangerButton(
                        text = "删除计划",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        DeletePlanConfirm(
            title = "删除周期计划",
            body = "删除后不再自动入账。请选择已入账账单的处理方式。",
            rollbackHint = "该计划已入账的每一笔账单都会被删除，账户余额与统计随之变化，不可恢复。",
            onConfirm = { rollback ->
                confirmDelete = false
                onDelete(rollback) { err ->
                    if (err == null) {
                        EcoToast.show(if (rollback) "已删除（含已入账账单）" else "已删除")
                        onClose()
                    } else {
                        localError = err
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * 删除计划确认（三选）：默认「保留已入账账单」，需要用户手动切到「回滚删除」才会连带删除历史账单。
 */
@Composable
private fun DeletePlanConfirm(
    title: String,
    body: String,
    rollbackHint: String,
    onConfirm: (rollback: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    var rollback by remember { mutableStateOf(false) }
    EcoDialog(onDismiss = onDismiss) {
        Column {
            Text(title, style = EcoType.ModalTitle, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text(body, style = EcoType.ModalBody, color = c.muted)
            Spacer(Modifier.height(14.dp))
            EcoPane(elevation = 0.dp) {
                Column {
                    DeleteChoiceRow(
                        title = "保留已入账账单",
                        subtitle = "默认：只删除计划，历史账单继续保留",
                        selected = !rollback,
                        onClick = { rollback = false },
                    )
                    EcoRowDivider()
                    DeleteChoiceRow(
                        title = "回滚删除已入账账单",
                        subtitle = rollbackHint,
                        selected = rollback,
                        onClick = { rollback = true },
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f), height = 40.dp)
                Box(Modifier.weight(1f)) {
                    EcoDangerButton(
                        text = "删除计划",
                        onClick = { onConfirm(rollback) },
                        height = 40.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.blue.copy(alpha = 0.08f) else Color.Transparent),
    ) {
        EcoRow(
            title = title,
            subtitle = subtitle,
            onClick = onClick,
            trailing = {
                Icon(
                    imageVector = EcoIcons.Check,
                    contentDescription = if (selected) "已选择" else null,
                    tint = if (selected) c.blue else c.quiet.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}
