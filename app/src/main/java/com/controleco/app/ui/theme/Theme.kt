package com.controleco.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.data.budget.Budget
import kotlin.math.pow

/**
 * ECO v5 设计系统。
 *
 * 纸面/墨色/强调色/玉绿/琥珀/陶土六组语义色，深浅两套；强调色有 7 套可切换（含「白」）。
 * 业务代码只通过 [MaterialTheme.eco]（纸面、轨、强调色族等）与 [MaterialTheme.accBook]
 * （旧语义：收入/警告/危险）取色，禁止散落硬编码。金额统一 tabular-nums。
 *
 * **强调色贯穿原则**（本次重构的核心）：`blue*` 是「主强调色族」，`hero*` 是 hero 卡渐变，
 * `accentGrad*` 是按钮/FAB 渐变，`paper/surface*` 是中性面。这四组都由 [EcoAccent] 驱动：
 * 换主题时渐变、描边、选中态、主按钮、hero、FAB、背景微光全部跟着走，
 * 只有收入/警告/危险（jade/amber/clay）保持不变。
 */

// ---------- 主题色 ----------

/**
 * 强调色预设。
 *
 * - [Family] 六档强调色族（light/dark 各一套）：blue 主色、deep 深端、mid 中间、hi 亮端、
 *   soft 浅底、wash 更浅的填充底。
 * - [onAccent]：压在 `blue` 实心块上的前景色（选中 chip、实心按钮）。
 * - `heroA/B/C`：hero / 封面深色渐变（永远偏深，配白系前景）。
 * - `accentGradA/B/C`：按钮 / FAB 的强调渐变（比 hero 亮，是「主操作」的颜色）。
 * - [secondaryShift]：**次要色**的色相偏移。主色按这个角度旋转就得到次要色族
 *   （`second*`），让页面能有两个色相做区分，而不是所有卡片一个颜色；
 *   但两个色相都来自主题，所以不会出现「固定青绿」。
 * - [neutral]/[neutralDark]：中性面（纸面/卡片/轨道）的色调。
 *   默认是「近白 + 一点主题色」，`White` 主题则是纯白 + 灰阶中性。
 */
enum class EcoAccent(
    val id: String,
    val label: String,
    val note: String,
    val light: Family,
    val dark: Family,
    val heroA: Color,
    val heroB: Color,
    val heroC: Color,
    val accentGradA: Color,
    val accentGradB: Color,
    val accentGradC: Color,
    val neutral: Neutral = Neutral.TINTED,
    val neutralDark: Neutral = Neutral.TINTED,
    /**
     * 次要色的色相偏移（度）。主色整族按这个角度旋转 = 次要色族，
     * 明度/饱和度关系保持不变，所以对比度和层次都是可预期的。
     */
    val secondaryShift: Float = 0f,
) {
    Tide(
        "default", "潮汐", "青蓝",
        Family(0xFF24788A, 0xFF145B70, 0xFF419DAB, 0xFFA0DFDE, 0xFFE0F3F2, 0xFFECF7F4),
        Family(0xFF88D5DF, 0xFFB8EDF0, 0xFF62B8C6, 0xFFC0F4E9, 0xFF264E54, 0xFF20434A),
        // hero 渐变：深端不要压到近黑（真机设置页曾出现右半张黑块），
        // 但亮端也要压住，否则白系前景在最亮的 heroC 上对比度不够。
        heroA = Color(0xFF0B3A40), heroB = Color(0xFF104A50), heroC = Color(0xFF14595B),
        accentGradA = Color(0xFF1F8B80), accentGradB = Color(0xFF15707A), accentGradC = Color(0xFF0C4F5A),
        // 青蓝 -> 暮紫：冷调双色相，卡片之间靠蓝/紫区分
        secondaryShift = 75f,
    ),
    Meadow(
        "meadow", "松林", "玉绿",
        Family(0xFF2E8069, 0xFF22624E, 0xFF479C7B, 0xFFA3D9BE, 0xFFE6F4EA, 0xFFEEF8F2),
        Family(0xFF94D8B5, 0xFFC5F0D8, 0xFF78BD9B, 0xFFB7E7CB, 0xFF29443B, 0xFF213A32),
        heroA = Color(0xFF0E2E26), heroB = Color(0xFF14453A), heroC = Color(0xFF1A5644),
        accentGradA = Color(0xFF35926E), accentGradB = Color(0xFF237058), accentGradC = Color(0xFF174F3E),
        // 玉绿 -> 柠檬/橄榄：同属自然系，和语义绿(jade)仍能区分
        secondaryShift = -62f,
    ),
    Iris(
        "iris", "鸢尾", "暮紫",
        Family(0xFF7964B1, 0xFF55438A, 0xFF9281C1, 0xFFCBBFEC, 0xFFEFEAF9, 0xFFF8F4FD),
        Family(0xFFC5B1E8, 0xFFE4D9FA, 0xFFAC94DA, 0xFFDCCDF4, 0xFF383147, 0xFF2D2939),
        heroA = Color(0xFF211F35), heroB = Color(0xFF3A3357), heroC = Color(0xFF4D4272),
        accentGradA = Color(0xFF7A62B0), accentGradB = Color(0xFF5B468F), accentGradC = Color(0xFF413270),
        // 暮紫 -> 玫红：紫系内的邻近色，层次拉开但不打架。
        // +90 落在 346°（玫瑰），比原来的 +75(331°) 离语义紫更远（ΔE 22.0 -> 26.6）
        secondaryShift = 90f,
    ),
    Coral(
        "coral", "珊瑚", "陶红",
        Family(0xFFB66678, 0xFF924A5E, 0xFFCA8B94, 0xFFF0BFC2, 0xFFFAE9EB, 0xFFFFF4F1),
        Family(0xFFF0ADBB, 0xFFFFD5DB, 0xFFD78EA5, 0xFFF6C6D1, 0xFF47333B, 0xFF382A32),
        heroA = Color(0xFF331F2B), heroB = Color(0xFF5D3044), heroC = Color(0xFF7A3D53),
        accentGradA = Color(0xFFB5677B), accentGradB = Color(0xFF96485E), accentGradC = Color(0xFF77344A),
        // 陶红 -> 梅子紫：避免退回青色，红紫配也更暖。
        // -50 落在 296°，比原来的 -63(283°) 明显离开语义紫（ΔE 21.5 -> 24.0）
        secondaryShift = -50f,
    ),
    Amber(
        "amber", "暖金", "琥珀",
        Family(0xFFB77E42, 0xFF885A2A, 0xFFC29461, 0xFFEFD1A0, 0xFFF8EDDB, 0xFFFFF8EB),
        Family(0xFFEAC38E, 0xFFFFE3AF, 0xFFCEA66A, 0xFFF4DBAB, 0xFF43392B, 0xFF352F27),
        heroA = Color(0xFF2E2418), heroB = Color(0xFF4A3821), heroC = Color(0xFF634829),
        accentGradA = Color(0xFFA9793A), accentGradB = Color(0xFF8A6128), accentGradC = Color(0xFF6B4A1C),
        // 琥珀 -> 橄榄绿：暖色系里的第二色相，别和语义金(amber)混
        secondaryShift = 60f,
    ),
    Sky(
        "sky", "晴海", "天青",
        Family(0xFF317FAB, 0xFF235E89, 0xFF579EC3, 0xFFA7D9EC, 0xFFE4F2F9, 0xFFF0F8FD),
        Family(0xFFA4D8F3, 0xFFD0EEFC, 0xFF84B8D9, 0xFFC1E3F5, 0xFF294353, 0xFF233947),
        heroA = Color(0xFF0D2A3B), heroB = Color(0xFF133D57), heroC = Color(0xFF19506F),
        accentGradA = Color(0xFF3D86B4), accentGradB = Color(0xFF28678F), accentGradC = Color(0xFF1A4D6E),
        // 天青 -> 草绿：不能转到 160° 附近，那正是语义「收入绿 jade」的色相
        secondaryShift = -86f,
    ),

    /**
     * 白主题：大面积背景/卡片为**纯白**，中性色改为中性灰（不再偏青绿），
     * 强调色用墨黑——最克制、最现代的一套；渐变与实心块走石墨灰，靠留白和层次取胜。
     */
    White(
        "white", "纯白", "素白",
        // 中性灰阶：蓝/深/中三档都是灰，不带青味（饱和度过高会让白主题发绿）
        Family(0xFF2F3134, 0xFF191B1D, 0xFF4C4F53, 0xFFC9CCD0, 0xFFF0F1F3, 0xFFF8F9FA),
        Family(0xFFD3D6DA, 0xFFEDEFF2, 0xFFAEB2B7, 0xFFE4E6E9, 0xFF23262A, 0xFF1B1E21),
        // hero 压得更深，主按钮用中灰：按钮必须比 hero 亮，才像「可点的操作」
        heroA = Color(0xFF0D1012), heroB = Color(0xFF171A1D), heroC = Color(0xFF262B30),
        accentGradA = Color(0xFF4E555C), accentGradB = Color(0xFF3A4046), accentGradC = Color(0xFF272C31),
        // 纯白主题的主色是中性灰，旋转后仍是灰（饱和度极低），不会破掉黑白调性
        secondaryShift = 60f,
        neutral = Neutral.WHITE,
        neutralDark = Neutral.GRAPHITE,
    ),
    ;

    companion object {
        fun fromId(id: String?): EcoAccent = entries.firstOrNull { it.id == id } ?: Tide
    }
}

