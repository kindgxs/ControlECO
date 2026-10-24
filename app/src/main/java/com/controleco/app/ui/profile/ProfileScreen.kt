package com.controleco.app.ui.profile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.BuildConfig
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.budget.Budget
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.WheelPicker
import com.controleco.app.ui.common.WheelTrigger
import com.controleco.app.ui.eco.EcoArrival
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.eco.EcoCountUpMoney
import com.controleco.app.ui.eco.EcoBudgetSheet
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoDialog
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoHeroCard
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.theme.AccentThemeStore
import com.controleco.app.ui.theme.EcoAccent
import com.controleco.app.ui.theme.EcoColors
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.contentColorOn
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.theme.ecoColors
import com.controleco.app.ui.theme.heroPlate
import com.controleco.app.ui.theme.heroProgress
import com.controleco.app.ui.theme.onHero
import com.controleco.app.ui.theme.onHeroBorder
import com.controleco.app.ui.theme.onHeroChip
import com.controleco.app.ui.theme.onHeroMuted
import com.controleco.app.util.Money
import java.time.LocalDate
import kotlin.math.roundToInt

// ---- 版式常量：按原型最终级联值（hero 197 / duo 161 / mid 150 / bottom 66）----
private val HERO_HEIGHT = 197.dp
private val DUO_HEIGHT = 161.dp
private val PENDING_WIDTH = 111.dp
private val UTILITY_WIDTH = 127.dp
private val MID_HEIGHT = 150.dp
private val START_CARD_HEIGHT = 80.dp
private val SETTINGS_CARD_HEIGHT = 62.dp
private val MID_GAP = 8.dp
private val BOTTOM_HEIGHT = 66.dp

/**
 * 书签形待确认卡：上边与左右是圆角，下边中间向上凹一个 V 形口。
 * 对应原型里那张异形卡（不是贴图，是路径）。
 */
private object BookmarkShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { 16.dp.toPx() }.coerceAtMost(w / 2f).coerceAtMost(h / 4f)
        val notchHalf = w * 0.26f
        val notchDepth = h * 0.22f
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            quadraticBezierTo(w, 0f, w, r)
            lineTo(w, h - r)
            quadraticBezierTo(w, h, w - r, h)
            // 下边：右角 → 中间凸峰（峰顶略作圆角）→ 左角
            lineTo(w * 0.5f + notchHalf, h)
            quadraticBezierTo(
                w * 0.5f + notchHalf * 0.35f, h - notchDepth * 0.72f,
                w * 0.5f, h - notchDepth,
            )
            quadraticBezierTo(
                w * 0.5f - notchHalf * 0.35f, h - notchDepth * 0.72f,
                w * 0.5f - notchHalf, h,
            )
            lineTo(r, h)
            quadraticBezierTo(0f, h, 0f, h - r)
            lineTo(0f, r)
            quadraticBezierTo(0f, 0f, r, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * 同形状底盘：把同样的路径往右下垫一层实色，做出「厚」的侧面。
 * 右边比底边薄（原型里侧面只露一点点），所以水平偏移默认小于垂直偏移。
 * 它和卡片同一个形状，所以不会像外投影那样在圆角/异形处冒出方框。
 */
private fun Modifier.cardPlate(
    shape: Shape,
    color: Color,
    offsetX: Dp = 1.5.dp,
    offsetY: Dp = 3.5.dp,
): Modifier = drawBehind {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
    translate(offsetX.toPx(), offsetY.toPx()) { drawPath(path, color) }
}

/**
 * 卡内浮雕：贴顶边一圈高光 + 底边内侧一道暗边，都按卡片自身形状裁切。
 * 颜色/厚度逐卡传参，用来区分不同卡片的材质。
 */
private fun Modifier.cardInset(
    shape: Shape,
    topColor: Color,
    topHeight: Dp = 1.2.dp,
    bottomColor: Color,
    band: Dp = 12.dp,
): Modifier = drawWithContent {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent)) }
    clipPath(path) {
        drawRect(topColor, size = Size(size.width, topHeight.toPx()))
        val b = band.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to bottomColor,
                startY = size.height - b,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - b),
            size = Size(size.width, b),
        )
    }
    drawContent()
}
// 深色 hero 上的前景色：**由主题推导**（见 Theme.kt 的 onHero* 扩展），
// 原来是固定的青绿常量，换紫色/珊瑚主题后会出现「紫底绿字」。
// 保留常量名以兼容既有调用点，但值改为读当前主题。
private val HERO_MINT: Color
    @Composable get() = MaterialTheme.eco.onHeroMuted
private val HERO_MINT_SOFT: Color
    @Composable get() = MaterialTheme.eco.onHero
private val HERO_INK: Color
    @Composable get() = MaterialTheme.eco.onHero
private val HERO_BTN_INK: Color
    @Composable get() = MaterialTheme.eco.onHeroChip

