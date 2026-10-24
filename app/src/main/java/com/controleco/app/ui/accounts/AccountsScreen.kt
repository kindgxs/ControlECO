package com.controleco.app.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.AccountBalanceRow
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.AccountKind
import com.controleco.app.data.model.AssetAccountSnapshot
import com.controleco.app.data.model.AssetMath
import com.controleco.app.data.plan.InstallmentMath
import com.controleco.app.data.model.AssetSummary
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoDangerButton
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoSwitchRow
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money

/**
 * 账户管理（设计稿 5.22）：总资产卡 + 账户列表 + 新增/编辑底部卡。
 *
 * 余额口径不变：账户余额 = 期初余额 + 全部收入 − 全部支出（VM 的 accountBalances）。
 * 账户表本身没有颜色/图标字段，行首标记按账户名稳定取色 + 首字，与分类标记同一套规则。
 */
@Composable
fun AccountsScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val balances by vm.accountBalances.collectAsStateWithLifecycle(initialValue = emptyList())
    val asset by vm.assetSummary.collectAsStateWithLifecycle(initialValue = null)
    val installmentPlans by vm.installmentPlans.collectAsStateWithLifecycle()

    // 各账户「还未到出账时间」的分期金额：只用于展示，不计入流水与余额
    val unpostedByAccount = remember(installmentPlans) {
        installmentPlans.groupBy { it.accountId }.mapValues { (_, plans) ->
            plans.sumOf { plan ->
                InstallmentMath.unpostedTotal(
                    plan.totalPrincipalCents, plan.totalInterestCents, plan.periods, plan.postedPeriods,
                )
            }
        }
    }

    val snapshots = remember(accounts, balances) {
        accounts.map { account ->
            val row = balances.firstOrNull { it.accountId == account.id }
            AssetAccountSnapshot(
                id = account.id,
                sharedGroupId = account.sharedGroupId,
                initialBalanceCents = row?.initialBalanceCents ?: account.initialBalanceCents,
                flowCents = row?.signedSumCents ?: 0L,
            )
        }
    }
    // 共用额度组内的卡：流水合并展示（扣卡 1 时卡 2 同步），资产只算一次
    val displayFlows = remember(snapshots) { AssetMath.displayedFlows(snapshots) }
    val cashAccounts = remember(accounts) { accounts.filter { it.kind == AccountKind.CASH } }
    val creditAccounts = remember(accounts) { accounts.filter { it.kind == AccountKind.CREDIT } }

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var sheetError by remember { mutableStateOf<String?>(null) }

    fun openEditor(account: AccountEntity?) {
        editing = account
        sheetError = null
        showEditor = true
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "ACCOUNTS",
            title = "账户管理",
            onBack = onBack,
            action = {
                EcoIconButton(
                    icon = EcoIcons.Plus,
                    contentDescription = "新增账户",
                    onClick = { openEditor(null) },
                    size = 38.dp,
                )
            },
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp),
        ) {
            item(key = "total") {
                Spacer(Modifier.height(8.dp))
                AssetsTotalCard(asset = asset, accountCount = accounts.size)
            }
            item(key = "headCash") {
                EcoSectionHead(title = "存款", aside = "CASH")
            }
            item(key = "cashList") {
                AccountPane(
                    accounts = cashAccounts,
                    balances = balances,
                    displayFlows = displayFlows,
                    allAccounts = accounts,
                    unpostedByAccount = unpostedByAccount,
                    emptyBody = "点右上角 + 新增存款账户，余额与流水都记在账户下",
                    onOpen = { openEditor(it) },
                )
            }
            item(key = "headCredit") {
                EcoSectionHead(title = "信用卡", aside = "CREDIT")
            }
            item(key = "creditList") {
                AccountPane(
                    accounts = creditAccounts,
                    balances = balances,
                    displayFlows = displayFlows,
                    allAccounts = accounts,
                    unpostedByAccount = unpostedByAccount,
                    emptyBody = "点右上角 + 新增信用卡；同行的卡可设为共用额度",
                    onOpen = { openEditor(it) },
                )
            }
            item(key = "note") {
                EcoPageNote(
                    "账户余额 = 期初余额 + 全部收入 − 全部支出。修改期初余额会改变当前余额，不会改动流水；" +
                        "账户下已有流水时无法删除。「未出账分期」是尚未到出账时间的期数金额，不计入流水与余额。",
                )
            }
        }
    }

    if (showEditor) {
        val editingNow = editing
        val deleteAction: (() -> Unit)? = editingNow?.let { entity ->
            val action: () -> Unit = {
                vm.deleteAccount(entity.id) { err ->
                    if (err == null) {
                        sheetError = null
                        showEditor = false
                    } else {
                        sheetError = err
                    }
                }
            }
            action
        }
        AccountEditorSheet(
            initial = editingNow,
            accounts = accounts,
            currentBalanceCents = editingNow?.let { entity ->
                val bal = balances.firstOrNull { it.accountId == entity.id }
                if (bal == null) null else bal.initialBalanceCents + bal.signedSumCents
            },
            error = sheetError,
            onMakeDefault = {
                editingNow?.let { vm.setDefaultAccount(it.id) }
            },
            onSave = { id, name, kind, cents, note, creditLimitCents, isDefault, shareWith ->
                vm.saveAccount(
                    id = id, name = name, initialBalanceCents = cents, note = note,
                    creditLimitCents = creditLimitCents, sharedGroupId = 0L,
                    isDefault = isDefault, kind = kind,
                ) { err ->
                    if (err == null) {
                        if (shareWith != null && id > 0L) vm.setSharedCreditGroup(id, shareWith)
                        sheetError = null
                        showEditor = false
                    } else {
                        sheetError = err
                    }
                }
            },
            onDelete = deleteAction,
            onClose = { showEditor = false },
        )
    }
}

