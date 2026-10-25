package com.controleco.app.ui.pending

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoSwitchRow
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 待确认账单：通知/页面捕获到的交易先落在这里，用户逐条确认（分类/账户/商户）或丢弃；
 * 0 元占位条需先补金额；微信红包（ASK）必须逐条明确确认，不参与「全部确认」。
 *
 * 除此之外还有一类条目：**已经入账、但还没处理**的退款收入（捕获来的退款）。
 * 它和捕获条目**并进同一个列表，不单独分区**（产品要求）；点开列同金额的支出让用户选，
 * 选中后那笔支出标记「已退款」、不再计入支出；选不到就「记为收入」。
 * 导入的退款账单由账单自身标记，不进这里。
 */
@Composable
fun PendingScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val pending by vm.pendingList.collectAsStateWithLifecycle(initialValue = emptyList())
    val refunds by vm.pendingRefunds.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    var confirming by remember { mutableStateOf<PendingTransactionEntity?>(null) }
    var amountEditing by remember { mutableStateOf<PendingTransactionEntity?>(null) }
    var confirmAllAsk by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<TransactionRow?>(null) }
    var candidates by remember { mutableStateOf<List<TransactionRow>?>(null) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var incomeAsk by remember { mutableStateOf<TransactionRow?>(null) }
    val amountMissing = pending.count { it.amountCents <= 0 }
    val confirmableCount = pending.count { it.status != PendingStatus.ASK && it.amountCents > 0 }
    val askCount = pending.count { it.status == PendingStatus.ASK }
    val context = LocalContext.current
    val c = MaterialTheme.eco

    // 捕获条目与退款条目并进一个列表，按时间倒序；不分区、不加小标题
    val entries = remember(pending, refunds) {
        buildList {
            pending.forEach { add(PendingEntry.Capture(it)) }
            refunds.forEach { add(PendingEntry.Refund(it)) }
        }.sortedByDescending { it.sortAt }
    }

    // 打开退款 sheet 时拉同金额的支出候选
    LaunchedEffect(linking?.id) {
        val row = linking
        candidates = null
        linkError = null
        submitting = false
        if (row != null) candidates = vm.refundExpenseCandidates(row.amountCents)
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "PENDING",
            title = "待确认账单",
            onBack = onBack,
            action = {
                if (confirmableCount > 0) {
                    EcoTextAction(text = "全部确认", onClick = { confirmAllAsk = true })
                }
            },
        )

        if (entries.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EcoEmpty(
                    title = "没有待确认账单",
                    body = "开启通知监听后，收款/动账通知会出现在这里",
                    icon = EcoIcons.Check,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 32.dp),
            ) {
                if (amountMissing > 0) {
                    item(key = "amount-warning") {
                        PendingWarning(
                            icon = EcoIcons.Warning,
                            text = "有 $amountMissing 条金额待补录：点「补金额」输入，" +
                                "或从通知栏通知直接回复（「全部确认」会跳过它们）",
                            fg = c.amber,
                            bg = c.amberSoft,
                        )
                    }
                }
                if (askCount > 0) {
                    item(key = "ask-warning") {
                        PendingWarning(
                            icon = EcoIcons.Gift,
                            text = "有 $askCount 条微信红包待确认：请逐条确认是否记账，不参与「全部确认」。",
                            fg = c.clay,
                            bg = c.claySoft,
                        )
                    }
                }
                if (refunds.isNotEmpty()) {
                    item(key = "refund-warning") {
                        PendingWarning(
                            icon = EcoIcons.Receipt,
                            text = "有 ${refunds.size} 条退款待处理：选中被退款的支出后，" +
                                "那笔支出会标记为「已退款」，不再计入本月支出与分类预算。",
                            fg = c.blue,
                            bg = c.blueSoft,
                        )
                    }
                }
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is PendingEntry.Capture -> PendingRow(
                            item = entry.item,
                            onConfirm = { confirming = entry.item },
                            onFillAmount = { amountEditing = entry.item },
                            onDiscard = { vm.discardPending(entry.item.id) },
                        )
                        is PendingEntry.Refund -> RefundPendingRow(
                            row = entry.row,
                            onLink = { linking = entry.row },
                            onMarkIncome = { incomeAsk = entry.row },
                        )
                    }
                }
                item(key = "summary") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildString {
                                append("共 ${pending.size + refunds.size} 条，$confirmableCount 条可一键确认")
                                if (refunds.isNotEmpty()) append("，${refunds.size} 条退款待处理")
                            },
                            style = EcoType.PageNote,
                            color = c.quiet,
                        )
                    }
                }
            }
        }
    }

    confirming?.let { p ->
        ConfirmPendingSheet(
            item = p,
            accounts = accounts,
            categories = categories,
            defaultCategoryId = vm.guessCategoryId(
                p.type,
                p.counterparty.ifBlank { p.note },
                isRefund = p.refundMark == RefundMark.REFUND_INCOME,
            ),
            defaultAccountId = vm.guessAccountId(p.sourcePackage, p.ruleName, p.paymentInstrument),
            onConfirm = { categoryId, accountId, counterparty, excludeFromAssets, excludeFromStats ->
                vm.confirmPending(
                    p, categoryId, accountId, counterparty,
                    excludeFromAssets, excludeFromStats,
                )
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }

    amountEditing?.let { p ->
        PendingAmountSheet(
            item = p,
            onSave = { cents ->
                vm.setPendingAmount(p.id, cents) { message ->
                    if (message != null) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
                amountEditing = null
            },
            onDismiss = { amountEditing = null },
        )
    }

    linking?.let { row ->
        RefundLinkSheet(
            row = row,
            candidates = candidates,
            error = linkError,
            submitting = submitting,
            onPick = { expense ->
                submitting = true
                vm.handlePendingRefund(row.id, expense.id) { error ->
                    submitting = false
                    if (error == null) {
                        linking = null
                        EcoToast.show("已标记为已退款")
                    } else {
                        linkError = error
                    }
                }
            },
            onMarkIncome = {
                linking = null
                incomeAsk = row
            },
            onDismiss = { linking = null },
        )
    }

    incomeAsk?.let { row ->
        EcoConfirm(
            title = "记为收入",
            body = "这笔退款按普通收入记账；对应的支出不会被标记为已退款，仍计入本月支出。之后不会再出现在待处理里。",
            confirmText = "记为收入",
            onConfirm = {
                vm.markRefundAsIncome(row.id) { error ->
                    if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    else EcoToast.show("已记为收入")
                }
                incomeAsk = null
            },
            onDismiss = { incomeAsk = null },
        )
    }

    if (confirmAllAsk) {
        val confirmable = pending.count { it.status != PendingStatus.ASK && it.amountCents > 0 }
        val confirmBody = buildString {
            append("将把 $confirmable 条按默认分类（其他）与推测账户入账，之后可在流水中修改。")
            if (amountMissing > 0) append("另有 $amountMissing 条金额待补录，不会被确认。")
            if (refunds.isNotEmpty()) append("${refunds.size} 条退款待处理不会被动到。")
        }
        EcoConfirm(
            title = "全部确认",
            body = confirmBody,
            confirmText = "确认",
            onConfirm = {
                vm.confirmAllPending()
                confirmAllAsk = false
            },
            onDismiss = { confirmAllAsk = false },
        )
    }
}

/** 待确认页的一行：捕获待确认，或已入账但还没指认原支出的退款 */
private sealed interface PendingEntry {
    val key: String
    val sortAt: Long

    data class Capture(val item: PendingTransactionEntity) : PendingEntry {
        override val key: String get() = "capture-${item.id}"
        override val sortAt: Long get() = item.createdAt
    }

    data class Refund(val row: TransactionRow) : PendingEntry {
        override val key: String get() = "refund-${row.id}"

        /** 按入库时间排：几个月前导入的退款也要浮到顶部，而不是按业务时间沉下去 */
        override val sortAt: Long get() = row.createdAt
    }
}

// ---------- 列表 ----------

@Composable
private fun PendingWarning(
    icon: ImageVector,
    text: String,
    fg: Color,
    bg: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = EcoType.PageLead.copy(fontSize = 10.sp, lineHeight = 15.sp),
            color = fg,
        )
    }
}

