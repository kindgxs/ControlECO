package com.controleco.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.CategoryTree
import com.controleco.app.data.SubcategoryRollupMode
import com.controleco.app.data.budget.Budget
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.db.AccountBalanceRow
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import com.controleco.app.data.model.AssetAccountSnapshot
import com.controleco.app.data.model.AssetMath
import com.controleco.app.data.model.AssetSummary
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.rememberReducedMotion
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.eco.EcoBudgetSheet
import com.controleco.app.ui.eco.EcoArrival
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoCountUpMoney
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoMicro
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoProgressBar
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.LocalAnimationsEnabled
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.theme.EcoColors
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val MODE_COUNT = 3
private const val DRAG_FLIP_THRESHOLD_DEG = 45f
private const val DRAG_FULL_TURN_WIDTH_FRACTION = 0.3f
private const val FLICK_WIDTHS_PER_SECOND = 1.1f

/** 分类条数据：预算/已花/剩余 + 图标规格 + 分类色 */
internal data class CategoryBarData(
    val name: String,
    val icon: String,
    val budgetCents: Long,
    val spentCents: Long,
    val remainingCents: Long,
    val ratio: Float,
    val tone: Budget.Tone,
    val isOther: Boolean = false,
)

private data class HomeTrend(val spent: List<Float>, val deposit: List<Float>)

/**
 * 分类环数据（旧首页模式①的纯计算，保留给单测与历史口径）。
 * 新分类条 UI 用 [CategoryBarData]；两者口径一致（含"其他"聚合）。
 */
internal data class CategoryRingData(
    val name: String,
    val remainingCents: Long,
    val ratio: Float,
    val tone: Budget.Tone,
    val severity: Float,
    val isOther: Boolean,
)

