package com.controleco.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import kotlin.math.cos
import kotlin.math.sin

/**
 * 分类标记：账单列表行首的小 logo，也是自定义图标的"内置图形"库。
 *
 * 图形来源两类：
 * 1. **用户提供的 6 个 SVG**（购物 / 餐饮 / 交通 / 工资 / 娱乐 / 日用，见 [GlyphPaths]）——
 *    按路径原样绘制，颜色由分类色板下发；
 * 2. 自绘几何图形（碗 / 车 / 袋 / 房 / 十字 / 星 / 书 / 信号 / 红包 / K 线 / ￥ …）。
 *
 * 常见分类按关键词命中语义图形，其余按分类名哈希稳定取形；同一分类在任何页面、
 * 任何会话都是同一形状 + 同一颜色，便于判别。
 */
enum class Glyph(val label: String) {
    // 用户提供的 SVG 图形
    SVG_BAG("购物袋"),
    SVG_MEAL("餐盘"),
    SVG_CAR("汽车"),
    SVG_SALARY("工资袋"),
    SVG_GAME("游戏手柄"),
    SVG_DAILY("日用瓶"),
    // 自绘几何图形
    BURGER("汉堡"),
    BOWL("碗"),
    CAR("小车"),
    BAG("袋子"),
    HOUSE("房子"),
    CROSS("医疗"),
    STAR("星星"),
    BOOK("书本"),
    SIGNAL("信号"),
    ARROW_UP("上行箭头"),
    WAVE("波动"),
    RED_PACKET("红包"),
    CANDLES("K 线"),
    YEN("￥"),
    RING("圆环"),
    DOTS("省略号"),
    TRIANGLE("三角"),
    DIAMOND("菱形"),
    BARS("柱状图"),
    GRID("方块组"),
    // 扩充图形库（分类图标选择器可选用；不参与默认哈希取形，避免改变已有分类的外观）
    COFFEE("咖啡杯"),
    PET("宠物爪"),
    SPORT("运动"),
    FLOWER("鲜花"),
    GIFT("礼物"),
    MUSIC("音乐"),
    CAMERA("相机"),
    PLANE("飞机"),
    BUS("公交"),
    BIKE("单车"),
    DROP("水滴"),
    BOLT("闪电"),
    GRAD_CAP("学业"),
    LAPTOP("电脑"),
    LIPSTICK("美妆"),
    APPLE("水果"),
    ICE_CREAM("冰淇淋"),
    WINE("酒水"),
    SHIELD("保险"),
    RECEIPT("票据"),
    WRENCH("维修"),
    BABY("母婴"),
    HEART("心意"),
    CROWN("会员"),
    BED("酒店"),
    // 第二批扩充图形（同样只进选择器，不参与默认哈希取形）
    PIZZA("披萨"),
    CAKE("蛋糕"),
    CANDY("糖果"),
    BREAD("面包"),
    FISH("海鲜"),
    CARROT("蔬菜"),
    LEAF("绿植"),
    UMBRELLA("雨伞"),
    SHIRT("服饰"),
    SHOE("鞋履"),
    GLASSES("眼镜"),
    WATCH("手表"),
    PHONE("手机"),
    HEADPHONE("耳机"),
    TV("电视"),
    TRAIN("火车"),
    SHIP("船运"),
    FUEL("加油"),
    PARKING("停车"),
    TICKET("门票"),
    TROPHY("奖杯"),
    TARGET("目标"),
    CLOCK("时间"),
    CALENDAR("日历"),
    WALLET("钱包"),
    BANK("银行"),
    COIN("金币"),
    CARD("银行卡"),
    PIGGY("存钱罐"),
    ;

    /** SVG 图形对应的路径数据；自绘图形为 null */
    val svgData: String?
        get() = when (this) {
            SVG_BAG -> GlyphPaths.BAG
            SVG_MEAL -> GlyphPaths.MEAL
            SVG_CAR -> GlyphPaths.CAR
            SVG_SALARY -> GlyphPaths.SALARY
            SVG_GAME -> GlyphPaths.GAME
            SVG_DAILY -> GlyphPaths.DAILY
            else -> null
        }
}

private val KEYWORDS: List<Pair<List<String>, Glyph>> = listOf(
    listOf("餐", "食", "饭", "菜", "汉堡", "快餐", "外卖", "宵夜", "烧烤", "饮料") to Glyph.SVG_MEAL,
    listOf("咖啡", "奶茶", "饮", "酒", "茶") to Glyph.BOWL,
    listOf("交通", "出行", "打车", "网约", "出租", "地铁", "公交", "加油", "停车", "高铁", "机票", "车") to Glyph.SVG_CAR,
    listOf("购物", "服饰", "衣", "鞋", "超市", "百货", "美妆", "数码") to Glyph.SVG_BAG,
    listOf("日用", "生活用品", "日用品", "家居日用") to Glyph.SVG_DAILY,
    listOf("住", "房", "租", "物业", "水电", "燃气", "装修") to Glyph.HOUSE,
    listOf("医", "药", "健康", "体检", "挂号") to Glyph.CROSS,
    listOf("游戏", "娱乐", "电玩", "网吧") to Glyph.SVG_GAME,
    listOf("电影", "旅行", "旅游", "景点", "演出", "会员") to Glyph.STAR,
    listOf("教育", "学习", "书", "培训", "课程", "考试") to Glyph.BOOK,
    listOf("通讯", "话费", "流量", "网络", "宽带", "电话") to Glyph.SIGNAL,
    listOf("红包", "压岁") to Glyph.RED_PACKET,
    listOf("工资", "薪", "奖金", "收入", "报酬", "补贴") to Glyph.SVG_SALARY,
    listOf("报销", "退款", "收款", "转入") to Glyph.ARROW_UP,
    listOf("转", "理财", "投资", "基金", "股票", "公积金", "收益") to Glyph.CANDLES,
    listOf("其他", "其它", "杂") to Glyph.DOTS,
)

/** 按分类名哈希取形的候选集合：固定为 v5 的 24 个图形，新增图形只进选择器、不改变已有分类外观 */
private val HASH_SHAPES: List<Glyph> = listOf(
    Glyph.SVG_BAG, Glyph.SVG_MEAL, Glyph.SVG_CAR, Glyph.SVG_SALARY, Glyph.SVG_GAME, Glyph.SVG_DAILY,
    Glyph.BURGER, Glyph.BOWL, Glyph.CAR, Glyph.BAG, Glyph.HOUSE, Glyph.CROSS, Glyph.STAR, Glyph.BOOK,
    Glyph.SIGNAL, Glyph.ARROW_UP, Glyph.WAVE, Glyph.CANDLES, Glyph.YEN, Glyph.RING, Glyph.TRIANGLE,
    Glyph.DIAMOND, Glyph.BARS, Glyph.GRID,
)

