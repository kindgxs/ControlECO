package com.controleco.app.ui.transactions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.CardTransform
import com.controleco.app.data.PeriodMode
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.importCardImage
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.PeriodPickerSheet
import com.controleco.app.ui.common.dayOfWeekZh
import com.controleco.app.ui.common.periodScopeLabel
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoFilterStrip
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 范围/多选删除的二次确认（标题 + 说明 + 执行动作） */
private data class DeleteConfirm(val title: String, val body: String, val onConfirm: () -> Unit)

/** 收支类型筛选：三段式（全部 / 支出 / 收入） */
private enum class TxFilter(val label: String) {
    ALL("全部"),
    EXPENSE("支出"),
    INCOME("收入"),
}

private const val FIRST_PAGE_ROWS = 30
private const val PAGE_ROWS = 30
private const val MIN_THUMB_FRACTION = 0.12f
private const val MAX_THUMB_FRACTION = 0.9f

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 账单行文案：标题 + 小行第一行 + 备注行（备注为空时不占位） */
internal data class BillRowText(val title: String, val subLine1: String, val note: String)

internal fun billRowText(counterparty: String, categoryName: String, time: String, note: String): BillRowText {
    val merchant = counterparty.trim()
    return if (merchant.isNotEmpty()) {
        BillRowText(title = merchant, subLine1 = "$time  $categoryName", note = note)
    } else {
        BillRowText(title = categoryName, subLine1 = time, note = note)
    }
}

internal fun pageDays(
    days: List<LocalDate>,
    grouped: Map<LocalDate, List<TransactionRow>>,
    limit: Int,
): Pair<List<LocalDate>, Int> {
    if (days.isEmpty()) return emptyList<LocalDate>() to 0
    val out = ArrayList<LocalDate>(minOf(days.size, 16))
    var rows = 0
    for (d in days) {
        out += d
        rows += grouped[d]?.size ?: 0
        if (rows >= limit) break
    }
    return out to rows
}

internal fun rowsUpToDay(
    days: List<LocalDate>,
    grouped: Map<LocalDate, List<TransactionRow>>,
    day: LocalDate,
): Int {
    var acc = 0
    for (d in days) {
        acc += grouped[d]?.size ?: 0
        if (d == day) return acc
    }
    return acc
}

internal fun headerIndexForDay(
    days: List<LocalDate>,
    grouped: Map<LocalDate, List<TransactionRow>>,
    index: Int,
): Int {
    var acc = 0
    for (i in 0 until index) acc += 1 + (grouped[days[i]]?.size ?: 0)
    return acc
}