/** 一组账户（存款 / 信用卡）：列表 pane + 空态 */
@Composable
private fun AccountPane(
    accounts: List<AccountEntity>,
    balances: List<AccountBalanceRow>,
    displayFlows: Map<Long, Long>,
    allAccounts: List<AccountEntity>,
    unpostedByAccount: Map<Long, Long>,
    emptyBody: String,
    onOpen: (AccountEntity) -> Unit,
) {
    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
        if (accounts.isEmpty()) {
            EcoEmpty(
                title = "暂无账户",
                body = emptyBody,
                icon = EcoIcons.Wallet,
            )
        } else {
            Column {
                accounts.forEachIndexed { index, account ->
                    val bal = balances.firstOrNull { it.accountId == account.id }
                    val initial = bal?.initialBalanceCents ?: account.initialBalanceCents
                    val flow = displayFlows[account.id] ?: bal?.signedSumCents ?: 0L
                    AccountRowItem(
                        account = account,
                        initialCents = initial,
                        flowCents = flow,
                        unpostedCents = unpostedByAccount[account.id] ?: 0L,
                        isDefault = account.isDefault,
                        sharedCount = if (account.sharedGroupId != 0L) {
                            allAccounts.count { it.sharedGroupId == account.sharedGroupId }
                        } else {
                            0
                        },
                        onClick = { onOpen(account) },
                    )
                    if (index != accounts.lastIndex) EcoRowDivider(inset = 15.dp)
                }
            }
        }
    }
}

/** 净资产卡：净资产 + 存款 / 负债两列 + 账户数 */
@Composable
private fun AssetsTotalCard(asset: AssetSummary?, accountCount: Int) {
    val c = MaterialTheme.eco
    val net = asset?.netCents ?: 0L
    val deposit = asset?.depositCents ?: 0L
    val liability = asset?.liabilityCents ?: 0L
    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 13.dp),
        ) {
            Text("NET ASSETS / 净资产", style = EcoType.AssetLabel, color = c.quiet)
            Spacer(Modifier.height(5.dp))
            EcoAutoMoney(
                text = Money.formatYuanGroupedFixed(net),
                style = EcoType.AssetAmount.copy(fontSize = 27.sp, lineHeight = 32.sp),
                color = if (net < 0) c.clay else c.ink,
                modifier = Modifier.fillMaxWidth(),
                minSize = 19.sp,
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AssetMiniColumn(
                    label = "存款",
                    cents = deposit,
                    color = c.jade,
                    modifier = Modifier.weight(1f),
                )
                AssetMiniColumn(
                    label = "负债",
                    cents = liability,
                    color = c.clay,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "$accountCount 个账户，点账户编辑",
                style = EcoType.PageNote,
                color = c.quiet,
            )
        }
    }
}

@Composable
private fun AssetMiniColumn(
    label: String,
    cents: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    Column(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(c.surface2)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = EcoType.PageNote, color = c.muted)
        Spacer(Modifier.height(3.dp))
        EcoAutoMoney(
            text = Money.formatYuanGroupedFixed(cents),
            style = EcoType.RowTrail.copy(fontSize = 14.sp),
            color = color,
            modifier = Modifier.fillMaxWidth(),
            minSize = 11.sp,
            textAlign = TextAlign.Start,
        )
    }
}