/**
 * 中性面色调：决定纸面/卡片/分隔线是「冷青灰」还是「纯白/中性灰」。
 * [WHITE] 只作用于浅色模式，[GRAPHITE] 只作用于深色模式。
 */
enum class Neutral { TINTED, WHITE, GRAPHITE }

@Immutable
data class Family(
    val blue: Long,
    val deep: Long,
    val mid: Long,
    val hi: Long,
    val soft: Long,
    val wash: Long,
)

/** 色相旋转：只改色相，饱和度/明度原样保留（层次关系因此不变）。 */
private fun rotateHue(color: Color, degrees: Float): Color {
    val hsv = rgbToHsv(color.red, color.green, color.blue)
    val rgb = hsvToRgb(hsv[0] + degrees, hsv[1], hsv[2])
    return Color(rgb[0], rgb[1], rgb[2])
}

private fun Long.rotatedBy(degrees: Float): Long =
    rotateHue(Color(this), degrees).toArgb().toLong() and 0xFFFFFFFFL

private fun Family.rotated(degrees: Float): Family = Family(
    blue = blue.rotatedBy(degrees),
    deep = deep.rotatedBy(degrees),
    mid = mid.rotatedBy(degrees),
    hi = hi.rotatedBy(degrees),
    soft = soft.rotatedBy(degrees),
    wash = wash.rotatedBy(degrees),
)

// ---------- 扩展色 ----------

@Immutable
data class EcoColors(
    val isDark: Boolean,
    val accent: EcoAccent,
    val paper: Color,
    val paperDeep: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val surfaceInset: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val quiet: Color,
    val rule: Color,
    val ruleStrong: Color,
    val track: Color,
    val blue: Color,
    val blueDeep: Color,
    val blue2: Color,
    val blueHi: Color,
    val blueSoft: Color,
    val blueWash: Color,
    /** 压在 [blue]/[blueDeep] 实心块上的前景色（选中 chip、实心按钮文字） */
    val onAccent: Color,
    /**
     * **次要色**（主色按 [EcoAccent.secondaryShift] 旋转得到）。
     * 用来给卡片/装饰块换一个色相，让页面有层次，但依然由主题派生。
     * 主操作（按钮/FAB/选中态）不要用它，用 [blue]。
     */
    val second: Color,
    val secondDeep: Color,
    val second2: Color,
    val secondHi: Color,
    val secondSoft: Color,
    val secondWash: Color,
    /** 压在 [second]/[secondDeep] 实心块上的前景色 */
    val onSecond: Color,
    val jade: Color,
    val jadeHi: Color,
    val jadeSoft: Color,
    val amber: Color,
    val amberHi: Color,
    val amberSoft: Color,
    val clay: Color,
    val clayHi: Color,
    val claySoft: Color,
    val violet: Color,
    val violetSoft: Color,
    val heroA: Color,
    val heroB: Color,
    val heroC: Color,
    /** 按钮 / FAB / 进度条等「主操作」的强调渐变（比 hero 亮） */
    val accentGradA: Color,
    val accentGradB: Color,
    val accentGradC: Color,
)

// ---------- 前景自动取色 ----------

private fun srgbChannel(v: Float): Float =
    if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

/** WCAG 相对亮度（0..1） */
fun relativeLuminance(color: Color): Float =
    0.2126f * srgbChannel(color.red) +
        0.7152f * srgbChannel(color.green) +
        0.0722f * srgbChannel(color.blue)

/** WCAG 对比度（1..21） */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * 压在某块底色上的前景色：底亮用深墨、底暗用白。
 *
 * 翻转点取 **0.179**（不是 0.42）：这是黑白两色对比度相等的亮度
 * （解 `(L+0.05)/0.05 = 1.05/(L+0.05)`），超过它才该换深色字。
 * 强调色既有深（潮汐/鸢尾/石墨）也有浅（暖金、深色模式的高亮档），
 * 写死 `Color.White` 会在浅底上糊成一片。
 */
fun contentColorOn(background: Color): Color =
    if (relativeLuminance(background) > 0.179f) Color(0xFF14181B) else Color(0xFFFFFFFF)

// ---------- 主题色整体提亮 ----------