/** 分类名 → 形状：先关键词，再按名字哈希稳定取形 */
fun glyphForName(categoryName: String): Glyph {
    KEYWORDS.firstOrNull { (words, _) -> words.any { categoryName.contains(it) } }?.let { return it.second }
    var h = 7
    for (ch in categoryName) h = (h * 31 + ch.code) and 0x7FFFFFFF
    return HASH_SHAPES[h % HASH_SHAPES.size]
}

/** 图标规格里的图形名 → 图形（未知名字返回 null，调用方回落到按名字取形） */
fun glyphByName(name: String): Glyph? = Glyph.entries.firstOrNull { it.name == name }

/** 图形的固定用色：红包用喜庆红，其余用分类稳定色 */
@Composable
fun glyphTint(glyph: Glyph, categoryName: String): Color =
    if (glyph == Glyph.RED_PACKET) {
        MaterialTheme.accBook.festiveRed
    } else {
        categoryColor(MaterialTheme.accBook.categoryPalette, categoryName)
    }

/** 默认分类标记（未自定义图标时）：按分类名取形 + 分类色 */
@Composable
fun CategoryGlyph(categoryName: String, size: Dp = 40.dp) {
    val glyph = remember(categoryName) { glyphForName(categoryName) }
    // 不画底板，只留图形本身
    GlyphIcon(glyph = glyph, tint = glyphTint(glyph, categoryName), size = size)
}

/** 指定图形的渲染（图标选择器、自定义图标、默认标记共用） */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.62f)) { drawGlyph(glyph, tint) }
    }
}