@Composable
fun HomeScreen(
    vm: LedgerViewModel,
    onOpenCategories: () -> Unit,
) {
    val budgetCents by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val spentCents by vm.homeSpent.collectAsStateWithLifecycle()
    val todaySpentCents by vm.homeTodaySpent.collectAsStateWithLifecycle()
    val categoryBudgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val categorySums by vm.homeCategorySums.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val assetSummary by vm.assetSummary.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val accountBalances by vm.accountBalances.collectAsStateWithLifecycle(initialValue = emptyList())
    val assetCardPick by vm.assetCardPick.collectAsStateWithLifecycle()
    val monthStartDay by vm.monthStartDay.collectAsStateWithLifecycle()
    val dailyBudgetMode by vm.dailyBudgetMode.collectAsStateWithLifecycle()
    val dailyBudgetCents by vm.dailyBudgetCents.collectAsStateWithLifecycle()
    val periodRows by vm.homePeriodRows.collectAsStateWithLifecycle()

    val spentReady = spentCents
    val todaySpentReady = todaySpentCents
    val sumsReady = categorySums
    val asset = assetSummary
    if (spentReady == null || todaySpentReady == null || sumsReady == null || asset == null) return
    val depositReady = asset.depositCents

    var showBudgetSheet by remember { mutableStateOf(false) }
    var todayState by remember { mutableStateOf(LocalDate.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                todayState = LocalDate.now()
                vm.refreshToday()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val today = todayState
    val period = remember(today, monthStartDay) { MonthPeriod.of(today, monthStartDay) }
    val snapshot = remember(budgetCents, spentReady, today, todaySpentReady, period, dailyBudgetMode, dailyBudgetCents) {
        Budget.compute(
            budgetCents,
            spentReady,
            today,
            todaySpentReady,
            period = period,
            dailyBudgetCents = if (dailyBudgetMode) dailyBudgetCents else 0L,
        )
    }
    val s = snapshot
    val reducedMotion = rememberReducedMotion()
    // 设置里关掉「显示动画」后等同于减少动效：翻转/环/趋势图都直接到位
    val motionOff = reducedMotion || !LocalAnimationsEnabled.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val flip = remember { Animatable(0f) }
    val density = LocalDensity.current.density
    var dragging by remember { mutableStateOf(false) }
    var dragRotation by remember { mutableFloatStateOf(0f) }

    fun wrap(target: Int): Int = ((target % MODE_COUNT) + MODE_COUNT) % MODE_COUNT

    val flipTo: (Int) -> Unit = { target ->
        val t = wrap(target)
        if (!flip.isRunning && t != mode) {
            scope.launch {
                if (motionOff) {
                    mode = t
                } else {
                    val forward = wrap(t - mode)
                    val sign = if (forward == MODE_COUNT - 1) 1f else -1f
                    flip.animateTo(90f * sign, tween(150, easing = FastOutSlowInEasing))
                    mode = t
                    flip.snapTo(-90f * sign)
                    flip.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
                }
            }
        }
    }
    val latestFlipTo by rememberUpdatedState(flipTo)
    val advance: () -> Unit = { latestFlipTo(mode + 1) }

    val onDragEnd: (rotation: Float, velocityX: Float, widthPx: Float) -> Unit = { rotation, velocityX, widthPx ->
        if (dragging) {
            scope.launch {
                flip.snapTo(rotation)
                dragging = false
                val flicked = abs(velocityX) >= widthPx * FLICK_WIDTHS_PER_SECOND
                val turned = abs(rotation) >= DRAG_FLIP_THRESHOLD_DEG
                val next = if (flicked && !turned) velocityX < 0f else rotation < 0f
                if (motionOff) {
                    if (flicked || turned) mode = wrap(mode + if (next) 1 else -1)
                    flip.snapTo(0f)
                } else if (flicked || turned) {
                    val sign = if (next) -1f else 1f
                    flip.animateTo(90f * sign, tween(130, easing = FastOutSlowInEasing))
                    mode = wrap(mode + if (next) 1 else -1)
                    flip.snapTo(-90f * sign)
                    flip.animateTo(0f, tween(130, easing = FastOutSlowInEasing))
                } else {
                    flip.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
                }
            }
        }
    }
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    val bars = remember(categoryBudgets, sumsReady, categories, budgetCents, spentReady, period, today) {
        // 预算只挂在一级分类上：二级分类的支出先并回所属一级分类再算预算
        val rolled = CategoryTree.rollup(sumsReady, categories, SubcategoryRollupMode.BY_PARENT)
        buildCategoryBars(
            categoryBudgets,
            rolled,
            categories,
            budgetCents,
            spentReady,
            thresholds = s?.thresholds ?: Budget.thresholds(1, 1),
        )
    }
    val trend = remember(periodRows, period, depositReady) {
        buildHomeTrend(periodRows.orEmpty(), period, depositReady)
    }
    val categoryBudgetTotal = remember(categoryBudgets) {
        categoryBudgets.values.filter { it > 0L }.sum()
    }

    val monthCn = chineseMonth(period.start.monthValue)
    val monthEn = period.start.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)).uppercase(Locale.ENGLISH)
    val modeLow = when (mode) {
        0 -> " / 预算"
        1 -> " / 分类"
        else -> " / 资产"
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "${period.start.year} / $monthEn 账本",
            title = monthCn,
            low = modeLow,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val rot = if (dragging) dragRotation else flip.value
                        rotationY = rot
                        val p = (abs(rot) / 90f).coerceIn(0f, 1f)
                        cameraDistance = (14f - 8f * p) * density
                        val scale = 1f - 0.06f * p
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.2f * p
                    }
                    .pointerInput(Unit) { detectTapGestures { advance() } }
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = {
                                if (!flip.isRunning) {
                                    dragging = true
                                    dragRotation = 0f
                                    tracker.resetTracking()
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (dragging) {
                                    change.consume()
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    val k = 90f / (size.width * DRAG_FULL_TURN_WIDTH_FRACTION)
                                    dragRotation = (dragRotation + dragAmount * k).coerceIn(-90f, 90f)
                                }
                            },
                            onDragEnd = {
                                latestOnDragEnd(dragRotation, tracker.calculateVelocity().x, size.width.toFloat())
                            },
                            onDragCancel = {
                                if (dragging) {
                                    dragging = false
                                    dragRotation = 0f
                                }
                            },
                        )
                    },
            ) {
                when (mode) {
                    0 -> if (s != null) {
                        ModeRemaining(s, period, today)
                    } else {
                        ModeNeedsBudget(dailyMode = dailyBudgetMode, title = "预算", onSetBudget = { showBudgetSheet = true })
                    }
                    1 -> if (s != null) {
                        ModeCategories(
                            totalRemaining = s.remainingCents,
                            budgetCents = s.budgetCents,
                            categoryBudgetCents = categoryBudgetTotal,
                            period = period,
                            today = today,
                            bars = bars,
                        )
                    } else {
                        ModeNeedsBudget(dailyMode = dailyBudgetMode, title = "分类预算", onSetBudget = { showBudgetSheet = true })
                    }
                    else -> ModeSpentAndDeposit(
                        spentCents = spentReady,
                        asset = asset,
                        accounts = accounts,
                        balances = accountBalances,
                        cardPick = assetCardPick,
                        onPickCard = { groupId, accountId -> vm.setAssetCardPick(groupId, accountId) },
                    )
                }
            }
            if (mode == 1 && s != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 0.dp),
                ) {
                    ManageButton(onClick = onOpenCategories)
                }
            }
        }
        ModeDots(current = mode, onSelect = { flipTo(it) })
        Spacer(Modifier.height(ecoBottomContentPadding(extra = 4.dp)))
    }

    if (showBudgetSheet) {
        EcoBudgetSheet(
            dailyMode = dailyBudgetMode,
            currentCents = if (dailyBudgetMode) dailyBudgetCents else budgetCents,
            onSave = {
                if (dailyBudgetMode) vm.setDailyBudget(it) else vm.setMonthlyBudget(it)
                showBudgetSheet = false
            },
            onDismiss = { showBudgetSheet = false },
        )
    }
}

