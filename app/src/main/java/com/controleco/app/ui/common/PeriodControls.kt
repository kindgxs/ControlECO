package com.controleco.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.data.PeriodMode
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.eco
import java.time.YearMonth
import kotlinx.coroutines.launch

/** 选择面板覆盖的年份范围（月视图一年一页，年视图一页 12 年） */
private const val PICKER_YEAR_MIN = 1970
private const val PICKER_YEAR_MAX = 2099
private const val YEARS_PER_PAGE = 12

/** 周期文案：原型统一 "2026.10" / "2026"（点号，不写年月） */
fun periodLabel(yearMonth: YearMonth, mode: PeriodMode): String = when (mode) {
    PeriodMode.MONTH -> "${yearMonth.year}.${yearMonth.monthValue.toString().padStart(2, '0')}"
    PeriodMode.YEAR -> "${yearMonth.year}"
}

/** 结余文案前缀：本月结余 / 本年结余 */
fun periodBalanceLabel(mode: PeriodMode): String = when (mode) {
    PeriodMode.MONTH -> "本月结余"
    PeriodMode.YEAR -> "本年结余"
}

/** 周期范围文案（用于空态与提示）：本月 / 本年 */
fun periodScopeLabel(mode: PeriodMode): String = when (mode) {
    PeriodMode.MONTH -> "本月"
    PeriodMode.YEAR -> "本年"
}

/**
 * 通用分段切换（月/年切换、账单"全部/支出/收入"筛选共用同一套外观）：
 * 外层浅主色胶囊，选中段主色实底 + 白字加粗，未选中透明——开着就有"能点、点哪个"的直观感。
 * [compact] = 账单页那种"一屏塞更多信息"的小一号（文字小一档、内边距收窄）。
 */