/**
 * 主题色提亮系数。
 *
 * 第一轮为了修对比度，把 hero 渐变、按钮渐变、主色深档压得偏黑，整套主题看起来发闷。
 * 这里把**强调色**统一向白色混合一点：
 * `0f` = 原始设计色，值越大越亮。
 *
 * 只作用于 `blue*` / `second*` / `hero*` / `accentGrad*`，
 * **不动**纸面、卡片面、分隔线和文字，所以不会把已有的层次冲淡。
 * 派生色（`onHero*`、`heroProgress`、`heroPlate`、`accentBorder`）都读提亮后的值，
 * 因此对比关系自动跟着走。
 */
private const val ACCENT_LIFT = 0.12f

/** 深档是实心块底色（白字压在上面），提亮要保守一点，别把对比吃掉。 */
private const val ACCENT_LIFT_DEEP = 0.08f

/** 已经很亮的高光档只做轻微提亮，避免糊成白色。 */
private const val ACCENT_LIFT_HI = 0.05f

/** 次要色是装饰色，不承担白字对比，提亮少一点以免被冲淡、撞上语义紫。 */
private const val ACCENT_LIFT_SECOND = 0.06f

// ---------- 封面 / 主操作：按「目标亮度」定，不按混合比例 ----------

/**
 * hero（结余卡、导入卡、个人页预算卡）三档的目标相对亮度。
 *
 * 为什么用目标亮度而不是混合比例：各主题作者的 hero 本来深浅不一
 * （潮汐 0.035、鸢尾 0.016），用同一个比例提亮后仍然「有的很黑、有的还行」。
 * 定死亮度之后，7 套主题只差色相，明暗完全一致。
 *
 * 上限是硬的：`heroC` 是渐变最亮端，白字压上去要 4.5:1，
 * 所以 `heroC ≤ 0.183`（1.05/4.5 − 0.05）。但 `onHero` 不是纯白（带一点主题色相），
 * 实际只能到 0.172，再高就掉到 4.41:1。
 */
private const val HERO_LUMA_A = 0.108f
private const val HERO_LUMA_B = 0.140f
private const val HERO_LUMA_C = 0.172f

/**
 * 封面的饱和度系数。
 *
 * 明度顶到 0.17 左右时如果只抬 V，观感偏灰；但**加太多会太艳**。
 * 1.0 = 保持作者原色，< 1 = 收敛。取 0.95：比原色略淡一点，柔和耐看。
 */
private const val HERO_SAT_SCALE = 0.95f

/** 主操作渐变的饱和度系数（按钮/加号），比封面再收敛一点。 */
private const val GRAD_SAT_SCALE = 0.98f

/**
 * 纯白主题的封面必须保持无彩色。
 * 它的 hero 原始色带一点点蓝（S≈0.21），按 1.18 加饱和后会变成明显蓝灰，
 * 与「纯白 = 无彩色」的定位冲突，所以这里反而要压掉。
 */
private const val NEUTRAL_SAT_SCALE = 0.15f

/**
 * 主操作渐变（按钮 / FAB / 加号）三档的目标亮度。
 * 上面压的是图标和白字，底线 3:1 → `gradA ≤ 0.30`，取 0.285。
 */
private const val GRAD_LUMA_A = 0.285f
private const val GRAD_LUMA_B = 0.235f
private const val GRAD_LUMA_C = 0.185f

private fun lift(color: Color, amount: Float): Color =
    if (amount <= 0f) color else lerp(color, Color.White, amount)

/**
 * 把颜色沿「向白 / 向黑」推到目标相对亮度，色相和彩度关系尽量保留。
 *
 * 用二分找混合比例，因为 Oklab 混合后的亮度不是线性的，直接算比例不准。
 * 只用于 hero / 主操作渐变这类「亮度优先」的色，其它 token 不要用。
 *
 * 注意：**向白混合会冲淡彩度**，所以彩色封面不要直接用它，用 [vividTone]。
 */
private fun toneTo(color: Color, target: Float): Color {
    val current = relativeLuminance(color)
    if (kotlin.math.abs(current - target) < 0.003f) return color
    val toWhite = current < target
    val anchor = if (toWhite) Color.White else Color.Black
    var low = 0f
    var high = 1f
    repeat(20) {
        val mid = (low + high) / 2f
        val lum = relativeLuminance(lerp(color, anchor, mid))
        val stillTooDark = if (toWhite) lum < target else lum > target
        if (stillTooDark) low = mid else high = mid
    }
    return lerp(color, anchor, (low + high) / 2f)
}

/** HSV -> Color（H 为 0..360，S/V 为 0..1） */
private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f / 60f
    val i = hh.toInt()
    val f = hh - i
    val p = v * (1 - s)
    val q = v * (1 - s * f)
    val t = v * (1 - s * (1 - f))
    return when (i % 6) {
        0 -> Color(v, t, p)
        1 -> Color(q, v, p)
        2 -> Color(p, v, t)
        3 -> Color(p, q, v)
        4 -> Color(t, p, v)
        else -> Color(v, p, q)
    }
}

/**
 * 彩色封面的提亮：**抬明度、保住彩度**。
 *
 * 之前的做法是向白色混合，结果亮度上去了、彩度也被冲掉，
 * 深绿封面变成灰青（`#4C7377`），看着「太灰」。
 * 这里改成在 HSV 里二分抬 V，S 按 [satScale] 缩放，
 * 得到的是同色相的**鲜艳**版本，而不是掺白的灰版本。
 *
 * [satScale] > 1 加饱和，< 1 减饱和（纯白主题用它压成无彩色）。
 * 只有彩度拉满（V=1）仍达不到目标亮度时，才退回向白冲淡。
 */
private fun vividTone(color: Color, target: Float, satScale: Float = 1f): Color {
    val hsv = rgbToHsv(color.red, color.green, color.blue)
    val hue = hsv[0]
    val sat = (hsv[1] * satScale).coerceIn(0f, 1f)
    if (relativeLuminance(hsvToColor(hue, sat, 1f)) < target) {
        return toneTo(color, target)
    }
    var low = 0f
    var high = 1f
    repeat(20) {
        val mid = (low + high) / 2f
        if (relativeLuminance(hsvToColor(hue, sat, mid)) < target) low = mid else high = mid
    }
    return hsvToColor(hue, sat, (low + high) / 2f)
}

/** 主操作渐变（按钮/FAB/进度）：三段 stop */
val EcoColors.accentGradient: List<Color> get() = listOf(accentGradA, accentGradB, accentGradC)

/**
 * hero / 封面渐变：**从左上（亮）到右下（深）**。
 *
 * 顺序刻意是 `heroC → heroB → heroA`（亮→深），和卡片上已有的
 * 「顶边 1px 白高光 + 底边内侧压深」同向，光感才成立。
 * 三档同色相、只差明暗，所以是柔和的体积感，不是两种颜色的过渡。
 */
