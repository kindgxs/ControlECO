package com.controleco.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.MerchantRuleEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.SettingsDivider
import com.controleco.app.ui.common.SettingsLazyPage
import com.controleco.app.ui.common.SettingsPane
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 设置 → 商户归类：学习到的「商户 → 分类/账户」规则（确认或编辑捕获交易时自动学习）。
 *
 * 滚动结构：整页只有 [SettingsLazyPage] 里的一个 LazyColumn。旧实现把
 * `LazyColumn(Modifier.fillMaxSize())` 嵌在 `SettingsPage` 的 `verticalScroll` 里，
 * 规则非空时子列表拿到无限高度约束会直接崩；这里把 LazyColumn 提升为页面滚动容器，
 * 规则行放在同一个 item 的非滚动 Column 里，两者不会再嵌套滚动。
 *
 * 点按条目可查看并修改规则；删除单条与清空全部都需要二次确认，默认停在「取消」。
 */
@Composable
fun SettingsMerchantRulesScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val rules by vm.merchantRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var clearAsk by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MerchantRuleEntity?>(null) }
    var deleteAsk by remember { mutableStateOf<MerchantRuleEntity?>(null) }

    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    // 规则本身已是「最近在前」，这里再按更新时间兜底，避免 DAO 排序变化打乱展示
    val sorted = remember(rules) { rules.sortedByDescending { it.updatedAt } }

    SettingsLazyPage(micro = "MERCHANT RULES", title = "商户归类", onBack = onBack) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${sorted.size} 条已学规则 · 点按可查看/修改",
                    style = EcoType.SectionAside,
                    color = MaterialTheme.eco.muted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (sorted.isNotEmpty()) {
                    Text(
                        "清空全部（${sorted.size}）",
                        style = EcoType.Pill.copy(fontWeight = FontWeight.W800),
                        color = MaterialTheme.eco.clay,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { clearAsk = true }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }
            }
        }
        item {
            if (sorted.isEmpty()) {
                SettingsPane {
                    EcoEmpty(
                        title = "还没有学到的归类",
                        body = "确认几笔捕获账单后，这里会出现学到的商户规则",
                        icon = EcoIcons.Tag,
                    )
                }
            } else {
                SettingsPane {
                    sorted.forEachIndexed { index, rule ->
                        if (index > 0) SettingsDivider()
                        MerchantRuleRow(
                            rule = rule,
                            categoryName = categoryNames[rule.categoryId] ?: "已删除分类",
                            accountName = rule.accountId?.let { accountNames[it] ?: "已删除账户" },
                            onOpen = { editing = rule },
                            onDelete = { deleteAsk = rule },
                        )
                    }
                }
            }
        }
        item {
        }
    }

    editing?.let { rule ->
        MerchantRuleEditSheet(
            rule = rule,
            categories = categories,
            accounts = accounts,
            validate = vm::normalizeMerchantKey,
            onSave = { key, type, categoryId, accountId ->
                vm.updateMerchantRule(rule.id, key, type, categoryId, accountId)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleteAsk?.let { rule ->
        EcoConfirm(
            title = "删除这条归类？",
            body = "「${rule.matchKey}」以后不再自动归类。只删除学习到的规则，不影响已入账的账单。",
            confirmText = "删除",
            onConfirm = {
                vm.deleteMerchantRule(rule.id)
                deleteAsk = null
            },
            onDismiss = { deleteAsk = null },
            danger = true,
            defaultDismiss = true,
        )
    }

    if (clearAsk) {
        EcoConfirm(
            title = "清空商户归类",
            body = "只清除学习到的规则，不影响已入账的账单。之后确认账单会重新开始学习。",
            confirmText = "清空",
            onConfirm = {
                vm.clearMerchantRules()
                clearAsk = false
            },
            onDismiss = { clearAsk = false },
            danger = true,
            defaultDismiss = true,
        )
    }
}

/** 单条规则：商户关键字 + 方向 pill / → 分类 · 账户 · 命中次数 / 更新时间 / 删除 */
@Composable
private fun MerchantRuleRow(
    rule: MerchantRuleEntity,
    categoryName: String,
    accountName: String?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.eco
    val income = rule.type == TxType.INCOME
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 15.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.matchKey,
                    style = EcoType.RowTitle.copy(fontSize = 10.5.sp, fontWeight = FontWeight(740)),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                EcoPill(
                    text = if (income) "收入" else "支出",
                    tone = if (income) EcoPillTone.Good else EcoPillTone.Neutral,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildAnnotatedString {
                    append("→ ")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.W700)) {
                        append(categoryName)
                    }
                    if (accountName != null) {
                        append(" / ")
                        append(accountName)
                    }
                    append(" / 命中 ${rule.hits} 次")
                },
                style = EcoType.RowSub.copy(fontSize = 8.5.sp),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatTimestamp(rule.updatedAt),
                style = EcoType.PageNote.copy(fontSize = 8.sp),
                color = c.quiet,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "删除",
            style = EcoType.RowTrail.copy(fontSize = 9.5.sp, fontWeight = FontWeight.W700),
            color = c.clay,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onDelete)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
    }
}

