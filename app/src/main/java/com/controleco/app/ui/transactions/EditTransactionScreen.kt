package com.controleco.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.CategoryTree
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoHairline
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 编辑记录：金额卡（EcoPane + EditAmount）→ 类型 → 分类 → 账户与日期 → 商户与备注 →
 * 保存修改 → 删除这笔记录（与顶栏「删除」冗余但等价）。
 *
 * 编辑已有记录时保留 id / createdAt / 原时分（只改日期不会把时间抹成 00:00）。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(vm: LedgerViewModel, txId: Long, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    var existing by remember { mutableStateOf<TransactionEntity?>(null) }
    LaunchedEffect(txId) {
        if (txId > 0) existing = vm.transactionById(txId)
    }

    var type by remember { mutableStateOf(TxType.EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    // 编辑时保留原记录的时分（改日期不该把时间抹成 00:00）
    var timeOfDay by remember { mutableStateOf(LocalTime.now()) }
    var counterparty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var excludeFromAssets by remember { mutableStateOf(false) }
    var countInStats by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 退款关联：当前记录已配对的对端记录（共享 refundLinkId）
    var linkedPair by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    var showLinkPicker by remember { mutableStateOf(false) }
    var linkCandidates by remember { mutableStateOf<List<TransactionRow>>(emptyList()) }
    // 退款多于付款时需要二次确认：Pair(expenseId, refundId)
    var pendingLinkConfirm by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var linkConfirmMessage by remember { mutableStateOf<String?>(null) }

    // 编辑模式：加载完成后回填表单（仅一次）
    LaunchedEffect(existing) {
        existing?.let { t ->
            type = t.type
            amountText = Money.formatCents(t.amountCents)
            categoryId = t.categoryId
            accountId = t.accountId
            val local = Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault())
            date = local.toLocalDate()
            timeOfDay = local.toLocalTime()
            counterparty = t.counterparty
            note = t.note
            excludeFromAssets = t.excludeFromAssets
            countInStats = t.countInStats
        }
    }

    // 加载退款关联的对端记录（共享 refundLinkId 的另一笔）
    LaunchedEffect(existing?.refundLinkId) {
        val linkId = existing?.refundLinkId
        linkedPair = if (linkId != null && linkId > 0) {
            listOfNotNull(vm.transactionById(linkId)).filter { it.id != existing?.id }
        } else {
            emptyList()
        }
    }

    // 默认选中：默认账户 + 当前类型的第一个一级分类
    LaunchedEffect(accounts, categories, type) {
        if (accountId == null) {
            accountId = accounts.firstOrNull { it.isDefault }?.id ?: accounts.firstOrNull()?.id
        }
        if (categories.firstOrNull { it.id == categoryId }?.type != type) {
            categoryId = CategoryTree.topLevel(categories, type).firstOrNull()?.id
        }
    }

    // 只展示一级分类；二级分类通过长按一级分类选择
    val catsOfType = CategoryTree.topLevel(categories, type)
    var subPickerFor by remember { mutableStateOf<CategoryEntity?>(null) }
    val cents = Money.parseCents(amountText)
    val valid = cents != null && cents > 0L && categoryId != null && accountId != null
    val c = MaterialTheme.eco
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    fun save() {
        val value = Money.parseCents(amountText)
        when {
            value == null || value <= 0L -> error = "请输入有效金额"
            categoryId == null -> error = "请选择分类"
            accountId == null -> error = "请选择账户"
            else -> {
                val ts = LocalDateTime.of(date, timeOfDay)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                vm.saveTransaction(
                    txId, type, value, categoryId!!, accountId!!, ts, note.trim(), counterparty.trim(),
                    excludeFromAssets, countInStats,
                )
                EcoToast.show(if (txId > 0) "已保存修改" else "已记一笔")
                onBack()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        EcoTopBar(
            micro = "EDIT ENTRY",
            title = "编辑记录",
            onBack = onBack,
            action = {
                if (txId > 0 && existing != null) {
                    EcoTextAction(
                        text = "删除",
                        onClick = { showDeleteConfirm = true },
                        color = c.clay,
                    )
                }
            },
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding())
                .verticalScroll(scroll)
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 金额卡：设计稿的圆角卡片 + 内嵌大字输入 + 底部发丝线
            EcoPane(shape = RoundedCornerShape(20.dp), elevation = 9.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                ) {
                    TxFieldLabel("金额", color = c.quiet)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            Money.symbol,
                            style = TxEditCurrencyStyle,
                            color = c.blue,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        BasicTextField(
                            value = amountText,
                            onValueChange = {
                                amountText = it
                                error = null
                            },
                            textStyle = EcoType.EditAmount.copy(color = c.ink),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            cursorBrush = SolidColor(c.blue),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box {
                                    if (amountText.isEmpty()) {
                                        Text("0.00", style = EcoType.EditAmount, color = c.quiet)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    EcoHairline()
                }
            }

            Spacer(Modifier.height(15.dp))
            TxSectionLabel("类型")
            Spacer(Modifier.height(8.dp))
            TxTypeSegmented(
                type = type,
                onChange = {
                    type = it
                    error = null
                },
                modifier = Modifier.width(140.dp),
            )

            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TxSectionLabel("分类")
                Spacer(Modifier.weight(1f))
                Text("长按一级分类可选二级", style = EcoType.PageNote, color = c.quiet, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            if (catsOfType.isEmpty()) {
                Text(
                    "当前类型下还没有分类",
                    style = EcoType.PageNote,
                    color = c.quiet,
                )
            } else {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    catsOfType.forEach { category ->
                        val selectedCategory = categories.firstOrNull { it.id == categoryId }
                        TxCategoryChip(
                            name = category.name,
                            selected = category.id == categoryId ||
                                selectedCategory?.parentId == category.id,
                            onClick = { categoryId = category.id },
                            onLongClick = { subPickerFor = category },
                            height = 33.dp,
                        )
                    }
                }
                val picked = categories.firstOrNull { it.id == categoryId }
                if (picked?.parentId != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已选：${CategoryTree.displayName(categories, picked.id)}",
                        style = EcoType.PageNote,
                        color = c.blue,
                    )
                }
            }

            Spacer(Modifier.height(15.dp))
            TxSectionLabel("账户与日期")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TxSelectField(
                    value = accounts.firstOrNull { it.id == accountId }?.name ?: "",
                    options = accounts.map { it.id to it.name },
                    onSelect = { accountId = it },
                    modifier = Modifier.weight(1f),
                    label = "账户",
                    contentDescription = "选择账户",
                )
                TxDateField(
                    date = date,
                    onPick = { date = it },
                    modifier = Modifier.weight(1f),
                    label = "日期",
                )
            }

            Spacer(Modifier.height(15.dp))
            TxSectionLabel("商户与备注")
            Spacer(Modifier.height(8.dp))
            EcoField(
                label = "商户",
                value = counterparty,
                onValueChange = { counterparty = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "例如：瑞幸咖啡",
            )
            Spacer(Modifier.height(8.dp))
            EcoField(
                label = "备注",
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "可选",
            )

            Spacer(Modifier.height(15.dp))
            TxSectionLabel("资产")
            Spacer(Modifier.height(8.dp))
            TxSwitchField(
                label = "计入资产统计",
                subtitle = "关闭后这笔账不影响账户余额与资产，但仍计入账单和收支统计",
                checked = !excludeFromAssets,
                onCheckedChange = { excludeFromAssets = !it },
            )
            Spacer(Modifier.height(8.dp))
            TxSwitchField(
                label = "计入账单和收支统计",
                subtitle = "关闭后只在账单显示一条记录，不计入收支/预算/存款统计",
                checked = countInStats,
                onCheckedChange = { countInStats = it },
            )

            // 退款关联：仅编辑已有记录时显示
            if (txId > 0) {
                Spacer(Modifier.height(15.dp))
                TxSectionLabel("退款关联")
                Spacer(Modifier.height(8.dp))
                val current = existing
                val linkId = current?.refundLinkId
                when {
                    linkId != null && linkId > 0 -> {
                        val peer = linkedPair.firstOrNull()
                        EcoPane {
                            EcoRow(
                                title = peer?.counterparty?.ifBlank { peer.note }?.ifBlank { "已关联的账单" }
                                    ?: "关联的账单",
                                subtitle = buildString {
                                    append(if (peer?.type == TxType.EXPENSE) "支出 " else "收入 ")
                                    append(Money.formatYuan(peer?.amountCents ?: 0L))
                                    append(" · ")
                                    append(
                                        peer?.let {
                                            Instant.ofEpochMilli(it.timestamp)
                                                .atZone(ZoneId.systemDefault())
                                                .toLocalDate().toString()
                                        } ?: "—"
                                    )
                                },
                                trail = "已关联",
                                trailColor = c.blue,
                                onClick = null,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            EcoTextAction(
                                text = "解除关联",
                                onClick = {
                                    vm.unlinkTransactions(linkId) { err ->
                                        if (err == null) {
                                            EcoToast.show("已解除关联")
                                            linkedPair = emptyList()
                                            existing = existing?.copy(refundLinkId = null)
                                        } else {
                                            EcoToast.show(err, EcoPillTone.Bad)
                                        }
                                    }
                                },
                                color = c.clay,
                            )
                        }
                    }
                    current?.type == TxType.EXPENSE -> {
                        EcoRow(
                            title = "关联退款…",
                            subtitle = "选择对应的退款收入，按净额计入统计",
                            trail = null,
                            onClick = {
                                scope.launch {
                                    linkCandidates = vm.recentRefundIncomeCandidates()
                                    showLinkPicker = true
                                }
                            },
                        )
                    }
                    current?.refundMark == RefundMark.REFUND_INCOME -> {
                        EcoRow(
                            title = "关联支出…",
                            subtitle = "选择对应的支出账单，按净额计入统计",
                            trail = null,
                            onClick = {
                                scope.launch {
                                    linkCandidates = vm.recentExpenseCandidates()
                                    showLinkPicker = true
                                }
                            },
                        )
                    }
                }
            }

            val message = error
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = TxErrorStyle,
                    color = c.clay,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            EcoPrimaryButton(
                text = if (txId > 0) "保存修改" else "保存",
                onClick = ::save,
                enabled = valid,
                height = 46.dp,
            )

            Spacer(Modifier.height(26.dp))
        }
    }

    // 长按一级分类：选择它下面的二级分类（也可继续记在一级上）
    val picker = subPickerFor
    if (picker != null) {
        val children = CategoryTree.childrenOf(categories, picker.id)
        EcoSheet(
            onDismiss = { subPickerFor = null },
            title = picker.name,
            subtitle = if (children.isEmpty()) "此分类还没有二级分类" else "选择二级分类，或记在一级分类上",
        ) {
            Column {
                EcoRow(
                    title = "记在「${picker.name}」",
                    subtitle = "不使用二级分类",
                    trail = if (categoryId == picker.id) "已选" else null,
                    trailColor = c.blue,
                    onClick = {
                        categoryId = picker.id
                        subPickerFor = null
                    },
                )
                children.forEach { child ->
                    EcoRowDivider()
                    EcoRow(
                        title = child.name,
                        subtitle = CategoryTree.displayName(categories, child.id),
                        trail = if (categoryId == child.id) "已选" else null,
                        trailColor = c.blue,
                        onClick = {
                            categoryId = child.id
                            subPickerFor = null
                        },
                    )
                }
                if (children.isEmpty()) {
                    EcoRowDivider()
                    EcoRow(
                        title = "去「分类」页长按添加二级分类",
                        subtitle = "二级分类只占所属一级分类的预算",
                        onClick = null,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm && existing != null) {
        EcoConfirm(
            title = "删除记录",
            body = "确定删除这笔交易吗？删除后不可恢复。",
            confirmText = "删除",
            danger = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                existing?.let { record ->
                    vm.deleteTransaction(record.id) { err ->
                        if (err == null) {
                            EcoToast.show("已删除", EcoPillTone.Bad)
                        } else {
                            error = err
                        }
                        onBack()
                    }
                }
            },
        )
    }

    // 退款关联候选 sheet
    if (showLinkPicker) {
        val isExpenseSide = existing?.type == TxType.EXPENSE
        EcoSheet(
            onDismiss = { showLinkPicker = false },
            title = if (isExpenseSide) "选择退款收入" else "选择支出账单",
            subtitle = "选择与本笔关联的对端账单，关联后按净额计入统计",
        ) {
            if (linkCandidates.isEmpty()) {
                EcoRow(
                    title = "暂无可关联的账单",
                    subtitle = if (isExpenseSide) "还没有未关联的退款收入" else "还没有未关联的支出",
                    onClick = null,
                )
            } else {
                LazyColumn {
                    items(linkCandidates, key = { it.id }) { row ->
                        EcoRow(
                            title = row.counterparty.ifBlank { row.note }.ifBlank { "未命名" },
                            subtitle = buildString {
                                append(Money.formatYuan(row.amountCents))
                                append(" · ")
                                append(
                                    Instant.ofEpochMilli(row.timestamp)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate().toString()
                                )
                            },
                            onClick = {
                                val expenseId = if (isExpenseSide) existing!!.id else row.id
                                val refundId = if (isExpenseSide) row.id else existing!!.id
                                showLinkPicker = false
                                vm.linkTransactions(expenseId, refundId) { msg ->
                                    if (msg == null) {
                                        EcoToast.show("已关联")
                                        scope.launch {
                                            existing = vm.transactionById(existing!!.id)
                                            linkedPair = listOfNotNull(vm.transactionById(expenseId))
                                                .filter { it.id != existing?.id }
                                        }
                                    } else {
                                        pendingLinkConfirm = expenseId to refundId
                                        linkConfirmMessage = msg
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 退款多于付款：二次确认
    val pending = pendingLinkConfirm
    if (pending != null) {
        EcoConfirm(
            title = "退款多于付款",
            body = linkConfirmMessage ?: "",
            confirmText = "确认关联",
            danger = false,
            onDismiss = {
                pendingLinkConfirm = null
                linkConfirmMessage = null
            },
            onConfirm = {
                val (expenseId, refundId) = pending
                pendingLinkConfirm = null
                linkConfirmMessage = null
                vm.linkTransactionsConfirmed(expenseId, refundId) { err ->
                    if (err == null) {
                        EcoToast.show("已关联")
                        scope.launch {
                            existing = vm.transactionById(existing!!.id)
                            linkedPair = listOfNotNull(vm.transactionById(expenseId))
                                .filter { it.id != existing?.id }
                        }
                    } else {
                        EcoToast.show(err, EcoPillTone.Bad)
                    }
                }
            },
        )
    }
}
