package com.controleco.app.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 记账 / 编辑记录两页共用的表单件（只在 ui/transactions 内使用）。
 *
 * 版式对齐设计稿：小节抬头 9/690、字段标签 8.5/680、字段主体是
 * 「surface2 底 + 1dp 发丝描边 + 11dp 圆角」的行，行内不放 Material 输入框，
 * 避免两页出现两套描边/焦点样式。
 */

// ---------- 文本样式 ----------

private fun txText(size: Float, weight: Int, em: Float = 0f, lineHeight: Float = size * 1.25f) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = (size * em).sp,
    lineHeight = lineHeight.sp,
    fontFeatureSettings = "tnum",
)

internal val TxSectionLabelStyle = txText(9f, 690, 0.04f, 12f)
internal val TxCategoryNameStyle = txText(11f, 740, 0f, 15f)
internal val TxToggleStyle = txText(10f, 690, 0f, 13f)
internal val TxDeleteLinkStyle = txText(10f, 680, 0f, 13f)
internal val TxErrorStyle = txText(10f, 710, 0f, 14f)
internal val TxAmountLabelStyle = txText(9f, 740, 0.19f, 12f)
internal val TxCurrencyStyle = txText(20f, 790, 0f, 24f)
internal val TxEditCurrencyStyle = txText(19f, 700, 0f, 23f)
internal val TxKeypadStyle = txText(20f, 700, 0f, 24f)

private val TxDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

// ---------- 标题 / 标签 ----------

/** 分组抬头：类型 / 分类 / 账户与日期 …（9/690 灰） */
@Composable
fun TxSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TxSectionLabelStyle, color = MaterialTheme.eco.muted, modifier = modifier)
}

/** 字段标签：账户 / 日期 / 商户 / 备注（用设计系统的 FieldLabel，和 EcoField 完全一致） */
@Composable
fun TxFieldLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.eco.muted) {
    Text(text, style = EcoType.FieldLabel, color = color, modifier = modifier)
}

// ---------- 选择行 / 日期行 / 输入行 ----------

/**
 * 下拉选择行（账户、分类）。
 * [elevation] > 0 时带卡片阴影（快速记账的分类选择条）。
 */
@Composable
fun TxSelectField(
    value: String,
    options: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "请选择",
    height: Dp = 43.dp,
    radius: Dp = 11.dp,
    leading: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = EcoType.Input,
    container: Color = MaterialTheme.eco.surface2,
    borderColor: Color = MaterialTheme.eco.rule,
    elevation: Dp = 0.dp,
    chevronTint: Color = MaterialTheme.eco.muted,
    chevronSize: Dp = 14.dp,
    horizontalPadding: Dp = 10.dp,
    contentDescription: String = "选择$placeholder",
) {
    val c = MaterialTheme.eco
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius)
    Column(modifier) {
        if (label != null) {
            TxFieldLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .then(
                        if (elevation > 0.dp) {
                            Modifier.shadow(
                                elevation = elevation,
                                shape = shape,
                                clip = false,
                                ambientColor = c.ink.copy(alpha = if (c.isDark) 0.3f else 0.08f),
                                spotColor = c.ink.copy(alpha = if (c.isDark) 0.34f else 0.12f),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(shape)
                    .background(container)
                    .border(1.dp, borderColor, shape)
                    .clickable { expanded = true }
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = value.ifBlank { placeholder },
                    style = textStyle,
                    color = if (value.isBlank()) c.quiet else c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    EcoIcons.ChevDown,
                    contentDescription = contentDescription,
                    tint = chevronTint,
                    modifier = Modifier.size(chevronSize),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, style = EcoType.Input) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 日期行：yyyy/MM/dd + 日历图标，点击开系统日期面板 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxDateField(
    date: LocalDate,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    height: Dp = 43.dp,
    radius: Dp = 11.dp,
    icon: ImageVector = EcoIcons.Calendar,
) {
    val c = MaterialTheme.eco
    var show by remember { mutableStateOf(false) }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    show = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } },
        ) {
            DatePicker(state = state)
        }
    }
    val shape = RoundedCornerShape(radius)
    Column(modifier) {
        if (label != null) {
            TxFieldLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(c.surface2)
                .border(1.dp, c.rule, shape)
                .clickable { show = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date.format(TxDateFormatter),
                style = EcoType.Input,
                color = c.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(icon, contentDescription = "选择日期", tint = c.muted, modifier = Modifier.size(15.dp))
        }
    }
}

// ---------- 收支分段 / 分类 chip ----------

/** 支出 / 收入分段（编辑页；快速记账用更轻的 EcoFilterStrip，贴合各自设计稿） */
@Composable
fun TxTypeSegmented(
    type: TxType,
    onChange: (TxType) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
) {
    EcoSegmented(
        options = listOf("支出", "收入"),
        selectedIndex = if (type == TxType.EXPENSE) 0 else 1,
        onSelect = { onChange(if (it == 0) TxType.EXPENSE else TxType.INCOME) },
        modifier = modifier,
        height = height,
    )
}

/** 分类 chip：带分类色点，选中反白 */
@Composable
fun TxCategoryChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 33.dp,
    onLongClick: (() -> Unit)? = null,
) {
    EcoChip(
        text = name,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        height = height,
        onLongClick = onLongClick,
    )
}

// ---------- 数字键盘 ----------

/**
 * 3 列数字键盘：1-9 / . / 0 / 退格（退格长按清空）。
 * 键在按下时铺一层 blueSoft（设计稿 hover/active 态），不做外框以保持纸面感。
 */
@Composable
fun TxKeypad(
    onDigit: (String) -> Unit,
    onDot: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", "⌫"),
        )
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { key ->
                    TxKeypadKey(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (key) {
                                "." -> onDot()
                                "⌫" -> onBackspace()
                                else -> onDigit(key)
                            }
                        },
                        onLongClick = if (key == "⌫") onClear else null,
                    ) {
                        if (key == "⌫") {
                            Icon(
                                EcoIcons.Backspace,
                                contentDescription = "退格",
                                tint = MaterialTheme.eco.blue,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text(key, style = TxKeypadStyle, color = MaterialTheme.eco.ink)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TxKeypadKey(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .height(47.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (pressed) c.blueSoft else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