/** 编辑弹层：商户关键字 / 方向 / 分类 / 账户（账户可置空） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MerchantRuleEditSheet(
    rule: MerchantRuleEntity,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    validate: (String) -> String?,
    onSave: (String, TxType, Long, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    var key by remember(rule.id) { mutableStateOf(rule.matchKey) }
    var type by remember(rule.id) { mutableStateOf(rule.type) }
    var categoryId by remember(rule.id) { mutableStateOf(rule.categoryId) }
    var accountId by remember(rule.id) { mutableStateOf(rule.accountId) }
    var error by remember(rule.id) { mutableStateOf<String?>(null) }
    val catsOfType = remember(categories, type) { categories.filter { it.type == type } }

    EcoSheet(
        onDismiss = onDismiss,
        title = "编辑商户归类",
        subtitle = "命中 ${rule.hits} 次 · 保存后下次捕获即生效",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EcoField(
                label = "商户关键字",
                value = key,
                onValueChange = {
                    key = it
                    if (error != null) error = null
                },
                placeholder = "例如：luckin coffee",
            )

            Column {
                Text("方向", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(6.dp))
                EcoSegmented(
                    options = listOf("支出", "收入"),
                    selectedIndex = if (type == TxType.EXPENSE) 0 else 1,
                    onSelect = { index ->
                        val next = if (index == 1) TxType.INCOME else TxType.EXPENSE
                        if (next != type) {
                            type = next
                            // 换了方向后原分类可能不适用，自动落到新方向的第一个分类
                            if (categories.none { it.id == categoryId && it.type == next }) {
                                categoryId = categories.firstOrNull { it.type == next }?.id ?: 0L
                            }
                        }
                        if (error != null) error = null
                    },
                    height = 32.dp,
                    small = true,
                )
            }

            Column {
                Text("分类", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(6.dp))
                if (catsOfType.isEmpty()) {
                    Text("暂无该方向的分类，请先在分类管理中添加", style = EcoType.PageNote, color = c.amber)
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EcoChip(
                        text = "不指定",
                        selected = accountId == null,
                        onClick = { accountId = null },
                    )
                    accounts.forEach { acc ->
                        EcoChip(
                            text = acc.name,
                            selected = accountId == acc.id,
                            onClick = { accountId = acc.id },
                        )
                    }
                }
            }

            error?.let { Text(it, style = EcoType.PageNote, color = c.clay) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(text = "取消", onClick = onDismiss, height = 42.dp)
                }
                Box(Modifier.weight(1f)) {
                    EcoPrimaryButton(
                        text = "保存",
                        height = 42.dp,
                        onClick = save@{
                            val normalized = validate(key)
                            if (normalized == null) {
                                error = "关键字无效：至少 2 个字符，不能是纯数字或通用词"
                                return@save
                            }
                            if (categoryId == 0L || categories.none { it.id == categoryId && it.type == type }) {
                                error = "请选择分类"
                                return@save
                            }
                            error = null
                            onSave(key, type, categoryId, accountId)
                        },
                    )
                }
            }
        }
    }
}