@Composable
private fun ModeRemaining(s: Budget.Snapshot, period: MonthPeriod, today: LocalDate) {
    val c = MaterialTheme.eco
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 原型环径 306：只受宽度限制，不再被高度压小（小屏按 84% 宽兜底）
        val ringSize = min(maxWidth.value * 0.84f, 306f).coerceAtLeast(176f).dp
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 环在「顶部」与「本日剩余」之间居中：上下留白相等
            Spacer(Modifier.weight(1f))
            BudgetRing(s = s, period = period, today = today, size = ringSize)
            Spacer(Modifier.weight(0.35f))
            TodayCard(s = s)
            Spacer(Modifier.weight(0.93f))
        }
    }
}

@Composable
private fun BudgetRing(s: Budget.Snapshot, period: MonthPeriod, today: LocalDate, size: Dp) {
    val c = MaterialTheme.eco
    val tone = when (s.tone) {
        Budget.Tone.OK -> c.jade
        Budget.Tone.MID -> c.amber
        Budget.Tone.LOW -> c.clay
    }
    val toneHi = when (s.tone) {
        Budget.Tone.OK -> c.jadeHi
        Budget.Tone.MID -> c.amberHi
        Budget.Tone.LOW -> c.clayHi
    }
    val ratio = s.remainingRatio.coerceIn(0f, 1f)
    val daysLeft = period.daysLeft(today)
    val timeRatio = (daysLeft.toFloat() / period.days.coerceAtLeast(1)).coerceIn(0f, 1f)
    // 原型：液面 1.1s、时间环稍慢地画出来（每次进入首页都从 0 重播）
    val drawn = remember { Animatable(0f) }
    val timeDrawn = remember { Animatable(0f) }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(ratio, animationsEnabled) {
        if (!animationsEnabled) {
            drawn.snapTo(ratio)
            return@LaunchedEffect
        }
        drawn.animateTo(
            ratio,
            tween(1100, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)),
        )
    }
    LaunchedEffect(timeRatio, animationsEnabled) {
        if (!animationsEnabled) {
            timeDrawn.snapTo(timeRatio)
            return@LaunchedEffect
        }
        timeDrawn.animateTo(
            timeRatio,
            tween(1300, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)),
        )
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val u = this.size.minDimension / 320f
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val stroke = 34f * u
            val r = this.size.minDimension / 2f - stroke / 2f - 2f * u

            // 底部投影环（错位 3.5u）
            drawCircle(
                color = c.ink.copy(alpha = if (c.isDark) 0.24f else 0.07f),
                radius = r,
                center = Offset(cx, cy + 3.5f * u),
                style = Stroke(stroke),
            )
            // 主轨：偏玉色的浅轨（原型 track 不是灰蓝）
            drawCircle(
                color = lerp(c.track, c.jadeSoft, 0.55f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(stroke),
            )
            // 外描边 + 内亮线
            drawCircle(
                color = c.rule.copy(alpha = 0.8f),
                radius = r + 18.3f * u,
                center = Offset(cx, cy),
                style = Stroke(1.3f * u),
            )
            drawCircle(
                color = c.surface.copy(alpha = 0.9f),
                radius = r - 17.3f * u,
                center = Offset(cx, cy),
                style = Stroke(1.7f * u),
            )
            // 液面
            drawArc(
                // 原型是斜向线性渐变（budgetFill），sweep 会在 12 点留一道接缝
                brush = Brush.linearGradient(
                    colors = listOf(toneHi, tone, toneHi, tone),
                    start = Offset(cx - r, cy - r),
                    end = Offset(cx + r, cy + r),
                ),
                startAngle = -90f,
                sweepAngle = 360f * drawn.value,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // 时间细环（蓝色，弧长 = 本月还剩多少时间）
            val timeR = r - 27.5f * u
            drawCircle(
                color = c.blueSoft,
                radius = timeR,
                center = Offset(cx, cy),
                style = Stroke(7f * u),
            )
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(c.blueHi, c.blue, c.blueHi),
                    center = Offset(cx, cy),
                ),
                startAngle = -90f,
                sweepAngle = 360f * timeDrawn.value,
                useCenter = false,
                topLeft = Offset(cx - timeR, cy - timeR),
                size = Size(timeR * 2, timeR * 2),
                style = Stroke(width = 5.5f * u, cap = StrokeCap.Round),
            )
            // 虚线装饰
            drawCircle(
                color = c.rule.copy(alpha = 0.55f),
                radius = r - 37f * u,
                center = Offset(cx, cy),
                style = Stroke(
                    width = 1f * u,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f * u, 7f * u)),
                ),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("本月剩余", style = EcoType.MeterLabel, color = c.muted)
            Spacer(Modifier.height(4.dp))
            EcoCountUpMoney(
                cents = s.remainingCents,
                style = EcoType.MeterMoney,
                color = c.ink,
                widthLimit = size * 0.65f,
                minSize = 21.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${(s.remainingRatio * 100).roundToInt()}%",
                style = EcoType.Chip.copy(fontSize = 11.sp),
                color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "$daysLeft 天",
                style = EcoType.Pill.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = c.blueDeep,
            )
        }
    }
}

