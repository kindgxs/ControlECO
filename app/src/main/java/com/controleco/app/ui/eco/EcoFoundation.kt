package com.controleco.app.ui.eco

import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.util.Money
import kotlin.math.roundToLong
import com.controleco.app.ui.theme.EcoColors
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.theme.heroGradient

/**
 * 美化动效开关（来自设置里的「显示动画」，默认 true）。
 * 为 false 时组件把动画值直接定格到终值：不滚数、不逐帧画环/柱、卡片不弹入。
 * 由 [com.controleco.app.MainActivity] 在根部提供。
 */
val LocalAnimationsEnabled = compositionLocalOf { true }

/**
 * 纸面背景：纸色 + 两团极淡径向光。整页共用一张，位于所有内容之下；
 * 状态栏区域也铺满，配合 edge-to-edge 让顶部没有色带。
 */
@Composable
fun EcoBackdrop(modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    val reveal = remember { Animatable(0f) }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) {
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.fillMaxSize()) {
        drawRect(c.paper)
        val w = size.width
        val h = size.height
        // 右上主光 = 强调色；左下补光 = 强调色的亮端（原来是固定玉绿，
        // 任何主题下左下都泛绿，这也是「青绿太多」的来源之一）
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c.blue.copy(alpha = if (c.isDark) 0.075f else 0.05f), Color.Transparent),
                center = Offset(w * 0.91f, h * 0.14f),
                radius = w * 0.72f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c.blueHi.copy(alpha = if (c.isDark) 0.055f else 0.04f), Color.Transparent),
                center = Offset(w * 0.01f, h * 0.76f),
                radius = w * 0.78f,
            ),
        )

        // 原型的 geo 层：等高线 / 细规线 / 点阵 / 十字 / 菱形，入场 0.6s 淡入
        val a = reveal.value
        if (a <= 0.001f) return@Canvas
        val line = if (c.isDark) 0.38f else 0.58f
        val thin = 1.dp.toPx()
        val inkLine = c.blue.copy(alpha = line * 0.55f * a)
        val ruleLine = c.rule.copy(alpha = line * a)
        val stroke = Stroke(width = thin, cap = StrokeCap.Round)

        // 右上大圆与内圈
        drawCircle(
            color = ruleLine,
            radius = w * 0.52f,
            center = Offset(w * 1.10f, h * 0.10f),
            style = stroke,
        )
        drawCircle(
            color = ruleLine.copy(alpha = ruleLine.alpha * 0.7f),
            radius = w * 0.37f,
            center = Offset(w * 1.02f, h * 0.22f),
            style = stroke,
        )
        // 顶部两道等高线，越向左侧越淡
        val contour1 = Path().apply {
            moveTo(-w * 0.12f, h * 0.115f)
            cubicTo(w * 0.18f, h * 0.055f, w * 0.48f, h * 0.13f, w * 1.12f, h * 0.255f)
        }
        drawPath(contour1, inkLine, style = stroke)
        val contour2 = Path().apply {
            moveTo(-w * 0.10f, h * 0.165f)
            cubicTo(w * 0.24f, h * 0.105f, w * 0.54f, h * 0.18f, w * 1.10f, h * 0.325f)
        }
        drawPath(contour2, inkLine.copy(alpha = inkLine.alpha * 0.62f), style = stroke)
        val contour3 = Path().apply {
            moveTo(-w * 0.06f, h * 0.70f)
            cubicTo(w * 0.20f, h * 0.60f, w * 0.46f, h * 0.79f, w * 0.92f, h * 0.70f)
        }
        drawPath(contour3, inkLine.copy(alpha = inkLine.alpha * 0.42f), style = stroke)

        // 左下点阵（向中心衰减）
        val cols = 7
        val rows = 6
        val gap = 19.dp.toPx()
        val ox = -w * 0.055f
        val oy = h * 0.755f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val fade = (1f - (row / rows.toFloat()) * 0.65f) * (1f - (col / cols.toFloat()) * 0.35f)
                drawCircle(
                    color = c.blue.copy(alpha = 0.24f * a * fade),
                    radius = 1.15.dp.toPx(),
                    center = Offset(ox + col * gap, oy + row * gap),
                )
            }
        }

        // 十字 / 菱形 / 小圆点：装饰元素用「主色 + 次要色」，不占语义色
        // （原来这里用 amber，导致每页背景都有固定的金色点缀）
        fun cross(cx: Float, cy: Float, r: Float, color: Color) {
            drawLine(color, Offset(cx - r, cy), Offset(cx + r, cy), thin, StrokeCap.Round)
            drawLine(color, Offset(cx, cy - r), Offset(cx, cy + r), thin, StrokeCap.Round)
        }
        cross(w * 0.075f, h * 0.635f, 7.dp.toPx(), c.second.copy(alpha = 0.35f * a))
        cross(w * 0.80f, h * 0.405f, 8.dp.toPx(), c.blue.copy(alpha = 0.25f * a))
        val diamond = Path().apply {
            moveTo(w * 0.925f, h * 0.455f - 9.dp.toPx())
            lineTo(w * 0.925f + 13.dp.toPx(), h * 0.455f)
            lineTo(w * 0.925f, h * 0.455f + 9.dp.toPx())
            lineTo(w * 0.925f - 13.dp.toPx(), h * 0.455f)
            close()
        }
        drawPath(diamond, c.rule.copy(alpha = 0.35f * a), style = stroke)
        drawCircle(c.blue.copy(alpha = 0.45f * a), radius = 2.6.dp.toPx(), center = Offset(w * 0.155f, h * 0.225f))
        drawCircle(c.second.copy(alpha = 0.30f * a), radius = 1.8.dp.toPx(), center = Offset(w * 0.86f, h * 0.29f))

        // 斜规线：从左到右极淡消失
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, c.blue.copy(alpha = 0.11f * a), Color.Transparent),
            ),
            start = Offset(w * 0.02f, h * 0.84f),
            end = Offset(w * 0.92f, h * 0.75f),
            strokeWidth = thin,
        )
    }
}

