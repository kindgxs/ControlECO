package com.controleco.app.ui.eco

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.theme.onAccentGrad
import kotlinx.coroutines.delay

// ---------- 顶栏 ----------

@Composable
fun EcoTopBar(
    micro: String,
    title: String,
    modifier: Modifier = Modifier,
    low: String? = null,
    onBack: (() -> Unit)? = null,
    /** 标题右侧的附加入口（如账单页的日历图标） */
    titleLeading: (@Composable () -> Unit)? = null,
    action: @Composable RowScope.() -> Unit = {},
) {
    val c = MaterialTheme.eco
    val main = onBack == null
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(if (main) 72.dp else 64.dp)
            .padding(start = if (main) 22.dp else 16.dp, end = if (main) 22.dp else 18.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!main) {
            EcoBackButton(onClick = onBack)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            EcoMicro(micro)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    title,
                    style = if (main) EcoType.TopTitle else EcoType.SubTitle,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (low != null) {
                    Text(
                        low,
                        style = if (main) EcoType.TopLow else EcoType.SubTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        color = c.muted,
                        maxLines = 1,
                    )
                }
                if (titleLeading != null) {
                    Spacer(Modifier.width(6.dp))
                    titleLeading()
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

/** 首页/账单右上角的设置入口：只有一个齿轮图标，无底、无边框、无文字 */
@Composable
fun EcoSettingsPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .ecoPressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            EcoIcons.Gear,
            contentDescription = "设置",
            tint = c.blueDeep,
            modifier = Modifier.size(21.dp),
        )
    }
}

// ---------- 底部导航 ----------

data class EcoNavItem(val label: String, val icon: ImageVector)

@Composable
fun EcoBottomNav(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val items = remember {
        listOf(
            EcoNavItem("首页", EcoIcons.Home),
            EcoNavItem("账单", EcoIcons.List),
            EcoNavItem("统计", EcoIcons.Chart),
            EcoNavItem("个人", EcoIcons.User),
        )
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, c.ruleStrong.copy(alpha = 0.6f), Color.Transparent),
                    ),
                ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(78.dp)
                .padding(start = 9.dp, end = 9.dp, top = 7.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val on = index == selected
                // 原型 nav：选中图标上浮 2dp、渐变胶囊 + 投影，底部还有 3dp 玉色小点
                val lift by animateDpAsState(
                    targetValue = if (on) (-2).dp else 0.dp,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "nav-lift-$index",
                )
                val iconTint by animateColorAsState(
                    targetValue = if (on) c.blueDeep else c.quiet,
                    animationSpec = tween(220),
                    label = "nav-icon-$index",
                )
                val labelColor by animateColorAsState(
                    targetValue = if (on) c.blueDeep else c.quiet,
                    animationSpec = tween(220),
                    label = "nav-label-$index",
                )
                // 胶囊底/投影/小点一起淡入淡出：只让图标颜色渐变、底色却瞬切，切换时最扎眼
                val pillAlpha by animateFloatAsState(
                    targetValue = if (on) 1f else 0f,
                    animationSpec = tween(220),
                    label = "nav-pill-$index",
                )
                val pillElevation by animateDpAsState(
                    targetValue = if (on) 9.dp else 0.dp,
                    animationSpec = tween(220),
                    label = "nav-pill-shadow-$index",
                )
                val dotAlpha by animateFloatAsState(
                    targetValue = if (on) 1f else 0f,
                    animationSpec = tween(220),
                    label = "nav-dot-$index",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .offset(y = lift)
                            .width(46.dp)
                            .height(29.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // alpha 只作用于胶囊背景：图标必须始终可见，未选中时只是没有底色
                        Box(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = pillAlpha }
                                .shadow(
                                    elevation = pillElevation,
                                    shape = RoundedCornerShape(15.dp),
                                    clip = false,
                                    ambientColor = c.blueDeep.copy(alpha = 0.27f),
                                    spotColor = c.blueDeep.copy(alpha = 0.27f),
                                )
                                .clip(RoundedCornerShape(15.dp))
                                .background(
                                    Brush.linearGradient(
                                        // 选中胶囊：强调色软底 → 主题浅底（原来起手是写死的浅青）
                                        listOf(lerp(c.blueSoft, c.surface, 0.35f), c.blueSoft),
                                    ),
                                ),
                        )
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = iconTint,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        style = EcoType.Nav.copy(
                            fontWeight = if (on) FontWeight(760) else FontWeight(640),
                        ),
                        color = labelColor,
                    )
                    Box(
                        Modifier
                            .padding(top = 1.dp)
                            .size(3.dp)
                            .clip(CircleShape)
                            .graphicsLayer { alpha = dotAlpha }
                            // 选中页指示点：跟随主题（原来固定玉绿，任何主题下都有一点绿）
                            .background(c.blue),
                    )
                }
            }
        }
    }
}

