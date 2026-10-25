package com.controleco.app.ui.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.PeriodMode
import com.controleco.app.data.calendar.CalendarMath
import com.controleco.app.data.calendar.DayMoney
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.PeriodPickerSheet
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日历 4 种显示口径 */
private enum class CalendarMode(val label: String) {
    BOTH("收&支"),
    INCOME("收入"),
    EXPENSE("支出"),
    BALANCE("结余"),
}

private val DayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val WeekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 日历账本（功能1）：月历、每日收支 / 结余、月汇总，选中日期后列出当天明细。
 */
@Composable
fun CalendarScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val c = MaterialTheme.eco
    val month by vm.calendarMonth.collectAsStateWithLifecycle()
    val rows by vm.calendarRows.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    var mode by rememberSaveable { mutableStateOf(CalendarMode.BOTH.ordinal) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var pickerOpen by remember { mutableStateOf(false) }

    val monthRows = rows.orEmpty()
    // 日历的日收支与月汇总属于统计口径：剔除「不计入账单和收支统计」的记录；
    // 当天明细列表仍用全量（选「否」的那笔要能在账单/日历里看到这一条）
    val statsRows = remember(monthRows) { monthRows.filter { it.countInStats } }
    val byDay = remember(statsRows) { CalendarMath.byDay(statsRows, zone) }
    val totals = remember(statsRows) { CalendarMath.totals(statsRows) }
    val selected = runCatching { LocalDate.parse(selectedDate) }.getOrNull() ?: LocalDate.now()
    val dayRows = remember(monthRows, selected) {
        monthRows.filter {
            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == selected
        }.sortedByDescending { it.timestamp }
    }
    val currentMode = CalendarMode.entries[mode.coerceIn(0, CalendarMode.entries.lastIndex)]
    val categoryById = remember(categories) { categories.associateBy { it.id } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        EcoTopBar(micro = "CALENDAR / ${month.year}", title = "日历", onBack = onBack)

        // 月份切换
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { pickerOpen = true }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${month.year} 年 ${month.monthValue} 月",
                    style = EcoType.SectionTitle,
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(5.dp))
                Text("▾", style = EcoType.Micro.copy(fontSize = 10.sp), color = c.quiet)
            }
            EcoIconButton(
                icon = EcoIcons.Back,
                contentDescription = "上个月",
                onClick = { vm.prevCalendarMonth() },
                size = 34.dp,
            )
            Spacer(Modifier.width(8.dp))
            EcoIconButton(
                icon = EcoIcons.Next,
                contentDescription = "下个月",
                onClick = { vm.nextCalendarMonth() },
                size = 34.dp,
            )
        }

        EcoPane(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            // EcoPane 的内容容器是 Box：多个子项会叠在一起，日历必须自己用 Column 竖排
            Column(Modifier.fillMaxWidth()) {
                // 星期抬头
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .padding(start = 6.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WeekLabels.forEach {
                        Text(
                            it,
                            style = EcoType.Micro,
                            color = c.quiet,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // 日期格子
                val first = month.atDay(1)
                val offset = first.dayOfWeek.value - 1
                val days = month.lengthOfMonth()
                val cells = offset + days
                val weeks = (cells + 6) / 7
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = 0.dp, bottom = 4.dp),
                ) {
                    repeat(weeks) { week ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { col ->
                                val index = week * 7 + col
                                val dayNumber = index - offset + 1
                                if (dayNumber in 1..days) {
                                    val date = month.atDay(dayNumber)
                                    DayCell(
                                        date = date,
                                        money = byDay[date],
                                        mode = currentMode,
                                        selected = date == selected,
                                        isToday = date == LocalDate.now(),
                                        onClick = { selectedDate = date.toString() },
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f).height(52.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 显示口径
        EcoSegmented(
            options = CalendarMode.entries.map { it.label },
            selectedIndex = mode,
            onSelect = { mode = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            small = true,
        )

        // 月收入 / 月支出 / 月结余
        EcoPane(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                MonthTotal("月收入", Money.formatYuanCompact(totals.incomeCents), c.jade)
                MonthTotal("月支出", Money.formatYuanCompact(totals.expenseCents), c.clay)
                MonthTotal("月结余", Money.formatYuanCompact(totals.netCents), c.blueDeep)
            }
        }

        // 当天明细
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "${selected.monthValue} 月 ${selected.dayOfMonth} 日",
                style = EcoType.SectionTitle,
                color = c.ink,
                modifier = Modifier.weight(1f),
            )
            Text("${dayRows.size} 笔", style = EcoType.Micro, color = c.quiet)
        }
        EcoPane(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            // 同样要 Column 竖排，否则多条账单会全部叠在卡片顶部
            Column(Modifier.fillMaxWidth()) {
                if (dayRows.isEmpty()) {
                    EcoEmpty(title = "当天没有账单", icon = EcoIcons.Calendar)
                } else {
                    dayRows.forEachIndexed { index, row ->
                        if (index > 0) EcoRowDivider()
                        DayRow(row = row, categoryName = row.categoryName, parentName = categoryById[row.categoryId]?.parentId?.let { categoryById[it]?.name })
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (pickerOpen) {
        PeriodPickerSheet(
            mode = PeriodMode.MONTH,
            current = month,
            onSelectMonth = { vm.setCalendarMonth(it) },
            onSelectYear = { year -> vm.setCalendarMonth(month.withYear(year)) },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    money: DayMoney?,
    mode: CalendarMode,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val bg by animateColorAsState(
        targetValue = when {
            selected -> c.blueSoft
            isToday -> c.surface2
            else -> c.surface.copy(alpha = 0f)
        },
        label = "cal-cell-bg",
    )
    Column(
        modifier
            .height(52.dp)
            .padding(1.5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(if (selected) Modifier.border(1.dp, c.blue.copy(alpha = 0.45f), RoundedCornerShape(10.dp)) else Modifier)
            .ecoPressable(scaleTo = 0.93f, onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = EcoType.Micro.copy(fontSize = 12.sp),
            color = if (isToday) c.blueDeep else c.ink,
        )
        Spacer(Modifier.height(1.dp))
        val moneyValue = money ?: DayMoney()
        when (mode) {
            CalendarMode.BOTH -> {
                if (moneyValue.incomeCents != 0L) {
                    DayAmount("+${Money.formatYuanCompact(moneyValue.incomeCents)}", c.jade)
                }
                if (moneyValue.expenseCents != 0L) {
                    DayAmount("-${Money.formatYuanCompact(moneyValue.expenseCents)}", c.clay)
                }
            }
            CalendarMode.INCOME -> if (moneyValue.incomeCents != 0L) {
                DayAmount(Money.formatYuanCompact(moneyValue.incomeCents), c.jade)
            }
            CalendarMode.EXPENSE -> if (moneyValue.expenseCents != 0L) {
                DayAmount(Money.formatYuanCompact(moneyValue.expenseCents), c.clay)
            }
            CalendarMode.BALANCE -> if (!moneyValue.isEmpty) {
                val net = moneyValue.netCents
                DayAmount((if (net > 0) "+" else "") + Money.formatYuanCompact(net), if (net >= 0) c.blueDeep else c.clay)
            }
        }
    }
}

@Composable
private fun DayAmount(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = EcoType.Micro.copy(fontSize = 7.5.sp, lineHeight = 9.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MonthTotal(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = EcoType.Micro, color = MaterialTheme.eco.quiet)
        Spacer(Modifier.height(2.dp))
        Text(value, style = EcoType.RowTitle.copy(fontSize = 11.5.sp), color = color, maxLines = 1)
    }
}

@Composable
private fun DayRow(row: TransactionRow, categoryName: String, parentName: String?) {
    val c = MaterialTheme.eco
    val expense = row.type == TxType.EXPENSE
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.counterparty.ifBlank { row.note.ifBlank { categoryName } },
                style = EcoType.RowTitle,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(Instant.ofEpochMilli(row.timestamp).atZone(ZoneId.systemDefault()).toLocalTime().format(DayFormatter))
                    append(" · ")
                    if (parentName != null) append("$parentName / ")
                    append(categoryName)
                },
                style = EcoType.RowSub,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            (if (expense) "-" else "+") + Money.formatYuanCompact(row.amountCents),
            style = EcoType.RowTitle,
            color = if (expense) c.ink else c.jade,
            maxLines = 1,
        )
    }
}