/**
 * 个人页：预算 hero + 月/日预算双列（待确认椭圆）+ 分类/每月首日/设置三卡 + 底排（导入/打赏/关于）+ 更多。
 *
 * 版式与原型 1:1（5.6 profile）：hero 197 高、非对称圆角 12/33/12/33；
 * 双列左侧是分段 + 日预算瓦片，右侧是待确认椭圆（跨两行）；
 * 中排左侧分类卡（25/9/25/9），右侧「每月首日」（内置常驻滚轮）+「设置」；
 * 底排 63 / 1.03fr / 1.27fr 三个胶囊；其余既有入口（货币、主题色、GitHub、版本、关于）收进「更多」。
 */
@Composable
fun ProfileScreen(
    vm: LedgerViewModel,
    onOpenAccount: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenMonthlyBudget: () -> Unit,
    onOpenDeposit: () -> Unit,
) {
    val context = LocalContext.current
    val budget by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val spent by vm.homeSpent.collectAsStateWithLifecycle()
    val currency by vm.currencySymbol.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val monthStartDay by vm.monthStartDay.collectAsStateWithLifecycle()
    val dailyBudgetMode by vm.dailyBudgetMode.collectAsStateWithLifecycle()
    val dailyBudgetCents by vm.dailyBudgetCents.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val depositGoal by vm.depositGoalCents.collectAsStateWithLifecycle()
    val currentSaving by vm.currentMonthSaving.collectAsStateWithLifecycle()

    // 与首页同口径：本月净支出未到位前整页不画（避免预算卡先显示 0 再跳数）
    val spentReady = spent ?: return
    val today = LocalDate.now()
    val period = remember(today, monthStartDay) { MonthPeriod.of(today, monthStartDay) }
    val snapshot = remember(budget, spentReady, period) { Budget.compute(budget, spentReady, today, period = period) }

    var showBudgetSheet by remember { mutableStateOf(false) }
    var showCurrencySheet by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showMonthStart by remember { mutableStateOf(false) }

    // 预算卡永远显示「日均」：日预算模式下就是日预算本身；
    // 月预算模式下是月预算折算的日均（月预算 ÷ 本期天数，四舍五入），
    // 这样改完月预算卡片会立刻跟着变，不会停在旧的日预算上。
    val tileDailyCents = if (dailyBudgetMode) dailyBudgetCents else Budget.dailyEquivalent(budget, period.days)

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(micro = "PERSONAL SPACE", title = "我的")
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // 卡片视觉上比槽位小一圈（余量由 EcoArrival 在动画图层内预留），
                // 这里保持原设计的页面边距。
                .padding(horizontal = 22.dp)
                .padding(top = 6.dp, bottom = ecoBottomContentPadding(extra = 12.dp)),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            EcoArrival(randomDelay = true, delayMillis = 60, turn = 3.2f) {
                BudgetHero(
                    budgetCents = budget,
                    spentCents = spentReady,
                    snapshot = snapshot,
                    currency = currency,
                    onCurrency = { showCurrencySheet = true },
                    onOpenAccount = onOpenAccount,
                    onManageBudget = onOpenMonthlyBudget,
                )
            }

            BudgetDuo(
                dailyMode = dailyBudgetMode,
                dailyBudgetCents = tileDailyCents,
                pendingCount = pendingCount,
                onModeChange = { vm.setDailyBudgetMode(it) },
                // 点卡片编辑当前模式对应的预算（月预算 → 按月预算页，日预算 → 日预算 sheet）
                onManageBudget = { if (dailyBudgetMode) showBudgetSheet = true else onOpenMonthlyBudget() },
                onOpenPending = onOpenPending,
            )

            MidRow(
                categories = categories,
                monthStartDay = monthStartDay,
                onOpenCategories = onOpenCategories,
                onOpenSettings = onOpenSettings,
                onOpenMonthStart = { showMonthStart = true },
            )

            BottomRow(
                onOpenPlan = onOpenPlan,
                onOpenBackup = onOpenImport,
                onTheme = { showTheme = true },
            )

            EcoArrival(randomDelay = true, delayMillis = 700, turn = 1.8f) {
                DepositEntry(
                    goalCents = depositGoal,
                    savingCents = currentSaving?.savingCents ?: 0L,
                    onClick = onOpenDeposit,
                )
            }
        }
    }

    if (showBudgetSheet) {
        // 同一个 sheet：跟着分段走——「月预算」编辑月预算，「日预算」编辑日预算
        EcoBudgetSheet(
            dailyMode = dailyBudgetMode,
            currentCents = if (dailyBudgetMode) dailyBudgetCents else budget,
            onSave = {
                if (dailyBudgetMode) vm.setDailyBudget(it) else vm.setMonthlyBudget(it)
                showBudgetSheet = false
            },
            onDismiss = { showBudgetSheet = false },
        )
    }

    if (showCurrencySheet) {
        CurrencySheet(
            current = currency,
            onPick = { vm.setCurrencySymbol(it); showCurrencySheet = false },
            onDismiss = { showCurrencySheet = false },
        )
    }

    if (showTheme) {
        ThemeSheet(
            accent = AccentThemeStore.current,
            onPickAccent = { AccentThemeStore.set(context, it) },
            onDismiss = { showTheme = false },
        )
    }

    if (showMonthStart) {
        MonthStartSheet(
            monthStartDay = monthStartDay,
            onPick = { vm.setMonthStartDay(it) },
            onDismiss = { showMonthStart = false },
        )
    }
}