/** 主标签页内容底部留白：底栏 78 + 系统手势条 + 呼吸 */
@Composable
fun ecoBottomContentPadding(extra: Dp = 18.dp): Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return 78.dp + navBottom + extra
}

@Composable
fun ecoFabBottomOffset(): Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return 78.dp + navBottom + 14.dp
}

// ---------- FAB ----------

@Composable
fun EcoFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    val transition = rememberInfiniteTransition(label = "fab-breath")
    // 原型 4.2s：整体只呼吸 2.5%、上浮 3.5dp；外圈 0.95→1.07、透明度 .2→.5
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "fab-breath-phase",
    )
    // 原型最终覆盖：FAB 本体不位移、不缩放，只有外圈呼吸
    val ringScale = 0.95f + 0.12f * phase
    val ringAlpha = 0.20f + 0.30f * phase
    Box(modifier.size(62.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(50.dp)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                    alpha = ringAlpha
                }
                .border(1.dp, c.heroB.copy(alpha = 0.65f), CircleShape),
        )
        Box(
            Modifier
                .size(50.dp)
                .graphicsLayer {
                    scaleX = 1f
                    scaleY = 1f
                }
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    // 阴影染上主题色深端，避免固定青色阴影在紫色/珊瑚主题下露馅
                    ambientColor = c.accentGradC.copy(alpha = 0.33f),
                    spotColor = c.accentGradC.copy(alpha = 0.40f),
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(c.accentGradA, c.accentGradB, c.accentGradC),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(130f, 130f),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.36f), CircleShape)
                .ecoPressable(scaleTo = 0.92f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // 加号固定纯白：渐变提亮后（gradB 亮度 0.234）contentColorOn 会自动选成深墨色，
            // 但 FAB 上的加号是图形不是正文，白色在整条渐变上仍有 3.1:1（图标底线 3:1），
            // 而且白加号在这套「彩色渐变 + 白描边」的观感里才对。
            Icon(EcoIcons.Plus, contentDescription = "记一笔", tint = Color.White, modifier = Modifier.size(21.dp))
        }
    }
}