val EcoColors.heroGradient: List<Color> get() = listOf(heroC, heroB, heroA)

/**
 * 强调色浅底渐变：卡片「软渐变」面（选中态、分类卡等）。
 * 从 [EcoColors.blueSoft] 提亮一档到接近 surface，得到不脏的浅渐变。
 */
fun softGradient(c: EcoColors, base: Color = c.blueSoft, topMix: Float = 0.62f, bottomMix: Float = 0.5f): Brush {
    val top = lerp(base, c.surface, topMix)
    val bottom = lerp(base, c.surface, bottomMix)
    return Brush.verticalGradient(listOf(top, bottom))
}

/** 强调色描边：比 1dp 纯色更「有主题感」的 1dp 描边色 */
val EcoColors.accentBorder: Color get() = blue.copy(alpha = if (isDark) 0.42f else 0.26f)

/** 主按钮/FAB 上的前景：按渐变中间色自动取白或深墨（暖金、白主题的亮按钮不能用白字） */
val EcoColors.onAccentGrad: Color get() = contentColorOn(accentGradB)

// ---------- hero 前景色 ----------
//
// hero / 封面渐变永远偏深，所以前景永远是浅色系；但**色调要跟着主题走**：
// 原来的 `HERO_MINT #BDE9DA` / `HERO_INK #EAFBF2` 都是青绿系常量，
// 换到鸢尾/珊瑚主题后 hero 变成紫底绿字，很脏。
// 这里改成从 hero 中段色向白色提亮——既保证对比度，又带上主题的色相。
//
// 提亮后 hero 变浅了（heroC 0.180），前景必须更白才守得住 4.5:1，
// 所以这组系数整体比原来高。

/** hero 主前景（大金额、正文）：接近纯白，带一点主题色相 */
val EcoColors.onHero: Color get() = lerp(heroB, Color.White, 0.97f)

/** hero 次级前景（眉标、单位）：比 [onHero] 淡一档，但仍守住 3:1 */
val EcoColors.onHeroMuted: Color get() = lerp(heroB, Color.White, 0.82f)

/** hero 上的小控件前景（胶囊文字、图标） */
val EcoColors.onHeroChip: Color get() = lerp(heroB, Color.White, 0.92f)

/** hero 上的白色描边/分隔（低透明，不抢主体） */
val EcoColors.onHeroBorder: Color get() = lerp(heroB, Color.White, 0.88f).copy(alpha = 0.26f)

/** hero 上进度条等装饰的填充渐变：主题亮端 → 主题高亮端 */
val EcoColors.heroProgress: List<Color> get() = listOf(lerp(heroB, Color.White, 0.45f), lerp(heroC, Color.White, 0.30f))

/** hero 的「厚度」底盘色（同形状下移一层），取 hero 深端 */
val EcoColors.heroPlate: Color get() = lerp(heroA, Color.Black, 0.35f)

val LocalEcoColors = staticCompositionLocalOf {
    ecoColors(false, EcoAccent.Tide)
}

val MaterialTheme.eco: EcoColors
    @Composable get() = LocalEcoColors.current

/**
 * 中性面（纸面/卡片/分隔线/轨道）配色。
 *
 * 原来的浅色纸面是**冷青灰**（`#F7F8F3` / `#F0F6F2` / `#DCE9E1`），
 * 即使换成鸢尾/珊瑚主题，背景依然泛青绿——这正是「青绿色太多」的主因之一。
 * 现在中性面按 [EcoAccent.neutral] 分档：默认冷调（青灰）、[Neutral.WHITE] 纯白、
 * [Neutral.GRAPHITE] 石墨深灰（深色模式的白主题）。
 */
private data class Neutrals(
    val paper: Long, val paperDeep: Long,
    val surface: Long, val surface2: Long, val surface3: Long, val surfaceInset: Long,
    val ink: Long, val ink2: Long, val muted: Long, val quiet: Long,
    val rule: Long, val ruleStrong: Long, val track: Long,
)

/** 近白面色：白与强调色按极小比例混合——大面积仍是白，只留一点主题色相。 */
private fun nearWhite(tint: Color, mix: Float): Long =
    lerp(Color.White, tint, mix).toArgb().toLong() and 0xFFFFFFFFL

/** 在给定底色上叠一点主题色相。 */
private fun tinted(base: Long, tint: Color, mix: Float): Long =
    lerp(Color(base), tint, mix).toArgb().toLong() and 0xFFFFFFFFL

/**
 * 浅色中性面：**近乎白色 + 一点主题色**。
 *
 * 原来是固定的青灰纸面（`#F7F8F7` 一类），所以无论换什么主题，背景都是一片青味。
 * 现在整组都由强调色派生：纸面 3% 着色、卡片面 1%（几乎是纯白）、
 * 分隔线/轨道 12–25%。面积越大越白，只有线条和次级面才看得出色相。
 */
private fun tintedLightNeutrals(accent: EcoAccent, tintOverride: Color? = null): Neutrals {
    val t = tintOverride ?: Color(accent.light.blue)
    return Neutrals(
        paper = nearWhite(t, 0.030f),
        paperDeep = nearWhite(t, 0.070f),
        surface = nearWhite(t, 0.010f),
        surface2 = nearWhite(t, 0.050f),
        surface3 = nearWhite(t, 0.090f),
        surfaceInset = nearWhite(t, 0.060f),
        ink = tinted(0xFF101617, t, 0.10f),
        ink2 = tinted(0xFF36403F, t, 0.13f),
        muted = tinted(0xFF586866, t, 0.16f),
        quiet = tinted(0xFF82938F, t, 0.16f),
        rule = nearWhite(t, 0.150f),
        ruleStrong = nearWhite(t, 0.250f),
        track = nearWhite(t, 0.120f),
    )
}

/** 深色中性面：同样随主题偏色，避免任何主题下都是一片深青。 */
private fun tintedDarkNeutrals(accent: EcoAccent, tintOverride: Color? = null): Neutrals {
    val t = tintOverride ?: Color(accent.light.blue)
    return Neutrals(
        paper = tinted(0xFF0A0F10, t, 0.10f),
        paperDeep = tinted(0xFF0D1416, t, 0.12f),
        surface = tinted(0xFF121A1C, t, 0.13f),
        surface2 = tinted(0xFF182225, t, 0.15f),
        surface3 = tinted(0xFF1F2B2E, t, 0.17f),
        surfaceInset = tinted(0xFF111A1C, t, 0.13f),
        ink = tinted(0xFFF3F7F6, t, 0.03f),
        ink2 = tinted(0xFFDCE5E4, t, 0.05f),
        muted = tinted(0xFFB0C2C1, t, 0.07f),
        quiet = tinted(0xFF8AA0A0, t, 0.07f),
        rule = tinted(0xFF2B3A3E, t, 0.22f),
        ruleStrong = tinted(0xFF3E5256, t, 0.24f),
        track = tinted(0xFF2A3A3D, t, 0.20f),
    )
}