@Composable
private fun PendingRow(
    item: PendingTransactionEntity,
    onConfirm: () -> Unit,
    onFillAmount: () -> Unit,
    onDiscard: () -> Unit,
) {
    val c = MaterialTheme.eco
    val amountMissing = item.amountCents <= 0
    val isAsk = item.status == PendingStatus.ASK

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = if (amountMissing) onFillAmount else onConfirm)
            .padding(top = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val amountText = when {
                    amountMissing -> "金额待补"
                    item.type == TxType.INCOME -> "+" + Money.formatYuan(item.amountCents)
                    else -> "-" + Money.formatYuan(item.amountCents)
                }
                Text(
                    amountText,
                    style = if (amountMissing) {
                        EcoType.PendingAmount.copy(fontSize = 12.sp)
                    } else {
                        EcoType.PendingAmount
                    },
                    color = when {
                        amountMissing -> c.amber
                        item.type == TxType.INCOME -> c.jade
                        else -> c.ink
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    sourceLine(item),
                    style = EcoType.RowSub.copy(lineHeight = 12.sp),
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.counterparty.isNotBlank()) {
                    Text(
                        "收款方 ${item.counterparty}",
                        style = EcoType.RowSub.copy(lineHeight = 12.sp),
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.note.isNotBlank()) {
                    Text(
                        item.note,
                        style = EcoType.RowSub.copy(lineHeight = 12.sp),
                        color = c.quiet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.paymentInstrument.isNotBlank()) {
                    Text(
                        item.paymentInstrument,
                        style = EcoType.RowSub.copy(lineHeight = 12.sp),
                        color = c.quiet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    pendingTime(item.timestamp),
                    style = EcoType.PageNote.copy(fontSize = 8.sp, lineHeight = 10.sp),
                    color = c.quiet,
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                amountMissing -> EcoPill(text = "待补", tone = EcoPillTone.Warn)
                isAsk -> EcoPill(text = "红包", tone = EcoPillTone.Bad)
                else -> EcoPill(text = "待确认", tone = EcoPillTone.Blue)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                amountMissing -> PendingTextAction("补金额", c.blue, onFillAmount)
                else -> PendingTextAction("确认", c.blue, onConfirm)
            }
            Spacer(Modifier.width(18.dp))
            PendingTextAction("丢弃", c.clay, onDiscard)
        }
    }
    EcoRowDivider(inset = 0.dp)
}

/**
 * 退款待处理行：已入账的退款收入，还没标记对应的原支出。
 * 动作：选被退款的账单（那笔支出标「已退款」，不再计入支出）/ 记为收入（只按普通收入记账）。
 */
@Composable
private fun RefundPendingRow(
    row: TransactionRow,
    onLink: () -> Unit,
    onMarkIncome: () -> Unit,
) {
    val c = MaterialTheme.eco
    val merchant = row.counterparty.ifBlank { row.note }.ifBlank { "未命名退款" }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onLink)
            .padding(top = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "+" + Money.formatYuan(row.amountCents),
                    style = EcoType.PendingAmount,
                    color = c.jade,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "退款到账 ${refundSourceLabel(row.source)}",
                    style = EcoType.RowSub.copy(lineHeight = 12.sp),
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    merchant,
                    style = EcoType.RowSub.copy(lineHeight = 12.sp),
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pendingTime(row.timestamp),
                    style = EcoType.PageNote.copy(fontSize = 8.sp, lineHeight = 10.sp),
                    color = c.quiet,
                )
            }
            Spacer(Modifier.width(10.dp))
            EcoPill(text = "待处理", tone = EcoPillTone.Warn)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PendingTextAction("选择原账单", c.blue, onLink)
            Spacer(Modifier.width(18.dp))
            PendingTextAction("记为收入", c.muted, onMarkIncome)
        }
    }
    EcoRowDivider(inset = 0.dp)
}