/** 账户/分类页的方形次级 FAB（50x50） */
@Composable
fun EcoSquareFab(onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = EcoIcons.Plus) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .size(50.dp)
            .shadow(
                12.dp,
                RoundedCornerShape(17.dp),
                ambientColor = c.accentGradC.copy(alpha = 0.33f),
                spotColor = c.accentGradC.copy(alpha = 0.40f),
            )
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(listOf(c.accentGradA, c.accentGradB)))
            .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(17.dp))
            .ecoPressable(scaleTo = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 与 EcoFab 一致：方形 FAB 上的图标也用纯白，避免两个 FAB 的加号一黑一白
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

// ---------- Sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcoSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = modifier,
        containerColor = c.surface,
        contentColor = c.ink,
        scrimColor = Color.Black.copy(alpha = if (c.isDark) 0.58f else 0.32f),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp),
        dragHandle = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { EcoHandle() }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
        ) {
            if (title != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = EcoType.SheetTitle, color = c.ink)
                        if (subtitle != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(subtitle, style = EcoType.SheetSub, color = c.quiet)
                        }
                    }
                    EcoIconButton(
                        icon = EcoIcons.Close,
                        contentDescription = "关闭",
                        onClick = onDismiss,
                        size = 28.dp,
                        container = c.surface2,
                        border = false,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            if (scrollable) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

// ---------- Dialog ----------

@Composable
fun EcoDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 原型 modalIn .28s：scale .94 → 1、上移 8dp、淡入
            val entrance = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                entrance.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            }
            Box(
                modifier
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = entrance.value
                        val s = 0.94f + 0.06f * entrance.value
                        scaleX = s
                        scaleY = s
                        translationY = (1f - entrance.value) * 8.dp.toPx()
                    }
                    .shadow(
                        26.dp,
                        RoundedCornerShape(23.dp),
                        // 中性投影：用墨色的低透明，不再染青
                        ambientColor = MaterialTheme.eco.ink.copy(alpha = 0.28f),
                        spotColor = MaterialTheme.eco.ink.copy(alpha = 0.28f),
                    )
                    .clip(RoundedCornerShape(23.dp))
                    .background(MaterialTheme.eco.surface)
                    .border(1.dp, MaterialTheme.eco.rule, RoundedCornerShape(23.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .padding(22.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
fun EcoConfirm(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "取消",
    danger: Boolean = false,
) {
    val c = MaterialTheme.eco
    EcoDialog(onDismiss = onDismiss) {
        Column {
            Text(title, style = EcoType.ModalTitle, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text(body, style = EcoType.ModalBody, color = c.muted)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f), height = 40.dp)
                if (danger) {
                    Box(Modifier.weight(1f)) {
                        EcoDangerButton(text = confirmText, onClick = onConfirm, height = 40.dp)
                    }
                } else {
                    Box(Modifier.weight(1f)) {
                        EcoPrimaryButton(text = confirmText, onClick = onConfirm, height = 40.dp)
                    }
                }
            }
        }
    }
}

// ---------- Toast ----------

data class EcoToastData(val id: Long, val text: String, val tone: EcoPillTone)

object EcoToast {
    var current by mutableStateOf<EcoToastData?>(null)
        private set

    fun show(text: String, tone: EcoPillTone = EcoPillTone.Good) {
        current = EcoToastData(System.nanoTime(), text, tone)
    }

    fun clear() {
        current = null
    }

    fun clearIf(id: Long) {
        if (current?.id == id) current = null
    }
}

@Composable
fun EcoToastHost(modifier: Modifier = Modifier) {
    val data = EcoToast.current
    LaunchedEffect(data?.id) {
        val shown = data ?: return@LaunchedEffect
        delay(2400)
        EcoToast.clearIf(shown.id)
    }
    AnimatedVisibility(
        visible = data != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(140)),
    ) {
        val c = MaterialTheme.eco
        val tone = data?.tone ?: EcoPillTone.Good
        val accent = when (tone) {
            EcoPillTone.Bad -> c.clay
            EcoPillTone.Warn -> c.amber
            EcoPillTone.Blue -> c.blue
            else -> c.jade
        }
        Row(
            Modifier
                .padding(horizontal = 22.dp)
                .shadow(
                    16.dp,
                    RoundedCornerShape(13.dp),
                    ambientColor = c.ink.copy(alpha = 0.30f),
                    spotColor = c.ink.copy(alpha = 0.30f),
                )
                .clip(RoundedCornerShape(13.dp))
                .background(c.ink)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EcoIcons.Check, contentDescription = null, tint = c.paper, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(data?.text.orEmpty(), style = EcoType.RowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.paper, maxLines = 2)
        }
    }
}