// ---------- 1. 预算 hero ----------

/**
 * 本月预算 hero：非对称圆角 12/33/12/33、197 高，用强调色 heroA/B/C 渐变 + 投影。
 * 四行内容：眉毛 + 货币/账户动作、48 号金额（可点开预算卡）、6dp 进度轨、已花/百分比/剩余。
 */
@Composable
private fun BudgetHero(
    budgetCents: Long,
    spentCents: Long,
    snapshot: Budget.Snapshot?,
    currency: String,
    onCurrency: () -> Unit,
    onOpenAccount: () -> Unit,
    onManageBudget: () -> Unit,
) {
    val c = MaterialTheme.eco
    val ratio = (snapshot?.spentRatio ?: if (budgetCents > 0) spentCents.toFloat() / budgetCents else 0f)
        .coerceIn(0f, 1f)
    val pct = (ratio * 100f).roundToInt()
    val remaining = budgetCents - spentCents
    // 原型 hero 金额固定两位小数（¥3,000.00），未设置时显示短字样
    val amountText = if (budgetCents > 0) Money.formatYuanGroupedFixed(budgetCents) else "未设置"

    // 本月预算：圆角方形（不再是 12/33 的非对称圆角）
    val heroShape = RoundedCornerShape(28.dp)
    EcoHeroCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            // hero 的厚度：同形状底盘下移 4dp
            .cardPlate(
                heroShape,
                c.heroPlate.copy(alpha = 0.55f),
                offsetX = 2.dp, offsetY = 4.2.dp,
            ),
        shape = heroShape,
    ) {
        Column(Modifier.fillMaxSize().padding(17.dp)) {
            // 眉毛 + 右侧两个动作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本月预算",
                    style = EcoType.Micro.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight(740),
                        letterSpacing = 1.58.sp,
                    ),
                    color = HERO_MINT,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .height(27.dp)
                        .widthIn(min = 30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color.White.copy(alpha = 0.09f))
                        .border(1.dp, c.onHeroBorder, RoundedCornerShape(15.dp))
                        .clickable(onClick = onCurrency)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        currency,
                        style = EcoType.Chip.copy(fontSize = 12.sp, fontWeight = FontWeight(760)),
                        color = HERO_BTN_INK,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Row(
                    Modifier
                        .width(77.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(11.dp, 16.dp, 11.dp, 16.dp))
                        .background(c.onHeroBorder.copy(alpha = 0.40f))
                        .clickable(onClick = onOpenAccount),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        EcoIcons.Wallet,
                        contentDescription = null,
                        tint = HERO_INK,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "账户",
                        style = EcoType.Chip.copy(fontSize = 10.5.sp, fontWeight = FontWeight(760)),
                        color = HERO_INK,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 金额（点金额或右侧铅笔都能改预算）
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (budgetCents > 0) {
                    EcoCountUpMoney(
                        cents = budgetCents,
                        style = EcoType.HeroAmount,
                        color = c.onHero,
                        modifier = Modifier.weight(1f).clickable(onClick = onManageBudget),
                        textAlign = TextAlign.Start,
                        minSize = 21.sp,
                    )
                } else {
                    Text(
                        amountText,
                        style = EcoType.HeroAmount,
                        color = c.onHero,
                        modifier = Modifier.weight(1f).clickable(onClick = onManageBudget),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(onClick = onManageBudget),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        EcoIcons.Edit,
                        contentDescription = "设置本月预算",
                        tint = HERO_MINT.copy(alpha = 0.9f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 进度轨
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(c.heroProgress),
                        ),
                )
            }
            Spacer(Modifier.height(10.dp))

            // 元信息：已花 / 百分比 / 剩余
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已花 ${Money.formatYuanGrouped(spentCents)}",
                    style = EcoType.RowTrail,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$pct%",
                    style = EcoType.RowTrail.copy(fontSize = 11.sp, fontWeight = FontWeight(760)),
                    color = HERO_MINT_SOFT,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "剩余 ${Money.formatYuanGrouped(remaining)}",
                    style = EcoType.RowTrail,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------- 2. 双列：分段 + 日预算瓦片 / 待确认椭圆 ----------

@Composable
private fun BudgetDuo(
    dailyMode: Boolean,
    dailyBudgetCents: Long,
    pendingCount: Int,
    onModeChange: (Boolean) -> Unit,
    onManageBudget: () -> Unit,
    onOpenPending: () -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier.fillMaxWidth().height(DUO_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            EcoArrival(randomDelay = true, delayMillis = 140, turn = -2.6f) {
                EcoSegmented(
                    options = listOf("月预算", "日预算"),
                    selectedIndex = if (dailyMode) 1 else 0,
                    onSelect = { onModeChange(it == 1) },
                    modifier = Modifier.fillMaxWidth(),
                    height = 42.dp,
                    selectedContainerColor = c.ink,
                    selectedContentColor = c.paper,
                )
            }
            EcoArrival(randomDelay = true, delayMillis = 210, turn = 2.2f, modifier = Modifier.weight(1f)) {
                DailyTile(
                    // 永远显示日均：日预算模式下是日预算，月预算模式下是月预算折算的日均
                    amountCents = dailyBudgetCents,
                    onManage = onManageBudget,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        EcoArrival(randomDelay = true, delayMillis = 280, turn = -2.2f, modifier = Modifier.width(PENDING_WIDTH).fillMaxHeight()) {
            PendingPill(
                count = pendingCount,
                onClick = onOpenPending,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 预算瓦片：非对称圆角 10/27/10/27。
 *
 * 永远显示**日均**：日预算模式下是日预算本身，月预算模式下是「月预算 ÷ 本期天数」的折算值，
 * 所以改完月预算这张卡会立刻跟着变。
 * 整张卡都可点，打开的是**当前分段模式**对应的预算 sheet（月预算 → 月预算，日预算 → 日预算）；
 * 右侧铅笔只是装饰，不单独接点击。
 */
@Composable
private fun DailyTile(
    amountCents: Long,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(10.dp, 27.dp, 10.dp, 27.dp)
    Box(
        modifier
            .shadow(
                elevation = 2.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Transparent,
                spotColor = c.ink.copy(alpha = if (c.isDark) 0.34f else 0.16f),
            )
            .cardPlate(shape, c.blue.copy(alpha = if (c.isDark) 0.34f else 0.26f), offsetX = 1.2.dp, offsetY = 2.8.dp)
            .clip(shape)
            .background(softGradient(c, c.blueSoft, 0.62f, 0.5f))
            .cardInset(
                shape = shape,
                topColor = Color.White.copy(alpha = if (c.isDark) 0.14f else 0.72f),
                topHeight = 1.5.dp,
                bottomColor = c.blue.copy(alpha = if (c.isDark) 0.20f else 0.13f),
                band = 13.dp,
            )
            .border(1.dp, c.rule, shape)
            .clickable(onClick = onManage),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 15.dp, top = 16.dp, end = 62.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (amountCents > 0) {
                EcoCountUpMoney(
                    cents = amountCents,
                    style = EcoType.DailyValue,
                    color = c.ink,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    minSize = 13.sp,

                    // 金额的 DailyValue 带 -0.065em（31sp 时约 -2sp）负字距，后缀必须显式归零，
                    // 否则 "/day" 会继承负字距挤成一团；前面的空格用来和金额留一点呼吸。
                    suffix = " /day",
                    suffixStyle = SpanStyle(
                        fontSize = 10.sp,
                        color = c.muted,
                        fontWeight = FontWeight(700),
                        letterSpacing = 0.sp,
                    ),
                )
            } else {
                Text(
                    text = "未设置",
                    style = EcoType.RowTitle,
                    color = c.muted,
                    maxLines = 1,
                )
            }
        }

        // 原型右侧的大铅笔只是水印，透明度 .2；文字「预算管理」在右下
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(66.dp)
                .padding(end = 9.dp),

            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = EcoIcons.Edit,
                contentDescription = null,
                tint = c.blue.copy(alpha = 0.28f),
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        rotationZ = -8f
                    }
            )

            Spacer(Modifier.height(3.dp))

            Text(
                text = "预算管理",
                style = EcoType.Chip.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight(800),
                    letterSpacing = 0.sp
                ),
                color = c.blue,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * 待确认椭圆：宽 109、跨双列两行；双层虚线环 + 收据/对勾图标 + 笔数 + 待确认。
 * 有未确认时用陶土（clay，真语义）；清零时用**次要色**（装饰性，随主题）。
 */
@Composable
private fun PendingPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    val alert = count > 0
    // 原型 pendingWiggle：每 4.5s 末尾摇一下，只在有待确认时
    val wiggleT = rememberInfiniteTransition(label = "pending-wiggle")
    val wiggle by wiggleT.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 4500
                0f at 0
                0f at 3960
                -11f at 4095
                9f at 4275
                -4f at 4410
                0f at 4500
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "pending-wiggle-angle",
    )
    val shape: Shape = BookmarkShape
    val bgTop: Color
    val bgBottom: Color
    val borderColor: Color
    val fg: Color
    val plate: Color
    val innerShade: Color
    if (alert) {
        // 待确认：陶土语义色（永远不变），浅色下从语义色提亮，不再写死米/橙
        bgTop = if (c.isDark) lerp(c.claySoft, Color.White, 0.10f) else lerp(c.claySoft, Color.White, 0.45f)
        bgBottom = if (c.isDark) lerp(c.claySoft, c.surface, 0.18f) else lerp(c.claySoft, c.surface, 0.30f)
        borderColor = if (c.isDark) c.clay.copy(alpha = 0.42f) else lerp(c.clay, c.surface, 0.62f)
        fg = if (c.isDark) c.clayHi else lerp(c.clay, Color.Black, 0.22f)
        plate = lerp(c.clay, Color.Black, 0.05f)
        innerShade = lerp(c.clay, Color.Black, 0.30f)
    } else {
        // 无待确认：用**次要色**（不是语义绿 jade）——这张卡是装饰性的，
        // 之前写死玉绿导致换任何主题它都还是绿的。
        bgTop = if (c.isDark) lerp(c.secondSoft, Color.White, 0.05f) else lerp(c.secondSoft, Color.White, 0.55f)
        bgBottom = if (c.isDark) lerp(c.secondSoft, c.surface, 0.16f) else lerp(c.secondSoft, c.surface, 0.40f)
        borderColor = if (c.isDark) c.second.copy(alpha = 0.36f) else lerp(c.second, c.surface, 0.66f)
        fg = if (c.isDark) c.second else lerp(c.second, Color.Black, 0.30f)
        plate = lerp(c.second, c.surface, 0.45f)
        innerShade = lerp(c.second, Color.Black, 0.36f)
    }

    Box(
        modifier
            .shadow(
                elevation = 2.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Transparent,
                spotColor = fg.copy(alpha = if (c.isDark) 0.34f else 0.18f),
            )
            // 书签的“厚度”：同形状底盘下移 3.5dp
            .cardPlate(shape, plate.copy(alpha = if (c.isDark) 0.80f else 0.70f), offsetX = 1.7.dp, offsetY = 3.8.dp)
            .clip(shape)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(bgTop, bgBottom),
                        center = Offset(size.width * 0.24f, size.height * 0.12f),
                        radius = maxOf(size.width, size.height),
                    ),
                )
            }
            .cardInset(
                shape = shape,
                topColor = Color.White.copy(alpha = if (c.isDark) 0.16f else 0.74f),
                topHeight = 3.dp,
                bottomColor = innerShade.copy(alpha = if (c.isDark) 0.22f else 0.16f),
                band = 15.dp,
            )
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick),
    ) {
        // 内圈虚线：同一书签形状内缩 6dp，跟着缺口走
        Canvas(Modifier.matchParentSize()) {
            val stroke = 1.dp.toPx()
            val inset = 6.dp.toPx()
            val inner = Path().apply {
                addOutline(
                    BookmarkShape.createOutline(
                        Size(size.width - inset * 2f, size.height - inset * 2f),
                        LayoutDirection.Ltr,
                        this@Canvas,
                    ),
                )
            }
            translate(inset, inset) {
                drawPath(
                    path = inner,
                    color = fg.copy(alpha = 0.28f),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(3.dp.toPx(), 4.dp.toPx()),
                        ),
                    ),
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 9.dp, vertical = 11.dp)
                // 卡片底部有书签凹口，几何中心偏高一点，整组上移 7dp 视觉更居中
                .offset(y = (-7).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                if (alert) EcoIcons.Receipt else EcoIcons.Check,
                contentDescription = null,
                tint = fg.copy(alpha = 0.92f),
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer { if (alert) rotationZ = wiggle },
            )
            if (alert) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "%02d".format(count),
                    style = EcoType.PendingCount,
                    color = fg,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(1.dp))
            Text(
                "待确认",
                style = EcoType.RowTrail.copy(fontSize = 15.5.sp, fontWeight = FontWeight(740)),
                color = fg.copy(alpha = 0.86f),
                maxLines = 1,
            )
        }
    }
}