// ---------- 选原账单 sheet ----------

/**
 * 选原账单：**只列同金额的支出**（产品口径）。没有候选时引导「记为收入」；
 * 每一行点一下就完成处理（那笔支出标「已退款」、退款标「已处理」），不再多一步确认。
 */
@Composable
private fun RefundLinkSheet(
    row: TransactionRow,
    candidates: List<TransactionRow>?,
    error: String?,
    submitting: Boolean,
    onPick: (TransactionRow) -> Unit,
    onMarkIncome: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    val merchant = row.counterparty.ifBlank { row.note }.ifBlank { "未命名退款" }

    EcoSheet(
        onDismiss = { if (!submitting) onDismiss() },
        title = "选择被退款的账单",
        subtitle = "退款 +${Money.formatYuan(row.amountCents)}，$merchant，${pendingTime(row.timestamp)}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "选中后，那笔支出会标记为「已退款」，不再计入本月支出与分类预算。",
                style = EcoType.PageNote,
                color = c.muted,
            )
            Text("同金额的支出账单", style = EcoType.FieldLabel, color = c.muted)
            when {
                candidates == null -> Text("加载中…", style = EcoType.PageNote, color = c.quiet)
                candidates.isEmpty() -> Column {
                    Text("没有同金额的支出账单", style = EcoType.PageNote, color = c.amber)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "可以「记为收入」：这笔退款按普通收入记账，对应的支出不会被标记为已退款。",
                        style = EcoType.PageNote,
                        color = c.quiet,
                    )
                }
                else -> EcoPane {
                    Column {
                        candidates.forEachIndexed { index, expense ->
                            RefundCandidateRow(
                                row = expense,
                                enabled = !submitting,
                                onClick = { onPick(expense) },
                            )
                            if (index != candidates.lastIndex) EcoRowDivider(inset = 14.dp)
                        }
                    }
                }
            }

            error?.let {
                Text(it, style = EcoType.PageNote, color = c.clay)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(
                        text = "记为收入",
                        onClick = onMarkIncome,
                        enabled = !submitting,
                        height = 42.dp,
                    )
                }
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(
                        text = "取消",
                        onClick = onDismiss,
                        enabled = !submitting,
                        height = 42.dp,
                    )
                }
            }
        }
    }
}