/** 陶瓷卡片：surface 面 + 1dp 描边 + 四段阴影 + 顶部中间玉色发丝线 */
fun Modifier.ecoPane(
    colors: EcoColors,
    shape: Shape = RoundedCornerShape(19.dp),
    elevation: Dp = 10.dp,
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = colors.ink.copy(alpha = if (colors.isDark) 0.34f else 0.10f),
        spotColor = colors.ink.copy(alpha = if (colors.isDark) 0.42f else 0.16f),
    )
    .clip(shape)
    .background(
        Brush.linearGradient(
            colors = listOf(colors.surface, lerp(colors.surface, colors.blueSoft, 0.13f)),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
    )
    .border(1.dp, colors.rule, shape)

@Composable
fun EcoPane(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(19.dp),
    elevation: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .ecoPane(c, shape, elevation)
            .drawWithContent {
                drawContent()
                val inset = 21.dp.toPx()
                drawLine(
                    // 卡片顶部发丝高光取强调色亮端，跟主题走（原来固定玉绿）
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, c.blueHi.copy(alpha = 0.19f), Color.Transparent),
                        startX = inset,
                        endX = size.width - inset,
                    ),
                    start = Offset(inset, 0.5f),
                    end = Offset(size.width - inset, 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(contentPadding),
    ) {
        content()
    }
}

@Composable
fun EcoHairline(modifier: Modifier = Modifier, color: Color = MaterialTheme.eco.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 行间分隔：与原型一致，左右缩进 16dp */
@Composable
fun EcoRowDivider(inset: Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(MaterialTheme.eco.rule),
    )
}

/** 可按压缩放（替代默认水波纹，接近原型的 scale(.97) 手感）；[onLongClick] 用于长按菜单 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.ecoPressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.97f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleTo else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "eco-press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (onLongClick == null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                )
            } else {
                Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            },
        )
}

/**
 * 大金额自适应：先按设计字号测量，超宽依次降到 88% / 78% / 68%，不换行。
 * 用法：`EcoAutoMoney(text, EcoType.MeterMoney, maxWidth = 220.dp)`
 *
 * [suffix] 紧跟在金额后面，用 [suffixStyle] 单独设字号/颜色（如 `/day`），
 * 与金额同属一行，不会被挤到右侧。
 */
@Composable
fun EcoAutoMoney(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    widthLimit: Dp? = null,
    minSize: TextUnit = 18.sp,
    textAlign: TextAlign = TextAlign.Center,
    // 量文本用哪个字符串：滚数时传最终值，字体尺寸只在目标变化时量一次；
    // 动画期间逐帧换数字不再重复量 12 次（切页/滚数卡顿的主因）。
    // remember 以 fitText 为 key，数值更新时旧测量结果自动丢弃，不会越积越多。
    fitText: String = text,
    suffix: String = "",
    suffixStyle: SpanStyle = SpanStyle(),
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val available = widthLimit ?: maxWidth
        val availablePx = if (available == Dp.Infinity || available.value <= 0f) {
            with(density) { 320.dp.toPx() }
        } else {
            with(density) { available.toPx() }
        }
        val richText = remember(text, suffix, suffixStyle) {
            buildAnnotatedString {
                append(text)
                if (suffix.isNotEmpty()) withStyle(suffixStyle) { append(suffix) }
            }
        }
        // 量字号用“最终值 + 后缀”：滚动时只量一次，后缀的小字号也计入宽度
        val fitRichText = remember(fitText, suffix, suffixStyle) {
            buildAnnotatedString {
                append(fitText)
                if (suffix.isNotEmpty()) withStyle(suffixStyle) { append(suffix) }
            }
        }
        val fitted = remember(fitRichText, style, availablePx) {
            var size = style.fontSize
            var guard = 0
            while (guard++ < 12) {
                val result = measurer.measure(
                    text = fitRichText,
                    // 字号缩小要同步缩字距，否则 DailyValue 的 -2sp 会把数字压在一起
                    style = style.fitTo(size),
                    maxLines = 1,
                    softWrap = false,
                )
                if (result.size.width <= availablePx || size.value <= minSize.value) break
                size = (size.value * 0.88f).coerceAtLeast(minSize.value).sp
            }
            size
        }
        Text(
            text = richText,
            style = style.fitTo(fitted),
            color = color,
            maxLines = 1,
            softWrap = false,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 金额滚数：按原型 940ms easeOutQuart 从 0 滚到目标值。
 * 每次页面进入（重新组合）都会重播；数值变化时也会重新滚。
 */
@Composable
fun EcoCountUpMoney(
    cents: Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    widthLimit: Dp? = null,
    minSize: TextUnit = 18.sp,
    textAlign: TextAlign = TextAlign.Center,
    durationMillis: Int = 940,
    format: (Long) -> String = { Money.formatYuanGroupedFixed(it) },
    // 紧跟在金额后面的小字（如 "/day"），与金额同一行、单独样式
    suffix: String = "",
    suffixStyle: SpanStyle = SpanStyle(),
) {
    val anim = remember { Animatable(0f) }
    // 目标值的字符串只算一次：作为 EcoAutoMoney 的测量缓存 key，
    // cents 变化时 key 变化，旧的测量结果被自动丢弃，不会累积。
    val fitText = remember(cents, format) { format(cents) }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(cents, animationsEnabled) {
        if (!animationsEnabled) {
            // 关闭美化动效：直接显示目标金额
            anim.snapTo(cents.toFloat())
            return@LaunchedEffect
        }
        anim.snapTo(0f)
        anim.animateTo(
            targetValue = cents.toFloat(),
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f),
            ),
        )
    }
    EcoAutoMoney(
        text = format(anim.value.roundToLong()),
        style = style,
        color = color,
        modifier = modifier,
        widthLimit = widthLimit,
        minSize = minSize,
        textAlign = textAlign,
        // 用最终值做一次测量并缓存，滚动过程中复用，避免每帧重新量
        fitText = fitText,
        suffix = suffix,
        suffixStyle = suffixStyle,
    )
}

/**
 * 按目标字号等比例缩放 sp 字距。
 * 命名版式里的 `letterSpacing` 是按设计字号算出的固定 sp 值（如 DailyValue 31sp × -0.065 = -2.015sp），
 * 自适应缩到 13sp 后若仍用 -2sp，数字会压在一起；em 字距本身随字号缩放，保持原样。
 */
private fun TextStyle.fitTo(size: TextUnit): TextStyle {
    val base = fontSize.value
    val ratio = if (base > 0f) size.value / base else 1f
    val tracking = letterSpacing
    return copy(
        fontSize = size,
        letterSpacing = if (tracking.isSp) (tracking.value * ratio).sp else tracking,
    )
}

/**
 * 个人页「散落弹入」：逐帧照搬原型 `.profile-arrival` / `ecoScatteredPop`
 * （0.56s cubic-bezier(.17,.8,.18,1)：25px 上移 + .79 缩放 + 起始 2px 模糊，
 * 65% 处过冲到 -5px / 1.042 / -0.7°，84% 回落到 1px / .992，最后归位）。
 */
@Composable
fun EcoArrival(
    delayMillis: Int = 0,
    turn: Float = 3f,
    modifier: Modifier = Modifier,
    /** true 时忽略 delayMillis，30–630ms 随机延迟（个人页卡片无序弹出） */
    randomDelay: Boolean = false,
    content: @Composable () -> Unit,
) {
    val actualDelay = remember(randomDelay, delayMillis) {
        if (randomDelay) 30 + kotlin.random.Random.nextInt(0, 600) else delayMillis
    }
    // 原型：--arrival-turn 符号随序号交替，幅度 2.8–5.0deg
    val startTurn = remember(turn) {
        if (turn == 0f) 0f
        else (if (kotlin.random.Random.nextBoolean()) -1f else 1f) * (2.8f + kotlin.random.Random.nextFloat() * 2.2f)
    }
    val progress = remember { Animatable(0f) }
    val ease = CubicBezierEasing(0.17f, 0.8f, 0.18f, 1f)
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(actualDelay, animationsEnabled) {
        if (!animationsEnabled) {
            // 关闭美化动效：卡片直接落位
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(actualDelay.toLong())
        progress.animateTo(0.65f, tween(durationMillis = 364, easing = ease))
        progress.animateTo(0.84f, tween(durationMillis = 106, easing = ease))
        progress.animateTo(1f, tween(durationMillis = 90, easing = ease))
    }
    val p = progress.value
    fun lerpTo(a: Float, b: Float, t: Float) = a + (b - a) * t
    // 幅度按可见区域收窄：缩放上限 1.012、上移 14px，避免被父容器裁掉
    val risePx = when {
        p <= 0.65f -> lerpTo(14f, -3f, p / 0.65f)
        p <= 0.84f -> lerpTo(-3f, 0.6f, (p - 0.65f) / 0.19f)
        else -> lerpTo(0.6f, 0f, (p - 0.84f) / 0.16f)
    }
    val scale = when {
        p <= 0.65f -> lerpTo(0.90f, 1.012f, p / 0.65f)
        p <= 0.84f -> lerpTo(1.012f, 0.996f, (p - 0.65f) / 0.19f)
        else -> lerpTo(0.996f, 1f, (p - 0.84f) / 0.16f)
    }
    val rot = when {
        p <= 0.65f -> lerpTo(startTurn, -0.7f, p / 0.65f)
        p <= 0.84f -> lerpTo(-0.7f, 0f, (p - 0.65f) / 0.19f)
        else -> 0f
    }
    val fade = (p / 0.65f).coerceIn(0f, 1f)
    val blurPx = (1f - p / 0.65f).coerceIn(0f, 1f) * 2f
    
    Box(
        modifier
            // 弹跳期间提升绘制层级，避免被相邻卡片盖住
            .zIndex(if (p < 1f) 1f else 0f)
            .graphicsLayer {
                // 避免透明度动画触发裁切用的离屏缓冲区
                compositingStrategy = CompositingStrategy.ModulateAlpha

                // 允许阴影、底盘和弹跳内容超出自身边界
                clip = false

                transformOrigin = TransformOrigin(0.5f, 0.66f)

                alpha = fade
                scaleX = scale
                scaleY = scale
                translationY = risePx.dp.toPx()
                rotationZ = rot
            }
    ) {
        content()
    }
}


/** 章节头：标题 / 右侧说明（可选） */
@Composable
fun EcoSectionHead(
    title: String,
    aside: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = EcoType.SectionTitle, color = c.ink)
        // 原型：标题和右侧 aside 之间有一条极细的延长线
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.rule))
        Spacer(Modifier.width(12.dp))
        if (aside != null) Text(aside, style = EcoType.SectionAside, color = c.quiet)
        trailing?.invoke()
    }
}