@Composable
private fun TodayCard(s: Budget.Snapshot) {
    val c = MaterialTheme.eco
    // 原型 starBreath 3.5s：星星在 .9→1.15 之间呼吸并轻微旋转
    val star = rememberInfiniteTransition(label = "today-star")
    val starPhase by star.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1750, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "today-star-phase",
    )
    val over = s.todayLeftCents < 0
    val tone = when {
        over -> c.clay
        s.todayLeftCents <= s.suggestedDailyCents * 0.25f -> c.amber
        else -> c.ink
    }
    Box(
        modifier = Modifier
            .fillMaxWidth(0.77f)
            .height(108.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (over) "今日已超支" else "本日剩余",
                    style = EcoType.AssetLabel.copy(fontSize = 10.sp),
                    color = c.muted,
                )
                Spacer(Modifier.height(4.dp))
                EcoCountUpMoney(
                    cents = if (over) -s.todayLeftCents else s.todayLeftCents,
                    style = EcoType.TodayAmount,
                    color = tone,
                    widthLimit = 240.dp,
                    minSize = 26.sp,
                )
                Spacer(Modifier.height(6.dp))
                Canvas(Modifier.width(92.dp).height(8.dp)) {
                    val mid = size.width / 2
                    drawLine(c.jade.copy(alpha = 0.55f), Offset(0f, mid), Offset(mid - 12f, mid), 1f)
                    drawLine(c.jade.copy(alpha = 0.55f), Offset(mid + 12f, mid), Offset(size.width, mid), 1f)
                    drawCircle(c.jade.copy(alpha = 0.7f), radius = 2.5f, center = Offset(mid, mid), style = Stroke(1f))
                }
            }
            Icon(
                EcoIcons.Spark,
                contentDescription = null,
                tint = c.jade.copy(alpha = 0.63f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 22.dp)
                    .size(16.dp)
                    .graphicsLayer {
                        alpha = 0.46f + 0.48f * starPhase
                        rotationZ = -8f + 16f * starPhase
                        val sc = 0.90f + 0.25f * starPhase
                        scaleX = sc
                        scaleY = sc
                    },
            )
        }
    }
}

@Composable
private fun ModeCategories(
    totalRemaining: Long,
    budgetCents: Long,
    categoryBudgetCents: Long,
    period: MonthPeriod,
    today: LocalDate,
    bars: List<CategoryBarData>,
) {
    val c = MaterialTheme.eco
    val allocatableCents = budgetCents - categoryBudgetCents
    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分类剩余", style = EcoType.AssetLabel.copy(fontSize = 10.sp), color = c.muted)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "分类预算 ${Money.formatYuanGroupedFixed(categoryBudgetCents)} · " +
                            "剩余可分配 ${Money.formatYuanGroupedFixed(allocatableCents)}",
                        style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                        color = if (allocatableCents < 0L) c.clay else c.quiet,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
                EcoCountUpMoney(
                    cents = totalRemaining,
                    style = EcoType.CatTotal,
                    color = c.ink,
                    widthLimit = 250.dp,
                    minSize = 22.sp,
                    textAlign = TextAlign.Start,
                )
            }
            Spacer(Modifier.width(54.dp))
        }
        Spacer(Modifier.height(10.dp))
        MonthProgress(period, today)
        Spacer(Modifier.height(8.dp))
        val scrollable = bars.size >= 6
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(bottom = 8.dp),
            verticalArrangement = if (scrollable) Arrangement.Top else Arrangement.SpaceEvenly,
        ) {
            bars.forEachIndexed { index, bar ->
                EcoArrival(
                    delayMillis = 70 + index * 55,
                    turn = if (index % 2 == 0) 2.2f else -2.2f,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CategoryBarRow(bar, index = index, compact = bars.size >= 6, dense = bars.size >= 8)
                }
            }
            if (bars.isEmpty()) {
                Text(
                    "还没有分类预算。到「个人 → 分类」为支出分类设定预算后，这里会显示每条预算的剩余。",
                    style = EcoType.PageNote,
                    color = c.quiet,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun MonthProgress(period: MonthPeriod, today: LocalDate) {
    val c = MaterialTheme.eco
    val elapsed = period.daysElapsed(today)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EcoIconTile(icon = EcoIcons.Calendar, tint = c.blue, size = 30.dp, radius = 10.dp, iconSize = 16.dp, bare = true)
            Spacer(Modifier.width(10.dp))
            Text("本月进度", style = EcoType.RowTitle.copy(fontSize = 14.sp), color = c.muted)
            Spacer(Modifier.weight(1f))
            Text("$elapsed / ${period.days}", style = EcoType.RowTitle.copy(fontSize = 13.sp), color = c.ink2)
        }
        Spacer(Modifier.height(7.dp))
        EcoProgressBar(
            progress = elapsed.toFloat() / period.days.coerceAtLeast(1),
            height = 6.dp,
            track = c.track,
            fill = Brush.horizontalGradient(listOf(c.blue.copy(alpha = 0.31f), c.blue.copy(alpha = 0.55f))),
        )
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("$elapsed 天已过", style = EcoType.PageNote.copy(fontSize = 8.5.sp), color = c.quiet)
            Spacer(Modifier.weight(1f))
            Text("剩余 ${period.daysLeft(today)} 天", style = EcoType.PageNote.copy(fontSize = 8.5.sp), color = c.quiet)
        }
    }
}