// ---------- 各形状画法：统一画在 s×s 画布里 ----------

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val svg = glyph.svgData
    if (svg != null) {
        drawSvgGlyph(svg, color)
        return
    }
    val s = size.minDimension
    val w = s * 0.15f
    val stroke = Stroke(width = w, cap = StrokeCap.Round)
    when (glyph) {
        Glyph.BURGER -> {
            // 三层汉堡：上层面包（半圆）+ 中间夹层 + 下层面包
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(s * 0.06f, s * 0.14f),
                size = Size(s * 0.88f, s * 0.88f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.04f, s * 0.52f),
                size = Size(s * 0.92f, s * 0.16f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.10f, s * 0.74f),
                size = Size(s * 0.80f, s * 0.18f),
                cornerRadius = CornerRadius(s * 0.09f),
            )
        }
        Glyph.RED_PACKET -> {
            // 红包：上方倒三角（最长边 = 长方形宽）+ 下方长方形顶部扣去倒三角。
            // 三角的两条斜边与长方形缺口的斜边**平行**，只留一条均匀窄缝（0.10s 的纵向错位），
            // 而不是把长方形整体下移留出一大片空隙。
            val flap = Path().apply {
                moveTo(s * 0.16f, s * 0.08f)
                lineTo(s * 0.84f, s * 0.08f)
                lineTo(s * 0.50f, s * 0.42f)
                close()
            }
            drawPath(flap, color)
            val body = Path().apply {
                moveTo(s * 0.16f, s * 0.18f)
                lineTo(s * 0.50f, s * 0.52f)
                lineTo(s * 0.84f, s * 0.18f)
                lineTo(s * 0.84f, s * 0.94f)
                lineTo(s * 0.16f, s * 0.94f)
                close()
            }
            drawPath(body, color)
        }
        Glyph.CANDLES -> {
            // 三根 K 线：实体矩形 + 中间穿插竖线，高度/长短各不相同
            listOf(
                Triple(0.16f, 0.44f, 0.34f),
                Triple(0.42f, 0.18f, 0.50f),
                Triple(0.68f, 0.32f, 0.38f),
            ).forEach { (x, top, height) ->
                val cx = s * (x + 0.08f)
                drawLine(
                    color = color,
                    start = Offset(cx, s * (top - 0.12f).coerceAtLeast(0f)),
                    end = Offset(cx, s * (top + height + 0.12f).coerceAtMost(s)),
                    strokeWidth = s * 0.05f,
                    cap = StrokeCap.Round,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * x, s * top),
                    size = Size(s * 0.16f, s * height),
                    cornerRadius = CornerRadius(s * 0.04f),
                )
            }
        }
        Glyph.YEN -> {
            // ￥：两撇 + 竖干 + 两横
            drawLine(color, Offset(s * 0.20f, s * 0.10f), Offset(s * 0.50f, s * 0.46f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.80f, s * 0.10f), Offset(s * 0.50f, s * 0.46f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.50f, s * 0.42f), Offset(s * 0.50f, s * 0.92f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.22f, s * 0.58f), Offset(s * 0.78f, s * 0.58f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.22f, s * 0.78f), Offset(s * 0.78f, s * 0.78f), w, StrokeCap.Round)
        }
        Glyph.BOWL -> {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.06f, s * 0.30f),
                size = Size(s * 0.88f, s * 0.88f),
                style = stroke,
            )
            drawLine(
                color = color,
                start = Offset(s * 0.06f, s * 0.30f),
                end = Offset(s * 0.94f, s * 0.30f),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
        Glyph.CAR -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.38f),
                size = Size(s * 0.88f, s * 0.30f),
                cornerRadius = CornerRadius(s * 0.10f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.24f, s * 0.16f),
                size = Size(s * 0.52f, s * 0.22f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.26f, s * 0.76f))
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.74f, s * 0.76f))
        }
        Glyph.BAG -> {
            val body = Path().apply {
                moveTo(s * 0.16f, s * 0.34f)
                lineTo(s * 0.84f, s * 0.34f)
                lineTo(s * 0.74f, s * 0.90f)
                lineTo(s * 0.26f, s * 0.90f)
                close()
            }
            drawPath(body, color)
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.32f, s * 0.08f),
                size = Size(s * 0.36f, s * 0.36f),
                style = stroke,
            )
        }
        Glyph.HOUSE -> {
            val roof = Path().apply {
                moveTo(s * 0.50f, s * 0.08f)
                lineTo(s * 0.94f, s * 0.46f)
                lineTo(s * 0.06f, s * 0.46f)
                close()
            }
            drawPath(roof, color)
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.20f, s * 0.50f),
                size = Size(s * 0.60f, s * 0.40f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
        }
        Glyph.CROSS -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.40f, s * 0.10f),
                size = Size(s * 0.20f, s * 0.80f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.10f, s * 0.40f),
                size = Size(s * 0.80f, s * 0.20f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
        }
        Glyph.STAR -> {
            listOf(0f, 90f).forEach { deg ->
                rotate(deg, pivot = Offset(s / 2f, s / 2f)) {
                    val p = Path().apply {
                        moveTo(s * 0.50f, s * 0.04f)
                        lineTo(s * 0.70f, s * 0.50f)
                        lineTo(s * 0.50f, s * 0.96f)
                        lineTo(s * 0.30f, s * 0.50f)
                        close()
                    }
                    drawPath(p, color)
                }
            }
        }
        Glyph.BOOK -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.24f),
                size = Size(s * 0.38f, s * 0.52f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.56f, s * 0.24f),
                size = Size(s * 0.38f, s * 0.52f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.46f, s * 0.16f),
                size = Size(s * 0.08f, s * 0.68f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
        }
        Glyph.SIGNAL -> {
            listOf(0.42f, 0.70f, 0.98f).forEach { f ->
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(s * (0.5f - f / 2f), s * 0.86f - f * s * 0.72f),
                    size = Size(s * f, s * f),
                    style = stroke,
                )
            }
            drawCircle(color = color, radius = s * 0.11f, center = Offset(s * 0.50f, s * 0.86f))
        }
        Glyph.ARROW_UP -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.42f, s * 0.32f),
                size = Size(s * 0.16f, s * 0.60f),
                cornerRadius = CornerRadius(s * 0.06f),
            )
            val head = Path().apply {
                moveTo(s * 0.50f, s * 0.06f)
                lineTo(s * 0.84f, s * 0.42f)
                lineTo(s * 0.16f, s * 0.42f)
                close()
            }
            drawPath(head, color)
        }
        Glyph.WAVE -> {
            val p = Path().apply {
                moveTo(s * 0.06f, s * 0.62f)
                cubicTo(s * 0.26f, s * 0.14f, s * 0.48f, s * 0.94f, s * 0.94f, s * 0.38f)
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.RING -> {
            drawCircle(color = color, radius = s * 0.34f, center = Offset(s / 2f, s / 2f), style = Stroke(w))
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.74f, s * 0.74f))
        }
        Glyph.DOTS -> {
            drawCircle(color = color, radius = s * 0.13f, center = Offset(s * 0.22f, s * 0.50f))
            drawCircle(color = color, radius = s * 0.13f, center = Offset(s * 0.50f, s * 0.50f))
            drawCircle(color = color, radius = s * 0.13f, center = Offset(s * 0.78f, s * 0.50f))
        }
        Glyph.TRIANGLE -> {
            val p = Path().apply {
                moveTo(s * 0.50f, s * 0.10f)
                lineTo(s * 0.92f, s * 0.86f)
                lineTo(s * 0.08f, s * 0.86f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.DIAMOND -> {
            val p = Path().apply {
                moveTo(s * 0.50f, s * 0.06f)
                lineTo(s * 0.92f, s * 0.50f)
                lineTo(s * 0.50f, s * 0.94f)
                lineTo(s * 0.08f, s * 0.50f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.BARS -> {
            listOf(0.16f to 0.44f, 0.42f to 0.68f, 0.68f to 0.90f).forEach { (x, h) ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * x, s * (0.92f - h)),
                    size = Size(s * 0.16f, s * h),
                    cornerRadius = CornerRadius(s * 0.06f),
                )
            }
        }
        Glyph.GRID -> {
            listOf(0.10f to 0.10f, 0.54f to 0.10f, 0.10f to 0.54f, 0.54f to 0.54f).forEach { (x, y) ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * x, s * y),
                    size = Size(s * 0.36f, s * 0.36f),
                    cornerRadius = CornerRadius(s * 0.10f),
                )
            }
        }
        // ---------- 扩充图形库 ----------
        Glyph.COFFEE -> {
            // 咖啡杯：先把把手圆环画出来，再用实心杯身盖住左半边
            drawCircle(
                color = color,
                radius = s * 0.13f,
                center = Offset(s * 0.76f, s * 0.60f),
                style = Stroke(width = s * 0.075f),
            )
            val cup = Path().apply {
                moveTo(s * 0.14f, s * 0.42f)
                lineTo(s * 0.72f, s * 0.42f)
                lineTo(s * 0.64f, s * 0.84f)
                lineTo(s * 0.22f, s * 0.84f)
                close()
            }
            drawPath(cup, color)
            drawLine(color, Offset(s * 0.30f, s * 0.30f), Offset(s * 0.30f, s * 0.16f), s * 0.065f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.48f, s * 0.30f), Offset(s * 0.48f, s * 0.16f), s * 0.065f, StrokeCap.Round)
        }
        Glyph.PET -> {
            // 宠物爪：四个脚趾 + 掌垫
            drawCircle(color, radius = s * 0.09f, center = Offset(s * 0.24f, s * 0.32f))
            drawCircle(color, radius = s * 0.09f, center = Offset(s * 0.42f, s * 0.20f))
            drawCircle(color, radius = s * 0.09f, center = Offset(s * 0.60f, s * 0.20f))
            drawCircle(color, radius = s * 0.09f, center = Offset(s * 0.78f, s * 0.32f))
            drawOval(color, topLeft = Offset(s * 0.28f, s * 0.50f), size = Size(s * 0.44f, s * 0.36f))
        }
        Glyph.SPORT -> {
            // 运动：哑铃
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.28f, s * 0.42f),
                size = Size(s * 0.44f, s * 0.16f),
                cornerRadius = CornerRadius(s * 0.06f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.08f, s * 0.26f),
                size = Size(s * 0.20f, s * 0.48f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.72f, s * 0.26f),
                size = Size(s * 0.20f, s * 0.48f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
        }
        Glyph.FLOWER -> {
            // 鲜花：五片花瓣环绕花心
            val cx = s * 0.50f
            val cy = s * 0.48f
            listOf(90f, 162f, 234f, 306f, 18f).forEach { deg ->
                val rad = Math.toRadians(deg.toDouble())
                drawCircle(
                    color = color,
                    radius = s * 0.15f,
                    center = Offset(
                        cx + (s * 0.22f * cos(rad)).toFloat(),
                        cy - (s * 0.22f * sin(rad)).toFloat(),
                    ),
                )
            }
            drawCircle(color = color, radius = s * 0.13f, center = Offset(cx, cy))
        }
        Glyph.GIFT -> {
            // 礼物：盒身 + 外凸的盒盖 + 蝴蝶结
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.16f, s * 0.44f),
                size = Size(s * 0.68f, s * 0.50f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.08f, s * 0.30f),
                size = Size(s * 0.84f, s * 0.16f),
                cornerRadius = CornerRadius(s * 0.06f),
            )
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.38f, s * 0.24f))
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.62f, s * 0.24f))
            drawCircle(color = color, radius = s * 0.05f, center = Offset(s * 0.50f, s * 0.29f))
        }
        Glyph.MUSIC -> {
            // 音乐：两个音符 + 顶部横梁
            drawLine(color, Offset(s * 0.34f, s * 0.74f), Offset(s * 0.34f, s * 0.22f), s * 0.075f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.70f, s * 0.68f), Offset(s * 0.70f, s * 0.16f), s * 0.075f, StrokeCap.Round)
            val beam = Path().apply {
                moveTo(s * 0.34f, s * 0.22f)
                lineTo(s * 0.70f, s * 0.16f)
                lineTo(s * 0.70f, s * 0.34f)
                lineTo(s * 0.34f, s * 0.40f)
                close()
            }
            drawPath(beam, color)
            drawCircle(color = color, radius = s * 0.12f, center = Offset(s * 0.22f, s * 0.76f))
            drawCircle(color = color, radius = s * 0.12f, center = Offset(s * 0.58f, s * 0.70f))
        }
        Glyph.CAMERA -> {
            // 相机：机身轮廓 + 取景器凸起 + 镜头圈 + 闪光点
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.26f),
                size = Size(s * 0.88f, s * 0.60f),
                cornerRadius = CornerRadius(s * 0.12f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.34f, s * 0.14f),
                size = Size(s * 0.24f, s * 0.16f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
            drawCircle(
                color = color,
                radius = s * 0.17f,
                center = Offset(s * 0.50f, s * 0.58f),
                style = Stroke(width = s * 0.10f),
            )
            drawCircle(color = color, radius = s * 0.05f, center = Offset(s * 0.80f, s * 0.38f))
        }
        Glyph.PLANE -> {
            // 飞机：纸飞机（右箭头形）
            val p = Path().apply {
                moveTo(s * 0.08f, s * 0.92f)
                lineTo(s * 0.94f, s * 0.50f)
                lineTo(s * 0.08f, s * 0.08f)
                lineTo(s * 0.08f, s * 0.40f)
                lineTo(s * 0.56f, s * 0.50f)
                lineTo(s * 0.08f, s * 0.60f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.BUS -> {
            // 公交：车身轮廓 + 前窗 + 车轮
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.12f, s * 0.06f),
                size = Size(s * 0.76f, s * 0.72f),
                cornerRadius = CornerRadius(s * 0.14f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.22f, s * 0.18f),
                size = Size(s * 0.56f, s * 0.24f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.30f, s * 0.86f))
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.70f, s * 0.86f))
        }
        Glyph.BIKE -> {
            // 单车：两个车轮 + 车架 + 车把
            val wheel = Stroke(width = s * 0.09f)
            drawCircle(color = color, radius = s * 0.20f, center = Offset(s * 0.22f, s * 0.72f), style = wheel)
            drawCircle(color = color, radius = s * 0.20f, center = Offset(s * 0.78f, s * 0.72f), style = wheel)
            val frame = Path().apply {
                moveTo(s * 0.22f, s * 0.72f)
                lineTo(s * 0.46f, s * 0.40f)
                lineTo(s * 0.78f, s * 0.72f)
                moveTo(s * 0.46f, s * 0.40f)
                lineTo(s * 0.64f, s * 0.40f)
                moveTo(s * 0.78f, s * 0.72f)
                lineTo(s * 0.66f, s * 0.30f)
                moveTo(s * 0.56f, s * 0.30f)
                lineTo(s * 0.76f, s * 0.30f)
            }
            drawPath(frame, color, style = Stroke(width = s * 0.08f, cap = StrokeCap.Round))
        }
        Glyph.DROP -> {
            // 水滴
            val p = Path().apply {
                moveTo(s * 0.50f, s * 0.06f)
                cubicTo(s * 0.76f, s * 0.34f, s * 0.88f, s * 0.52f, s * 0.88f, s * 0.66f)
                cubicTo(s * 0.88f, s * 0.86f, s * 0.72f, s * 0.96f, s * 0.50f, s * 0.96f)
                cubicTo(s * 0.28f, s * 0.96f, s * 0.12f, s * 0.86f, s * 0.12f, s * 0.66f)
                cubicTo(s * 0.12f, s * 0.52f, s * 0.24f, s * 0.34f, s * 0.50f, s * 0.06f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.BOLT -> {
            // 闪电
            val p = Path().apply {
                moveTo(s * 0.62f, s * 0.04f)
                lineTo(s * 0.10f, s * 0.56f)
                lineTo(s * 0.44f, s * 0.56f)
                lineTo(s * 0.34f, s * 0.96f)
                lineTo(s * 0.90f, s * 0.42f)
                lineTo(s * 0.54f, s * 0.42f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.GRAD_CAP -> {
            // 学业：学士帽（方形帽顶 + 帽檐 + 流苏）
            val top = Path().apply {
                moveTo(s * 0.50f, s * 0.10f)
                lineTo(s * 0.94f, s * 0.32f)
                lineTo(s * 0.50f, s * 0.54f)
                lineTo(s * 0.06f, s * 0.32f)
                close()
            }
            drawPath(top, color)
            val base = Path().apply {
                moveTo(s * 0.26f, s * 0.44f)
                lineTo(s * 0.74f, s * 0.44f)
                lineTo(s * 0.74f, s * 0.68f)
                lineTo(s * 0.50f, s * 0.82f)
                lineTo(s * 0.26f, s * 0.68f)
                close()
            }
            drawPath(base, color)
            drawLine(color, Offset(s * 0.90f, s * 0.34f), Offset(s * 0.90f, s * 0.62f), s * 0.05f, StrokeCap.Round)
            drawCircle(color = color, radius = s * 0.055f, center = Offset(s * 0.90f, s * 0.68f))
        }
        Glyph.LAPTOP -> {
            // 电脑：屏幕轮廓 + 键盘底座
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.16f, s * 0.10f),
                size = Size(s * 0.68f, s * 0.50f),
                cornerRadius = CornerRadius(s * 0.08f),
                style = stroke,
            )
            val base = Path().apply {
                moveTo(s * 0.04f, s * 0.72f)
                lineTo(s * 0.96f, s * 0.72f)
                lineTo(s * 0.86f, s * 0.88f)
                lineTo(s * 0.14f, s * 0.88f)
                close()
            }
            drawPath(base, color)
        }
        Glyph.LIPSTICK -> {
            // 美妆：口红（斜切膏体 + 管身）
            val bullet = Path().apply {
                moveTo(s * 0.36f, s * 0.52f)
                lineTo(s * 0.36f, s * 0.24f)
                lineTo(s * 0.64f, s * 0.12f)
                lineTo(s * 0.64f, s * 0.52f)
                close()
            }
            drawPath(bullet, color)
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.34f, s * 0.52f),
                size = Size(s * 0.32f, s * 0.42f),
                cornerRadius = CornerRadius(s * 0.06f),
            )
        }
        Glyph.APPLE -> {
            // 水果：苹果 + 果柄 + 叶片
            val body = Path().apply {
                moveTo(s * 0.50f, s * 0.32f)
                cubicTo(s * 0.34f, s * 0.16f, s * 0.10f, s * 0.26f, s * 0.10f, s * 0.52f)
                cubicTo(s * 0.10f, s * 0.78f, s * 0.32f, s * 0.94f, s * 0.50f, s * 0.94f)
                cubicTo(s * 0.68f, s * 0.94f, s * 0.90f, s * 0.78f, s * 0.90f, s * 0.52f)
                cubicTo(s * 0.90f, s * 0.26f, s * 0.66f, s * 0.16f, s * 0.50f, s * 0.32f)
                close()
            }
            drawPath(body, color)
            drawLine(color, Offset(s * 0.50f, s * 0.30f), Offset(s * 0.54f, s * 0.10f), s * 0.06f, StrokeCap.Round)
            val leaf = Path().apply {
                moveTo(s * 0.56f, s * 0.18f)
                cubicTo(s * 0.68f, s * 0.04f, s * 0.84f, s * 0.06f, s * 0.86f, s * 0.10f)
                cubicTo(s * 0.84f, s * 0.22f, s * 0.70f, s * 0.28f, s * 0.56f, s * 0.18f)
                close()
            }
            drawPath(leaf, color)
        }
        Glyph.ICE_CREAM -> {
            // 冰淇淋：圆球 + 三角蛋筒
            drawCircle(color = color, radius = s * 0.24f, center = Offset(s * 0.50f, s * 0.30f))
            val cone = Path().apply {
                moveTo(s * 0.26f, s * 0.50f)
                lineTo(s * 0.74f, s * 0.50f)
                lineTo(s * 0.50f, s * 0.94f)
                close()
            }
            drawPath(cone, color)
        }
        Glyph.WINE -> {
            // 酒水：高脚杯
            val bowl = Path().apply {
                moveTo(s * 0.26f, s * 0.10f)
                lineTo(s * 0.74f, s * 0.10f)
                cubicTo(s * 0.74f, s * 0.36f, s * 0.66f, s * 0.54f, s * 0.50f, s * 0.54f)
                cubicTo(s * 0.34f, s * 0.54f, s * 0.26f, s * 0.36f, s * 0.26f, s * 0.10f)
                close()
            }
            drawPath(bowl, color)
            drawLine(color, Offset(s * 0.50f, s * 0.52f), Offset(s * 0.50f, s * 0.84f), s * 0.07f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.32f, s * 0.88f), Offset(s * 0.68f, s * 0.88f), s * 0.09f, StrokeCap.Round)
        }
        Glyph.SHIELD -> {
            // 保险：盾牌轮廓 + 对勾
            val shield = Path().apply {
                moveTo(s * 0.50f, s * 0.06f)
                lineTo(s * 0.88f, s * 0.20f)
                lineTo(s * 0.88f, s * 0.50f)
                cubicTo(s * 0.88f, s * 0.72f, s * 0.72f, s * 0.88f, s * 0.50f, s * 0.94f)
                cubicTo(s * 0.28f, s * 0.88f, s * 0.12f, s * 0.72f, s * 0.12f, s * 0.50f)
                lineTo(s * 0.12f, s * 0.20f)
                close()
            }
            drawPath(shield, color, style = Stroke(width = s * 0.09f))
            val check = Path().apply {
                moveTo(s * 0.34f, s * 0.50f)
                lineTo(s * 0.46f, s * 0.62f)
                lineTo(s * 0.68f, s * 0.38f)
            }
            drawPath(check, color, style = Stroke(width = s * 0.10f, cap = StrokeCap.Round))
        }
        Glyph.RECEIPT -> {
            // 票据：带锯齿底边的小票
            val p = Path().apply {
                moveTo(s * 0.24f, s * 0.06f)
                lineTo(s * 0.76f, s * 0.06f)
                lineTo(s * 0.76f, s * 0.82f)
                lineTo(s * 0.63f, s * 0.92f)
                lineTo(s * 0.50f, s * 0.82f)
                lineTo(s * 0.37f, s * 0.92f)
                lineTo(s * 0.24f, s * 0.82f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.WRENCH -> {
            // 维修：扳手（斜放）+ 开口卡口
            rotate(-45f, pivot = Offset(s / 2f, s / 2f)) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.44f, s * 0.34f),
                    size = Size(s * 0.12f, s * 0.58f),
                    cornerRadius = CornerRadius(s * 0.06f),
                )
                drawArc(
                    color = color,
                    startAngle = 120f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(s * 0.30f, s * 0.04f),
                    size = Size(s * 0.40f, s * 0.40f),
                    style = Stroke(width = s * 0.12f, cap = StrokeCap.Round),
                )
            }
        }
        Glyph.BABY -> {
            // 母婴：奶瓶（奶嘴 + 瓶盖 + 瓶身）
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.50f, s * 0.14f))
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.36f, s * 0.20f),
                size = Size(s * 0.28f, s * 0.14f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.30f, s * 0.32f),
                size = Size(s * 0.40f, s * 0.62f),
                cornerRadius = CornerRadius(s * 0.12f),
            )
        }
        Glyph.HEART -> {
            // 心意：心形
            val p = Path().apply {
                moveTo(s * 0.50f, s * 0.92f)
                cubicTo(s * 0.10f, s * 0.64f, s * 0.02f, s * 0.44f, s * 0.12f, s * 0.26f)
                cubicTo(s * 0.24f, s * 0.06f, s * 0.46f, s * 0.12f, s * 0.50f, s * 0.30f)
                cubicTo(s * 0.54f, s * 0.12f, s * 0.76f, s * 0.06f, s * 0.88f, s * 0.26f)
                cubicTo(s * 0.98f, s * 0.44f, s * 0.90f, s * 0.64f, s * 0.50f, s * 0.92f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.CROWN -> {
            // 会员：皇冠 + 底座
            val p = Path().apply {
                moveTo(s * 0.08f, s * 0.74f)
                lineTo(s * 0.06f, s * 0.24f)
                lineTo(s * 0.30f, s * 0.46f)
                lineTo(s * 0.50f, s * 0.12f)
                lineTo(s * 0.70f, s * 0.46f)
                lineTo(s * 0.94f, s * 0.24f)
                lineTo(s * 0.92f, s * 0.74f)
                close()
            }
            drawPath(p, color)
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.12f, s * 0.76f),
                size = Size(s * 0.76f, s * 0.14f),
                cornerRadius = CornerRadius(s * 0.06f),
            )
        }
        Glyph.BED -> {
            // 酒店：床（床头 + 枕头 + 床垫 + 床腿）
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.04f, s * 0.28f),
                size = Size(s * 0.18f, s * 0.60f),
                cornerRadius = CornerRadius(s * 0.06f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.20f, s * 0.52f),
                size = Size(s * 0.76f, s * 0.20f),
                cornerRadius = CornerRadius(s * 0.06f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.26f, s * 0.36f),
                size = Size(s * 0.26f, s * 0.16f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.22f, s * 0.72f),
                size = Size(s * 0.10f, s * 0.20f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.86f, s * 0.72f),
                size = Size(s * 0.10f, s * 0.20f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
        }
        // ---------- 第二批扩充图形 ----------
        Glyph.PIZZA -> {
            // 披萨：三角形饼身轮廓 + 底部饼边 + 三颗配料
            val slice = Path().apply {
                moveTo(s * 0.50f, s * 0.06f)
                lineTo(s * 0.86f, s * 0.80f)
                lineTo(s * 0.14f, s * 0.80f)
                close()
            }
            drawPath(slice, color, style = Stroke(width = w, join = StrokeJoin.Round))
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.14f, s * 0.78f),
                size = Size(s * 0.72f, s * 0.14f),
                cornerRadius = CornerRadius(s * 0.07f),
            )
            drawCircle(color = color, radius = s * 0.06f, center = Offset(s * 0.44f, s * 0.40f))
            drawCircle(color = color, radius = s * 0.06f, center = Offset(s * 0.58f, s * 0.56f))
            drawCircle(color = color, radius = s * 0.06f, center = Offset(s * 0.38f, s * 0.62f))
        }
        Glyph.CAKE -> {
            // 蛋糕：下层 + 上层 + 蜡烛
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.12f, s * 0.54f),
                size = Size(s * 0.76f, s * 0.34f),
                cornerRadius = CornerRadius(s * 0.07f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.20f, s * 0.34f),
                size = Size(s * 0.60f, s * 0.20f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawLine(color, Offset(s * 0.50f, s * 0.32f), Offset(s * 0.50f, s * 0.16f), s * 0.05f, StrokeCap.Round)
            drawCircle(color = color, radius = s * 0.06f, center = Offset(s * 0.50f, s * 0.10f))
        }
        Glyph.CANDY -> {
            // 糖果：糖体 + 两侧糖纸
            drawCircle(color = color, radius = s * 0.21f, center = Offset(s * 0.50f, s * 0.50f))
            val left = Path().apply {
                moveTo(s * 0.30f, s * 0.50f)
                lineTo(s * 0.04f, s * 0.32f)
                lineTo(s * 0.04f, s * 0.68f)
                close()
            }
            drawPath(left, color)
            val right = Path().apply {
                moveTo(s * 0.70f, s * 0.50f)
                lineTo(s * 0.96f, s * 0.32f)
                lineTo(s * 0.96f, s * 0.68f)
                close()
            }
            drawPath(right, color)
        }
        Glyph.BREAD -> {
            // 面包：椭圆轮廓 + 三道割口
            drawOval(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.26f),
                size = Size(s * 0.88f, s * 0.52f),
                style = Stroke(width = w),
            )
            listOf(0.32f, 0.50f, 0.68f).forEach { x ->
                drawLine(
                    color = color,
                    start = Offset(s * x, s * 0.36f),
                    end = Offset(s * (x + 0.08f), s * 0.66f),
                    strokeWidth = s * 0.07f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Glyph.FISH -> {
            // 海鲜：鱼身 + 鱼尾
            val body = Path().apply {
                moveTo(s * 0.08f, s * 0.50f)
                cubicTo(s * 0.24f, s * 0.18f, s * 0.58f, s * 0.18f, s * 0.72f, s * 0.50f)
                cubicTo(s * 0.58f, s * 0.82f, s * 0.24f, s * 0.82f, s * 0.08f, s * 0.50f)
                close()
            }
            drawPath(body, color)
            val tail = Path().apply {
                moveTo(s * 0.68f, s * 0.50f)
                lineTo(s * 0.94f, s * 0.28f)
                lineTo(s * 0.94f, s * 0.72f)
                close()
            }
            drawPath(tail, color)
        }
        Glyph.CARROT -> {
            // 蔬菜：胡萝卜（根 + 叶）
            val root = Path().apply {
                moveTo(s * 0.30f, s * 0.34f)
                lineTo(s * 0.66f, s * 0.34f)
                lineTo(s * 0.48f, s * 0.94f)
                close()
            }
            drawPath(root, color)
            val leaf = Path().apply {
                moveTo(s * 0.44f, s * 0.36f)
                lineTo(s * 0.30f, s * 0.06f)
                lineTo(s * 0.46f, s * 0.18f)
                lineTo(s * 0.56f, s * 0.04f)
                lineTo(s * 0.64f, s * 0.30f)
                close()
            }
            drawPath(leaf, color)
        }
        Glyph.LEAF -> {
            // 绿植：一片叶子
            val p = Path().apply {
                moveTo(s * 0.16f, s * 0.90f)
                cubicTo(s * 0.04f, s * 0.44f, s * 0.34f, s * 0.10f, s * 0.88f, s * 0.10f)
                cubicTo(s * 0.90f, s * 0.66f, s * 0.62f, s * 0.92f, s * 0.16f, s * 0.90f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.UMBRELLA -> {
            // 雨伞：伞面 + 伞柄 + 弯钩
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(s * 0.05f, s * 0.10f),
                size = Size(s * 0.90f, s * 0.86f),
            )
            drawLine(color, Offset(s * 0.50f, s * 0.52f), Offset(s * 0.50f, s * 0.88f), s * 0.07f, StrokeCap.Round)
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.50f, s * 0.76f),
                size = Size(s * 0.22f, s * 0.18f),
                style = Stroke(width = s * 0.07f),
            )
        }
        Glyph.SHIRT -> {
            // 服饰：T 恤轮廓
            val p = Path().apply {
                moveTo(s * 0.32f, s * 0.14f)
                lineTo(s * 0.14f, s * 0.26f)
                lineTo(s * 0.06f, s * 0.50f)
                lineTo(s * 0.26f, s * 0.58f)
                lineTo(s * 0.26f, s * 0.92f)
                lineTo(s * 0.74f, s * 0.92f)
                lineTo(s * 0.74f, s * 0.58f)
                lineTo(s * 0.94f, s * 0.50f)
                lineTo(s * 0.86f, s * 0.26f)
                lineTo(s * 0.68f, s * 0.14f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.SHOE -> {
            // 鞋履：鞋身 + 鞋底
            val p = Path().apply {
                moveTo(s * 0.36f, s * 0.10f)
                lineTo(s * 0.36f, s * 0.48f)
                lineTo(s * 0.12f, s * 0.62f)
                lineTo(s * 0.10f, s * 0.80f)
                lineTo(s * 0.90f, s * 0.80f)
                lineTo(s * 0.90f, s * 0.58f)
                cubicTo(s * 0.70f, s * 0.56f, s * 0.58f, s * 0.42f, s * 0.54f, s * 0.10f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.GLASSES -> {
            // 眼镜：两片镜片 + 鼻梁
            drawCircle(color = color, radius = s * 0.22f, center = Offset(s * 0.28f, s * 0.56f), style = Stroke(width = s * 0.09f))
            drawCircle(color = color, radius = s * 0.22f, center = Offset(s * 0.72f, s * 0.56f), style = Stroke(width = s * 0.09f))
            drawLine(color, Offset(s * 0.46f, s * 0.52f), Offset(s * 0.54f, s * 0.52f), s * 0.08f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.06f, s * 0.46f), Offset(s * 0.16f, s * 0.40f), s * 0.07f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.84f, s * 0.40f), Offset(s * 0.94f, s * 0.46f), s * 0.07f, StrokeCap.Round)
        }
        Glyph.WATCH -> {
            // 手表：上下表带 + 表盘 + 指针
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.36f, s * 0.02f),
                size = Size(s * 0.28f, s * 0.22f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.36f, s * 0.76f),
                size = Size(s * 0.28f, s * 0.22f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
            drawCircle(color = color, radius = s * 0.24f, center = Offset(s * 0.50f, s * 0.50f), style = Stroke(width = s * 0.10f))
            drawLine(color, Offset(s * 0.50f, s * 0.50f), Offset(s * 0.50f, s * 0.36f), s * 0.05f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.50f, s * 0.50f), Offset(s * 0.60f, s * 0.56f), s * 0.05f, StrokeCap.Round)
        }
        Glyph.PHONE -> {
            // 手机：机身轮廓 + 听筒 + Home 点
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.28f, s * 0.04f),
                size = Size(s * 0.44f, s * 0.92f),
                cornerRadius = CornerRadius(s * 0.11f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.42f, s * 0.12f),
                size = Size(s * 0.16f, s * 0.04f),
                cornerRadius = CornerRadius(s * 0.02f),
            )
            drawCircle(color = color, radius = s * 0.05f, center = Offset(s * 0.50f, s * 0.84f))
        }
        Glyph.HEADPHONE -> {
            // 耳机：头梁 + 两侧耳罩
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.14f, s * 0.10f),
                size = Size(s * 0.72f, s * 0.72f),
                style = Stroke(width = s * 0.10f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.04f, s * 0.44f),
                size = Size(s * 0.20f, s * 0.34f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.76f, s * 0.44f),
                size = Size(s * 0.20f, s * 0.34f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
        }
        Glyph.TV -> {
            // 电视：屏幕轮廓 + 支架 + 底座
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.12f),
                size = Size(s * 0.88f, s * 0.58f),
                cornerRadius = CornerRadius(s * 0.08f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.44f, s * 0.70f),
                size = Size(s * 0.12f, s * 0.12f),
                cornerRadius = CornerRadius(s * 0.03f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.26f, s * 0.82f),
                size = Size(s * 0.48f, s * 0.08f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
        }
        Glyph.TRAIN -> {
            // 火车：车头轮廓 + 车窗 + 车轮 + 轨道
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.14f, s * 0.06f),
                size = Size(s * 0.72f, s * 0.60f),
                cornerRadius = CornerRadius(s * 0.14f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.26f, s * 0.18f),
                size = Size(s * 0.48f, s * 0.20f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
            drawCircle(color = color, radius = s * 0.09f, center = Offset(s * 0.32f, s * 0.74f))
            drawCircle(color = color, radius = s * 0.09f, center = Offset(s * 0.68f, s * 0.74f))
            drawLine(color, Offset(s * 0.06f, s * 0.90f), Offset(s * 0.94f, s * 0.90f), s * 0.06f, StrokeCap.Round)
        }
        Glyph.SHIP -> {
            // 船运：船身 + 桅杆 + 双帆
            val hull = Path().apply {
                moveTo(s * 0.06f, s * 0.62f)
                lineTo(s * 0.94f, s * 0.62f)
                lineTo(s * 0.78f, s * 0.86f)
                lineTo(s * 0.22f, s * 0.86f)
                close()
            }
            drawPath(hull, color)
            drawLine(color, Offset(s * 0.50f, s * 0.08f), Offset(s * 0.50f, s * 0.60f), s * 0.06f, StrokeCap.Round)
            val left = Path().apply {
                moveTo(s * 0.44f, s * 0.16f)
                lineTo(s * 0.20f, s * 0.54f)
                lineTo(s * 0.44f, s * 0.54f)
                close()
            }
            drawPath(left, color)
            val right = Path().apply {
                moveTo(s * 0.56f, s * 0.20f)
                lineTo(s * 0.80f, s * 0.54f)
                lineTo(s * 0.56f, s * 0.54f)
                close()
            }
            drawPath(right, color)
        }
        Glyph.FUEL -> {
            // 加油：加油机 + 油枪软管
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.14f, s * 0.10f),
                size = Size(s * 0.44f, s * 0.78f),
                cornerRadius = CornerRadius(s * 0.10f),
            )
            val hose = Path().apply {
                moveTo(s * 0.58f, s * 0.32f)
                cubicTo(s * 0.78f, s * 0.32f, s * 0.82f, s * 0.44f, s * 0.82f, s * 0.58f)
                lineTo(s * 0.82f, s * 0.76f)
            }
            drawPath(hose, color, style = Stroke(width = s * 0.08f, cap = StrokeCap.Round))
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.74f, s * 0.72f),
                size = Size(s * 0.16f, s * 0.16f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
        }
        Glyph.PARKING -> {
            // 停车：方框 + 字母 P
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.10f, s * 0.10f),
                size = Size(s * 0.80f, s * 0.80f),
                cornerRadius = CornerRadius(s * 0.18f),
                style = Stroke(width = s * 0.08f),
            )
            drawLine(color, Offset(s * 0.40f, s * 0.74f), Offset(s * 0.40f, s * 0.26f), s * 0.10f, StrokeCap.Round)
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.40f, s * 0.26f),
                size = Size(s * 0.32f, s * 0.28f),
                style = Stroke(width = s * 0.10f),
            )
        }
        Glyph.TICKET -> {
            // 门票：票面轮廓 + 中缝撕裂线
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.24f),
                size = Size(s * 0.88f, s * 0.52f),
                cornerRadius = CornerRadius(s * 0.07f),
                style = Stroke(width = s * 0.08f),
            )
            listOf(0.32f, 0.44f, 0.56f, 0.68f).forEach { y ->
                drawLine(
                    color = color,
                    start = Offset(s * 0.50f, s * y),
                    end = Offset(s * 0.50f, s * (y + 0.04f)),
                    strokeWidth = s * 0.05f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Glyph.TROPHY -> {
            // 奖杯：杯身 + 两侧把手 + 底座
            val cup = Path().apply {
                moveTo(s * 0.30f, s * 0.10f)
                lineTo(s * 0.70f, s * 0.10f)
                lineTo(s * 0.66f, s * 0.48f)
                cubicTo(s * 0.64f, s * 0.62f, s * 0.58f, s * 0.66f, s * 0.50f, s * 0.66f)
                cubicTo(s * 0.42f, s * 0.66f, s * 0.36f, s * 0.62f, s * 0.34f, s * 0.48f)
                close()
            }
            drawPath(cup, color)
            drawArc(
                color = color,
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.10f, s * 0.14f),
                size = Size(s * 0.26f, s * 0.30f),
                style = Stroke(width = s * 0.07f),
            )
            drawArc(
                color = color,
                startAngle = 270f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.64f, s * 0.14f),
                size = Size(s * 0.26f, s * 0.30f),
                style = Stroke(width = s * 0.07f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.44f, s * 0.66f),
                size = Size(s * 0.12f, s * 0.10f),
                cornerRadius = CornerRadius(s * 0.02f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.30f, s * 0.76f),
                size = Size(s * 0.40f, s * 0.14f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
        }
        Glyph.TARGET -> {
            // 目标：三层同心圆
            drawCircle(color = color, radius = s * 0.42f, center = Offset(s * 0.50f, s * 0.50f), style = Stroke(width = s * 0.09f))
            drawCircle(color = color, radius = s * 0.26f, center = Offset(s * 0.50f, s * 0.50f), style = Stroke(width = s * 0.09f))
            drawCircle(color = color, radius = s * 0.09f, center = Offset(s * 0.50f, s * 0.50f))
        }
        Glyph.CLOCK -> {
            // 时间：表盘 + 指针
            drawCircle(color = color, radius = s * 0.40f, center = Offset(s * 0.50f, s * 0.50f), style = Stroke(width = s * 0.10f))
            drawLine(color, Offset(s * 0.50f, s * 0.50f), Offset(s * 0.50f, s * 0.24f), s * 0.06f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.50f, s * 0.50f), Offset(s * 0.70f, s * 0.60f), s * 0.06f, StrokeCap.Round)
        }
        Glyph.CALENDAR -> {
            // 日历：页框 + 顶部色块 + 装订环 + 日期点
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.10f, s * 0.18f),
                size = Size(s * 0.80f, s * 0.72f),
                cornerRadius = CornerRadius(s * 0.08f),
                style = Stroke(width = s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.10f, s * 0.18f),
                size = Size(s * 0.80f, s * 0.18f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawLine(color, Offset(s * 0.34f, s * 0.08f), Offset(s * 0.34f, s * 0.24f), s * 0.07f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.66f, s * 0.08f), Offset(s * 0.66f, s * 0.24f), s * 0.07f, StrokeCap.Round)
            listOf(0.32f to 0.50f, 0.50f to 0.50f, 0.68f to 0.50f, 0.32f to 0.68f, 0.50f to 0.68f).forEach { (x, y) ->
                drawCircle(color = color, radius = s * 0.045f, center = Offset(s * x, s * y))
            }
        }
        Glyph.WALLET -> {
            // 钱包：票夹轮廓 + 分隔线 + 扣子
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.08f, s * 0.24f),
                size = Size(s * 0.84f, s * 0.58f),
                cornerRadius = CornerRadius(s * 0.10f),
                style = Stroke(width = s * 0.09f),
            )
            drawLine(color, Offset(s * 0.08f, s * 0.46f), Offset(s * 0.92f, s * 0.46f), s * 0.06f, StrokeCap.Round)
            drawCircle(color = color, radius = s * 0.07f, center = Offset(s * 0.70f, s * 0.62f))
        }
        Glyph.BANK -> {
            // 银行：三角屋顶 + 立柱 + 台阶
            val roof = Path().apply {
                moveTo(s * 0.50f, s * 0.06f)
                lineTo(s * 0.94f, s * 0.30f)
                lineTo(s * 0.06f, s * 0.30f)
                close()
            }
            drawPath(roof, color)
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.08f, s * 0.34f),
                size = Size(s * 0.84f, s * 0.08f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
            listOf(0.16f, 0.36f, 0.56f, 0.76f).forEach { x ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * x, s * 0.46f),
                    size = Size(s * 0.08f, s * 0.26f),
                    cornerRadius = CornerRadius(s * 0.03f),
                )
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.08f, s * 0.76f),
                size = Size(s * 0.84f, s * 0.10f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
        }
        Glyph.COIN -> {
            // 金币：外圈 + 方形钱孔
            drawCircle(color = color, radius = s * 0.40f, center = Offset(s * 0.50f, s * 0.50f), style = Stroke(width = s * 0.10f))
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.40f, s * 0.40f),
                size = Size(s * 0.20f, s * 0.20f),
                cornerRadius = CornerRadius(s * 0.03f),
            )
        }
        Glyph.CARD -> {
            // 银行卡：卡面轮廓 + 磁条 + 芯片
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.24f),
                size = Size(s * 0.88f, s * 0.52f),
                cornerRadius = CornerRadius(s * 0.08f),
                style = Stroke(width = s * 0.09f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.36f),
                size = Size(s * 0.88f, s * 0.12f),
                cornerRadius = CornerRadius(s * 0.02f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.20f, s * 0.56f),
                size = Size(s * 0.18f, s * 0.12f),
                cornerRadius = CornerRadius(s * 0.03f),
            )
        }
        Glyph.PIGGY -> {
            // 存钱罐：罐身轮廓 + 耳朵 + 投币口 + 眼睛
            drawOval(
                color = color,
                topLeft = Offset(s * 0.10f, s * 0.32f),
                size = Size(s * 0.78f, s * 0.48f),
                style = Stroke(width = s * 0.09f),
            )
            val ear = Path().apply {
                moveTo(s * 0.24f, s * 0.36f)
                lineTo(s * 0.34f, s * 0.14f)
                lineTo(s * 0.48f, s * 0.36f)
                close()
            }
            drawPath(ear, color)
            drawLine(color, Offset(s * 0.40f, s * 0.40f), Offset(s * 0.54f, s * 0.40f), s * 0.07f, StrokeCap.Round)
            drawCircle(color = color, radius = s * 0.06f, center = Offset(s * 0.28f, s * 0.54f))
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.26f, s * 0.80f),
                size = Size(s * 0.10f, s * 0.12f),
                cornerRadius = CornerRadius(s * 0.03f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.62f, s * 0.80f),
                size = Size(s * 0.10f, s * 0.12f),
                cornerRadius = CornerRadius(s * 0.03f),
            )
        }
        // 六个 SVG 图形在上面已经提前 return
        else -> Unit
    }
}

// ---------- 用户提供的 SVG 图形：按路径原样绘制 ----------

/** 解析结果进程内缓存（只在绘制线程访问）：同一图形反复画不再重复解析 */
private val svgPathCache = HashMap<String, Path>()

private fun svgPathOf(data: String): Path = svgPathCache.getOrPut(data) {
    runCatching { PathParser().parsePathString(data).toPath() }.getOrElse { Path() }
}

/** 把 960×960 的 viewBox（x∈[0,960]，y∈[-960,0]）等比缩放到当前画布并居中 */
private fun DrawScope.drawSvgGlyph(data: String, color: Color) {
    val s = size.minDimension
    val k = s / GlyphPaths.VIEWPORT
    val half = GlyphPaths.VIEWPORT / 2f
    withTransform({
        translate(size.width / 2f, size.height / 2f)
        scale(k, k, pivot = Offset.Zero)
        // 该坐标系原点在左上、y 向下为负：先把中心 (480,-480) 平移到原点
        translate(-half, half)
    }) {
        drawPath(svgPathOf(data), color)
    }
}