@Composable
fun EcoPageLead(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = EcoType.PageLead,
        color = MaterialTheme.eco.muted,
        modifier = modifier.padding(horizontal = 3.dp, vertical = 5.dp),
    )
}

@Composable
fun EcoPageNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = EcoType.PageNote,
        color = MaterialTheme.eco.quiet,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
fun EcoEmpty(
    title: String,
    body: String? = null,
    icon: ImageVector = EcoIcons.Spark,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(c.blueSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.blue, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = EcoType.EmptyTitle, color = c.ink, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = EcoType.EmptyBody, color = c.muted, textAlign = TextAlign.Center)
        }
    }
}

/** 深色 hero 卡（预算/封面/关于等）：渐变 + 右上装饰环 */
@Composable
fun EcoHeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp, 33.dp, 12.dp, 33.dp),
    colors: List<Color>? = null,
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    val gradient = colors ?: c.heroGradient
    val glow = rememberInfiniteTransition(label = "hero-glow")
    val glowPhase by glow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "hero-glow-phase",
    )
    Box(
        modifier
            .shadow(
                elevation = 13.dp,
                shape = shape,
                clip = false,
                // 阴影取该 hero 渐变的深端，跟着主题走（原来是固定的青黑）
                ambientColor = gradient.last().copy(alpha = 0.25f),
                spotColor = gradient.last().copy(alpha = 0.62f),
            )
            .clip(shape)
            .background(Brush.linearGradient(gradient, start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            // 原型 hero 的 inset 高光/暗边：顶边 1px 白、底边内侧压深
            .drawWithContent {
                val outlinePath = Path().apply {
                    addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent))
                }
                clipPath(outlinePath) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.24f),
                        size = Size(size.width, 1.2.dp.toPx()),
                    )
                    val band = 10.dp.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.30f),
                            startY = size.height - band,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - band),
                        size = Size(size.width, band),
                    )
                }
                drawContent()
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 原型 hero 右上柔光只能用 shader 画，但本机 Skia 会把大的 radialGradient
            // 插值成硬边黑块；改为只让装饰环缓慢漂移/呼吸，避免黑块回归。
            val ringCenter = Offset(
                size.width * (0.94f + 0.02f * glowPhase),
                -size.height * (0.08f - 0.035f * glowPhase),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = size.width * 0.34f,
                center = ringCenter,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = size.width * 0.24f,
                center = ringCenter,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        content()
    }
}