/** 候选支出行：分类瓦片 + 金额/商户 + 分类 · 账户 · 日期；已打「已退」标记的也列出来 */
@Composable
private fun RefundCandidateRow(
    row: TransactionRow,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    val merchant = row.counterparty.ifBlank { row.note }.ifBlank { "未命名支出" }
    val detail = listOf(row.categoryName, row.accountName, pendingTime(row.timestamp))
        .filter { it.isNotBlank() }
        .joinToString(" ")

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EcoCategoryTile(
            categoryName = row.categoryName,
            icon = row.categoryIcon,
            size = 34.dp,
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    "-" + Money.formatYuan(row.amountCents),
                    style = EcoType.BillAmount,
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    merchant,
                    style = EcoType.RowTitle,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.refundMark == RefundMark.REFUNDED_EXPENSE) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "已退",
                        style = EcoType.Pill.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                        color = c.amber,
                    )
                }
            }
            Text(
                detail,
                style = EcoType.RowSub,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 退款行的来源标签（导入账单的退款来源就是微信/支付宝） */
private fun refundSourceLabel(source: TxSource): String = when (source) {
    TxSource.WECHAT -> "微信支付"
    TxSource.ALIPAY -> "支付宝"
    TxSource.NOTIFICATION -> "通知捕获"
    TxSource.BANK -> "银行动账"
    TxSource.MANUAL -> "手动记录"
}

@Composable
private fun PendingTextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        style = EcoType.Btn.copy(fontSize = 10.sp),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

// ---------- 确认 sheet ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmPendingSheet(
    item: PendingTransactionEntity,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    defaultCategoryId: Long?,
    defaultAccountId: Long?,
    onConfirm: (Long, Long, String, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var categoryId by remember(item.id) { mutableStateOf(defaultCategoryId) }
    var accountId by remember(item.id) { mutableStateOf(defaultAccountId) }
    var counterparty by remember(item.id) { mutableStateOf(item.counterparty) }
    // 是否计入资产统计：默认计入（自动入账口径）
    var excludeFromAssets by remember(item.id) { mutableStateOf(false) }
    // 是否计入账单和收支统计：默认计入（自动入账口径）
    var excludeFromStats by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    val catsOfType = categories.filter { it.type == item.type }
    val c = MaterialTheme.eco
    val kind = if (item.type == TxType.EXPENSE) "支出" else "收入"

    EcoSheet(
        onDismiss = onDismiss,
        title = "确认$kind ${Money.formatYuan(item.amountCents)}",
        subtitle = "${sourceLine(item)} ${pendingTime(item.timestamp)}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (item.note.isNotBlank()) PendingQuote(item.note)

            EcoField(
                label = "收款方 / 商户",
                value = counterparty,
                onValueChange = { counterparty = it },
                placeholder = "例如：美团外卖",
            )

            Column {
                Text("分类", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(6.dp))
                if (catsOfType.isEmpty()) {
                    Text(
                        "暂无分类，请先在分类管理中添加",
                        style = EcoType.PageNote,
                        color = c.amber,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        catsOfType.forEach { cat ->
                            EcoChip(
                                text = cat.name,
                                selected = categoryId == cat.id,
                                onClick = {
                                    categoryId = cat.id
                                    if (error != null) error = null
                                },
                            )
                        }
                    }
                }
            }

            Column {
                Text("账户", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(6.dp))
                if (accounts.isEmpty()) {
                    Text("暂无账户，请先在账户管理中添加", style = EcoType.PageNote, color = c.amber)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        accounts.forEach { acc ->
                            EcoChip(
                                text = acc.name,
                                selected = accountId == acc.id,
                                onClick = {
                                    accountId = acc.id
                                    if (error != null) error = null
                                },
                            )
                        }
                    }
                }
            }

            EcoSwitchRow(
                title = "计入资产统计",
                checked = !excludeFromAssets,
                onCheckedChange = { excludeFromAssets = !it },
                subtitle = "关闭后只记账，不改变账户余额",
            )

            EcoSwitchRow(
                title = "计入账单和收支统计",
                checked = !excludeFromStats,
                onCheckedChange = { excludeFromStats = !it },
                subtitle = "关闭后只在账单列表显示一条记录，不计入收支统计",
            )

            error?.let {
                Text(it, style = EcoType.PageNote, color = c.clay)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(text = "取消", onClick = onDismiss, height = 42.dp)
                }
                Box(Modifier.weight(1f)) {
                    EcoPrimaryButton(
                        text = "入账",
                        height = 42.dp,
                        onClick = save@ {
                            val cat = categoryId
                            val acc = accountId
                            if (cat == null) {
                                error = "请选择分类"
                                return@save
                            }
                            if (acc == null) {
                                error = "请选择账户"
                                return@save
                            }
                            error = null
                            onConfirm(
                                cat, acc, counterparty.trim(),
                                excludeFromAssets, excludeFromStats,
                            )
                        },
                    )
                }
            }
        }
    }
}