@Composable
fun <T> SegmentedSwitch(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    containerColor: Color = MaterialTheme.eco.blue.copy(alpha = 0.16f),
    selectedColor: Color = MaterialTheme.eco.blueDeep,
    selectedContentColor: Color = MaterialTheme.eco.onAccent,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(containerColor)
            .padding(if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .clip(shape)
                    .then(if (isSelected) Modifier.background(selectedColor) else Modifier)
                    .clickable { onSelect(option) }
                    .padding(
                        horizontal = if (compact) 12.dp else 14.dp,
                        vertical = if (compact) 3.dp else 5.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    color = if (isSelected) selectedContentColor else contentColor,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 滑动分段开关（[月预算|日预算]、[金额|比例] 同款）：等宽分段，选中胶囊在选项之间**平滑滑动**
 * （点击时从一侧滑到另一侧）。只放标签长度接近的两段互斥选项。
 */
@Composable
fun SlidingSwitch(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(50)
    val pad = 3.dp
    val segHeight = 30.dp
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(c.blue.copy(alpha = 0.16f))
            .padding(pad),
    ) {
        val segWidth = maxWidth / options.size
        val offset by animateDpAsState(
            targetValue = segWidth * safeIndex,
            animationSpec = tween(260, easing = FastOutSlowInEasing),
            label = "segSlide",
        )
        // 滑动胶囊（画在文字下层）
        Box(
            Modifier
                .offset(x = offset)
                .width(segWidth)
                .height(segHeight)
                .clip(shape)
                .background(c.blueDeep),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, label ->
                val isSelected = index == safeIndex
                Box(
                    Modifier
                        .weight(1f)
                        .height(segHeight)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            c.onAccent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 月 / 年 两段式切换：选中段主色实底，未选中透明。
 * 账单页结余卡与统计页顶部共用，两处永远同步（同一份 ViewModel 状态）。
 */
@Composable
fun PeriodSwitch(    mode: PeriodMode,
    onChange: (PeriodMode) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.eco.blue.copy(alpha = 0.16f),
    selectedColor: Color = MaterialTheme.eco.blueDeep,
    selectedContentColor: Color = MaterialTheme.eco.onAccent,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    SegmentedSwitch(
        options = PeriodMode.entries,
        selected = mode,
        label = { it.label },
        onSelect = onChange,
        modifier = modifier,
        containerColor = containerColor,
        selectedColor = selectedColor,
        selectedContentColor = selectedContentColor,
        contentColor = contentColor,
    )
}

/**
 * 周期步进条：[‹] [2026年10月] [›]。
 * 两侧箭头是浅蓝圆钮（唯一的"可点"图形提示），中间日期本身就是选择入口——
 * 不加方框、不加下拉小三角，文字自己站住；[compact] 供账单页用小一号（一屏多放几行）。
 */
@Composable
fun PeriodStepper(
    yearMonth: YearMonth,
    mode: PeriodMode,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepButton(
            icon = Icons.Filled.KeyboardArrowLeft,
            desc = "上一${mode.label}",
            onClick = onPrev,
            size = if (compact) 30.dp else 36.dp,
        )
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        Text(
            periodLabel(yearMonth, mode),
            style = if (compact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenPicker() }
                .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp),
        )
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        StepButton(
            icon = Icons.Filled.KeyboardArrowRight,
            desc = "下一${mode.label}",
            onClick = onNext,
            size = if (compact) 30.dp else 36.dp,
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, desc: String, onClick: () -> Unit, size: Dp = 36.dp) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(size).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = desc,
                tint = MaterialTheme.eco.blueDeep,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * 周期选择面板（账单/统计共用）：
 * 月模式 = 一年一页的 4×3 月份格子，**左右滑动切换年份**；年模式 = 一页 12 年的年份格子。
 * 选中项浅蓝实底，当前月/今年描边，「回到本月/今年」一键回到现在。
 *
 * [currentMonth] = 当前**会计月**（首日 ≠ 1 时与自然月不同，由调用方按 vm 口径传入），
 * 当前高亮与「回到本月」都以它为准；[currentYear] 同理（取会计月所属年份即可）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodPickerSheet(
    mode: PeriodMode,
    current: YearMonth,
    onSelectMonth: (YearMonth) -> Unit,
    onSelectYear: (Int) -> Unit,
    onDismiss: () -> Unit,
    currentMonth: YearMonth = YearMonth.now(),
    currentYear: Int = currentMonth.year,
) {
    val c = MaterialTheme.eco
    var year by remember(current.year) { mutableIntStateOf(current.year) }
    var yearPageStart by remember(current.year) {
        mutableIntStateOf(
            (current.year - PICKER_YEAR_MIN) / YEARS_PER_PAGE * YEARS_PER_PAGE + PICKER_YEAR_MIN,
        )
    }
    val monthMode = mode == PeriodMode.MONTH
    // 月模式下，点标题里的年份可临时切到「选年」网格，选完回到月份网格（不关闭面板）
    var yearPicking by remember { mutableStateOf(false) }

    EcoSheet(
        onDismiss = onDismiss,
        title = if (monthMode) "选择月份" else "选择年份",
        scrollable = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EcoIconButton(
                icon = EcoIcons.Back,
                contentDescription = "上一年",
                onClick = {
                    if (monthMode) year = (year - 1).coerceAtLeast(PICKER_YEAR_MIN)
                    else yearPageStart = (yearPageStart - YEARS_PER_PAGE).coerceAtLeast(PICKER_YEAR_MIN)
                },
                size = 28.dp,
                container = c.surface2,
                border = false,
                tint = c.ink2,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (monthMode) "$year 年" else "$yearPageStart – ${(yearPageStart + YEARS_PER_PAGE - 1).coerceAtMost(PICKER_YEAR_MAX)}",
                style = EcoType.SubTitle.copy(fontSize = 20.sp),
                color = c.ink,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (monthMode) {
                            Modifier.clickable { yearPicking = true }
                        } else {
                            Modifier
                        },
                    ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(8.dp))
            EcoIconButton(
                icon = EcoIcons.Next,
                contentDescription = "下一年",
                onClick = {
                    if (monthMode) year = (year + 1).coerceAtMost(PICKER_YEAR_MAX)
                    else yearPageStart = (yearPageStart + YEARS_PER_PAGE).coerceAtMost(PICKER_YEAR_MAX - YEARS_PER_PAGE + 1)
                },
                size = 28.dp,
                container = c.surface2,
                border = false,
                tint = c.ink2,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EcoTextAction(
                text = when {
                    monthMode && yearPicking -> "返回月份"
                    monthMode -> "回到本月"
                    else -> "回到今年"
                },
                onClick = {
                    when {
                        monthMode && yearPicking -> yearPicking = false
                        monthMode -> {
                            onSelectMonth(currentMonth)
                            onDismiss()
                        }
                        else -> {
                            onSelectYear(currentYear)
                            onDismiss()
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        if (monthMode && !yearPicking) {
            repeat(4) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    repeat(3) { col ->
                        val month = row * 3 + col + 1
                        val selected = year == current.year && month == current.monthValue
                        val highlighted = year == currentMonth.year && month == currentMonth.monthValue
                        val future = YearMonth.of(year, month) > currentMonth
                        Box(
                            Modifier
                                .weight(1f)
                                .height(43.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) c.blueDeep else c.surface2)
                                .then(
                                    if (highlighted && !selected) Modifier.border(1.dp, c.blue.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                    else Modifier,
                                )
                                .clickable {
                                    onSelectMonth(YearMonth.of(year, month))
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$month 月",
                                style = EcoType.RowTitle.copy(fontSize = 12.sp),
                                color = when {
                                    selected -> Color.White
                                    future -> c.quiet.copy(alpha = 0.38f)
                                    else -> c.ink2
                                },
                            )
                        }
                    }
                }
            }
        } else {
            val years = (0 until YEARS_PER_PAGE).map { yearPageStart + it }
            // 月模式下临时选年：高亮当前查看年，点选后回到月份网格；年模式直接回调年份
            val pickingYearInMonth = monthMode && yearPicking
            repeat(4) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    repeat(3) { col ->
                        val y = years[row * 3 + col]
                        val selected = if (pickingYearInMonth) y == year else y == current.year
                        val highlighted = y == currentYear
                        Box(
                            Modifier
                                .weight(1f)
                                .height(43.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) c.blueDeep else c.surface2)
                                .then(
                                    if (highlighted && !selected) Modifier.border(1.dp, c.blue.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                    else Modifier,
                                )
                                .clickable(enabled = y <= PICKER_YEAR_MAX) {
                                    if (pickingYearInMonth) {
                                        year = y
                                        yearPicking = false
                                    } else {
                                        onSelectYear(y)
                                        onDismiss()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$y",
                                style = EcoType.RowTitle.copy(fontSize = 12.sp),
                                color = if (selected) Color.White else c.ink2,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 选择面板里的年份步进：‹ 2026 年 ›，点箭头翻页（与左右滑动同步） */
@Composable
private fun PickerStepper(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Filled.KeyboardArrowLeft, "上一个", onPrev)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        StepButton(Icons.Filled.KeyboardArrowRight, "下一个", onNext)
    }
}

@Composable
private fun RowScope.PickerCell(label: String, selected: Boolean, highlighted: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            )
            .then(
                if (highlighted && !selected) {
                    Modifier.border(1.dp, MaterialTheme.eco.blueDeep, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