@Composable
private fun CategoryBarRow(bar: CategoryBarData, index: Int, compact: Boolean, dense: Boolean) {
    val c = MaterialTheme.eco
    val tint = if (bar.isOther) {
        c.amber
    } else {
        categoryColor(MaterialTheme.accBook.categoryPalette, bar.name)
    }
    val over = bar.remainingCents < 0
    val amountColor = when {
        over -> c.clay
        bar.ratio < 0.35f -> c.amber
        else -> c.jade
    }
    val titleSize = when {
        dense -> 13.5.sp
        compact -> 14.sp
        else -> 14.5.sp
    }
    val iconSize = when {
        dense -> 23.dp
        compact -> 25.dp
        else -> 27.dp
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) 58.dp else if (compact) 64.dp else 70.dp)
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bar.isOther) {
                EcoIconTile(
                    icon = EcoIcons.Spark,
                    tint = c.amber,
                    size = iconSize,
                    radius = iconSize * 0.33f,
                    iconSize = iconSize * 0.58f,
                    bare = true,
                )
            } else {
                EcoCategoryTile(
                    categoryName = bar.name,
                    icon = bar.icon,
                    size = iconSize,
                    radius = iconSize * 0.33f,
                    bare = true,
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                bar.name,
                style = EcoType.RowTitle.copy(fontSize = titleSize, fontWeight = FontWeight.Bold),
                color = if (over) c.clay else c.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (bar.budgetCents <= 0) {
                Text("未设预算", style = EcoType.Pill.copy(fontSize = 11.sp), color = c.quiet)
            } else {
                EcoCountUpMoney(
                    cents = bar.remainingCents,
                    style = EcoType.CatAmount.copy(fontSize = if (dense) 14.5.sp else 16.5.sp),
                    color = amountColor,
                    modifier = Modifier.widthIn(max = 140.dp),
                    widthLimit = 140.dp,
                    minSize = 12.sp,
                    textAlign = TextAlign.End,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        if (bar.budgetCents > 0) {
            EcoProgressBar(
                progress = (bar.spentCents.toFloat() / bar.budgetCents).coerceIn(0f, 1f),
                height = 9.dp,
                track = c.track,
                fill = Brush.horizontalGradient(
                    if (over) {
                        listOf(c.clayHi, c.clay)
                    } else {
                        listOf(tint.copy(alpha = 0.58f), tint)
                    },
                ),
                animationDelayMillis = 120 + index * 55,
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(CircleShape)
                    .background(c.track.copy(alpha = 0.6f))
                    .border(1.dp, c.rule, CircleShape),
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "已花 ${Money.formatYuanGroupedFixed(bar.spentCents)}",
                style = EcoType.PageNote.copy(fontSize = if (dense) 8.sp else 9.sp),
                color = c.muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (bar.budgetCents > 0) "预算 ${Money.formatYuanGroupedFixed(bar.budgetCents)}" else "未设预算",
                style = EcoType.PageNote.copy(fontSize = if (dense) 8.sp else 9.sp),
                color = c.muted,
            )
        }
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    val c = MaterialTheme.eco
    Box(
        Modifier
            .size(35.dp)
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.rule, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(EcoIcons.Edit, contentDescription = "分类预算", tint = c.ink2, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ModeSpentAndDeposit(
    spentCents: Long,
    asset: AssetSummary,
    accounts: List<AccountEntity>,
    balances: List<AccountBalanceRow>,
    cardPick: Map<Long, Long>,
    onPickCard: (groupId: Long, accountId: Long) -> Unit,
) {
    val c = MaterialTheme.eco
    // 资产页可下拉：本月已花 → 净资产（存款 / 负债）→ 我的资产账户余额
    val snapshots = remember(accounts, balances) {
        accounts.map { account ->
            val row = balances.firstOrNull { it.accountId == account.id }
            AssetAccountSnapshot(
                id = account.id,
                sharedGroupId = account.sharedGroupId,
                initialBalanceCents = row?.initialBalanceCents ?: account.initialBalanceCents,
                flowCents = row?.signedSumCents ?: 0L,
            )
        }
    }
    // 共用额度组内的卡流水一致（扣卡 1 卡 2 同步）
    val displayFlows = remember(snapshots) { AssetMath.displayedFlows(snapshots) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AssetStage(
            label = "本月已花",
            amountCents = spentCents,
            color = c.ink,
            save = false,
            stageColor = c.amber,
            modifier = Modifier.fillMaxWidth().height(214.dp),
        )
        Row(
            Modifier
                .fillMaxWidth(0.88f)
                .height(26.dp)
                .align(Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(c.rule))
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(5.dp).clip(CircleShape).border(1.dp, c.ruleStrong, CircleShape))
            }
            Box(Modifier.weight(1f).height(1.dp).background(c.rule))
        }
        AssetStage(
            label = "净资产",
            amountCents = asset.netCents,
            color = if (asset.netCents < 0) c.clay else c.blue,
            save = true,
            stageColor = c.jade,
            modifier = Modifier.fillMaxWidth().height(214.dp),
        )

        // 存款 / 负债：净资产 = 存款 − 负债
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AssetMini("存款", asset.depositCents, c.jade, Modifier.weight(1f))
            AssetMini("负债", asset.liabilityCents, c.clay, Modifier.weight(1f))
        }

        Text("我的资产", style = EcoType.SectionTitle, color = c.ink)
        Spacer(Modifier.height(8.dp))
        if (accounts.isEmpty()) {
            Text("还没有账户", style = EcoType.PageNote, color = c.muted)
        } else {
            // 同一共用额度组的卡（同银行多卡共用欠款）只显示一张：流水同步、资产也只算一次。
            // 显示哪张由用户点这一行手动切换（选择记忆在设置里）。
            val visibleAccounts = remember(accounts, snapshots, cardPick) {
                val byId = accounts.associateBy { it.id }
                AssetMath.visibleAccountIds(snapshots, cardPick).mapNotNull { byId[it] }
            }
            EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    visibleAccounts.forEachIndexed { index, account ->
                        val bal = balances.firstOrNull { it.accountId == account.id }
                        val cents = (bal?.initialBalanceCents ?: account.initialBalanceCents) +
                            (displayFlows[account.id] ?: bal?.signedSumCents ?: 0L)
                        val groupMembers = remember(accounts, account.sharedGroupId) {
                            if (account.sharedGroupId == 0L) {
                                emptyList()
                            } else {
                                accounts.filter { it.sharedGroupId == account.sharedGroupId }
                            }
                        }
                        val cycle: (() -> Unit)? = if (groupMembers.size > 1) {
                            {
                                val at = groupMembers.indexOfFirst { it.id == account.id }
                                val next = groupMembers[(at + 1) % groupMembers.size]
                                onPickCard(account.sharedGroupId, next.id)
                            }
                        } else {
                            null
                        }
                        AssetAccountRow(
                            name = account.name,
                            cents = cents,
                            sharedCount = groupMembers.size,
                            onClick = cycle,
                        )
                        if (index != visibleAccounts.lastIndex) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 15.dp)
                                    .height(1.dp)
                                    .background(c.rule),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun AssetMini(label: String, cents: Long, color: Color, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Column(
        modifier
            .clip(RoundedCornerShape(15.dp))
            .background(c.surface2)
            .border(1.dp, c.rule, RoundedCornerShape(15.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(label, style = EcoType.AssetLabel, color = c.muted)
        Spacer(Modifier.height(3.dp))
        EcoAutoMoney(
            text = Money.formatYuanGroupedFixed(cents),
            style = EcoType.RowTitle.copy(fontSize = 17.sp, lineHeight = 21.sp),
            color = if (cents == 0L) c.ink2 else color,
            modifier = Modifier.fillMaxWidth(),
            minSize = 12.sp,
        )
    }
}

@Composable
private fun AssetAccountRow(
    name: String,
    cents: Long,
    sharedCount: Int = 0,
    onClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(categoryColor(MaterialTheme.accBook.categoryPalette, name.ifBlank { "账户" })),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            style = EcoType.RowTitle,
            color = c.ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (sharedCount > 1 && onClick != null) {
            Spacer(Modifier.width(6.dp))
            EcoPill("$sharedCount 张 · 切换", EcoPillTone.Blue)
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            Money.formatYuanGroupedFixed(cents),
            style = EcoType.BillAmount.copy(fontSize = 13.sp),
            color = if (cents < 0L) c.clay else c.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun AssetStage(
    label: String,
    amountCents: Long,
    color: Color,
    save: Boolean,
    stageColor: Color,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AssetChart(save = save, color = stageColor, modifier = Modifier.fillMaxSize())
        // 原型 .asset-stage::before：金额后面垫一层纸色径向渐变，让曲线退到字后面
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                c.paper.copy(alpha = 0.94f),
                                c.paper.copy(alpha = 0.55f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.maxDimension * 0.50f,
                        ),
                    )
                },
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = EcoType.AssetLabel, color = c.muted)
            }
            Spacer(Modifier.height(6.dp))
            EcoCountUpMoney(
                cents = amountCents,
                style = EcoType.AssetAmount,
                color = color,
                widthLimit = 320.dp,
                minSize = 28.sp,
            )
        }
    }
}

@Composable
private fun AssetChart(save: Boolean, color: Color, modifier: Modifier = Modifier) {
    // 原型 homeAssets() 里的两条固定折线（viewBox 390x330），不是按真实数据拟合的曲线
    val points = if (save) {
        listOf(
            0f to 297f, 38f to 284f, 91f to 293f, 148f to 249f, 200f to 256f,
            258f to 214f, 308f to 190f, 361f to 154f, 410f to 150f,
        )
    } else {
        listOf(
            0f to 282f, 22f to 277f, 82f to 251f, 134f to 265f, 185f to 225f,
            251f to 234f, 320f to 177f, 367f to 164f, 410f to 130f,
        )
    }
    val reveal = remember { Animatable(0f) }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(save, animationsEnabled) {
        if (!animationsEnabled) {
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(1100, delayMillis = 220, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun x(v: Float) = w * v / 390f
        fun y(v: Float) = h * v / 330f
        val line = Path().apply {
            points.forEachIndexed { i, (px, py) ->
                if (i == 0) moveTo(x(px), y(py)) else lineTo(x(px), y(py))
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(410f), y(340f))
            lineTo(x(0f), y(340f))
            close()
        }
        // 原型面积 .18/.035/0 在浅纸上太淡；这里加深到 .44/.16/0，墨线也提到 .85
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = 0.44f), color.copy(alpha = 0.16f), color.copy(alpha = 0.01f)),
                startY = y(100f),
                endY = y(340f),
            ),
            alpha = reveal.value,
        )
        drawPath(
            path = line,
            color = color.copy(alpha = if (save) 0.34f else 0.40f),
            style = Stroke(
                width = (if (save) 1.3.dp else 1.2.dp).toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            alpha = reveal.value,
        )
    }
}

@Composable
private fun ModeNeedsBudget(dailyMode: Boolean, title: String, onSetBudget: () -> Unit) {
    val c = MaterialTheme.eco
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(c.track, radius = size.minDimension / 2f - 7.dp.toPx(), style = Stroke(14.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (dailyMode) "日预算" else "月预算", style = EcoType.PageNote, color = c.quiet)
                Text("未设置", style = EcoType.SubTitle.copy(fontSize = 24.sp), color = c.muted)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (dailyMode) "还没有设置日预算" else "还没有设置月预算",
            style = EcoType.EmptyTitle,
            color = c.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (dailyMode) {
                "设置每日预算后，这里会显示今天还能花多少。"
            } else {
                "设置本月预算后，这里显示本月还可以花与本日剩余。"
            },
            style = EcoType.PageLead,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Box(Modifier.width(190.dp)) {
            EcoPrimaryButton(
                text = if (dailyMode) "设置日预算" else if (title == "分类预算") "先设置本月预算" else "设置本月预算",
                onClick = onSetBudget,
            )
        }
    }
}

@Composable
private fun ModeDots(current: Int, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.eco
    Row(
        Modifier.fillMaxWidth().height(37.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(MODE_COUNT) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 25.dp else 7.dp, tween(220), label = "dot-w")
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .width(width)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            // 选中页点：纯强调色渐变（原来玉绿→青蓝，换主题后一半还是绿的）
                            Brush.horizontalGradient(listOf(c.blueHi, c.blue))
                        } else {
                            Brush.horizontalGradient(listOf(c.ruleStrong, c.ruleStrong))
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !selected,
                    ) { onSelect(index) },
            )
        }
    }
}

