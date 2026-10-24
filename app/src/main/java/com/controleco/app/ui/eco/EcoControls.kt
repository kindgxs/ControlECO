package com.controleco.app.ui.eco

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.data.CategoryIcon
import com.controleco.app.ui.common.CategoryLogo
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.theme.onAccentGrad

// ---------- 按钮 ----------

@Composable
fun EcoIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = MaterialTheme.eco.ink,
    container: Color = MaterialTheme.eco.surface,
    border: Boolean = true,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) container else c.surface2)
            .then(if (border) Modifier.border(1.dp, c.rule, CircleShape) else Modifier)
            .ecoPressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun EcoBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    EcoIconButton(
        icon = EcoIcons.Back,
        contentDescription = "返回",
        onClick = onClick,
        modifier = modifier,
        size = 34.dp,
        tint = MaterialTheme.eco.ink2,
    )
}

@Composable
fun EcoTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.eco.blue,
    enabled: Boolean = true,
) {
    Text(
        text,
        style = EcoType.Btn,
        color = color.copy(alpha = if (enabled) 1f else 0.35f),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
fun EcoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    height: Dp = 44.dp,
) {
    val c = MaterialTheme.eco
    // 主按钮渐变跟随主题色（原来是写死的青绿，换任何主题都不变）
    val gradient = listOf(c.accentGradA, c.accentGradB, c.accentGradC)
    val fg = c.onAccentGrad
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (enabled) {
                    Brush.linearGradient(gradient)
                } else {
                    Brush.linearGradient(gradient.map { it.copy(alpha = 0.35f) })
                },
            )
            .ecoPressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = EcoType.Btn, color = fg, maxLines = 1)
        }
    }
}

@Composable
fun EcoGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    color: Color = MaterialTheme.eco.blueDeep,
) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(13.dp))
            .background(c.surface)
            .border(1.dp, c.ruleStrong, RoundedCornerShape(13.dp))
            .ecoPressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = EcoType.Btn, color = color.copy(alpha = if (enabled) 1f else 0.35f), maxLines = 1)
    }
}

@Composable
fun EcoDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp,
) {
    val c = MaterialTheme.eco
    val bg = if (enabled) c.claySoft else c.surface3
    val line = if (enabled) c.clay.copy(alpha = 0.3f) else c.rule
    val fg = if (enabled) c.clay else c.quiet.copy(alpha = 0.55f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .border(1.dp, line, RoundedCornerShape(13.dp))
            .ecoPressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = EcoType.Btn, color = fg, maxLines = 1)
    }
}

// ---------- 胶囊 / 标签 ----------

@Composable
fun EcoChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    val bg by animateColorAsState(if (selected) c.blueDeep else c.surface, tween(160), label = "chip-bg")
    val fg by animateColorAsState(if (selected) c.onAccent else c.muted, tween(160), label = "chip-fg")
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else c.rule, RoundedCornerShape(11.dp))
            .then(if (selected) Modifier.shadow(5.dp, RoundedCornerShape(11.dp), ambientColor = c.blueDeep.copy(alpha = .2f), spotColor = c.blueDeep.copy(alpha = .3f)) else Modifier)
            .ecoPressable(scaleTo = 0.96f, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = EcoType.Chip, color = fg, maxLines = 1)
    }
}

enum class EcoPillTone { Neutral, Good, Warn, Bad, Blue }

@Composable
fun EcoPill(
    text: String,
    tone: EcoPillTone = EcoPillTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val bg = when (tone) {
        EcoPillTone.Neutral -> c.surface2
        EcoPillTone.Good -> c.jadeSoft
        EcoPillTone.Warn -> c.amberSoft
        EcoPillTone.Bad -> c.claySoft
        EcoPillTone.Blue -> c.blueSoft
    }
    val fg = when (tone) {
        EcoPillTone.Neutral -> c.muted
        EcoPillTone.Good -> c.jade
        EcoPillTone.Warn -> c.amber
        EcoPillTone.Bad -> c.clay
        EcoPillTone.Blue -> c.blue
    }
    Row(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = EcoType.Pill, color = fg, maxLines = 1)
    }
}