/** 白主题浅色：大面积纯白，分隔线/轨道走中性灰，text 走中性黑 */
private val WHITE_LIGHT = Neutrals(
    0xFFFFFFFF, 0xFFF7F8F9, 0xFFFFFFFF, 0xFFF7F8F9, 0xFFF0F1F3, 0xFFF7F8F9,
    0xFF1A1D20, 0xFF3D4247, 0xFF6B7178, 0xFF959BA2,
    0xFFE8EAED, 0xFFD5D9DE, 0xFFE8EAED,
)

/** 白主题深色：石墨底（不是纯黑，避免 OLED 上边缘发飘）。
 *  三通道尽量相等，避免深灰底透出彩度（发绿/发蓝）。 */
private val GRAPHITE_DARK = Neutrals(
    0xFF121212, 0xFF191919, 0xFF1E1E1E, 0xFF252525, 0xFF2D2D2D, 0xFF1B1B1B,
    0xFFF3F3F3, 0xFFD9D9D9, 0xFFA9A9A9, 0xFF838383,
    0xFF333333, 0xFF474747, 0xFF343434,
)

private fun neutralsFor(dark: Boolean, accent: EcoAccent, tintOverride: Color? = null): Neutrals = if (dark) {
    when (accent.neutralDark) {
        Neutral.GRAPHITE -> GRAPHITE_DARK
        else -> tintedDarkNeutrals(accent, tintOverride)
    }
} else {
    when (accent.neutral) {
        Neutral.WHITE -> WHITE_LIGHT
        else -> tintedLightNeutrals(accent, tintOverride)
    }
}

// ---------- 自定义主题色 ----------

/** 颜色的色相（0..360） */
internal fun hueOf(color: Color): Float = rgbToHsv(color.red, color.green, color.blue)[0]

/** 颜色的饱和度（0..1） */
internal fun saturationOf(color: Color): Float = rgbToHsv(color.red, color.green, color.blue)[1]

/** 只缩放饱和度，保留色相与明度 */
private fun Color.scaledSaturation(scale: Float): Color {
    val hsv = rgbToHsv(red, green, blue)
    val rgb = hsvToRgb(hsv[0], (hsv[1] * scale).coerceIn(0f, 1f), hsv[2])
    return Color(rgb[0], rgb[1], rgb[2])
}

private fun Long.scaledSaturation(scale: Float): Long =
    Color(this).scaledSaturation(scale).toArgb().toLong() and 0xFFFFFFFFL

private fun Family.hueShiftedAndScaled(shift: Float, satScale: Float): Family = Family(
    blue = blue.rotatedBy(shift).scaledSaturation(satScale),
    deep = deep.rotatedBy(shift).scaledSaturation(satScale),
    mid = mid.rotatedBy(shift).scaledSaturation(satScale),
    hi = hi.rotatedBy(shift).scaledSaturation(satScale),
    soft = soft.rotatedBy(shift).scaledSaturation(satScale),
    wash = wash.rotatedBy(shift).scaledSaturation(satScale),
)

/**
 * 自定义主题色：[customBase] 只决定「色相 + 饱和度」，
 * 明度骨架沿用当前预设（[EcoAccent]），因此对比度、层次关系仍然可预期，
 * 不会因为用户挑了一个很亮/很暗的颜色就出现看不清的按钮或封面。
 */
fun ecoColors(dark: Boolean, accent: EcoAccent, customBase: Color? = null): EcoColors {
    val ref = Color(accent.light.blue)
    val hueShift = if (customBase != null) hueOf(customBase) - hueOf(ref) else 0f
    val satScale = if (customBase != null) {
        (saturationOf(customBase) / saturationOf(ref).coerceAtLeast(0.05f)).coerceIn(0.2f, 1.3f)
    } else {
        1f
    }
    val b = if (customBase == null) {
        if (dark) accent.dark else accent.light
    } else {
        (if (dark) accent.dark else accent.light).hueShiftedAndScaled(hueShift, satScale)
    }
    val s = b.rotated(accent.secondaryShift)
    val n = neutralsFor(dark, accent, customBase)
    // 纯白主题是无彩色定位，封面/按钮不跟着加饱和，反而要压掉原始那点蓝味
    val monochrome = accent.neutral == Neutral.WHITE && accent.neutralDark == Neutral.GRAPHITE
    val heroSat = (if (monochrome) NEUTRAL_SAT_SCALE else HERO_SAT_SCALE) * satScale
    val gradSat = (if (monochrome) NEUTRAL_SAT_SCALE else GRAD_SAT_SCALE) * satScale
    val heroA = if (customBase == null) accent.heroA else rotateHue(accent.heroA, hueShift)
    val heroB = if (customBase == null) accent.heroB else rotateHue(accent.heroB, hueShift)
    val heroC = if (customBase == null) accent.heroC else rotateHue(accent.heroC, hueShift)
    val gradA = if (customBase == null) accent.accentGradA else rotateHue(accent.accentGradA, hueShift)
    val gradB = if (customBase == null) accent.accentGradB else rotateHue(accent.accentGradB, hueShift)
    val gradC = if (customBase == null) accent.accentGradC else rotateHue(accent.accentGradC, hueShift)
    return EcoColors(
        isDark = dark,
        accent = accent,
        paper = Color(n.paper),
        paperDeep = Color(n.paperDeep),
        surface = Color(n.surface),
        surface2 = Color(n.surface2),
        surface3 = Color(n.surface3),
        surfaceInset = Color(n.surfaceInset),
        ink = Color(n.ink),
        ink2 = Color(n.ink2),
        muted = Color(n.muted),
        quiet = Color(n.quiet),
        rule = Color(n.rule),
        ruleStrong = Color(n.ruleStrong),
        track = Color(n.track),
        blue = lift(Color(b.blue), ACCENT_LIFT),
        blueDeep = lift(Color(b.deep), ACCENT_LIFT_DEEP),
        blue2 = lift(Color(b.mid), ACCENT_LIFT),
        blueHi = lift(Color(b.hi), ACCENT_LIFT_HI),
        blueSoft = Color(b.soft),
        blueWash = Color(b.wash),
        onAccent = contentColorOn(lift(Color(b.deep), ACCENT_LIFT_DEEP)),
        second = lift(Color(s.blue), ACCENT_LIFT_SECOND),
        secondDeep = lift(Color(s.deep), ACCENT_LIFT_DEEP),
        second2 = lift(Color(s.mid), ACCENT_LIFT_SECOND),
        secondHi = lift(Color(s.hi), ACCENT_LIFT_HI),
        secondSoft = Color(s.soft),
        secondWash = Color(s.wash),
        onSecond = contentColorOn(lift(Color(s.deep), ACCENT_LIFT_DEEP)),
        // 语义色：收入/警告/危险永远不变，只有深浅两套
        jade = if (dark) Color(0xFF88DBB0) else Color(0xFF268E70),
        jadeHi = if (dark) Color(0xFFBBE9C9) else Color(0xFF98DDB1),
        jadeSoft = if (dark) Color(0xFF255044) else Color(0xFFE4F5EB),
        amber = if (dark) Color(0xFFF0C278) else Color(0xFFB98937),
        amberHi = if (dark) Color(0xFFFFE1A0) else Color(0xFFF4D58D),
        amberSoft = if (dark) Color(0xFF4A4130) else Color(0xFFFAF0DA),
        clay = if (dark) Color(0xFFF39F8E) else Color(0xFFCC6854),
        clayHi = if (dark) Color(0xFFFFC4AE) else Color(0xFFF1A28F),
        claySoft = if (dark) Color(0xFF503C3B) else Color(0xFFFCEAE4),
        violet = if (dark) Color(0xFFC4B3DF) else Color(0xFF907BA8),
        violetSoft = if (dark) Color(0xFF3C354B) else Color(0xFFF1EAF5),
        heroA = vividTone(heroA, HERO_LUMA_A, heroSat),
        heroB = vividTone(heroB, HERO_LUMA_B, heroSat),
        heroC = vividTone(heroC, HERO_LUMA_C, heroSat),
        accentGradA = vividTone(gradA, GRAD_LUMA_A, gradSat),
        accentGradB = vividTone(gradB, GRAD_LUMA_B, gradSat),
        accentGradC = vividTone(gradC, GRAD_LUMA_C, gradSat),
    )
}

