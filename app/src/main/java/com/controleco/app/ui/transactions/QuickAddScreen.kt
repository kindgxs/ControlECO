package com.controleco.app.ui.transactions

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.CategoryTree
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoFilterStrip
import com.controleco.app.ui.eco.EcoHairline
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 快速记账：金额第一——大字金额 + 三步引导 + 收支分段 + 分类折叠条 + 数字键盘 + 记一笔。
 *
 * 版式照设计稿：内容区（金额/分段/分类/补充信息）可滚动，键盘与主按钮钉在底部；
 * 系统输入法弹出时（写商户/备注）只收起数字键盘、把高度让给表单，主按钮始终可达，
 * 关闭输入法后键盘自动恢复，保证数字键盘永远不会被输入法盖住。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QuickAddScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    var amountText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TxType.EXPENSE) }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var counterparty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var excludeFromAssets by remember { mutableStateOf(false) }
    var countInStats by remember { mutableStateOf(true) }
    var showMore by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 默认选中：默认账户（没有则第一个）+ 当前类型的第一个分类
    LaunchedEffect(accounts, categories, type) {
        if (accountId == null) {
            accountId = accounts.firstOrNull { it.isDefault }?.id ?: accounts.firstOrNull()?.id
        }
        if (categories.firstOrNull { it.id == categoryId }?.type != type) {
            categoryId = CategoryTree.topLevel(categories, type).firstOrNull()?.id
        }
    }

    fun appendKey(key: String) {
        if (key == ".") {
            if (amountText.isEmpty()) {
                amountText = "0."
                error = null
            } else if (!amountText.contains('.')) {
                amountText += "."
                error = null
            }
            return
        }
        val next = if (amountText == "0") key else amountText + key
        val dot = next.indexOf('.')
        if (dot >= 0 && next.length - dot - 1 > 2) {
            error = "最多两位小数"
            return
        }
        if (next.replace(".", "").length > 9) {
            error = "金额最多 9 位整数"
            return
        }
        amountText = next
        error = null
    }

    fun backspace() {
        error = null
        if (amountText.isNotEmpty()) amountText = amountText.dropLast(1)
    }

    val cents = Money.parseCents(amountText)
    val hasAmount = cents != null && cents > 0L
    val valid = hasAmount && categoryId != null && accountId != null
    val catsOfType = categories.filter { it.type == type }
    val selectedCategory = catsOfType.firstOrNull { it.id == categoryId }
    val step = when {
        !hasAmount -> 0
        categoryId == null -> 1
        else -> 2
    }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val contentScroll = rememberScrollState()

    fun save() {
        val amount = Money.parseCents(amountText)
        when {
            amount == null || amount <= 0L -> error = "请输入有效金额"
            categoryId == null -> error = "请选择分类"
            accountId == null -> error = "请选择账户"
            else -> {
                // 手动记账：默认记的是"现在"（含时分），选了别的日期则沿用当前时刻
                val zone = ZoneId.systemDefault()
                val ts = if (date == LocalDate.now()) {
                    System.currentTimeMillis()
                } else {
                    date.atTime(LocalTime.now()).atZone(zone).toInstant().toEpochMilli()
                }
                vm.saveTransaction(
                    -1L, type, amount, categoryId!!, accountId!!, ts, note.trim(), counterparty.trim(),
                    excludeFromAssets, countInStats,
                )
                EcoToast.show("已记一笔")
                onBack()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        EcoTopBar(micro = "QUICK ENTRY", title = "记一笔", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(contentScroll)
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            AmountHero(amountText = amountText, hasAmount = hasAmount)

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 28.dp), contentAlignment = Alignment.Center) {
                val message = error
                if (message != null) {
                    Text(message, style = TxErrorStyle, color = MaterialTheme.eco.clay, textAlign = TextAlign.Center)
                } else {
                    QuickGuide(step = step)
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // 设计稿里快速记账的收支切换是「深色选中块 + 白字」，与编辑页的白滑块不同
                EcoFilterStrip(
                    options = listOf("支出", "收入"),
                    selectedIndex = if (type == TxType.EXPENSE) 0 else 1,
                    onSelect = {
                        type = if (it == 0) TxType.EXPENSE else TxType.INCOME
                        error = null
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            CategoryBlock(
                categories = catsOfType,
                selected = selectedCategory,
                expanded = categoryExpanded,
                onToggle = { categoryExpanded = !categoryExpanded },
                onSelect = { categoryId = it },
            )

            Spacer(Modifier.height(10.dp))
            DetailsToggle(expanded = showMore, onToggle = { showMore = !showMore })

            if (showMore) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TxSelectField(
                            value = accounts.firstOrNull { it.id == accountId }?.name ?: "",
                            options = accounts.map { it.id to it.name },
                            onSelect = { accountId = it },
                            modifier = Modifier.weight(1f),
                            label = "账户",
                            height = 34.dp,
                            radius = 9.dp,
                            contentDescription = "选择账户",
                        )
                        TxDateField(
                            date = date,
                            onPick = { date = it },
                            modifier = Modifier.weight(1f),
                            label = "日期",
                            height = 34.dp,
                            radius = 9.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EcoField(
                            label = "商户",
                            value = counterparty,
                            onValueChange = { counterparty = it },
                            modifier = Modifier.weight(1f),
                            placeholder = "例如：瑞幸咖啡",
                            minHeight = 34.dp,
                        )
                        EcoField(
                            label = "备注",
                            value = note,
                            onValueChange = { note = it },
                            modifier = Modifier.weight(1f),
                            placeholder = "可选",
                            minHeight = 34.dp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // 不计入资产：账户余额/资产不变，账单与收支统计照常
                    TxSwitchField(
                        label = "计入资产统计",
                        subtitle = "关闭后这笔账不影响账户余额与资产，但仍计入账单和收支统计",
                        checked = !excludeFromAssets,
                        onCheckedChange = { excludeFromAssets = !it },
                    )
                    Spacer(Modifier.height(6.dp))
                    // 不计入统计：只在账单里留一条记录，收支/预算/存款都不算它（与上一项互不影响）
                    TxSwitchField(
                        label = "计入账单和收支统计",
                        subtitle = "关闭后只在账单显示一条记录，不计入收支/预算/存款统计",
                        checked = countInStats,
                        onCheckedChange = { countInStats = it },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // 输入法弹出时只收起数字键盘（表单需要高度），主按钮始终可达；
        // 关掉输入法后键盘自动回来，保证数字键盘永远不会被输入法盖住。
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding()),
        ) {
            if (!imeVisible) {
                EcoHairline(color = MaterialTheme.eco.ruleStrong)
                Spacer(Modifier.height(4.dp))
                TxKeypad(
                    onDigit = ::appendKey,
                    onDot = { appendKey(".") },
                    onBackspace = ::backspace,
                    onClear = {
                        amountText = ""
                        error = null
                    },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            EcoPrimaryButton(
                text = "记一笔",
                onClick = ::save,
                modifier = Modifier.padding(horizontal = 22.dp),
                enabled = valid,
                leading = EcoIcons.Check,
                height = 46.dp,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 金额主体：AMOUNT / 金额 + ¥ + 自适应大数字 + 闪烁光标（背后一层极淡的玉色光斑） */
@Composable
private fun AmountHero(amountText: String, hasAmount: Boolean) {
    val c = MaterialTheme.eco
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        c.jade.copy(alpha = if (c.isDark) 0.13f else 0.11f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.maxDimension * 0.46f,
                ),
            )
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AMOUNT / 金额", style = TxAmountLabelStyle, color = c.blue)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                Text(
                    Money.symbol,
                    style = TxCurrencyStyle,
                    color = c.jade,
                    modifier = Modifier.padding(bottom = 9.dp),
                )
                Spacer(Modifier.width(5.dp))
                FittedAmountText(
                    text = amountText.ifEmpty { "0" },
                    color = if (hasAmount) c.ink else c.muted.copy(alpha = 0.58f),
                )
                BlinkingCaret()
            }
        }
    }
}

/** 53/780 大字：超出可用宽度按 0.88 递减（与 EcoAutoMoney 同一套缩放策略，光标才能紧跟数字） */
@Composable
private fun FittedAmountText(text: String, color: Color) {
    val base: TextStyle = EcoType.KeyAmount
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxPx = with(density) { 236.dp.toPx() }
    val fitted = remember(text, maxPx) {
        var size = base.fontSize
        var guard = 0
        while (guard++ < 12) {
            val result = measurer.measure(
                text = AnnotatedString(text),
                style = base.copy(fontSize = size),
                maxLines = 1,
                softWrap = false,
            )
            if (result.size.width <= maxPx || size.value <= 24f) break
            size = (size.value * 0.88f).coerceAtLeast(24f).sp
        }
        size
    }
    Text(
        text = text,
        style = base.copy(fontSize = fitted),
        color = color,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "qa-caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse),
        label = "qa-caret-alpha",
    )
    Box(
        Modifier
            .padding(start = 3.dp, bottom = 8.dp)
            .width(1.5.dp)
            .height(31.dp)
            .background(MaterialTheme.eco.blue.copy(alpha = alpha)),
    )
}

/** 三步引导：金额 → 分类 → 记一笔；done = 玉绿实心白勾，current = 蓝底外扩 */
@Composable
private fun QuickGuide(step: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GuideNode(EcoIcons.Plus, done = step > 0, current = step == 0)
        GuideLink()
        GuideNode(EcoIcons.Tag, done = step > 1, current = step == 1)
        GuideLink()
        GuideNode(EcoIcons.Check, done = false, current = step == 2)
    }
}

@Composable
private fun GuideNode(icon: ImageVector, done: Boolean, current: Boolean) {
    val c = MaterialTheme.eco
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        if (current) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(1.dp, c.blue.copy(alpha = 0.22f), CircleShape),
            )
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    when {
                        done -> c.jade
                        current -> c.blueSoft
                        else -> c.surface
                    },
                )
                .border(
                    1.dp,
                    when {
                        done -> Color.Transparent
                        current -> c.blue.copy(alpha = 0.38f)
                        else -> c.rule
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    done -> Color.White
                    current -> c.blueDeep
                    else -> c.quiet
                },
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun GuideLink() {
    Box(
        Modifier
            .width(23.dp)
            .height(1.dp)
            .background(MaterialTheme.eco.rule),
    )
}

/** 分类：抬头（分类 + tag 图标）+ 38 高的选择条 + 展开后的分类 chips（长按一级分类可选二级） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryBlock(
    categories: List<CategoryEntity>,
    selected: CategoryEntity?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(12.dp)
    var subPickerFor by remember { mutableStateOf<CategoryEntity?>(null) }
    val tops = remember(categories) {
        categories.filter { it.parentId == null }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }
    val selectedLabel = selected?.let { CategoryTree.displayName(categories, it.id) } ?: "请选择分类"
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("分类", style = TxSectionLabelStyle, color = c.muted)
            Spacer(Modifier.weight(1f))
            Text("长按一级分类可选二级", style = EcoType.PageNote, color = c.quiet, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(EcoIcons.Tag, contentDescription = null, tint = c.blue, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = c.ink.copy(alpha = if (c.isDark) 0.3f else 0.08f),
                    spotColor = c.ink.copy(alpha = if (c.isDark) 0.34f else 0.12f),
                )
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.ruleStrong, shape)
                .clickable { onToggle() }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                EcoCategoryTile(
                    categoryName = selected.name,
                    icon = selected.icon,
                    size = 20.dp,
                    radius = 7.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = selectedLabel,
                style = TxCategoryNameStyle,
                color = if (selected == null) c.muted else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                EcoIcons.ChevDown,
                contentDescription = if (expanded) "收起分类" else "展开分类",
                tint = c.blue,
                modifier = Modifier
                    .size(13.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            if (categories.isEmpty()) {
                Text(
                    "还没有分类，先去「分类」页新建一个",
                    style = EcoType.PageNote,
                    color = c.quiet,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                )
            } else {
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 82.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tops.forEach { category ->
                        TxCategoryChip(
                            name = category.name,
                            selected = category.id == selected?.id ||
                                (selected?.parentId == category.id),
                            onClick = { onSelect(category.id) },
                            onLongClick = { subPickerFor = category },
                            height = 27.dp,
                        )
                    }
                }
            }
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
                    trail = if (selected?.id == picker.id) "已选" else null,
                    trailColor = c.blue,
                    onClick = {
                        onSelect(picker.id)
                        subPickerFor = null
                    },
                )
                children.forEach { child ->
                    EcoRowDivider()
                    EcoRow(
                        title = child.name,
                        subtitle = CategoryTree.displayName(categories, child.id),
                        trail = if (selected?.id == child.id) "已选" else null,
                        trailColor = c.blue,
                        onClick = {
                            onSelect(child.id)
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
}

/** 补充信息开关：edit 图标 + 文案 + 右侧 chevron（展开转 90°） */
@Composable
private fun DetailsToggle(expanded: Boolean, onToggle: () -> Unit) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .ecoPressable(scaleTo = 0.99f, onClick = onToggle)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(EcoIcons.Edit, contentDescription = null, tint = c.blue, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text("补充信息", style = TxToggleStyle, color = c.muted)
        Spacer(Modifier.weight(1f))
        Icon(
            EcoIcons.Chev,
            contentDescription = if (expanded) "收起补充信息" else "展开补充信息",
            tint = c.blue,
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
        )
    }
}