// ---------- 纯逻辑 ----------

internal fun buildCategoryRings(
    budgets: Map<Long, Long>,
    sums: List<CategorySumRow>,
    categories: List<CategoryEntity>,
    monthlyBudgetCents: Long,
    totalSpentCents: Long,
    thresholds: Budget.Thresholds = Budget.thresholds(1, 1),
): List<CategoryRingData> {
    val byId = categories.associateBy { it.id }
    val spentById = sums.associate { it.categoryId to it.totalCents }
    val budgeted = budgets.entries.mapNotNull { (id, budget) ->
        if (budget <= 0L) return@mapNotNull null
        val category = byId[id] ?: return@mapNotNull null
        if (category.type != TxType.EXPENSE) return@mapNotNull null
        category to budget
    }

    val rings = budgeted.map { (category, budget) ->
        ringOf(category.name, budget, spentById[category.id] ?: 0L, thresholds, isOther = false)
    }.toMutableList()

    val allocatedBudget = budgeted.sumOf { it.second }
    val allocatedSpent = budgeted.sumOf { (category, _) -> spentById[category.id] ?: 0L }
    val otherBudget = monthlyBudgetCents - allocatedBudget
    if (monthlyBudgetCents > 0L && otherBudget > 0L) {
        rings += ringOf("其他", otherBudget, totalSpentCents - allocatedSpent, thresholds, isOther = true)
    }
    return rings.sortedBy { it.ratio }
}