/**
 * 账单页：结余封面 + 周期工具条 + 筛选 + 日期分组列表。
 * 月/年周期、月首日、退款显示、分页、跳日期、多选批删、封面图裁剪全部沿用 ViewModel 契约。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    vm: LedgerViewModel,
    onEditTransaction: (Long) -> Unit,
    onOpenCalendar: () -> Unit,
    scrollToTopTick: Int = 0,
) {
    val rows by vm.periodRows.collectAsStateWithLifecycle()
    val totals by vm.periodTotals.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val mode by vm.periodMode.collectAsStateWithLifecycle()
    val currentAccountingMonth by vm.currentAccountingMonth.collectAsStateWithLifecycle()
    val monthStartDay by vm.monthStartDay.collectAsStateWithLifecycle()
    val cardImageUri by vm.cardImageUri.collectAsStateWithLifecycle()
    val cardTransform by vm.cardTransform.collectAsStateWithLifecycle()
    val cardImage by vm.cardImage.collectAsStateWithLifecycle()
    val cardImageColor by vm.cardImageColor.collectAsStateWithLifecycle()
    val cardImageLuminance by vm.cardImageLuminance.collectAsStateWithLifecycle()

    val rowsReady = rows ?: return
    val totalsReady = totals ?: return

    val expense = totalsReady.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totalsReady.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

    // 筛选与分页深度都要跨「进详情页再返回」保留：分页深度若回到首页，
    // rememberLazyListState 恢复出来的 index 会被只排了首页的那点内容夹到页尾。
    var filter by rememberSaveable { mutableStateOf(TxFilter.ALL) }
    var loadedLimit by rememberSaveable { mutableIntStateOf(FIRST_PAGE_ROWS) }
    var jumpTarget by remember { mutableStateOf<LocalDate?>(null) }
    var pendingJump by remember { mutableStateOf<LocalDate?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showCoverSheet by remember { mutableStateOf(false) }
    var showImageEditor by remember { mutableStateOf(false) }
    var showBatchSheet by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<String?>(null) }
    var imageSnapshot by remember { mutableStateOf<CardImageSnapshot?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<DeleteConfirm?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { importCardImage(context, uri) }
                if (saved != null) {
                    imageSnapshot = CardImageSnapshot(cardImageUri, cardImageColor, cardImageLuminance, cardTransform)
                    pendingImage = saved.path
                    vm.setCardImage(saved.path)
                    vm.setCardImageStats(saved.colorArgb, saved.luminance)
                    vm.setCardTransform(CardTransform.DEFAULT)
                    showCoverSheet = false
                    showImageEditor = true
                } else {
                    EcoToast.show("这张图片读取失败，换一张试试", EcoPillTone.Bad)
                }
            }
        }
    }

    // 只在“再次点击当前 tab”时回顶：页面从别的 tab 切回来时不应复位已保留的滚动位置
    var lastReenterTick by remember { mutableIntStateOf(scrollToTopTick) }
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > lastReenterTick) {
            lastReenterTick = scrollToTopTick
            listState.animateScrollToItem(0)
        }
    }
    // 周期/筛选变了才回顶：LaunchedEffect 每次重新进入组合（从编辑页返回、切 tab 回来）
    // 都会重跑一遍，无条件 scrollToItem(0) 会把刚恢复好的滚动位置抹掉。
    var lastPeriodKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(month, mode) {
        val key = billPeriodKey(month, mode)
        val previous = lastPeriodKey
        lastPeriodKey = key
        if (!shouldResetBillList(previous, key)) return@LaunchedEffect
        selecting = false
        selected = emptySet()
        loadedLimit = FIRST_PAGE_ROWS
        listState.scrollToItem(0)
    }
    var lastFilterKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(filter) {
        val key = filter.name
        val previous = lastFilterKey
        lastFilterKey = key
        if (!shouldResetBillList(previous, key)) return@LaunchedEffect
        loadedLimit = FIRST_PAGE_ROWS
        listState.scrollToItem(0)
    }

    val report: (String?) -> Unit = { err -> if (err != null) EcoToast.show(err, EcoPillTone.Bad) }

    fun monthRange(m: YearMonth): Pair<Long, Long> = MonthPeriod.of(m, monthStartDay).toMillisRange()

    fun yearRange(m: YearMonth): Pair<Long, Long> = MonthPeriod.year(m.year, monthStartDay).toMillisRange()

    fun confirmRange(title: String, range: Pair<Long, Long>) {
        scope.launch {
            val n = vm.countTransactionsInRange(range.first, range.second)
            confirm = DeleteConfirm(title, "将删除 $n 条记录，删除后不可恢复。") {
                vm.deleteTransactionsInRange(range.first, range.second, report)
            }
        }
    }

    fun confirmDeleteAll() {
        scope.launch {
            val n = vm.countAllTransactions()
            confirm = DeleteConfirm("删除全部记录", "将删除全部 $n 条记录（所有月份），删除后不可恢复。") {
                vm.deleteAllTransactions(report)
            }
        }
    }

    val filtered = remember(rowsReady, filter) {
        when (filter) {
            TxFilter.ALL -> rowsReady
            TxFilter.EXPENSE -> rowsReady.filter { it.type == TxType.EXPENSE }
            TxFilter.INCOME -> rowsReady.filter { it.type == TxType.INCOME }
        }
    }
    val grouped: Map<LocalDate, List<TransactionRow>> = remember(filtered) {
        filtered.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    val allDays = grouped.keys.toList()
    val (shownDays, shownCount) = remember(allDays, grouped, loadedLimit) {
        pageDays(allDays, grouped, loadedLimit)
    }
    val hasMoreDays = shownDays.size < allDays.size

    val loadMore by rememberUpdatedState(hasMoreDays)
    LaunchedEffect(listState, filtered) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, totalItems) ->
                if (loadMore && totalItems > 0 && lastVisible >= totalItems - 3) {
                    loadedLimit += PAGE_ROWS
                }
            }
    }

    LaunchedEffect(pendingJump, shownDays) {
        val target = pendingJump ?: return@LaunchedEffect
        if (allDays.isEmpty()) return@LaunchedEffect
        val day = allDays.firstOrNull { it <= target } ?: allDays.last()
        val index = shownDays.indexOf(day)
        if (index < 0) {
            loadedLimit = maxOf(loadedLimit, rowsUpToDay(allDays, grouped, day))
            return@LaunchedEffect
        }
        val headerIndex = headerIndexForDay(shownDays, grouped, index)
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > headerIndex }
        listState.scrollToItem(headerIndex)
        pendingJump = null
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "LEDGER / ${month.year}",
            title = "账单",
            titleLeading = {
                EcoIconButton(
                    icon = EcoIcons.Calendar,
                    contentDescription = "日历",
                    onClick = onOpenCalendar,
                    size = 30.dp,
                )
            },
        )
        BalanceCard(
            mode = mode,
            balanceCents = income - expense,
            incomeCents = income,
            expenseCents = expense,
            imagePath = cardImageUri,
            image = cardImage,
            transform = cardTransform,
            placeholderColor = cardImageColor,
            placeholderLuminance = cardImageLuminance,
            style = CoverStyleStore.current,
            onModeChange = { vm.setPeriodMode(it) },
            onOpenImageSettings = { showCoverSheet = true },
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        BillToolbar(
            month = month,
            mode = mode,
            filter = filter,
            count = filtered.size,
            onPrev = { vm.prevPeriod() },
            onNext = { vm.nextPeriod() },
            onOpenPicker = { showPicker = true },
            onFilter = { filter = it },
            onMore = { showBatchSheet = true },
        )
        if (selecting) {
            BatchBar(
                selectedCount = selected.size,
                onSelectAll = { selected = rowsReady.map { it.id }.toSet() },
                onDelete = {
                    if (selected.isNotEmpty()) {
                        confirm = DeleteConfirm("删除已选记录", "将删除 ${selected.size} 条记录，删除后不可恢复。") {
                            vm.deleteTransactionsByIds(selected) { err ->
                                report(err)
                                if (err == null) {
                                    selecting = false
                                    selected = emptySet()
                                }
                            }
                        }
                    }
                },
                onCancel = { selecting = false; selected = emptySet() },
            )
        }
        Text(
            "${filtered.size} 笔",
            style = EcoType.PageNote.copy(fontSize = 9.sp),
            color = MaterialTheme.eco.quiet,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(end = 22.dp, top = 2.dp, bottom = 4.dp),
        )

        when {
            rowsReady.isEmpty() -> BillEmpty(
                title = "${periodScopeLabel(mode)}还没有记录",
                body = "点右下角「记账」补一笔，或到「个人」页导入账单。",
                modifier = Modifier.weight(1f),
            )
            filtered.isEmpty() -> BillEmpty(
                title = "${periodScopeLabel(mode)}没有${filter.label}记录",
                body = "切回「全部」看看其它记录。",
                modifier = Modifier.weight(1f),
            )
            else -> {
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp, end = 10.dp),
                    ) {
                        shownDays.forEach { day ->
                            val dayRows = grouped[day].orEmpty()
                            stickyHeader(key = "d${day.toEpochDay()}") {
                                DayHeader(day = day, dayRows = dayRows, onClickDay = { jumpTarget = day })
                            }
                            items(dayRows, key = { it.id }) { row ->
                                BillRow(
                                    row = row,
                                    selecting = selecting,
                                    selected = row.id in selected,
                                    onClick = {
                                        if (selecting) {
                                            selected = if (row.id in selected) selected - row.id else selected + row.id
                                        } else {
                                            onEditTransaction(row.id)
                                        }
                                    },
                                    onLongClick = {
                                        if (!selecting) {
                                            selecting = true
                                            selected = setOf(row.id)
                                        }
                                    },
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.eco.rule,
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(start = 55.dp),
                                )
                            }
                        }
                    }
                    ListScrollbar(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(14.dp)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(ecoBottomContentPadding(extra = 0.dp)))
    }

    if (showPicker) {
        PeriodPickerSheet(
            mode = mode,
            current = month,
            onSelectMonth = { vm.setMonth(it) },
            onSelectYear = { vm.setMonth(YearMonth.of(it, month.monthValue)) },
            onDismiss = { showPicker = false },
            currentMonth = currentAccountingMonth,
        )
    }

    if (showCoverSheet) {
        CoverSheet(
            hasImage = !cardImageUri.isNullOrBlank(),
            onPickPreset = { CoverStyleStore.set(context, it) },
            onPickImage = {
                showCoverSheet = false
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onAdjust = { showCoverSheet = false; showImageEditor = true },
            onRemove = { vm.setCardImage(null); showCoverSheet = false },
            onDismiss = { showCoverSheet = false },
        )
    }

    if (showImageEditor && !cardImageUri.isNullOrBlank()) {
        CardImageEditorDialog(
            image = cardImage?.drawable,
            initial = cardTransform,
            onApply = {
                vm.setCardTransform(it)
                vm.pruneCardImages()
                pendingImage = null
                imageSnapshot = null
                showImageEditor = false
            },
            onDismiss = {
                val snapshot = imageSnapshot
                if (pendingImage != null && snapshot != null) {
                    vm.setCardImage(snapshot.path)
                    if (snapshot.path != null && snapshot.colorArgb != null && snapshot.luminance != null) {
                        vm.setCardImageStats(snapshot.colorArgb, snapshot.luminance)
                    }
                    vm.setCardTransform(snapshot.transform)
                    vm.pruneCardImages()
                }
                pendingImage = null
                imageSnapshot = null
                showImageEditor = false
            },
        )
    }

    if (showBatchSheet) {
        BatchSheet(
            onBatch = { showBatchSheet = false; selecting = true; selected = emptySet() },
            onDeletePeriod = {
                showBatchSheet = false
                confirmRange(
                    if (mode == PeriodMode.MONTH) "删除本月记录" else "删除本年记录",
                    if (mode == PeriodMode.MONTH) monthRange(month) else yearRange(month),
                )
            },
            onDeleteYear = {
                showBatchSheet = false
                confirmRange("删除本年记录", yearRange(month))
            },
            onDeleteAll = { showBatchSheet = false; confirmDeleteAll() },
            onDismiss = { showBatchSheet = false },
        )
    }

    jumpTarget?.let { day ->
        DayJumpDialog(
            current = day,
            onPick = { picked ->
                when {
                    mode == PeriodMode.MONTH && YearMonth.from(picked) != month ->
                        vm.setMonth(YearMonth.from(picked))
                    mode == PeriodMode.YEAR && picked.year != month.year ->
                        vm.setMonth(YearMonth.of(picked.year, month.monthValue))
                }
                pendingJump = picked
                jumpTarget = null
            },
            onDismiss = { jumpTarget = null },
        )
    }

    confirm?.let { c ->
        EcoConfirm(
            title = c.title,
            body = c.body,
            confirmText = "删除",
            onConfirm = { confirm = null; c.onConfirm() },
            onDismiss = { confirm = null },
            danger = true,
        )
    }

}

@Composable
private fun BillToolbar(
    month: YearMonth,
    mode: PeriodMode,
    filter: TxFilter,
    count: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenPicker: () -> Unit,
    onFilter: (TxFilter) -> Unit,
    onMore: () -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .height(45.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarArrow(EcoIcons.Back, "上一周期", onPrev)
        Column(
            Modifier
                .width(82.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenPicker)
                .padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (mode == PeriodMode.MONTH) {
                    "${month.year}.${month.monthValue.toString().padStart(2, '0')}"
                } else {
                    "${month.year}"
                },
                style = EcoType.RowTitle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                color = c.ink,
                maxLines = 1,
            )
            Text(
                if (mode == PeriodMode.MONTH) "MONTH" else "YEAR",
                style = EcoType.Micro.copy(fontSize = 7.5.sp, letterSpacing = 0.8.sp),
                color = c.quiet,
            )
        }
        ToolbarArrow(EcoIcons.Next, "下一周期", onNext)
        Spacer(Modifier.weight(1f))
        EcoFilterStrip(
            options = TxFilter.entries.map { it.label },
            selectedIndex = filter.ordinal,
            onSelect = { onFilter(TxFilter.entries[it]) },
        )
        Spacer(Modifier.width(4.dp))
        ToolbarArrow(EcoIcons.More, "批量操作", onMore)
    }
}

@Composable
private fun ToolbarArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val c = MaterialTheme.eco
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = c.muted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun BatchBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = c.rule,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                )
            }
            .padding(horizontal = 22.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选 $selectedCount", style = EcoType.Pill.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = c.ink)
        Spacer(Modifier.weight(1f))
        EcoTextAction("全选", onSelectAll)
        Spacer(Modifier.width(4.dp))
        EcoTextAction("删除", onDelete, color = c.clay)
        Spacer(Modifier.width(4.dp))
        EcoTextAction("取消", onCancel, color = c.muted)
    }
}

@Composable
private fun BillEmpty(title: String, body: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 40.dp)) {
            Text(title, style = EcoType.EmptyTitle, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(body, style = EcoType.PageLead, color = c.quiet, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CoverSheet(
    hasImage: Boolean,
    onPickPreset: (String) -> Unit,
    onPickImage: () -> Unit,
    onAdjust: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    EcoSheet(
        onDismiss = onDismiss,
        title = "更换结余卡背景",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                // 「夜色」预览跟随当前主题的 hero 渐变，与真实卡片一致
                Triple(CoverStyleStore.NIGHT, "夜色", listOf(c.heroC, c.heroA)),
                Triple(CoverStyleStore.MIST, "薄雾", listOf(Color(0xFFE7ECE5), Color(0xFFF1F0E7))),
                Triple(CoverStyleStore.CLAY, "陶土", listOf(Color(0xFF5E2F2A), Color(0xFF8E5241))),
            ).forEach { (id, label, colors) ->
                val on = CoverStyleStore.current == id && !hasImage
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPickPreset(id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(66.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(colors))
                            .then(if (on) Modifier.border(1.5.dp, c.blue, RoundedCornerShape(12.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Text("当前", style = EcoType.Pill.copy(fontSize = 9.sp), color = Color.White)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(label, style = EcoType.PageNote, color = if (on) c.blueDeep else c.muted)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        EcoGhostButton(text = "从相册选择图片…", onClick = onPickImage)
        if (hasImage) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(text = "调整图片…", onClick = onAdjust)
                }
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(text = "移除图片", onClick = onRemove)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "有图片时优先用图片；移除后回到当前底色。调整可平移、缩放、旋转。",
            style = EcoType.PageNote,
            color = c.quiet,
        )
    }
}

@Composable
private fun BatchSheet(
    onBatch: () -> Unit,
    onDeletePeriod: () -> Unit,
    onDeleteYear: () -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    EcoSheet(
        onDismiss = onDismiss,
        title = "批量操作",
        subtitle = "删除后不可恢复",
    ) {
        BatchChoice(EcoIcons.Check, "批量管理", "逐笔勾选后删除", c.blue, onBatch)
        BatchChoice(EcoIcons.Calendar, "删除本周期全部记录…", "只删当前月/年", c.amber, onDeletePeriod)
        BatchChoice(EcoIcons.Trash, "删除本年全部记录…", "按会计年删除", c.amber, onDeleteYear)
        BatchChoice(EcoIcons.Warning, "删除全部记录…", "所有月份，含导入的账单", c.clay, onDeleteAll)
    }
}

@Composable
private fun BatchChoice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = EcoType.RowTitle, color = if (tint == c.clay) c.clay else c.ink)
            Text(sub, style = EcoType.PageNote.copy(fontSize = 9.sp), color = c.quiet)
        }
    }
}

/**
 * 列表滑动条（自绘）：拇指长度 = 视口/内容比例，位置 = 已滚像素比例；
 * 拖动把位移换算成滚动像素直接交给列表（`dispatchRawDelta`），拖多少内容走多少。
 */