// ---------- 旧语义色（保留给尚未迁移的组件与业务逻辑） ----------

/**
 * 旧语义色（保留给尚未迁移的组件与业务逻辑）。
 *
 * [primaryStrong] / [cardOnLight] / [cardOnDark] / [categoryPalette] 都会带上当前 [EcoAccent]，
 * 这样即便某个组件还没迁到 `MaterialTheme.eco`，它的主色与分类色板也会跟随主题，
 * 不会永远停在青蓝。
 */
@Immutable
data class AccBookColors(
    val income: Color,
    val warning: Color,
    val critical: Color,
    val criticalAlert: Color,
    val primaryStrong: Color,
    val hairline: Color,
    val ringTrack: Color,
    val festiveRed: Color,
    val overlayInk: Color,
    val cardOnLight: CardTones,
    val cardOnDark: CardTones,
    val categoryPalette: List<Color>,
)

/**
 * 分类色板：8 个槽位，任意两色 ΔE ≥ 30（有单测守着）。
 *
 * 槽位 5 原来是固定的「浅青 `#7EC8C8`」——它不跟主题，任何主题下都有一块青绿，
 * 所以改成**由当前强调色生成**：用强调色的 mid 档，保证与其它 7 色仍有足够色差。
 */
/**
 * 分类色板里那个「跟主题走」的槽位。
 *
 * 原来写死「浅青」，任何主题下都有一块青绿。改成由强调色生成时不能随便取一档：
 * 潮汐的 mid 会和「雾蓝」撞色（ΔE 掉到 24）。这里在强调色的四档里挑
 * **离其余 7 个固定色最远**的那一档，保证 8 色仍然一眼可分。
 */
private fun accentPaletteSlot(accent: EcoAccent, dark: Boolean, others: List<Color>): Color {
    val family = if (dark) accent.dark else accent.light
    // 候选：强调色的四个明确定义档 + 同色相下的一批明度/饱和度组合。
    // 只取四档时潮汐会撞「雾蓝」（ΔE 24），放宽到同色相格点后能稳定拉开。
    val base = Color(family.blue)
    val hue = rgbToHsv(base.red, base.green, base.blue)[0]
    val candidates = buildList {
        listOf(family.blue, family.deep, family.mid, family.hi).forEach { add(Color(it)) }
        for (s in listOf(0.40f, 0.60f, 0.80f)) {
            for (v in listOf(0.40f, 0.55f, 0.70f, 0.85f)) {
                val rgb = hsvToRgb(hue, s, v)
                add(Color(rgb[0], rgb[1], rgb[2]))
            }
        }
    }
    // 取「离其余固定色最远」的那一个
    return candidates.maxByOrNull { candidate -> others.minOf { colorDistance(candidate, it) } }
        ?: base
}

internal fun lightAccBookColors(accent: EcoAccent = EcoAccent.Tide): AccBookColors {
    val fixed = listOf(
        Color(0xFFB95B5B), // 陶红
        Color(0xFF796834), // 岩金
        Color(0xFFA3C87E), // 苔绿
        Color(0xFF347945), // 松林
        Color(0xFF7E91C8), // 雾蓝
        Color(0xFF70439D), // 鸢尾
        Color(0xFFC87EB6), // 藕荷
    )
    return AccBookColors(
    income = Color(0xFF268E70),
    warning = Color(0xFFB98937),
    critical = Color(0xFFCC6854),
    criticalAlert = Color(0xFFB44A38),
    primaryStrong = Color(accent.light.deep),
    hairline = Color(0xFFDCE9E1),
    ringTrack = Color(0xFFDFE9E1),
    festiveRed = Color(0xFFCC6854),
    overlayInk = Color(0xFFFFFFFF),
    cardOnLight = CardTones(
        ink = Color(0xFF142F37),
        sub = Color(0xFF38545A),
        income = Color(0xFF268E70),
        expense = Color(0xFFB44A38),
        scrim = Color(0xFFFFFFFF),
        pillContainer = Color(0x2624788A),
        pillSelected = Color(accent.light.deep),
        pillSelectedContent = Color(0xFFFFFFFF),
    ),
    cardOnDark = CardTones(
        ink = Color(0xFFF2F8F2),
        sub = Color(0xCCF2F8F2),
        income = Color(0xFF98DDB1),
        expense = Color(0xFFF1A28F),
        scrim = Color(0xFF000000),
        pillContainer = Color(0x33FFFFFF),
        pillSelected = Color(0xFFFFFFFF),
        pillSelectedContent = Color(0xFF142F37),
    ),
    categoryPalette = listOf(
        fixed[0], // 陶红
        fixed[1], // 岩金
        fixed[2], // 苔绿
        fixed[3], // 松林
        accentPaletteSlot(accent, dark = false, others = fixed), // 主题色（原「浅青」）
        fixed[4], // 雾蓝
        fixed[5], // 鸢尾
        fixed[6], // 藕荷
    ),
    )
}