/** 账户行：首字标记（按账户名稳定取色）+ 名称/备注 + 余额（期初 + 流水） */
@Composable
private fun AccountRowItem(
    account: AccountEntity,
    initialCents: Long,
    flowCents: Long,
    unpostedCents: Long,
    isDefault: Boolean,
    sharedCount: Int,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    val currentCents = initialCents + flowCents
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .ecoPressable(scaleTo = 0.99f, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountMark(name = account.name, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.name,
                    style = EcoType.RowTitle,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isDefault) {
                    Spacer(Modifier.width(6.dp))
                    EcoPill("默认", EcoPillTone.Neutral)
                }
                if (sharedCount > 1) {
                    Spacer(Modifier.width(6.dp))
                    EcoPill("共用额度", EcoPillTone.Blue)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                account.note.ifBlank {
                    when {
                        isDefault -> "新交易默认使用此账户"
                        sharedCount > 1 -> "$sharedCount 张卡共用欠款额度"
                        else -> "无备注"
                    }
                },
                style = EcoType.RowSub,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Money.formatYuanGroupedFixed(currentCents),
                style = EcoType.BillAmount.copy(fontSize = 13.sp),
                color = if (currentCents < 0) c.clay else c.ink,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "期初 ${Money.formatYuanGrouped(initialCents)}，流水 ${signedPlain(flowCents)}",
                style = EcoType.PageNote.copy(fontSize = 8.sp, lineHeight = 11.sp),
                color = c.quiet,
                maxLines = 1,
            )
            if (unpostedCents > 0L) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "未出账分期 ${Money.formatYuanGrouped(unpostedCents)}",
                    style = EcoType.PageNote.copy(fontSize = 8.sp, lineHeight = 11.sp),
                    color = c.amber,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 账户行首标记：32 圆角方块 + 首字，颜色与分类色板同源 */
@Composable
private fun AccountMark(name: String, size: Dp) {
    val c = MaterialTheme.eco
    val tint = categoryColor(MaterialTheme.accBook.categoryPalette, name.ifBlank { "账户" })
    val corner = RoundedCornerShape(size * 0.31f)
    Box(
        Modifier
            .size(size)
            .clip(corner)
            .background(tint.copy(alpha = if (c.isDark) 0.24f else 0.13f))
            .border(1.dp, tint.copy(alpha = 0.20f), corner),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).ifBlank { "账" },
            style = EcoType.RowTitle.copy(fontSize = (size.value * 0.37f).sp, fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
        )
    }
}

/** 账户新增/编辑底部卡：名称 / 期初余额 / 信用额度 / 默认账户 / 共用额度 / 删除（带二次确认） */
@Composable
private fun AccountEditorSheet(
    initial: AccountEntity?,
    accounts: List<AccountEntity>,
    currentBalanceCents: Long?,
    error: String?,
    onMakeDefault: () -> Unit,
    onSave: (Long, String, AccountKind, Long, String, Long, Boolean, Long?) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.eco
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var kind by remember(initial?.id) { mutableStateOf(initial?.kind ?: AccountKind.CASH) }
    var balanceText by remember(initial?.id) {
        mutableStateOf(initial?.let { Money.formatCents(it.initialBalanceCents) } ?: "")
    }
    var note by remember(initial?.id) { mutableStateOf(initial?.note ?: "") }
    var creditText by remember(initial?.id) {
        mutableStateOf(
            initial?.let { if (it.creditLimitCents > 0L) Money.formatCents(it.creditLimitCents) else "" } ?: "",
        )
    }
    var makeDefault by remember(initial?.id) {
        mutableStateOf(initial?.isDefault == true || accounts.isEmpty())
    }
    // 已存在的共用组：找出同组的另一张卡
    var sharedWith by remember(initial?.id) {
        mutableStateOf(
            initial?.let { me ->
                if (me.sharedGroupId == 0L) 0L
                else accounts.firstOrNull { it.id != me.id && it.sharedGroupId == me.sharedGroupId }?.id ?: 0L
            } ?: 0L,
        )
    }
    var localError by remember(initial?.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    EcoSheet(
        onDismiss = onClose,
        title = if (initial == null) "新增账户" else "编辑账户",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountMark(name = name.ifBlank { "账户" }, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name.trim().ifBlank { "新账户" },
                        style = EcoType.RowTitle,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (currentBalanceCents != null) {
                            "当前余额 ${Money.formatYuanGroupedFixed(currentBalanceCents)}"
                        } else {
                            "保存后开始记账"
                        },
                        style = EcoType.RowSub,
                        color = c.muted,
                    )
                }
            }

            EcoField(
                label = "账户名称",
                value = name,
                onValueChange = {
                    name = it
                    localError = null
                },
                placeholder = "例如：招商银行",
            )

            EcoMoneyField(
                label = "期初余额",
                value = balanceText,
                onValueChange = {
                    balanceText = it
                    localError = null
                },
                placeholder = "0.00",
                supporting = "可留空 = 0；修改期初余额会改变当前余额，不会改动流水。",
            )

            EcoSegmented(
                options = AccountKind.entries.map { it.label },
                selectedIndex = if (kind == AccountKind.CASH) 0 else 1,
                onSelect = { index ->
                    kind = AccountKind.entries[index]
                    if (kind == AccountKind.CASH) {
                        creditText = ""
                        sharedWith = 0L
                    }
                },
            )

            if (kind == AccountKind.CREDIT) {
                EcoMoneyField(
                    label = "信用额度",
                    value = creditText,
                    onValueChange = { creditText = it },
                    placeholder = "信用卡可填，0 或留空 = 不设额度",
                    supporting = "共用额度的卡会取组内最大值作为统一额度。",
                )
            }

            EcoSwitchRow(
                title = "设为默认账户",
                subtitle = "新增交易时默认选中此账户",
                checked = makeDefault,
                enabled = !(initial?.isDefault == true),
                onCheckedChange = {
                    makeDefault = it
                    if (it && initial != null && !initial.isDefault) onMakeDefault()
                },
            )

            EcoField(
                label = "备注",
                value = note,
                onValueChange = { note = it },
                placeholder = "可选，例如：储蓄卡 尾号 0921",
            )

            // 同行多卡共用欠款额度：把两张卡绑定到同一组
            if (kind == AccountKind.CREDIT && initial != null &&
                accounts.any { it.kind == AccountKind.CREDIT && it.id != initial.id }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("共用欠款额度", style = EcoType.FieldLabel, color = c.muted)
                    EcoRow(
                        title = "不共用",
                        subtitle = "此卡单独计算额度",
                        trail = if (sharedWith == 0L) "已选" else null,
                        trailColor = c.blue,
                        onClick = { sharedWith = 0L },
                        minHeight = 46.dp,
                    )
                    accounts.filter { it.kind == AccountKind.CREDIT && it.id != initial.id }.forEach { other ->
                        EcoRow(
                            title = other.name,
                            subtitle = "额度 ${Money.formatYuanGrouped(other.creditLimitCents)}",
                            trail = if (sharedWith == other.id) "已选" else null,
                            trailColor = c.blue,
                            onClick = { sharedWith = other.id },
                            minHeight = 46.dp,
                        )
                    }
                }
            }

            val shownError = localError ?: error
            if (shownError != null) {
                Text(shownError, style = EcoType.PageNote, color = c.clay)
            }

            EcoPrimaryButton(
                text = "保存",
                onClick = {
                    val trimmed = name.trim()
                    val cents = if (balanceText.isBlank()) 0L else Money.parseCents(balanceText)
                    val credit = if (creditText.isBlank()) 0L else Money.parseCents(creditText)
                    when {
                        trimmed.isEmpty() -> localError = "请输入名称"
                        cents == null -> localError = "初始金额格式不正确"
                        credit == null -> localError = "信用额度格式不正确"
                        else -> onSave(
                            initial?.id ?: 0L,
                            trimmed,
                            kind,
                            cents,
                            note.trim(),
                            credit,
                            makeDefault,
                            if (kind == AccountKind.CREDIT && sharedWith != 0L) sharedWith else null,
                        )
                    }
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(text = "取消", onClick = onClose, modifier = Modifier.weight(1f))
                if (onDelete != null) {
                    EcoDangerButton(
                        text = "删除账户",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(
                "账户与余额只保存在本机；删除账户不会影响已入账的流水。",
                style = EcoType.PageNote,
                color = c.quiet,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }

    if (confirmDelete && onDelete != null) {
        EcoConfirm(
            title = "删除账户",
            body = "删除后不可恢复。账户下若已有流水，需要先处理这些账单才能删除。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 流水净额：带显式正负号的短金额（用于"期初 + 流水"拆解） */
private fun signedPlain(cents: Long): String = when {
    cents > 0L -> "+" + Money.formatYuanGrouped(cents)
    cents < 0L -> "-" + Money.formatYuanGrouped(-cents)
    else -> Money.formatYuanGrouped(0L)
}