// ---------- 3. 中排：分类卡 / 每月首日（常驻滚轮）/ 设置 ----------

@Composable
private fun MidRow(
    categories: List<CategoryEntity>,
    monthStartDay: Int,
    onOpenCategories: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMonthStart: () -> Unit,
) {
    val tiles = remember(categories) {
        val expense = categories.filter { it.type == TxType.EXPENSE }
        (if (expense.isNotEmpty()) expense else categories).take(4)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EcoArrival(
            randomDelay = true,
            delayMillis = 350,
            turn = 2.4f,
            modifier = Modifier
                .weight(1f)
                .height(MID_HEIGHT),
        ) {
            CategoryCard(
                tiles = tiles,
                onClick = onOpenCategories,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            Modifier.width(UTILITY_WIDTH),
            verticalArrangement = Arrangement.spacedBy(MID_GAP),
        ) {
            EcoArrival(randomDelay = true, delayMillis = 420, turn = -2.6f) {
                MonthStartCard(
                    monthStartDay = monthStartDay,
                    onOpen = onOpenMonthStart,
                    modifier = Modifier.fillMaxWidth().height(START_CARD_HEIGHT),
                )
            }
            EcoArrival(randomDelay = true, delayMillis = 490, turn = 2.2f) {
                SettingsCard(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().height(SETTINGS_CARD_HEIGHT),
                )
            }
        }
    }
}

/** 分类卡：次要色渐变、圆角 25/9/25/9；标题 + 4 个真实分类图标瓦片，整卡点按进分类管理。 */
@Composable
private fun CategoryCard(
    tiles: List<CategoryEntity>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(25.dp, 9.dp, 25.dp, 9.dp)
    Box(
        modifier
            .shadow(
                elevation = 2.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Transparent,
                spotColor = c.second.copy(alpha = if (c.isDark) 0.34f else 0.16f),
            )
            .cardPlate(shape, c.second.copy(alpha = if (c.isDark) 0.34f else 0.26f), offsetX = 1.5.dp, offsetY = 3.4.dp)
            .clip(shape)
            .background(softGradient(c, c.secondSoft, 0.92f, 0.62f))
            .cardInset(
                shape = shape,
                topColor = Color.White.copy(alpha = if (c.isDark) 0.16f else 0.85f),
                topHeight = 1.5.dp,
                bottomColor = c.second.copy(alpha = if (c.isDark) 0.22f else 0.14f),
                band = 14.dp,
            )
            .border(1.dp, c.second.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick),
    ) {
        // 右上小装饰环（opacity .3）
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = c.second.copy(alpha = if (c.isDark) 0.16f else 0.22f),
                radius = minOf(size.width, size.height) * 0.42f,
                center = Offset(size.width * 0.97f, size.height * 0.02f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Column(
            // 原型 justify-content:space-evenly：标题/图标上下留白相等，不再是"标题贴顶、图标贴底"
            Modifier.fillMaxSize().padding(start = 9.dp, end = 9.dp, top = 15.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                "分类设置",
                style = EcoType.CatAmount.copy(
                    fontSize = 19.sp,
                    fontWeight = FontWeight(790),
                    letterSpacing = 0.66.sp,
                    lineHeight = 24.sp,
                ),
                color = c.ink,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // 瓦片宽度随可用宽度自适应（窄屏 30dp、常规 42dp），保证 4 个永远排得下
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 5.dp
                val tile = ((maxWidth - gap * 3f) / 4f).coerceIn(30.dp, 42.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tiles.forEach { category ->
                        EcoCategoryTile(
                            categoryName = category.name,
                            icon = category.icon,
                            size = tile,
                            height = 43.dp,
                            radius = 12.dp,
                            glyphScale = 0.57f,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「每月首日」卡：圆角 12/28/12/28、蓝色系渐变；日历图标 + 标题 + 当前值 + chevron。
 *
 * **滚轮常驻在卡内**（3 行 / 96x84）：点击 → 原位浮出滚轮卡选择；长按 → 不松手直接拖动，
 * 抬手后由 WheelPicker 自己惯性吸附并回写 —— 与改造前完全一致，只是从"整行右侧"变成"卡片内下方"。
 * 卡面不用 clip（用 background(brush, shape) 画圆角），长按展开的滚轮卡可以溢出卡边不被裁掉。
 */
@Composable
private fun MonthStartCard(
    monthStartDay: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    // 每月首日：圆角方形
    val shape = RoundedCornerShape(24.dp)
    val dayLabel = "%02d 日".format(monthStartDay)

    Box(
        modifier
            .shadow(
                elevation = 2.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Transparent,
                spotColor = c.ink.copy(alpha = if (c.isDark) 0.34f else 0.16f),
            )
            .cardPlate(shape, c.blue.copy(alpha = if (c.isDark) 0.34f else 0.26f), offsetX = 1.5.dp, offsetY = 3.4.dp)
            .clip(shape)
            .background(softGradient(c, c.blueSoft, 0.72f, 0.5f), shape)
            .cardInset(
                shape = shape,
                topColor = Color.White.copy(alpha = if (c.isDark) 0.15f else 0.80f),
                topHeight = 1.5.dp,
                bottomColor = c.blue.copy(alpha = if (c.isDark) 0.20f else 0.13f),
                band = 13.dp,
            )
            .border(1.dp, c.blue.copy(alpha = 0.16f), shape)
            .clickable(onClick = onOpen),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = c.blue.copy(alpha = if (c.isDark) 0.14f else 0.18f),
                radius = minOf(size.width, size.height) * 0.46f,
                center = Offset(size.width * 0.98f, size.height * 0.04f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 11.dp, top = 12.dp, bottom = 11.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    EcoIcons.Calendar,
                    contentDescription = null,
                    tint = c.blue,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "每月首日",
                        style = EcoType.Chip.copy(fontSize = 9.5.sp, fontWeight = FontWeight(730), letterSpacing = 0.sp),
                        color = c.muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        dayLabel,
                        style = EcoType.CatAmount.copy(fontSize = 21.sp, fontWeight = FontWeight(780), lineHeight = 25.sp),
                        color = c.ink,
                        maxLines = 1,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Icon(
                    EcoIcons.ChevDown,
                    contentDescription = "选择每月首日",
                    tint = c.quiet,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * 「设置」卡：圆角 28/12/28/12、surface→暖金浅底渐变，齿轮 + 设置。
 *
 * 暖金是**产品口径指定的固定色**，不随主题变（与打赏的陶红同理）。
 * 注意阴影/图标也要用 amber 同一族——之前阴影用 jade、卡面用 amber，看着是两张卡拼的。
 */
@Composable
private fun SettingsCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    // 设置：横向胶囊（上下直边、左右半圆）
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .shadow(
                elevation = 2.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Transparent,
                spotColor = c.amber.copy(alpha = if (c.isDark) 0.34f else 0.16f),
            )
            .cardPlate(shape, c.amber.copy(alpha = if (c.isDark) 0.34f else 0.26f), offsetX = 1.5.dp, offsetY = 3.4.dp)
            .clip(shape)
            .background(softGradient(c, c.amberSoft, 0.5f, 0.42f))
            .cardInset(
                shape = shape,
                topColor = Color.White.copy(alpha = if (c.isDark) 0.14f else 0.78f),
                topHeight = 1.5.dp,
                bottomColor = c.amber.copy(alpha = if (c.isDark) 0.20f else 0.13f),
                band = 12.dp,
            )
            .border(1.dp, c.rule, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {  
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = EcoIcons.Gear,
                contentDescription = null,
                tint = c.amber,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(18.dp)
            )

            Text(
                text = "设置",
                style = EcoType.CatAmount.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight(750),
                    lineHeight = 20.sp
                ),
                color = c.ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )

            Icon(
                imageVector = EcoIcons.Chev,
                contentDescription = null,
                tint = c.quiet,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(13.dp)
            )
        }
    }
}

// ---------- 4. 底排：分期·周期 / 主题色 / 备份还原 ----------

@Composable
private fun BottomRow(
    onOpenPlan: () -> Unit,
    onOpenBackup: () -> Unit,
    onTheme: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        val gap = 8.dp

        // 两个圆始终相同大小；窄屏自动缩小，给「分期·周期」胶囊保留至少约 94dp
        val circleSize = (
            (maxWidth - 94.dp - gap * 3f) / 3f
        ).coerceIn(48.dp, 66.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BOTTOM_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // 1. 分期·周期：胶囊形
            EcoArrival(
                randomDelay = true,
                delayMillis = 560,
                turn = 2.2f,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                PlanTile(
                    onClick = onOpenPlan,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2. 主题色
            EcoArrival(
                randomDelay = true,
                delayMillis = 610,
                turn = -2.4f,
                modifier = Modifier.size(circleSize)
            ) {
                AccentTile(
                    onClick = onTheme,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 3. 备份还原
            EcoArrival(
                randomDelay = true,
                delayMillis = 650,
                turn = 2.4f,
                modifier = Modifier.size(circleSize)
            ) {
                BackupTile(
                    onClick = onOpenBackup,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** 分期·周期：横向主 CTA 胶囊（沿用原导入账单胶囊的视觉）。 */
@Composable
private fun PlanTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(50),
                clip = false,
                ambientColor = Color.Transparent,
                spotColor = c.heroB.copy(alpha = 0.4f),
            )
            .clip(RoundedCornerShape(50))
            .background(Brush.linearGradient(listOf(c.heroC, c.heroB), start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(EcoIcons.Clock, contentDescription = null, tint = c.onHero, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "分期·周期",
                style = EcoType.Chip.copy(fontSize = 12.5.sp, fontWeight = FontWeight(800), letterSpacing = 0.sp),
                color = c.onHero,
                maxLines = 1,
            )
        }
    }
}

/** 备份还原：hero 渐变圆钮，下载图标 + 「备份还原」。 */
@Composable
private fun BackupTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .shadow(
                elevation = 3.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Transparent,
                spotColor = c.heroB.copy(alpha = 0.42f),
            )
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c.heroC, c.heroB), start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                EcoIcons.Download,
                contentDescription = "备份还原",
                tint = c.onHero,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "备份还原",
                style = EcoType.Micro.copy(fontSize = 8.5.sp, fontWeight = FontWeight(800), letterSpacing = 0.sp),
                color = c.onHero,
                maxLines = 1,
            )
        }
    }
}

/** 存款入口：本月实际新增 / 每月目标，点击进入存款页。 */
@Composable
private fun DepositEntry(
    goalCents: Long,
    savingCents: Long,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    EcoPane(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EcoIconTile(
                icon = EcoIcons.Wallet,
                tint = c.jade,
                size = 34.dp,
                radius = 11.dp,
                iconSize = 17.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "存款",
                    style = EcoType.RowTitle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "本月新增 ${Money.formatYuanGrouped(savingCents)}" +
                        if (goalCents > 0L) " / 目标 ${Money.formatYuanGrouped(goalCents)}" else " · 未设目标",
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
                    color = c.muted,
                    maxLines = 1,
                )
            }
            Icon(
                EcoIcons.Chev,
                contentDescription = null,
                tint = c.quiet,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 主题按钮：彩虹渐变底 + 白色画笔，**不叠任何白色圆底**。
 * 按用户要求只负责主题色，不再充当「更多」入口。
 */
@Composable
private fun AccentTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .shadow(1.5.dp, CircleShape, ambientColor = Color.Transparent, spotColor = c.ink.copy(alpha = 0.24f))
            .cardPlate(CircleShape, c.ink.copy(alpha = 0.45f), offsetX = 1.5.dp, offsetY = 3.4.dp)
            .clip(CircleShape)
            .background(
                // 彩虹是「主题色」这个功能本身的语义（表示多套配色可选），
                // 但色相顺序改成与 EcoAccent 一致，避免固定青绿那一段抢眼
                Brush.linearGradient(
                    listOf(
                        Color(0xFFD977AB), Color(0xFFEC9C77), Color(0xFFEFCE76),
                        Color(0xFF6A9DD2), Color(0xFFA582C9), Color(0xFF7BC8AA),
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .cardInset(
                shape = CircleShape,
                topColor = Color.White.copy(alpha = 0.40f),
                topHeight = 2.dp,
                bottomColor = c.ink.copy(alpha = 0.22f),
                band = 14.dp,
            )
            .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            EcoIcons.Brush,
            contentDescription = "主题色",
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 「每月首日」选择：保留项目原来的滚轮交互（上下拖动、惯性吸附、逐项回调），
 * 但改成卡内点击后从底部弹出；视觉上不再把三行滚轮常驻在 102dp 的卡里。
 */
@Composable
private fun MonthStartSheet(
    monthStartDay: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = remember { MonthPeriod.startDayOptions() }
    EcoSheet(
        onDismiss = onDismiss,
        title = "每月首日",
        // 滚轮自己处理竖向拖拽；不能让外层 verticalScroll 抢走手势
        scrollable = false,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(132.dp),
                contentAlignment = Alignment.Center,
            ) {
                WheelPicker(
                    items = options,
                    selectedIndex = MonthPeriod.startDayIndex(monthStartDay),
                    onSelected = { onPick(MonthPeriod.startDayAtIndex(it)) },
                    label = "每月首日",
                    itemHeight = 32.dp,
                    rows = 3,
                    expandedRows = 3,
                    trigger = WheelTrigger.DRAG,
                    updateWhileScrolling = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            EcoPrimaryButton("完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 主题按钮专用弹层：只包含主题色，不再混入「更多」。 */
@Composable
private fun ThemeSheet(
    accent: EcoAccent,
    onPickAccent: (EcoAccent) -> Unit,
    onDismiss: () -> Unit,
) {
    EcoSheet(onDismiss = onDismiss, title = "主题色") {
        ThemeRow(selected = accent, onPick = onPickAccent)
    }
}

/**
 * 主题色：7 个圆形色板（EcoAccent 七套，含「纯白」），选中项描环，点一下即时生效。
 *
 * 7 个一行在 390dp 下会把标签挤断，所以按 4 列分两行；色点直径随列宽自适应。
 */
@Composable
private fun ThemeRow(selected: EcoAccent, onPick: (EcoAccent) -> Unit) {
    val c = MaterialTheme.eco
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        EcoAccent.entries.chunked(4).forEach { rowEntries ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowEntries.forEach { entry ->
                    val on = entry == selected
                    // 色点要显示「实际生效」的主色（eco 里做过整体提亮），不能用原始 Family 值
                    val dot = ecoColors(c.isDark, entry).blue
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(entry) }
                            .padding(vertical = 4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(if (on) 44.dp else 40.dp)
                                .clip(CircleShape)
                                .background(dot)
                                .then(
                                    if (on) {
                                        Modifier.border(2.dp, c.blue, CircleShape)
                                    } else {
                                        Modifier.border(1.dp, c.rule, CircleShape)
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) {
                                Icon(
                                    EcoIcons.Check,
                                    contentDescription = null,
                                    // 浅底主题（白/暖金）用深色勾，深底用白勾
                                    tint = contentColorOn(dot),
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            entry.label,
                            style = EcoType.Pill.copy(fontSize = 10.5.sp),
                            color = if (on) c.ink else c.muted,
                            maxLines = 1,
                        )
                    }
                }
                // 末行不足 4 个时补占位，保持列宽一致
                repeat(4 - rowEntries.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------- 6. 弹层：货币 / 打赏 / 关于 ----------

/** 货币符号选择（9 种）：只改显示符号，不做换算。 */
@Composable
private fun CurrencySheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.eco
    EcoSheet(
        onDismiss = onDismiss,
        title = "货币符号",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Money.CURRENCIES.forEach { (symbol, name) ->
                val selected = symbol == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) c.blueSoft else Color.Transparent)
                        .clickable { onPick(symbol) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        symbol,
                        style = EcoType.CatAmount,
                        color = if (selected) c.blueDeep else c.ink,
                        modifier = Modifier.width(52.dp),
                        maxLines = 1,
                    )
                    Text(
                        name,
                        style = EcoType.RowTitle,
                        color = if (selected) c.blueDeep else c.muted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (selected) {
                        Icon(
                            EcoIcons.Check,
                            contentDescription = null,
                            tint = c.blue,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ---------- 公用 ----------

/** 卡片底：surface → 语义色的斜向渐变（深色模式下压暗，避免浅色块在暗色纸面上过亮）。 */
private fun softGradient(c: EcoColors, tint: Color, lightAmount: Float, darkAmount: Float): Brush =
    Brush.linearGradient(
        colors = listOf(c.surface, lerp(c.surface, tint, if (c.isDark) darkAmount else lightAmount)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

/** 货币名称（找不到时只显示符号本身） */
private fun currencyName(symbol: String): String =
    Money.CURRENCIES.firstOrNull { it.first == symbol }?.second ?: ""
