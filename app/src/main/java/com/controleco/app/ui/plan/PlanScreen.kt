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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.InstallmentPlanEntity
import com.controleco.app.data.db.RecurringPlanEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.data.plan.InstallmentMath
import com.controleco.app.data.plan.RecurringFrequency
import com.controleco.app.data.plan.RecurringMath
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoDangerButton
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
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

/**
 * 分期 · 周期（功能2）：信用卡分期与固定周期账单。
 *
 * 分期每期 = 本金份额 + 利息份额；总利息留空时按「每期 0.60% × 期数」自动算。
 * 两种计划都支持「只记账、不扣资产」。
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
    val categoryName = remember(categories) { categories.associate { it.id to it.name } }

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
            EcoToast.show(err ?: "已记下第 ${plan.postedPeriods + 1} 期", if (err == null) com.controleco.app.ui.eco.EcoPillTone.Good else com.controleco.app.ui.eco.EcoPillTone.Bad)
        }
    }

    fun postRecurring(plan: RecurringPlanEntity) {
        vm.postRecurring(plan.id) { err ->
            EcoToast.show(err ?: "已记一笔", if (err == null) com.controleco.app.ui.eco.EcoPillTone.Good else com.controleco.app.ui.eco.EcoPillTone.Bad)
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
                                body = "建立信用卡分期后，按期点「记一期」即可入账",
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
                            categoryName = categoryName[plan.categoryId] ?: "分类",
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
                                body = "建立周期账单后，到期点「记一笔」即可入账",
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
                            categoryName = categoryName[plan.categoryId] ?: "分类",
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
                            "开启「只记账不扣资产」后，入账记录不会改变账户余额。"
                    } else {
                        "周期账单到期后需要手动点「记一笔」，入账后自动顺延到下一个周期。" +
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
            onSave = { id, title, accountId, categoryId, principal, interest, periods, firstDate, deduct, note, onResult ->
                vm.saveInstallment(
                    id = id, title = title, accountId = accountId, categoryId = categoryId,
                    totalPrincipalCents = principal, totalInterestCents = interest,
                    periods = periods, firstDateMillis = RecurringMath.millisAt(firstDate),
                    deductAsset = deduct, note = note, onResult = onResult,
                )
            },
            onDelete = editingInstallment?.let { plan ->
                val action: ((String?) -> Unit) -> Unit = { onDone -> vm.deleteInstallment(plan.id, onDone) }
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
            onSave = { id, title, type, amount, categoryId, accountId, frequency, firstDate, deduct, note, onResult ->
                vm.saveRecurring(
                    id = id, title = title, type = type, amountCents = amount,
                    categoryId = categoryId, accountId = accountId, frequency = frequency,
                    firstDateMillis = RecurringMath.millisAt(firstDate), deductAsset = deduct,
                    note = note, onResult = onResult,
                )
            },
            onDelete = editingRecurring?.let { plan ->
                val action: ((String?) -> Unit) -> Unit = { onDone -> vm.deleteRecurring(plan.id, onDone) }
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
    val done = plan.postedPeriods >= plan.periods
    val nextIndex = (plan.postedPeriods + 1).coerceAtMost(plan.periods)
    val nextDate = InstallmentMath.periodDate(RecurringMath.dateOf(plan.firstDateMillis), nextIndex)
    val perCents = InstallmentMath.periodTotal(
        plan.totalPrincipalCents, plan.totalInterestCents, plan.periods, nextIndex,
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
                        "第 ${plan.postedPeriods}/${plan.periods} 期",
                        style = EcoType.RowSub,
                        color = if (done) c.jade else c.muted,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(accountName).append(" · ").append(categoryName)
                        if (!plan.deductAsset) append(" · 不扣资产")
                    },
                    style = EcoType.RowSub,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (done) {
                        "已全部记完 · 总还款 ${Money.formatYuanGroupedFixed(InstallmentMath.totalRepayment(plan.totalPrincipalCents, plan.totalInterestCents))}"
                    } else {
                        "下期 ${nextDate.monthValue}/${nextDate.dayOfMonth} · 每期 ${Money.formatYuanGroupedFixed(perCents)}" +
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
                Text("总本金 ${Money.formatYuanGroupedFixed(plan.totalPrincipalCents)}", style = EcoType.PageNote, color = c.quiet)
                Spacer(Modifier.weight(1f))
                EcoTextAction(
                    text = if (done) "已记完" else "记一期",
                    onClick = onPost,
                    enabled = !done,
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
    val nextDate = RecurringMath.dateOf(plan.nextDateMillis)
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
                        append(" · ").append(frequency.label)
                        if (!plan.deductAsset) append(" · 不扣资产")
                    },
                    style = EcoType.RowSub,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "下次 ${nextDate.year}/${nextDate.monthValue}/${nextDate.dayOfMonth}",
                    style = EcoType.PageNote,
                    color = c.quiet,
                )
            }
            EcoRowDivider(inset = 16.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("首期 ${RecurringMath.dateOf(plan.firstDateMillis)}", style = EcoType.PageNote, color = c.quiet)
                Spacer(Modifier.weight(1f))
                EcoTextAction(text = "记一笔", onClick = onPost)
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

/** 年 / 月 / 日三段下拉（首期日期） */
@Composable
private fun DateDropdowns(
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
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
            label = "首期年份",
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
    val topLevel = categories.filter { it.type == type && it.parentId == null }
    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    val parentId = selectedCategory?.parentId ?: selectedCategory?.id ?: 0L
    val children = categories.filter { it.parentId == parentId }

    TxSelectField(
        value = accounts.firstOrNull { it.id == accountId }?.name.orEmpty(),
        options = accounts.map { it.id to it.name },
        onSelect = onAccount,
        label = "账户",
        placeholder = "选择账户",
    )
    Spacer(Modifier.height(12.dp))
    TxSelectField(
        value = topLevel.firstOrNull { it.id == parentId }?.name.orEmpty(),
        options = topLevel.map { it.id to it.name },
        onSelect = { id -> onCategory(id) },
        label = "一级分类",
        placeholder = "选择一级分类",
    )
    if (children.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        TxSelectField(
            value = selectedCategory?.takeIf { it.parentId != null }?.name.orEmpty(),
            options = listOf(0L to "不选二级分类") + children.map { it.id to it.name },
            onSelect = { id -> onCategory(if (id <= 0L) parentId else id) },
            label = "二级分类",
            placeholder = "不选二级分类",
        )
    }
}

