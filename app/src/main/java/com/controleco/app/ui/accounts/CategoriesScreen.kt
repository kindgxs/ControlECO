package com.controleco.app.ui.accounts

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.CategoryIcon
import com.controleco.app.data.CategoryTree
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.budget.CategoryBudgetMath
import com.controleco.app.data.budget.CategoryBudgetMode
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.ColorWheelDialog
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.Glyph
import com.controleco.app.ui.common.GlyphIcon
import com.controleco.app.ui.common.PercentInput
import com.controleco.app.ui.common.glyphByName
import com.controleco.app.ui.common.glyphForName
import com.controleco.app.ui.common.glyphTint
import com.controleco.app.ui.common.hueWheelBrush
import com.controleco.app.ui.common.rgbOf
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoDangerButton
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.transactions.TxSelectField
import com.controleco.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑卡图标宫格的首屏数量（更多图形可展开，全部 26 个都在） */
private const val QUICK_GLYPH_COUNT = 12

/**
 * 分类管理（设计稿 5.23）：支出/收入两段列表 + 全局「金额 / 比例」预算口径。
 *
 * 新增/编辑全部收在**同一张可滚动底部卡**里：名称、类型、12 图标宫格（可展开全部）、
 * 自定义颜色（色板 + 色环）、Emoji、相册图片、分类预算（金额/比例）、删除。
 * 预算仍是全局口径开关（与首页/列表一致），切换只改显示与输入方式。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val budgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val budgetPercents by vm.categoryBudgetPercents.collectAsStateWithLifecycle()
    val budgetMode by vm.categoryBudgetMode.collectAsStateWithLifecycle()
    val monthlyBudget by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var editingParent by remember { mutableStateOf<CategoryEntity?>(null) }
    var actionTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var draftIcon by remember { mutableStateOf("") }
    var sheetError by remember { mutableStateOf<String?>(null) }
    // 本次编辑里刚导入、还没保存的图片：取消时删掉，避免留孤儿文件
    var freshImport by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val fileName = withContext(Dispatchers.IO) { CategoryIcon.importImage(context, uri) }
                if (fileName != null) {
                    CategoryIcon.deleteImage(context, freshImport)
                    freshImport = fileName
                    draftIcon = CategoryIcon.image(fileName)
                } else {
                    Toast.makeText(context, "这张图片读取失败，换一张试试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openEditor(category: CategoryEntity?, parent: CategoryEntity? = null) {
        editing = category
        editingParent = parent ?: category?.parentId?.let { pid -> categories.firstOrNull { it.id == pid } }
        draftIcon = category?.icon ?: ""
        freshImport = null
        sheetError = null
        showEditor = true
    }

    fun discardFreshImport() {
        CategoryIcon.deleteImage(context, freshImport)
        freshImport = null
    }

    // 「支出」按一级分类排序后紧跟其二级分类；预算只挂在一级分类上
    val expenseRows = CategoryTree.flatten(categories, TxType.EXPENSE)
    val incomeRows = CategoryTree.flatten(categories, TxType.INCOME)
    val expenseTop = CategoryTree.topLevel(categories, TxType.EXPENSE)
    val allocatedCents = expenseTop.sumOf { budgets[it.id] ?: 0L }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EcoTopBar(
                micro = "CATEGORIES",
                title = "分类管理",
                onBack = onBack,
                action = {
                    EcoIconButton(
                        icon = EcoIcons.Plus,
                        contentDescription = "新增分类",
                        onClick = { openEditor(null) },
                        size = 38.dp,
                    )
                },
            )

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp),
            ) {
                item(key = "head") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildString {
                                append("支出分类 已分配 ")
                                append(Money.formatYuanGroupedFixed(allocatedCents))
                                if (monthlyBudget > 0L) {
                                    append(" / ").append(Money.formatYuanGroupedFixed(monthlyBudget))
                                } else {
                                    append("，未设月预算")
                                }
                            },
                            style = EcoType.RowSub.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp,
                            ),
                            color = MaterialTheme.eco.muted,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        Spacer(Modifier.width(8.dp))
                        EcoSegmented(
                            options = listOf("金额", "比例"),
                            selectedIndex = if (budgetMode == CategoryBudgetMode.PERCENT) 1 else 0,
                            onSelect = {
                                vm.setCategoryBudgetMode(
                                    if (it == 1) CategoryBudgetMode.PERCENT else CategoryBudgetMode.AMOUNT,
                                )
                            },
                            modifier = Modifier.width(108.dp),
                            height = 30.dp,
                            small = true,
                        )
                    }
                }

                item(key = "expense") {
                    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                        if (expenseRows.isEmpty()) {
                            EcoEmpty(
                                title = "暂无支出分类",
                                body = "点右上角 + 新建一个分类；长按已有分类可新增二级分类或调整顺序",
                                icon = EcoIcons.Bowl,
                            )
                        } else {
                            Column {
                                expenseRows.forEachIndexed { index, category ->
                                    CategoryRowItem(
                                        category = category,
                                        budgetCents = budgets[category.id] ?: 0L,
                                        percentBp = budgetPercents[category.id] ?: 0,
                                        budgetMode = budgetMode,
                                        depth = if (category.parentId == null) 0 else 1,
                                        onClick = { openEditor(category) },
                                        onLongClick = { actionTarget = category },
                                    )
                                    if (index != expenseRows.lastIndex) EcoRowDivider(inset = 15.dp)
                                }
                            }
                        }
                    }
                }

                item(key = "incomeHead") {
                    EcoSectionHead(title = "收入分类", aside = "${CategoryTree.topLevel(categories, TxType.INCOME).size} ITEMS")
                }

                item(key = "income") {
                    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                        if (incomeRows.isEmpty()) {
                            EcoEmpty(
                                title = "暂无收入分类",
                                body = "新增分类时把类型切到「收入」即可",
                                icon = EcoIcons.Gift,
                            )
                        } else {
                            Column {
                                incomeRows.forEachIndexed { index, category ->
                                    CategoryRowItem(
                                        category = category,
                                        budgetCents = budgets[category.id] ?: 0L,
                                        percentBp = budgetPercents[category.id] ?: 0,
                                        budgetMode = budgetMode,
                                        depth = if (category.parentId == null) 0 else 1,
                                        onClick = { openEditor(category) },
                                        onLongClick = { actionTarget = category },
                                    )
                                    if (index != incomeRows.lastIndex) EcoRowDivider(inset = 15.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 长按分类：新增二级分类 / 编辑 / 调整同级顺序
    val target = actionTarget
    if (target != null) {
        val siblings = if (target.parentId == null) {
            CategoryTree.topLevel(categories, target.type)
        } else {
            CategoryTree.childrenOf(categories, target.parentId)
        }
        val pos = siblings.indexOfFirst { it.id == target.id }
        val parentName = target.parentId?.let { CategoryTree.nameOf(categories, it) }
        EcoSheet(
            onDismiss = { actionTarget = null },
            title = target.name,
            subtitle = if (parentName == null) "一级分类" else "二级分类 · 上级 $parentName",
        ) {
            Column {
                if (target.parentId == null) {
                    EcoRow(
                        title = "新增二级分类",
                        subtitle = "挂在「${target.name}」下，只占该一级分类预算",
                        icon = EcoIcons.Plus,
                        onClick = {
                            actionTarget = null
                            openEditor(null, target)
                        },
                    )
                    EcoRowDivider()
                }
                EcoRow(
                    title = "编辑分类",
                    icon = EcoIcons.Edit,
                    onClick = {
                        actionTarget = null
                        openEditor(target)
                    },
                )
                if (pos > 0) {
                    EcoRowDivider()
                    EcoRow(
                        title = "上移",
                        subtitle = "与同级分类交换顺序",
                        onClick = {
                            vm.moveCategory(target.id, up = true)
                            actionTarget = null
                        },
                    )
                }
                if (pos in 0 until siblings.lastIndex) {
                    EcoRowDivider()
                    EcoRow(
                        title = "下移",
                        subtitle = "与同级分类交换顺序",
                        onClick = {
                            vm.moveCategory(target.id, up = false)
                            actionTarget = null
                        },
                    )
                }
            }
        }
    }

    if (showEditor) {
        val editingNow = editing
        val deleteAction: (() -> Unit)? = editingNow?.let { entity ->
            val action: () -> Unit = {
                vm.deleteCategory(entity.id) { err ->
                    if (err == null) {
                        discardFreshImport()
                        sheetError = null
                        showEditor = false
                    } else {
                        sheetError = err
                    }
                }
            }
            action
        }
        CategoryEditorSheet(
            initial = editingNow,
            parent = editingParent,
            parentOptions = CategoryTree
                .topLevel(categories, editingParent?.type ?: (editingNow?.type ?: TxType.EXPENSE))
                .filter { it.id != editingNow?.id },
            error = sheetError,
            budgetMode = budgetMode,
            initialBudgetCents = editingNow?.let { budgets[it.id] } ?: 0L,
            initialBudgetPercentBp = editingNow?.let { budgetPercents[it.id] } ?: 0,
            totalBudgetCents = monthlyBudget,
            // 可分配余量 = 月总预算 − 其它分类预算之和（本分类不计入）
            otherAllocatedCents = budgets.entries
                .filter { it.key != editingNow?.id }
                .sumOf { it.value },
            // 比例口径的可分配余量 = 100% − 其它分类比例之和
            otherAllocatedBp = budgetPercents.entries
                .filter { it.key != editingNow?.id }
                .sumOf { it.value },
            icon = draftIcon,
            onIconChange = { draftIcon = it },
            onPickImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onClearFreshImport = { discardFreshImport() },
            onBudgetModeChange = { vm.setCategoryBudgetMode(it) },
            onSave = { id, name, type, budgetCents, budgetBp, icon, parentId ->
                vm.saveCategory(id, name, type, icon, parentId) { savedId, err ->
                    if (err == null) {
                        when {
                            type != TxType.EXPENSE || parentId != null -> {
                                vm.setCategoryBudget(savedId, 0L)
                                vm.setCategoryBudgetPercent(savedId, 0)
                            }
                            budgetMode == CategoryBudgetMode.PERCENT ->
                                vm.setCategoryBudgetPercent(savedId, budgetBp)
                            else -> vm.setCategoryBudget(savedId, budgetCents)
                        }
                        freshImport = null // 已入库，文件归分类所有
                        sheetError = null
                        showEditor = false
                    } else {
                        sheetError = err
                    }
                }
            },
            onDelete = deleteAction,
            onClose = {
                discardFreshImport()
                showEditor = false
            },
        )
    }
}

/** 分类行：分类标记 + 名称 + 预算读数（金额 / 比例）或「收入」；长按打开二级/排序菜单 */
@Composable
private fun CategoryRowItem(
    category: CategoryEntity,
    budgetCents: Long,
    percentBp: Int,
    budgetMode: CategoryBudgetMode,
    depth: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    val isExpense = category.type == TxType.EXPENSE
    val value = when {
        !isExpense -> "收入"
        depth > 0 -> "占上级预算"
        budgetMode == CategoryBudgetMode.PERCENT ->
            percentBp.takeIf { it > 0 }?.let { CategoryBudgetMath.formatPercent(it) }
        else -> budgetCents.takeIf { it > 0L }?.let { Money.formatYuanGroupedFixed(it) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .ecoPressable(scaleTo = 0.99f, onClick = onClick, onLongClick = onLongClick)
            .padding(start = 15.dp + (depth * 18).dp, end = 15.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EcoCategoryTile(
            categoryName = category.name,
            icon = category.icon,
            size = if (depth > 0) 25.dp else 31.dp,
            radius = if (depth > 0) 8.dp else 10.dp,
            bare = true,
        )
        Spacer(Modifier.width(11.dp))
        Text(
            category.name,
            style = if (depth > 0) EcoType.RowSub.copy(fontSize = 13.sp) else EcoType.RowTitle,
            color = if (depth > 0) c.muted else c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (value == null) {
            Text("未设预算", style = EcoType.RowTrail, color = c.quiet, maxLines = 1)
        } else {
            Text(
                value,
                style = EcoType.RowTrail.copy(fontWeight = FontWeight.Bold),
                color = if (isExpense) c.blue else c.quiet,
                maxLines = 1,
            )
        }
    }
}

/**
 * 新增 / 编辑分类底部卡——**唯一**的编辑入口，所有字段都在这一张可滚动卡里：
 * 名称 → 类型 → 图标（12 宫格 / 更多）→ 图形颜色（色板 + 色环）→ Emoji → 预算（金额/比例）→ 删除。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorSheet(
    initial: CategoryEntity?,
    parent: CategoryEntity?,
    parentOptions: List<CategoryEntity>,
    error: String?,
    budgetMode: CategoryBudgetMode,
    initialBudgetCents: Long,
    initialBudgetPercentBp: Int,
    totalBudgetCents: Long,
    otherAllocatedCents: Long,
    otherAllocatedBp: Int,
    icon: String,
    onIconChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onClearFreshImport: () -> Unit,
    onBudgetModeChange: (CategoryBudgetMode) -> Unit,
    onSave: (Long, String, TxType, Long, Int, String, Long?) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.eco
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: parent?.type ?: TxType.EXPENSE) }
    var parentId by remember(initial?.id) {
        mutableStateOf(initial?.parentId ?: parent?.id ?: 0L)
    }
    var budgetText by remember(initial?.id) {
        mutableStateOf(if (initialBudgetCents > 0L) Money.formatCents(initialBudgetCents) else "")
    }
    var percentCells by remember(initial?.id) {
        mutableStateOf(CategoryBudgetMath.bpToCells(initialBudgetPercentBp))
    }
    var emojiText by remember(initial?.id) { mutableStateOf(CategoryIcon.emojiOf(icon) ?: "") }
    var localError by remember(initial?.id) { mutableStateOf<String?>(null) }
    var showColorWheel by remember { mutableStateOf(false) }
    var showAllGlyphs by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 图形颜色：图标不是 emoji / 相册图片时都有"颜色"可谈（默认图形也能只换颜色）
    val glyphName = CategoryIcon.glyphOf(icon)
    val isGlyphBased = CategoryIcon.emojiOf(icon) == null && CategoryIcon.imageOf(icon) == null
    val currentColor = if (isGlyphBased) CategoryIcon.glyphColorOf(icon) else null
    // 当前（或将要用的）形状：自定义色环从它自动取到的颜色起步
    val previewGlyph = glyphName?.let { glyphByName(it) } ?: glyphForName(name.ifBlank { "默认" })

    val emojiInvalid = emojiText.isNotBlank() && !CategoryIcon.isEmojiInput(emojiText)
    val palette = MaterialTheme.accBook.categoryPalette
    val paletteRgb = palette.map { it.toArgb() and 0xFFFFFF }.toSet()

    // 还可分配多少：总预算 − 其它分类预算（本分类不计）。没设总预算时不限制
    val hasTotalBudget = totalBudgetCents > 0L
    val allocatableCents = if (hasTotalBudget) (totalBudgetCents - otherAllocatedCents).coerceAtLeast(0L) else 0L
    // 上限取"可分配"与"本分类现值"的较大者：历史数据已经超标时，至少能原样保存/往下调
    val maxAllowedCents = if (hasTotalBudget) maxOf(allocatableCents, initialBudgetCents) else Long.MAX_VALUE
    // 比例口径：可分配 = 100% − 其它分类比例之和（万分比）；同样保留"原值可原样保存"的余地
    val allocatableBp = (CategoryBudgetMath.PERCENT_SCALE - otherAllocatedBp).coerceAtLeast(0)
    val maxAllowedBp = maxOf(allocatableBp, initialBudgetPercentBp)

    val typedBp = CategoryBudgetMath.cellsToBp(percentCells)
    val typedCents = Money.parseCents(budgetText.trim())

    /** 卡内切换预算口径：先把已输入的值折算到另一侧，再切全局开关（不丢本次输入） */
    fun switchBudgetMode(next: CategoryBudgetMode) {
        if (next == budgetMode) return
        if (next == CategoryBudgetMode.PERCENT) {
            if (CategoryBudgetMath.cellsToBp(percentCells) == 0) {
                val cents = Money.parseCents(budgetText.trim()) ?: initialBudgetCents
                val bp = CategoryBudgetMath.percentOf(cents, totalBudgetCents)
                if (bp != null) percentCells = CategoryBudgetMath.bpToCells(bp)
            }
        } else {
            if (Money.parseCents(budgetText.trim()) == null) {
                val bp = CategoryBudgetMath.cellsToBp(percentCells)
                val cents = if (bp > 0) {
                    CategoryBudgetMath.centsOf(bp, totalBudgetCents)
                } else {
                    initialBudgetCents
                }
                if (cents > 0L) budgetText = Money.formatCents(cents)
            }
        }
        localError = null
        onBudgetModeChange(next)
    }

    val allGlyphs = Glyph.entries.toList()
    val visibleGlyphs = if (showAllGlyphs) allGlyphs else allGlyphs.take(QUICK_GLYPH_COUNT)
    val glyphTintColor = currentColor?.let { Color(it or 0xFF000000.toInt()) }
    // 「其他」是删除分类的兜底：不提供改名入口、类型锁死、删除置灰，靠界面本身表达约束
    val isFallback = initial?.name == "其他" && initial?.parentId == null
    val selectedParent = parentOptions.firstOrNull { it.id == parentId }

    EcoSheet(
        onDismiss = onClose,
        title = if (initial == null) "新增分类" else "编辑分类",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ---------- 顶部预览 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                EcoCategoryTile(
                    categoryName = name.ifBlank { "默认" },
                    icon = icon,
                    size = 44.dp,
                    radius = 14.dp,
                    bare = true,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name.trim().ifBlank { "新分类" },
                        style = EcoType.RowTitle,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        iconLabel(icon),
                        style = EcoType.RowSub,
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!isFallback) {
                EcoField(
                    label = "名称",
                    value = name,
                    onValueChange = {
                        name = it
                        localError = null
                    },
                    placeholder = "例如：餐饮",
                )
            }

            // ---------- 上级分类（二级分类） ----------
            Column {
                Text("上级分类", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(6.dp))
                TxSelectField(
                    value = selectedParent?.name ?: "",
                    options = listOf(0L to "无（一级分类）") + parentOptions.map { it.id to it.name },
                    onSelect = { picked ->
                        parentId = picked
                        parentOptions.firstOrNull { it.id == picked }?.let { p -> type = p.type }
                        localError = null
                    },
                    placeholder = "无（一级分类）",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    if (parentId == 0L) {
                        "作为一级分类，可单独设置分类预算"
                    } else {
                        "二级分类不单独占预算，只计入所属一级分类的预算与统计"
                    },
                    style = EcoType.PageNote,
                    color = c.quiet,
                )
            }

            // ---------- 类型 ----------
            Column {
                Text("类型", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(6.dp))
                EcoSegmented(
                    options = listOf("支出", "收入"),
                    selectedIndex = if (type == TxType.INCOME) 1 else 0,
                    onSelect = {
                        type = if (it == 1) TxType.INCOME else TxType.EXPENSE
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isFallback && parentId == 0L,
                )
            }

            // ---------- 图标：12 宫格（可展开全部） ----------
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("图标", style = EcoType.FieldLabel, color = c.muted, modifier = Modifier.weight(1f))
                    if (glyphName != null) EcoTextAction("按名称取形", onClick = { onIconChange("") })
                }
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visibleGlyphs.chunked(6).forEach { rowGlyphs ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowGlyphs.forEach { glyph ->
                                GlyphChoice(
                                    glyph = glyph,
                                    selected = glyphName == glyph.name,
                                    tint = glyphTintColor ?: glyphTint(glyph, name.ifBlank { "默认" }),
                                    modifier = Modifier.weight(1f).aspectRatio(1f),
                                    onClick = {
                                        emojiText = ""
                                        localError = null
                                        onIconChange(CategoryIcon.glyph(glyph.name, currentColor))
                                    },
                                )
                            }
                            repeat(6 - rowGlyphs.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                EcoTextAction(
                    if (showAllGlyphs) {
                        "收起图形"
                    } else {
                        "更多图形（${allGlyphs.size - QUICK_GLYPH_COUNT}）"
                    },
                    onClick = { showAllGlyphs = !showAllGlyphs },
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EcoGhostButton(
                        text = "从相册选图",
                        onClick = onPickImage,
                        height = 38.dp,
                        modifier = Modifier.weight(1f),
                    )
                    if (icon.isNotEmpty()) {
                        EcoGhostButton(
                            text = "恢复默认",
                            onClick = {
                                onClearFreshImport()
                                emojiText = ""
                                onIconChange("")
                            },
                            height = 38.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---------- 图形颜色：色板 + 自定义色环 ----------
            Column {
                Text("图形颜色", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    // 默认：按分类名自动取色（等于清除自定义颜色）
                    ColorDot(
                        selected = currentColor == null,
                        onClick = {
                            emojiText = ""
                            onIconChange(if (glyphName == null) "" else CategoryIcon.glyph(glyphName))
                        },
                    ) {
                        Text(
                            "默认",
                            style = EcoType.Pill.copy(fontSize = 8.sp),
                            color = if (currentColor == null) c.ink else c.muted,
                        )
                    }
                    palette.forEach { color ->
                        val rgb = color.toArgb() and 0xFFFFFF
                        ColorDot(
                            selected = currentColor == rgb,
                            color = color,
                            onClick = {
                                emojiText = ""
                                // 固定过形状就保留形状；否则只存颜色（形状按分类名取）
                                onIconChange(
                                    if (glyphName == null) {
                                        CategoryIcon.glyphColorOnly(rgb)
                                    } else {
                                        CategoryIcon.glyph(glyphName, rgb)
                                    },
                                )
                            },
                        ) {
                            if (currentColor == rgb) {
                                Icon(
                                    EcoIcons.Check,
                                    contentDescription = null,
                                    tint = if (color.luminance() > 0.55f) Color.Black else Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                    // 自定义：色环里挑任意颜色（色环里也能直接输十六进制 / RGB）
                    val customSelected = currentColor != null && currentColor !in paletteRgb
                    ColorDot(
                        selected = customSelected,
                        color = if (customSelected) Color(currentColor!! or 0xFF000000.toInt()) else null,
                        brush = if (customSelected) null else hueWheelBrush(),
                        onClick = {
                            emojiText = ""
                            showColorWheel = true
                        },
                    ) {
                        if (customSelected) {
                            Icon(
                                EcoIcons.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }

            // ---------- Emoji ----------
            EcoField(
                label = "Emoji 图标",
                value = emojiText,
                onValueChange = { input ->
                    emojiText = input
                    if (CategoryIcon.isEmojiInput(input)) {
                        localError = null
                        onIconChange(CategoryIcon.emoji(input))
                    }
                },
                placeholder = "在此输入 emoji 作为图标",
                isError = emojiInvalid,
                supporting = if (emojiInvalid) {
                    "请输入 emoji（不接受文字）"
                } else {
                    null
                },
            )

            // ---------- 分类预算（仅一级支出分类） ----------
            if (type == TxType.EXPENSE && parentId == 0L) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "分类月预算",
                            style = EcoType.FieldLabel,
                            color = c.muted,
                            modifier = Modifier.weight(1f),
                        )
                        EcoSegmented(
                            options = listOf("金额", "比例"),
                            selectedIndex = if (budgetMode == CategoryBudgetMode.PERCENT) 1 else 0,
                            onSelect = {
                                switchBudgetMode(
                                    if (it == 1) CategoryBudgetMode.PERCENT else CategoryBudgetMode.AMOUNT,
                                )
                            },
                            modifier = Modifier.width(104.dp),
                            height = 28.dp,
                            small = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (budgetMode == CategoryBudgetMode.PERCENT) {
                        val overAllocatable = typedBp > maxAllowedBp
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(c.surface2)
                                .border(
                                    1.dp,
                                    if (overAllocatable) c.clay else c.rule,
                                    RoundedCornerShape(11.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            PercentInput(
                                cells = percentCells,
                                onCellsChange = {
                                    percentCells = it
                                    localError = null
                                },
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            buildString {
                                append("还可分配 ${CategoryBudgetMath.formatPercent(allocatableBp)}")
                                if (typedBp > 0 && hasTotalBudget) {
                                    append("　≈ ")
                                    append(
                                        Money.formatYuanGroupedFixed(
                                            CategoryBudgetMath.centsOf(typedBp, totalBudgetCents),
                                        ),
                                    )
                                }
                            },
                            style = EcoType.PageNote,
                            color = if (overAllocatable) c.clay else c.quiet,
                        )
                    } else {
                        EcoMoneyField(
                            label = "每月金额",
                            value = budgetText,
                            onValueChange = {
                                budgetText = it
                                localError = null
                            },
                            placeholder = "0.00",
                            isError = typedCents != null && hasTotalBudget && typedCents > allocatableCents,
                            supporting = if (hasTotalBudget) {
                                "还可分配 ${Money.formatYuanGroupedFixed(allocatableCents)}"
                            } else {
                                null
                            },
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
                    val parentValue = if (parentId != 0L) parentId else null
                    when {
                        trimmed.isEmpty() -> localError = "请输入名称"
                        // 收入 / 二级分类没有独立预算：不解析（也不阻塞）预算输入
                        type != TxType.EXPENSE || parentId != 0L ->
                            onSave(initial?.id ?: 0L, trimmed, type, 0L, 0, icon, parentValue)
                        budgetMode == CategoryBudgetMode.PERCENT -> {
                            val bp = CategoryBudgetMath.cellsToBp(percentCells)
                            if (bp > maxAllowedBp) {
                                localError = "最多可设 ${CategoryBudgetMath.formatPercent(maxAllowedBp)}"
                            } else {
                                onSave(initial?.id ?: 0L, trimmed, type, 0L, bp, icon, parentValue)
                            }
                        }
                        else -> {
                            val raw = budgetText.trim()
                            val cents = if (raw.isEmpty()) 0L else Money.parseCents(raw)
                            if (cents == null || cents < 0L) {
                                localError = "分类月预算请输入有效金额（或留空）"
                            } else if (type == TxType.EXPENSE && cents > maxAllowedCents) {
                                localError = "最多可设 ${Money.formatYuanGrouped(maxAllowedCents)}"
                            } else {
                                onSave(initial?.id ?: 0L, trimmed, type, cents, 0, icon, parentValue)
                            }
                        }
                    }
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(text = "取消", onClick = onClose, modifier = Modifier.weight(1f))
                if (onDelete != null) {
                    EcoDangerButton(
                        text = "删除分类",
                        onClick = { confirmDelete = true },
                        enabled = !isFallback,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        EcoConfirm(
            title = "删除分类",
            body = "删除后，该分类下的流水会归入「其他」。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (showColorWheel) {
        // 从当前颜色起步（没自定义过就从"这个分类的自动取色"起步），确定后连形状一起写入
        val initialColor = currentColor?.let { Color(it or 0xFF000000.toInt()) }
            ?: glyphTint(previewGlyph, name.ifBlank { "默认" })
        ColorWheelDialog(
            initial = initialColor,
            onConfirm = { picked ->
                val rgb = rgbOf(picked)
                emojiText = ""
                onIconChange(
                    if (glyphName == null) {
                        CategoryIcon.glyphColorOnly(rgb)
                    } else {
                        CategoryIcon.glyph(glyphName, rgb)
                    },
                )
                showColorWheel = false
            },
            onDismiss = { showColorWheel = false },
        )
    }
}

/** 图标宫格里的一格：选中蓝底描边，图形按当前自定义色 / 分类名取色 */
@Composable
private fun GlyphChoice(
    glyph: Glyph,
    selected: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) c.blueSoft else c.surface2)
            .border(
                1.dp,
                if (selected) c.blue else c.rule,
                RoundedCornerShape(11.dp),
            )
            .ecoPressable(scaleTo = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph = glyph, tint = tint, size = 30.dp)
    }
}

/** 色板块：可传纯色或渐变（自定义色环用），选中加粗描边 + 对勾 */
@Composable
private fun ColorDot(
    selected: Boolean,
    onClick: () -> Unit,
    color: Color? = null,
    brush: Brush? = null,
    content: @Composable () -> Unit = {},
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(30.dp)
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(color ?: c.surface2))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.rule, shape)
            .ecoPressable(scaleTo = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 图标来源的说明文案 */
private fun iconLabel(icon: String): String = when {
    CategoryIcon.emojiOf(icon) != null -> "emoji 图标"
    CategoryIcon.glyphOf(icon) != null -> "内置图形"
    CategoryIcon.glyphColorOf(icon) != null -> "内置图形（自定颜色）"
    CategoryIcon.imageOf(icon) != null -> "相册图片"
    else -> "默认：按分类名自动取形"
}