/** sheet 顶部的把手（38 x 4，track 色） */
@Composable
fun EcoHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 9.dp, bottom = 5.dp)
            .width(38.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.eco.track),
    )
}

/** 细进度条（分类/时间/解析） */
@Composable
fun EcoProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 9.dp,
    track: Color = MaterialTheme.eco.surface3,
    fill: Brush = Brush.horizontalGradient(
        listOf(MaterialTheme.eco.blue.copy(alpha = 0.55f), MaterialTheme.eco.blue),
    ),
    animationDelayMillis: Int = 0,
) {
    val target = progress.coerceIn(0f, 1f)
    // 每次进入页面都从 0 重播入场（与原型一致）
    val drawn = remember { Animatable(0f) }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(target, animationsEnabled) {
        if (!animationsEnabled) {
            // 关闭美化动效：进度条直接到位
            drawn.snapTo(target)
            return@LaunchedEffect
        }
        drawn.snapTo(0f)
        if (animationDelayMillis > 0) kotlinx.coroutines.delay(animationDelayMillis.toLong())
        drawn.animateTo(
            target,
            tween(900, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)),
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(drawn.value)
                .height(height)
                .clip(CircleShape)
                .background(fill),
        )
    }
}

/** 顶栏 micro 状态标签（不带前置圆点） */
@Composable
fun EcoMicro(text: String) {
    val c = MaterialTheme.eco
    Text(text, style = EcoType.Micro, color = c.blue)
}