// ---------- 补金额 sheet ----------

@Composable
private fun PendingAmountSheet(
    item: PendingTransactionEntity,
    onSave: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(item.id) { mutableStateOf("") }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    val c = MaterialTheme.eco
    val kind = if (item.type == TxType.EXPENSE) "支出" else "收入"

    EcoSheet(
        onDismiss = onDismiss,
        title = "补录$kind 金额",
        subtitle = "${sourceLine(item)} ${pendingTime(item.timestamp)}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (item.note.isNotBlank()) PendingQuote(item.note)

            EcoMoneyField(
                label = "金额（元）",
                value = text,
                onValueChange = {
                    text = it
                    if (error != null) error = null
                },
                placeholder = "0.00",
                isError = error != null,
            )

            Text(
                "两条路都行：下拉通知栏点「输入金额」直接回复，或在这里补。",
                style = EcoType.PageNote,
                color = c.quiet,
            )

            error?.let {
                Text(it, style = EcoType.PageNote, color = c.clay)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(text = "取消", onClick = onDismiss, height = 42.dp)
                }
                Box(Modifier.weight(1f)) {
                    EcoPrimaryButton(
                        text = "保存",
                        height = 42.dp,
                        onClick = save@ {
                            val cents = Money.parseCents(text)
                            if (cents == null || cents <= 0) {
                                error = "请输入有效金额"
                                return@save
                            }
                            onSave(cents)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingQuote(text: String) {
    val c = MaterialTheme.eco
    Text(
        "「$text」",
        style = EcoType.PageLead,
        color = c.muted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    )
}

// ---------- 工具 ----------

/**
 * 来源行：来源标签与规则名可能重复（"微信" + "微信·支出/消费"、银行推送的
 * "工商银行通知" + "工商银行通知·支出"）——规则名已含前缀时只显示规则名。
 */
private fun sourceLine(item: PendingTransactionEntity): String {
    val label = NotificationRules.sourceLabelOrRule(item.sourcePackage, item.ruleName)
    return if (item.ruleName.startsWith(label)) item.ruleName else "$label ${item.ruleName}"
}

private val pendingTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun pendingTime(millis: Long): String = pendingTimeFormatter.format(Instant.ofEpochMilli(millis))