private fun ringOf(
    name: String,
    budgetCents: Long,
    spentCents: Long,
    thresholds: Budget.Thresholds,
    isOther: Boolean,
): CategoryRingData {
    val remaining = budgetCents - spentCents
    val ratio = remaining.toDouble() / budgetCents.toDouble()
    return CategoryRingData(
        name = name,
        remainingCents = remaining,
        ratio = ratio.toFloat(),
        tone = Budget.tone(ratio.toFloat(), thresholds),
        severity = Budget.warningSeverity(ratio.toFloat(), thresholds.redBelow),
        isOther = isOther,
    )
}

internal fun buildCategoryBars(
    budgets: Map<Long, Long>,
    sums: List<CategorySumRow>,
    categories: List<CategoryEntity>,
    monthlyBudgetCents: Long,
    totalSpentCents: Long,
    thresholds: Budget.Thresholds = Budget.thresholds(1, 1),
): List<CategoryBarData> {
    val byId = categories.associateBy { it.id }
    val spentById = sums.associate { it.categoryId to it.totalCents }
    val budgeted = budgets.entries.mapNotNull { (id, budget) ->
        if (budget <= 0L) return@mapNotNull null
        val category = byId[id] ?: return@mapNotNull null
        if (category.type != TxType.EXPENSE) return@mapNotNull null
        category to budget
    }
    val rows = budgeted.map { (category, budget) ->
        barOf(
            name = category.name,
            icon = category.icon,
            budgetCents = budget,
            spentCents = spentById[category.id] ?: 0L,
            thresholds = thresholds,
        )
    }.toMutableList()
    val allocatedBudget = budgeted.sumOf { it.second }
    val allocatedSpent = budgeted.sumOf { (category, _) -> spentById[category.id] ?: 0L }
    val otherBudget = monthlyBudgetCents - allocatedBudget
    if (monthlyBudgetCents > 0L && otherBudget > 0L) {
        rows += barOf(
            name = "其他",
            icon = "",
            budgetCents = otherBudget,
            spentCents = totalSpentCents - allocatedSpent,
            thresholds = thresholds,
            isOther = true,
        )
    }
    return rows.sortedBy { it.ratio }
}