internal fun darkAccBookColors(accent: EcoAccent = EcoAccent.Tide): AccBookColors {
    val fixed = listOf(
        Color(0xFF5FBF88), // 苔绿
        Color(0xFFD6A84A), // 岩金
        Color(0xFFD9826C), // 陶红
        Color(0xFFB98BD8), // 鸢尾（比原来的 #A98BC8 更紫，与雾蓝拉开）
        Color(0xFF5B93D8), // 雾蓝（比原来的 #6A9BD8 更蓝）
        Color(0xFFD8749E), // 藕荷
        Color(0xFF94A84E), // 橄榄
    )
    return AccBookColors(
    income = Color(0xFF88DBB0),
    warning = Color(0xFFF0C278),
    critical = Color(0xFFF39F8E),
    criticalAlert = Color(0xFFFF8A73),
    primaryStrong = Color(accent.dark.hi),
    hairline = Color(0xFF35555A),
    ringTrack = Color(0xFF355358),
    festiveRed = Color(0xFFF39F8E),
    overlayInk = Color(0xFFFFFFFF),
    cardOnLight = CardTones(
        ink = Color(0xFF142F37),
        sub = Color(0xFF38545A),
        income = Color(0xFF268E70),
        expense = Color(0xFFB44A38),
        scrim = Color(0xFFFFFFFF),
        pillContainer = Color(0x2624788A),
        pillSelected = Color(accent.light.deep),
        pillSelectedContent = Color(0xFFFFFFFF),
    ),
    cardOnDark = CardTones(
        ink = Color(0xFFF2F8F2),
        sub = Color(0xCCF2F8F2),
        income = Color(0xFF98DDB1),
        expense = Color(0xFFF1A28F),
        scrim = Color(0xFF000000),
        pillContainer = Color(0x33FFFFFF),
        pillSelected = Color(0xFFFFFFFF),
        pillSelectedContent = Color(0xFF142F37),
    ),
    categoryPalette = listOf(
        accentPaletteSlot(accent, dark = true, others = fixed), // 主题色（原「浅青」）
        fixed[0], // 苔绿
        fixed[1], // 岩金
        fixed[2], // 陶红
        fixed[3], // 鸢尾
        fixed[4], // 雾蓝
        fixed[5], // 藕荷
        fixed[6], // 橄榄
    ),
    )
}

val LocalAccBookColors = staticCompositionLocalOf { lightAccBookColors() }

val MaterialTheme.accBook: AccBookColors
    @Composable get() = LocalAccBookColors.current

@Immutable
data class CardTones(
    val ink: Color,
    val sub: Color,
    val income: Color,
    val expense: Color,
    val scrim: Color,
    val pillContainer: Color,
    val pillSelected: Color,
    val pillSelectedContent: Color,
)

fun categoryColor(palette: List<Color>, categoryName: String): Color {
    if (palette.isEmpty()) return Color.Unspecified
    return palette[preferredSlot(palette.size, categoryName)]
}

fun ringColors(palette: List<Color>, fixed: Map<String, Color>, names: List<String>): Map<String, Color> {
    val out = LinkedHashMap<String, Color>(fixed)
    if (names.isEmpty()) return out
    if (palette.isEmpty()) return out
    val used = ArrayList<Color>(fixed.values)
    names.filter { it.isNotEmpty() }.distinct().sorted().forEach { name ->
        if (out.containsKey(name)) return@forEach
        val slot = palette.indices.maxByOrNull { candidate ->
            if (used.isEmpty()) 0f else used.minOf { colorDistance(palette[candidate], it) }
        } ?: 0
        out[name] = palette[slot]
        used += palette[slot]
    }
    return out
}

internal const val RING_MIN_DELTA = 26f

internal fun colorDistance(a: Color, b: Color): Float {
    val la = toLab(a)
    val lb = toLab(b)
    val dl = la[0] - lb[0]
    val da = la[1] - lb[1]
    val db = la[2] - lb[2]
    return kotlin.math.sqrt(dl * dl + da * da + db * db)
}

private fun toLab(color: Color): FloatArray {
    val r = linearize(color.red)
    val g = linearize(color.green)
    val b = linearize(color.blue)
    val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
    val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
    val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
    val fx = labF(x)
    val fy = labF(y)
    val fz = labF(z)
    return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
}

private fun linearize(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

private fun labF(t: Float): Float =
    if (t > 0.008856f) t.pow(1f / 3f) else 7.787f * t + 16f / 116f

internal fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val rawHue = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf(if (rawHue < 0f) rawHue + 360f else rawHue, if (max == 0f) 0f else d / max, max)
}