@Composable
private fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    val idleColor = c.quiet.copy(alpha = 0.24f)
    val activeColor = c.quiet.copy(alpha = 0.55f)
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { 4.dp.toPx() }

    var metrics by remember(state) { mutableStateOf<ScrollMetrics?>(null) }
    LaunchedEffect(state) {
        var heights = HashMap<Int, Int>()
        var knownTotal = -1
        snapshotFlow { state.layoutInfo }.collect { info ->
            if (info.totalItemsCount != knownTotal) {
                heights = HashMap()
                knownTotal = info.totalItemsCount
            }
            var changed = false
            info.visibleItemsInfo.forEach { item ->
                if (heights[item.index] != item.size) {
                    heights[item.index] = item.size
                    changed = true
                }
            }
            if (changed || metrics == null) metrics = buildScrollMetrics(heights, knownTotal)
        }
    }

    Box(
        modifier
            .pointerInput(state) {
                var dragRatio = 1f
                var active = false
                detectVerticalDragGestures(
                    onDragStart = {
                        active = metrics != null && (state.canScrollForward || state.canScrollBackward)
                        dragging = active
                        val m = metrics
                        if (active && m != null) {
                            val ratio = scrollbarGeometry(state.layoutInfo, m, size.height.toFloat())
                            dragRatio = (ratio.scrollablePx / ratio.travelPx).coerceAtLeast(0.2f)
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    if (active) {
                        change.consume()
                        state.dispatchRawDelta(dragAmount * dragRatio)
                    }
                }
            }
            .drawBehind {
                val m = metrics ?: return@drawBehind
                val info = state.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull() ?: return@drawBehind
                val geo = scrollbarGeometry(info, m, size.height)
                if (geo.scrollablePx <= 0f) return@drawBehind
                val scrolled = (m.offsetOf(last.index) - last.offset).coerceIn(0f, geo.scrollablePx)
                val thumbHeight = size.height * geo.thumbFraction
                val top = (scrolled / geo.scrollablePx) * (size.height - thumbHeight)
                drawRoundRect(
                    color = if (dragging) activeColor else idleColor,
                    topLeft = Offset((size.width - thumbWidthPx) / 2f, top),
                    size = Size(thumbWidthPx, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidthPx / 2f),
                )
            },
    )
}

private class ScrollbarGeometry(
    val scrollablePx: Float,
    val thumbFraction: Float,
    val travelPx: Float,
)

private fun scrollbarGeometry(info: LazyListLayoutInfo, m: ScrollMetrics, trackHeight: Float): ScrollbarGeometry {
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val content = m.contentPx.coerceAtLeast(viewport.toFloat())
    val fraction = (viewport / content).coerceIn(MIN_THUMB_FRACTION, MAX_THUMB_FRACTION)
    return ScrollbarGeometry(
        scrollablePx = content - viewport,
        thumbFraction = fraction,
        travelPx = ((trackHeight * (1f - fraction)).coerceAtLeast(1f)),
    )
}

private class ScrollMetrics(val prefix: LongArray, val contentPx: Float) {
    fun offsetOf(index: Int): Float = prefix[index.coerceIn(0, prefix.lastIndex)].toFloat()
}

private fun buildScrollMetrics(heights: HashMap<Int, Int>, total: Int): ScrollMetrics {
    var measuredSum = 0L
    var measuredCount = 0
    heights.forEach { (index, h) ->
        if (index in 0 until total) {
            measuredSum += h
            measuredCount++
        }
    }
    val avg = if (measuredCount == 0) 0f else measuredSum.toFloat() / measuredCount
    val prefix = LongArray(total + 1)
    for (i in 0 until total) {
        prefix[i + 1] = prefix[i] + (heights[i] ?: avg.toInt()).toLong()
    }
    return ScrollMetrics(prefix, prefix[total].toFloat())
}

@Composable
private fun DayHeader(day: LocalDate, dayRows: List<TransactionRow>, onClickDay: () -> Unit) {
    val c = MaterialTheme.eco
    val today = LocalDate.now()
    val relative = when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> dayOfWeekZh(day.dayOfWeek)
    }
    val dayExpense = remember(dayRows) { dayRows.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents } }
    val dayIncome = remember(dayRows) { dayRows.filter { it.type == TxType.INCOME }.sumOf { it.amountCents } }
    val dayBalance = dayIncome - dayExpense
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(c.paper, c.paper.copy(alpha = 0.96f), c.paper.copy(alpha = 0.72f)),
                ),
            )
            .heightIn(min = 33.dp)
            .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(24.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(c.blueWash)
                .border(1.dp, c.rule, RoundedCornerShape(9.dp))
                .clickable(onClick = onClickDay)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${day.monthValue}月${day.dayOfMonth}日",
                style = EcoType.Pill.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                color = c.blueDeep,
            )
            Spacer(Modifier.width(5.dp))
            Text(relative, style = EcoType.PageNote.copy(fontSize = 8.5.sp), color = c.muted)
        }
        Text(
            Money.formatSignedCurrency(dayBalance),
            style = EcoType.Micro.copy(fontSize = 10.sp, letterSpacing = 0.sp),
            color = if (dayBalance >= 0L) c.jade else c.clay,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillRow(
    row: TransactionRow,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    val isExpense = row.type == TxType.EXPENSE
    val time = remember(row.timestamp) {
        Instant.ofEpochMilli(row.timestamp).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormatter)
    }
    val texts = billRowText(row.counterparty, row.categoryName, time, row.note)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) c.blue else Color.Transparent)
                    .border(1.5.dp, if (selected) c.blue else c.ruleStrong, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(EcoIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(9.dp))
        }
        EcoCategoryTile(
            categoryName = row.categoryName,
            icon = row.categoryIcon,
            size = 30.dp,
            radius = 10.dp,
            bare = true,
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    texts.title,
                    style = EcoType.RowTitle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.refundMark != RefundMark.NONE) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (row.refundMark == RefundMark.REFUND_INCOME) "退款" else "已退",
                        style = EcoType.Pill.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                        color = if (row.refundMark == RefundMark.REFUND_INCOME) c.jade else c.amber,
                    )
                }
                if (row.refundLinkId != null) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "已关联",
                        style = EcoType.Pill.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                        color = c.blue,
                    )
                }
            }
            Text(
                texts.subLine1,
                style = EcoType.PageNote.copy(fontSize = 9.5.sp),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (texts.note.isNotBlank()) {
                Text(
                    texts.note,
                    style = EcoType.PageNote.copy(fontSize = 9.5.sp),
                    color = c.quiet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            Money.formatSignedCurrency(if (isExpense) -row.amountCents else row.amountCents),
            style = EcoType.RowTitle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            color = if (isExpense) c.ink else c.jade,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayJumpDialog(current: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text("跳转") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

/** 选图前的卡面状态：编辑器点「取消」时按这份快照恢复 */
private data class CardImageSnapshot(
    val path: String?,
    val colorArgb: Int?,
    val luminance: Float?,
    val transform: CardTransform,
)