// ---------- 分段控件 ----------

@Composable
fun EcoSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    small: Boolean = false,
    selectedContainerColor: Color? = null,
    selectedContentColor: Color? = null,
    /** 不可选时整体变灰且点击无效（分类「其他」的类型固定） */
    enabled: Boolean = true,
) {
    val c = MaterialTheme.eco
    BoxWithConstraints(
        modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface3)
            .padding(3.dp),
    ) {
        val itemWidth = maxWidth / options.size.coerceAtLeast(1)
        // 原型选中块 300ms cubic-bezier(.22,1.35,.4,1)：带一点回弹
        val x by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.66f, stiffness = 460f),
            label = "seg-x",
        )
        Box(
            Modifier
                .offset(x = x)
                .width(itemWidth)
                .fillMaxHeight()
                .shadow(3.dp, RoundedCornerShape(9.dp), ambientColor = c.ink.copy(alpha = .12f), spotColor = c.ink.copy(alpha = .16f))
                .clip(RoundedCornerShape(9.dp))
                .background(selectedContainerColor ?: c.surface),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, text ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text,
                        style = if (small) EcoType.SegSmall else EcoType.Seg,
                        color = when {
                            !enabled -> c.quiet.copy(alpha = 0.55f)
                            index == selectedIndex -> selectedContentColor ?: c.blueDeep
                            else -> c.muted
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 筛选条：全部/支出/收入（比分段控件更矮、更轻） */
@Composable
fun EcoFilterStrip(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .border(1.dp, c.rule, RoundedCornerShape(10.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, text ->
            val on = index == selectedIndex
            Box(
                Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = EcoType.Pill, color = if (on) c.ink else c.muted)
            }
        }
    }
}

// ---------- 开关 ----------

@Composable
fun EcoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.eco
    val track by animateColorAsState(
        if (checked) c.blue else c.ruleStrong,
        tween(180),
        label = "switch-track",
    )
    val thumbX by animateDpAsState(if (checked) 18.dp else 2.dp, tween(180), label = "switch-thumb")
    Box(
        modifier
            .width(40.dp)
            .height(24.dp)
            .clip(CircleShape)
            .background(track.copy(alpha = if (enabled) 1f else 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = thumbX, y = 2.dp)
                .size(20.dp)
                .shadow(2.dp, CircleShape, ambientColor = c.ink.copy(alpha = .2f), spotColor = c.ink.copy(alpha = .25f))
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

// ---------- 图标瓦片 / 分类瓦片 ----------

@Composable
fun EcoIconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    radius: Dp = 12.dp,
    iconSize: Dp = 19.dp,
    background: Color = tint.copy(alpha = 0.13f),
    /** 去掉底色与描边，只留图标本身（首页/分类页/设置行按用户要求统一为此样式） */
    bare: Boolean = false,
) {
    // 去掉底后图标不再受 tile 尺寸限制，按原 iconSize 绘制会显小，这里按比例放大一点补足视觉重量
    val resolvedIconSize = if (bare) (iconSize * 1.35f).coerceAtMost(size) else iconSize
    Box(
        modifier
            .size(size)
            .then(
                if (bare) {
                    Modifier
                } else {
                    Modifier
                        .clip(RoundedCornerShape(radius))
                        .background(background)
                        .border(1.dp, tint.copy(alpha = 0.16f), RoundedCornerShape(radius))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(resolvedIconSize),
        )
    }
}

/** 分类瓦片：复用项目现有 CategoryLogo（emoji / GLYPH / IMAGE / 默认图形），外面套设计稿的浅色底 */
@Composable
fun EcoCategoryTile(
    categoryName: String,
    icon: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    height: Dp = size,
    radius: Dp = 12.dp,
    overrideTint: Color? = null,
    /** 图形相对瓦片的比例；个人页分类卡按原型 24/42 取 0.57 */
    glyphScale: Float = 0.72f,
    /** 账单列表里的分类 logo 按用户要求去掉所有底色与描边，只留图形本身 */
    bare: Boolean = false,
) {
    val c = MaterialTheme.eco
    val explicit = CategoryIcon.glyphColorOf(icon)?.let { Color(it or 0xFF000000.toInt()) }
    val tint = overrideTint ?: explicit ?: categoryColor(MaterialTheme.accBook.categoryPalette, categoryName)
    Box(
        modifier
            .size(width = size, height = height)
            .then(
                if (bare) {
                    Modifier
                } else {
                    Modifier
                        .clip(RoundedCornerShape(radius))
                        .background(tint.copy(alpha = if (c.isDark) 0.22f else 0.12f))
                        .border(1.dp, tint.copy(alpha = 0.18f), RoundedCornerShape(radius))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        CategoryLogo(
            categoryName = categoryName,
            icon = icon,
            size = if (bare) size else minOf(size, height) * glyphScale,
        )
    }
}

// ---------- 行 ----------

@Composable
fun EcoRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.eco.blue,
    iconBackground: Color = iconTint.copy(alpha = 0.13f),
    trail: String? = null,
    trailColor: Color = MaterialTheme.eco.muted,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    minHeight: Dp = 54.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    val titleColor = if (danger) c.clay else c.ink
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.ecoPressable(scaleTo = 0.99f, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            EcoIconTile(icon = icon, tint = iconTint, size = 30.dp, radius = 10.dp, iconSize = 16.dp, background = iconBackground)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = EcoType.RowTitle, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = EcoType.RowSub, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trail != null) {
            Text(trail, style = EcoType.RowTrail, color = trailColor, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        if (trailing != null) {
            trailing()
        } else if (showChevron) {
            Icon(EcoIcons.Chev, contentDescription = null, tint = c.quiet, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun EcoSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = EcoType.RowTitle, color = c.ink.copy(alpha = if (enabled) 1f else 0.45f))
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = EcoType.RowSub, color = c.muted, maxLines = 2)
            }
        }
        Spacer(Modifier.width(14.dp))
        EcoSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

// ---------- 输入 ----------

@Composable
fun EcoField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minHeight: Dp = 43.dp,
    textStyle: TextStyle = EcoType.Input,
    trailing: (@Composable () -> Unit)? = null,
    supporting: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.eco
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = EcoType.FieldLabel, color = c.muted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(RoundedCornerShape(11.dp))
                .background(c.surface2)
                .border(
                    1.dp,
                    when {
                        isError -> c.clay
                        focused -> c.blue.copy(alpha = 0.65f)
                        else -> c.rule
                    },
                    RoundedCornerShape(11.dp),
                )
                .padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 10.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = textStyle.copy(color = c.ink),
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = SolidColor(c.blue),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(placeholder, style = textStyle, color = c.quiet, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            trailing?.invoke()
        }
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(supporting, style = EcoType.PageNote, color = if (isError) c.clay else c.quiet)
        }
    }
}

@Composable
fun EcoMoneyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "0.00",
    supporting: String? = null,
    isError: Boolean = false,
    suffix: String? = null,
) {
    val c = MaterialTheme.eco
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = EcoType.FieldLabel, color = c.muted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 57.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(c.surface2)
                .border(1.dp, if (isError) c.clay else if (focused) c.blue.copy(alpha = .65f) else c.rule, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = EcoType.EditAmount.copy(color = c.ink),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(c.blue),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(placeholder, style = EcoType.EditAmount, color = c.quiet)
                        }
                        inner()
                    }
                },
            )
            if (suffix != null) Text(suffix, style = EcoType.RowTrail, color = c.quiet)
        }
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(supporting, style = EcoType.PageNote, color = if (isError) c.clay else c.quiet)
        }
    }
}

/** 通用行内数值（设置页右侧） */
@Composable
fun EcoRowValue(text: String, modifier: Modifier = Modifier) {
    Text(text, style = EcoType.RowTrail, color = MaterialTheme.eco.muted, modifier = modifier)
}

/** 金额符号 + 数字：统一正负号/颜色 */
@Composable
fun EcoSignedMoney(
    text: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = EcoType.BillAmount,
) {
    val c = MaterialTheme.eco
    Text(
        text,
        style = style,
        color = if (positive) c.jade else c.ink,
        textAlign = TextAlign.End,
        modifier = modifier,
        maxLines = 1,
    )
}