private fun barOf(
    name: String,
    icon: String,
    budgetCents: Long,
    spentCents: Long,
    thresholds: Budget.Thresholds,
    isOther: Boolean = false,
): CategoryBarData {
    val remaining = budgetCents - spentCents
    val ratio = remaining.toDouble() / budgetCents.toDouble()
    return CategoryBarData(
        name = name,
        icon = icon,
        budgetCents = budgetCents,
        spentCents = spentCents,
        remainingCents = remaining,
        ratio = ratio.toFloat(),
        tone = Budget.tone(ratio.toFloat(), thresholds),
        isOther = isOther,
    )
}

private fun buildHomeTrend(rows: List<TransactionRow>, period: MonthPeriod, depositCents: Long): HomeTrend {
    val zone = ZoneId.systemDefault()
    val byDay = Array(period.days) { 0L }
    rows.forEach { row ->
        val date = Instant.ofEpochMilli(row.timestamp).atZone(zone).toLocalDate()
        val index = (period.dayIndexOf(date) - 1).coerceIn(0, period.days - 1)
        val signed = when {
            row.type == TxType.EXPENSE -> -row.amountCents
            row.refundMark == RefundMark.REFUND_INCOME -> row.amountCents
            else -> row.amountCents
        }
        byDay[index] += signed
    }
    val spent = ArrayList<Float>(period.days)
    val deposit = ArrayList<Float>(period.days)
    var spentSum = 0L
    var depositSum = depositCents - byDay.sum()
    byDay.forEach { net ->
        spentSum += -net
        depositSum += net
        spent += spentSum.toFloat()
        deposit += depositSum.toFloat()
    }
    fun normalize(values: List<Float>): List<Float> {
        if (values.isEmpty()) return listOf(0f)
        val min = values.min()
        val max = values.max()
        if (max - min < 1f) return values.map { 0.5f }
        return values.map { (it - min) / (max - min) }
    }
    return HomeTrend(spent = normalize(spent), deposit = normalize(deposit))
}

private fun chineseMonth(month: Int): String {
    val names = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
    return names.getOrElse(month - 1) { "$month" } + "月"
}