@Composable
private fun InstallmentEditorSheet(
    initial: InstallmentPlanEntity?,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onSave: (
        Long, String, Long, Long, Long, Long, Int, LocalDate, Boolean, String, (String?) -> Unit,
    ) -> Unit,
    onDelete: (((String?) -> Unit) -> Unit)?,
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
    var accountId by remember(initial?.id) { mutableStateOf(initial?.accountId ?: (accounts.firstOrNull()?.id ?: 0L)) }
    var categoryId by remember(initial?.id) { mutableStateOf(initial?.categoryId ?: 0L) }
    var deduct by remember(initial?.id) { mutableStateOf(initial?.deductAsset ?: true) }
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
                    when {
                        title.isBlank() -> localError = "请输入名称"
                        principal <= 0L -> localError = "请输入总本金"
                        periods !in InstallmentMath.MIN_PERIODS..InstallmentMath.MAX_PERIODS ->
                            localError = "期数需在 1~${InstallmentMath.MAX_PERIODS} 之间"
                        accountId <= 0L -> localError = "请选择账户"
                        categoryId <= 0L -> localError = "请选择分类"
                        else -> onSave(
                            initial?.id ?: 0L, title.trim(), accountId, categoryId,
                            principal, interest, periods, firstDate, deduct, note.trim(),
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
        EcoConfirm(
            title = "删除分期计划",
            body = "已入账的账单不会回滚；删除后剩余期数不再提醒。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                onDelete { err ->
                    if (err == null) {
                        EcoToast.show("已删除")
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
    onSave: (
        Long, String, TxType, Long, Long, Long, RecurringFrequency, LocalDate, Boolean, String, (String?) -> Unit,
    ) -> Unit,
    onDelete: (((String?) -> Unit) -> Unit)?,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.eco
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: TxType.EXPENSE) }
    var amountText by remember(initial?.id) {
        mutableStateOf(initial?.let { Money.formatCents(it.amountCents) } ?: "")
    }
    var frequency by remember(initial?.id) { mutableStateOf(RecurringFrequency.of(initial?.frequency)) }
    var firstDate by remember(initial?.id) {
        mutableStateOf(initial?.let { RecurringMath.dateOf(it.firstDateMillis) } ?: LocalDate.now())
    }
    var accountId by remember(initial?.id) { mutableStateOf(initial?.accountId ?: (accounts.firstOrNull()?.id ?: 0L)) }
    var categoryId by remember(initial?.id) { mutableStateOf(initial?.categoryId ?: 0L) }
    var deduct by remember(initial?.id) { mutableStateOf(initial?.deductAsset ?: true) }
    var note by remember(initial?.id) { mutableStateOf(initial?.note ?: "") }
    var localError by remember(initial?.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

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
            EcoSegmented(
                options = RecurringFrequency.entries.map { it.label },
                selectedIndex = frequency.ordinal,
                onSelect = { frequency = RecurringFrequency.entries[it] },
            )
            DateDropdowns(date = firstDate, onChange = { firstDate = it })
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
                    when {
                        title.isBlank() -> localError = "请输入名称"
                        amount <= 0L -> localError = "请输入金额"
                        accountId <= 0L -> localError = "请选择账户"
                        categoryId <= 0L -> localError = "请选择分类"
                        else -> onSave(
                            initial?.id ?: 0L, title.trim(), type, amount, categoryId, accountId,
                            frequency, firstDate, deduct, note.trim(),
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
        EcoConfirm(
            title = "删除周期计划",
            body = "已入账的账单不会回滚；删除后不再顺延。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                onDelete { err ->
                    if (err == null) {
                        EcoToast.show("已删除")
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