internal fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
    val c = v * s
    val hp = (((h % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val r: Float
    val g: Float
    val b: Float
    when (hp.toInt()) {
        0 -> { r = c; g = x; b = 0f }
        1 -> { r = x; g = c; b = 0f }
        2 -> { r = 0f; g = c; b = x }
        3 -> { r = 0f; g = x; b = c }
        4 -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    val m = v - c
    return floatArrayOf(r + m, g + m, b + m)
}

private fun preferredSlot(size: Int, name: String): Int {
    if (size <= 0) return 0
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) and 0x7FFFFFFF
    return h % size
}

fun toneColor(tone: Budget.Tone, colors: AccBookColors): Color = when (tone) {
    Budget.Tone.OK -> colors.income
    Budget.Tone.MID -> colors.warning
    Budget.Tone.LOW -> colors.critical
}

/** 新设计：档位色直接取纸面语义色 */
fun toneColor(tone: Budget.Tone, colors: EcoColors): Color = when (tone) {
    Budget.Tone.OK -> colors.jade
    Budget.Tone.MID -> colors.amber
    Budget.Tone.LOW -> colors.clay
}

// ---------- Material 配色 ----------

private fun lightScheme(eco: EcoColors) = lightColorScheme(
    background = eco.paper,
    onBackground = eco.ink,
    surface = eco.surface,
    onSurface = eco.ink,
    surfaceVariant = eco.surface2,
    onSurfaceVariant = eco.muted,
    surfaceDim = eco.paperDeep,
    surfaceBright = eco.surface,
    surfaceContainerLowest = eco.surface,
    surfaceContainerLow = eco.surface2,
    surfaceContainer = eco.surface2,
    surfaceContainerHigh = eco.surface3,
    surfaceContainerHighest = eco.surface3,
    surfaceTint = eco.blue,
    primary = eco.blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = eco.blueSoft,
    onPrimaryContainer = eco.blueDeep,
    secondary = eco.jade,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = eco.jadeSoft,
    onSecondaryContainer = eco.ink,
    tertiary = eco.amber,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = eco.amberSoft,
    onTertiaryContainer = eco.ink,
    error = eco.clay,
    onError = Color(0xFFFFFFFF),
    errorContainer = eco.claySoft,
    onErrorContainer = eco.ink,
    outline = eco.ruleStrong,
    outlineVariant = eco.rule,
    inverseSurface = eco.ink,
    inverseOnSurface = eco.paper,
    inversePrimary = eco.blueHi,
)

private fun darkScheme(eco: EcoColors) = darkColorScheme(
    background = eco.paper,
    onBackground = eco.ink,
    surface = eco.surface,
    onSurface = eco.ink,
    surfaceVariant = eco.surface2,
    onSurfaceVariant = eco.muted,
    surfaceDim = eco.paperDeep,
    surfaceBright = eco.surface3,
    surfaceContainerLowest = eco.paperDeep,
    surfaceContainerLow = eco.surface,
    surfaceContainer = eco.surface,
    surfaceContainerHigh = eco.surface2,
    surfaceContainerHighest = eco.surface3,
    surfaceTint = eco.blue,
    primary = eco.blue,
    onPrimary = eco.paper,
    primaryContainer = eco.blueSoft,
    onPrimaryContainer = eco.blueDeep,
    secondary = eco.jade,
    onSecondary = eco.paper,
    secondaryContainer = eco.jadeSoft,
    onSecondaryContainer = eco.jade,
    tertiary = eco.amber,
    onTertiary = eco.paper,
    tertiaryContainer = eco.amberSoft,
    onTertiaryContainer = eco.amber,
    error = eco.clay,
    onError = eco.paper,
    errorContainer = eco.claySoft,
    onErrorContainer = eco.clay,
    outline = eco.ruleStrong,
    outlineVariant = eco.rule,
    inverseSurface = eco.ink,
    inverseOnSurface = eco.paper,
    inversePrimary = eco.blueDeep,
)

// ---------- 字体 ----------

private fun tracking(size: Float, em: Float) = (size * em).sp

private fun ecoText(
    size: Float,
    weight: Int,
    em: Float = 0f,
    lineHeight: Float = size * 1.25f,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking(size, em),
    lineHeight = lineHeight.sp,
    fontFeatureSettings = "tnum",
)

/** 命名版式：新代码直接引用，避免每处猜字号/字距 */
object EcoType {
    val TopTitle = ecoText(28f, 800, -0.064f, 32f)
    val SubTitle = ecoText(23f, 800, -0.064f, 28f)
    val TopLow = ecoText(28f, 500, -0.064f, 32f)
    val Micro = ecoText(9f, 800, 0.145f, 12f)
    val MicroQuiet = ecoText(9f, 700, 0.17f, 12f)
    val SectionNo = ecoText(8f, 800, 0f, 10f)
    val SectionTitle = ecoText(13f, 800, -0.015f, 17f)
    val SectionAside = ecoText(8.5f, 500, 0f, 11f)
    val RowTitle = ecoText(13f, 700, -0.014f, 17f)
    val RowSub = ecoText(9.5f, 400, 0f, 15f)
    val RowTrail = ecoText(10.5f, 500, 0f, 14f)
    val PageLead = ecoText(10f, 400, 0f, 17.5f)
    val PageNote = ecoText(9f, 400, 0f, 15f)
    val HeroAmount = ecoText(48f, 800, -0.078f, 48f)
    val MeterMoney = ecoText(39f, 700, -0.065f, 46f)
    val TodayAmount = ecoText(47f, 700, -0.065f, 51f)
    val CatTotal = ecoText(38f, 800, -0.065f, 39f)
    val AssetAmount = ecoText(48f, 800, -0.07f, 53f)
    val CoverMoney = ecoText(32f, 800, -0.07f, 32f)
    val ConclusionAmount = ecoText(29f, 700, -0.065f, 35f)
    val PendingAmount = ecoText(16f, 800, -0.05f, 20f)
    val BillAmount = ecoText(13.5f, 800, -0.025f, 18f)
    val CatAmount = ecoText(16.5f, 800, -0.045f, 21f)
    val KeyAmount = ecoText(53f, 800, -0.075f, 53f)
    val EditAmount = ecoText(33f, 800, -0.06f, 40f)
    val ConfirmAmount = ecoText(29f, 800, -0.06f, 35f)
    val PendingCount = ecoText(35f, 800, -0.07f, 37f)
    val DailyValue = ecoText(31f, 800, -0.065f, 35f)
    val Seg = ecoText(10.5f, 700, 0f, 14f)
    val SegSmall = ecoText(10f, 700, 0f, 13f)
    val Chip = ecoText(10.5f, 700, 0f, 14f)
    val Pill = ecoText(9f, 700, 0.02f, 12f)
    val Btn = ecoText(12f, 800, 0f, 16f)
    val FieldLabel = ecoText(10f, 700, 0.04f, 13f)
    val Input = ecoText(12f, 500, 0f, 16f)
    val AssetLabel = ecoText(11f, 700, 0.16f, 14f)
    val MeterLabel = ecoText(11f, 700, 0.06f, 14f)
    val DatePill = ecoText(9.5f, 700, 0.02f, 12f)
    val SheetTitle = ecoText(20f, 800, -0.05f, 24f)
    val SheetSub = ecoText(8.5f, 400, 0f, 13f)
    val ModalTitle = ecoText(18f, 800, -0.04f, 22f)
    val ModalBody = ecoText(10f, 400, 0f, 17f)
    val EmptyTitle = ecoText(13f, 700, 0f, 17f)
    val EmptyBody = ecoText(12f, 400, 0f, 21f)
    val LogChannel = ecoText(7.5f, 700, 0.04f, 9f)
    val TraceTime = ecoText(8f, 400, 0f, 11f)
    val Nav = ecoText(10f, 600, 0f, 12f)
    val SheetTitleSmall = ecoText(17f, 800, -0.04f, 21f)
}

private fun appTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = EcoType.HeroAmount,
        displayMedium = EcoType.MeterMoney,
        displaySmall = EcoType.ConclusionAmount,
        headlineLarge = EcoType.TopTitle,
        headlineMedium = EcoType.SubTitle,
        headlineSmall = EcoType.SheetTitle,
        titleLarge = EcoType.ModalTitle,
        titleMedium = EcoType.CatAmount,
        titleSmall = EcoType.RowTitle,
        bodyLarge = ecoText(13f, 500, 0f, 19f),
        bodyMedium = ecoText(11f, 400, 0f, 16f),
        bodySmall = EcoType.RowSub,
        labelLarge = EcoType.Btn,
        labelMedium = EcoType.Chip,
        labelSmall = EcoType.Pill,
    )
}

private val appShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(19.dp),
    extraLarge = RoundedCornerShape(25.dp),
)

@Composable
fun AccBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: EcoAccent = EcoAccent.Tide,
    /** 自定义主题色：只取色相 + 饱和度，明度骨架仍沿用 [accent] */
    customBase: Color? = null,
    content: @Composable () -> Unit,
) {
    val eco = ecoColors(darkTheme, accent, customBase)
    val colorScheme = if (darkTheme) darkScheme(eco) else lightScheme(eco)
    val extended = if (darkTheme) darkAccBookColors(accent) else lightAccBookColors(accent)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalEcoColors provides eco,
        LocalAccBookColors provides extended,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(),
            shapes = appShapes,
            content = content,
        )
    }
}
