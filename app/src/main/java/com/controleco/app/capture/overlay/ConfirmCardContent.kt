package com.controleco.app.capture.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.capture.CaptureDefaults
import com.controleco.app.capture.MerchantRules
import com.controleco.app.data.CategoryTree
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoHairline
import com.controleco.app.ui.eco.EcoHandle
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoSwitch
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 卡片底部的时间（与原型一致：`2026-10-19 12:41`） */
private val overlayTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatOverlayTime(millis: Long): String =
    overlayTimeFormatter.format(Instant.ofEpochMilli(millis))

/**
 * 悬浮确认卡的编辑状态。
 *
 * 自动回填只在用户没有手改过该字段时生效（用户改过的值不被后续富化覆盖），
 * 未改过的字段随后续捕获（页面合并/跨通道归并）刷新。
 */
internal class ConfirmCardState {

    var pendingId by mutableStateOf(-1L)
    var type by mutableStateOf(TxType.EXPENSE)
    var isRefund by mutableStateOf(false)
    var sourceLabel by mutableStateOf("")
    var ruleName by mutableStateOf("")
    var timestamp by mutableStateOf(0L)
    var amountText by mutableStateOf("")
    var counterparty by mutableStateOf("")
    var note by mutableStateOf("")
    var categoryId by mutableStateOf<Long?>(null)
    var accountId by mutableStateOf<Long?>(null)
    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
    var accounts by mutableStateOf<List<AccountEntity>>(emptyList())
    var learnedRules by mutableStateOf<List<com.controleco.app.data.db.MerchantRuleEntity>>(emptyList())
    /** 不计入资产统计：自动入账默认计入（false），用户可在卡片里改 */
    var excludeFromAssets by mutableStateOf(false)
    /** 不计入账单和收支统计：自动入账默认计入（true），与上一项互不影响 */
    var countInStats by mutableStateOf(true)

    private var amountUserEdited = false
    private var counterpartyUserEdited = false
    private var categoryUserEdited = false
    private var accountUserEdited = false
    private var assetUserEdited = false
    private var statsUserEdited = false

    val amountCents: Long? get() = Money.parseCents(amountText)?.takeIf { it > 0 }
    val canSubmit: Boolean get() = amountCents != null && categoryId != null && accountId != null

    /** 用户是否改过任何字段：关闭卡片时没有改动就不必回写数据库 */
    val userEdited: Boolean
        get() = amountUserEdited || counterpartyUserEdited || categoryUserEdited ||
            accountUserEdited || assetUserEdited || statsUserEdited

    /** 首次绑定（新的待确认条目） */
    fun bind(
        pending: PendingTransactionEntity,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        learnedRules: List<com.controleco.app.data.db.MerchantRuleEntity> = emptyList(),
    ) {
        amountUserEdited = false
        counterpartyUserEdited = false
        categoryUserEdited = false
        accountUserEdited = false
        assetUserEdited = false
        statsUserEdited = false
        excludeFromAssets = false
        countInStats = true
        pendingId = pending.id
        type = pending.type
        isRefund = pending.refundMark == com.controleco.app.data.db.RefundMark.REFUND_INCOME
        sourceLabel = com.controleco.app.capture.NotificationRules.sourceLabelOrRule(pending.sourcePackage, pending.ruleName)
        ruleName = pending.ruleName
        timestamp = pending.timestamp
        amountText = if (pending.amountCents > 0) Money.formatCents(pending.amountCents) else ""
        counterparty = pending.counterparty
        note = pending.note
        this.categories = categories
        this.accounts = accounts
        this.learnedRules = learnedRules
        categoryId = CaptureDefaults.categoryId(
            categories,
            pending.type,
            pending.counterparty.ifBlank { pending.note },
            learnedRules = learnedRules,
            counterparty = pending.counterparty,
            note = pending.note,
            isRefund = pending.refundMark == com.controleco.app.data.db.RefundMark.REFUND_INCOME,
        )
        accountId = CaptureDefaults.accountId(
            accounts,
            pending.sourcePackage,
            CaptureDefaults.bankNameOf(pending.sourcePackage, pending.ruleName),
            learnedAccountId = MerchantRules.accountIdFrom(
                learnedRules, accounts, pending.type, pending.counterparty, pending.note,
            ),
            instrumentLabel = pending.paymentInstrument,
        )
    }

    /** 富化刷新（页面合并/跨通道归并后）：用户改过的字段不动 */
    fun mergeFrom(
        pending: PendingTransactionEntity,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        learnedRules: List<com.controleco.app.data.db.MerchantRuleEntity> = emptyList(),
    ) {
        if (pendingId != pending.id) return
        if (!amountUserEdited && pending.amountCents > 0) {
            amountText = Money.formatCents(pending.amountCents)
        }
        if (!counterpartyUserEdited && pending.counterparty.isNotBlank()) {
            counterparty = pending.counterparty
        }
        note = pending.note
        ruleName = pending.ruleName
        timestamp = pending.timestamp
        this.categories = categories
        this.accounts = accounts
        this.learnedRules = learnedRules
        if (categoryId == null) {
            categoryId = CaptureDefaults.categoryId(
                categories, pending.type, pending.counterparty.ifBlank { pending.note },
                learnedRules = learnedRules, counterparty = pending.counterparty, note = pending.note,
                isRefund = pending.refundMark == com.controleco.app.data.db.RefundMark.REFUND_INCOME,
            )
        }
        if (accountId == null) {
            accountId = CaptureDefaults.accountId(
                accounts,
                pending.sourcePackage,
                CaptureDefaults.bankNameOf(pending.sourcePackage, pending.ruleName),
                instrumentLabel = pending.paymentInstrument,
            )
        }
    }

    fun updateAmount(text: String) {
        amountText = text
        amountUserEdited = true
    }

    fun updateCounterparty(text: String) {
        counterparty = text
        counterpartyUserEdited = true
    }

    fun selectCategory(id: Long) {
        categoryId = id
        categoryUserEdited = true
    }

    fun selectAccount(id: Long) {
        accountId = id
        accountUserEdited = true
    }

    /** 是否计入资产统计（true = 不计入；自动入账默认计入） */
    fun updateExcludeFromAssets(exclude: Boolean) {
        excludeFromAssets = exclude
        assetUserEdited = true
    }

    /** 是否计入账单和收支统计（自动入账默认计入；与资产开关互不影响） */
    fun updateCountInStats(count: Boolean) {
        countInStats = count
        statsUserEdited = true
    }
}

/**
 * 底部确认卡内容（系统悬浮窗里的 ComposeView，不是 Compose Dialog）。
 *
 * 视觉：贴底 bottom-sheet（顶部 27dp 圆角、surface 面、1dp hairline、把手、安全区内边距），
 * 内容全部使用应用的 ECO 设计组件（EcoPrimaryButton / EcoPill / EcoField / EcoCategoryTile…）。
 *
 * 三种模式：
 * - [alreadyEntered] = true（悬浮窗入账的常态）：**这笔已经入账了**——卡片是"改/删"入口，
 *   标题写明"已记入"，改完关闭卡片即同步，点「忽略」删除这笔；
 * - [alreadyEntered] = false（待确认条目）：点 ✕/卡片外空白 = 关闭时按卡内值入账，
 *   「忽略」= 不入账，「记一笔」= 以当前值入账；
 * - [askMode] = true（微信红包领取页）覆盖上述关闭语义：卡片只是询问，
 *   **只有点「记一笔」才入账**；关闭/超时都不会入账，条目继续留在待确认列表。
 *
 * 关闭手势：✕、点卡片**外**的透明遮罩、把标题/把手向下拖、60s 无操作。
 * 卡内空白区域不再触发关闭（原实现整屏 clickable 会误关，见 [onUserActivity] 同步修复）。
 */
@Composable
internal fun ConfirmCardContent(
    state: ConfirmCardState,
    alreadyEntered: Boolean = false,
    askMode: Boolean = false,
    onConfirm: (
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
        excludeFromAssets: Boolean,
        countInStats: Boolean,
    ) -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
    onUserActivity: () -> Unit = {},
) {
    val c = MaterialTheme.eco
    var showInvalid by remember { mutableStateOf(false) }
    var expandedSelector by remember { mutableStateOf(SelectorTarget.None) }
    /** 长按一级分类后展开的二级分类选择（内嵌在卡片里，避免在悬浮窗里开 Dialog 造成 BadToken） */
    var subPickerFor by remember { mutableStateOf<CategoryEntity?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragThresholdPx = with(LocalDensity.current) { 84.dp.toPx() }

    // 拖动中实时跟手，松手后回弹；offset 用 lambda 版本，避免拖动时整棵子树重组
    val animatedOffset by animateFloatAsState(
        targetValue = if (dragging) dragOffset else 0f,
        animationSpec = tween(durationMillis = if (dragging) 0 else 180),
        label = "confirm-card-drag",
    )
    val cardOffset = if (dragging) dragOffset else animatedOffset

    val catsOfType = state.categories.filter { it.type == state.type }
    // 分类选择条只列一级分类；二级分类通过长按一级分类就地展开
    val topCats = CategoryTree.topLevel(state.categories, state.type)
    val selectedCategory = catsOfType.firstOrNull { it.id == state.categoryId }
    val selectedCategoryLabel = selectedCategory
        ?.let { CategoryTree.displayName(state.categories, it.id).ifBlank { it.name } }
        ?: "请选择"
    val selectedAccount = state.accounts.firstOrNull { it.id == state.accountId }
    val amountInvalid = state.amountText.isNotBlank() && state.amountCents == null

    val kind = when {
        state.isRefund -> "退款"
        state.type == TxType.EXPENSE -> "支出"
        else -> "收入"
    }
    val title = when {
        askMode -> "检测到红包，是否记账？"
        alreadyEntered -> "已记入一笔$kind"
        else -> "捕获到一笔$kind"
    }
    val titleColor = when {
        askMode -> c.clay
        state.isRefund -> c.amber
        state.type == TxType.INCOME -> c.jade
        else -> c.ink
    }
    val primaryLabel = when {
        askMode -> "确认入账"
        alreadyEntered -> "保存"
        else -> "记一笔"
    }

    Box(
        Modifier
            .fillMaxSize()
            // P0：任何触摸（含输入框、分类/账户选择、拖动）都刷新 60s 自动收起计时，
            // 避免用户正在编辑时卡片被超时收起。这里只观察 down、不消费事件。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onUserActivity()
                }
            },
    ) {
        // 透明遮罩：窗口本身是全屏透明的（底部卡片不压暗支付页）。
        // 只有点卡片外的空白才关闭；卡内空白由卡片自身消费，不再误触关闭（P0）。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, cardOffset.roundToInt()) }
                .imePadding(),
        ) {
            val shape = RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 24.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = c.ink.copy(alpha = if (c.isDark) 0.34f else 0.10f),
                        spotColor = c.ink.copy(alpha = if (c.isDark) 0.42f else 0.16f),
                    )
                    .clip(shape)
                    .background(c.surface)
                    .border(1.dp, c.rule, shape)
                    // 卡片自身消费点按：点卡内空白/标题/分隔线不再冒泡到遮罩关闭
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            ) {
                // 把手 + 标题行：整块可作为下拉关闭的拖拽区
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    onUserActivity()
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                                    onUserActivity()
                                },
                                onDragEnd = {
                                    if (dragOffset >= dragThresholdPx) {
                                        dragging = false
                                        dragOffset = 0f
                                        onDismiss()
                                    } else {
                                        dragging = false
                                        dragOffset = 0f
                                    }
                                },
                                onDragCancel = {
                                    dragging = false
                                    dragOffset = 0f
                                },
                            )
                        },
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            EcoHandle()
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                style = EcoType.SectionTitle,
                                color = titleColor,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (askMode) {
                                Spacer(Modifier.width(6.dp))
                                EcoPill("红包", tone = EcoPillTone.Bad)
                            }
                            if (state.isRefund) {
                                Spacer(Modifier.width(6.dp))
                                EcoPill("退款", tone = EcoPillTone.Warn)
                            }
                            Spacer(Modifier.width(6.dp))
                            EcoPill(
                                text = state.sourceLabel.ifBlank { "自动捕获" }.take(8),
                                tone = EcoPillTone.Neutral,
                                modifier = Modifier.widthIn(max = 88.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            EcoIconButton(
                                icon = EcoIcons.Close,
                                contentDescription = when {
                                    askMode -> "关闭（不会入账，保留待确认）"
                                    alreadyEntered -> "关闭（保留这笔）"
                                    else -> "关闭并入账"
                                },
                                onClick = onDismiss,
                                size = 26.dp,
                                container = c.surface3,
                                border = false,
                                tint = c.muted,
                            )
                        }
                    }
                }

                // 金额：可编辑；符号/币种与金额同一行，数字用 tabular 大字号
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.type == TxType.EXPENSE) "-" else "+",
                        style = EcoType.ModalTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
                        color = c.muted,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        Money.symbol,
                        style = EcoType.BillAmount.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        color = c.blue,
                    )
                    Spacer(Modifier.width(2.dp))
                    BasicTextField(
                        value = state.amountText,
                        onValueChange = {
                            state.updateAmount(it)
                            onUserActivity()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { if (it.isFocused) onUserActivity() },
                        singleLine = true,
                        textStyle = EcoType.ConfirmAmount.copy(color = c.ink),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        cursorBrush = SolidColor(c.blue),
                        decorationBox = { inner ->
                            Box {
                                if (state.amountText.isEmpty()) {
                                    Text(
                                        "0.00",
                                        style = EcoType.ConfirmAmount,
                                        color = c.quiet,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                EcoHairline(color = if (amountInvalid) c.clay else c.rule)

                val amountHint = when {
                    amountInvalid -> "金额格式不对：请只输入数字"
                    state.amountText.isBlank() && askMode -> "请输入有效金额"
                    state.amountText.isBlank() ->
                        "金额缺失：直接在金额栏补录，或在待确认列表里补"
                    else -> null
                }
                if (amountHint != null) {
                    Text(
                        amountHint,
                        style = EcoType.PageNote,
                        color = c.amber,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                // 已入账：一句状态说明；待确认：说明关闭语义；红包询问：clay 警示条
                if (alreadyEntered && !askMode) {
                    Text(
                        "这笔已经记好；改完关闭卡片即同步，点「忽略」删除这笔。",
                        style = EcoType.PageNote,
                        color = c.quiet,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else if (askMode) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.claySoft)
                            .border(1.dp, c.clay.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            EcoIcons.Warning,
                            contentDescription = null,
                            tint = c.clay,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "关闭不会入账，会保留在待确认",
                            style = EcoType.PageNote,
                            color = c.clay,
                        )
                    }
                } else {
                    Text(
                        if (state.canSubmit) {
                            "关闭卡片会按当前值入账；点「忽略」这笔不记账。"
                        } else {
                            "填齐金额/分类/账户后关闭卡片即入账；点「忽略」不记账。"
                        },
                        style = EcoType.PageNote,
                        color = c.quiet,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // 商户 / 备注
                EcoField(
                    label = "商户 / 备注",
                    value = state.counterparty,
                    onValueChange = {
                        state.updateCounterparty(it)
                        onUserActivity()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    placeholder = "商户名称或备注",
                    minHeight = 38.dp,
                    textStyle = EcoType.Input,
                )

                // 分类 / 账户：收起时是原型里的两个 select；点开后横向滚动选
                // （分类用 EcoCategoryTile，账户用钱包图标），选中即收起。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConfirmSelect(
                        label = "分类",
                        value = selectedCategoryLabel,
                        expanded = expandedSelector == SelectorTarget.Category,
                        onClick = {
                            expandedSelector = if (expandedSelector == SelectorTarget.Category) {
                                SelectorTarget.None
                            } else {
                                SelectorTarget.Category
                            }
                            subPickerFor = null
                            onUserActivity()
                        },
                        modifier = Modifier.weight(1f),
                        leading = {
                            EcoCategoryTile(
                                categoryName = selectedCategory?.name ?: "分类",
                                icon = selectedCategory?.icon.orEmpty(),
                                size = 24.dp,
                                radius = 8.dp,
                            )
                        },
                    )
                    ConfirmSelect(
                        label = "账户",
                        value = selectedAccount?.name ?: "请选择",
                        expanded = expandedSelector == SelectorTarget.Account,
                        onClick = {
                            expandedSelector = if (expandedSelector == SelectorTarget.Account) {
                                SelectorTarget.None
                            } else {
                                SelectorTarget.Account
                            }
                            onUserActivity()
                        },
                        modifier = Modifier.weight(1.18f),
                        leading = {
                            EcoIconTile(
                                icon = EcoIcons.Wallet,
                                tint = c.blue,
                                size = 24.dp,
                                radius = 8.dp,
                                iconSize = 13.dp,
                            )
                        },
                    )
                }

                when (expandedSelector) {
                    SelectorTarget.Category -> {
                        if (topCats.isEmpty()) {
                            Text(
                                "暂无分类",
                                style = EcoType.PageNote,
                                color = c.quiet,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            // 长按一级分类后，二级面板就地替换掉一级横条（避免卡片过高，也少一层重复）
                            val picker = subPickerFor
                            if (picker != null) {
                                SubCategoryPanel(
                                    parent = picker,
                                    children = CategoryTree.childrenOf(state.categories, picker.id),
                                    selectedId = state.categoryId,
                                    onPick = { id ->
                                        state.selectCategory(id)
                                        expandedSelector = SelectorTarget.None
                                        subPickerFor = null
                                        onUserActivity()
                                    },
                                    onClose = { subPickerFor = null },
                                )
                            } else {
                                ChipStrip {
                                    topCats.forEach { category ->
                                        CategoryChip(
                                            category = category,
                                            selected = category.id == state.categoryId ||
                                                selectedCategory?.parentId == category.id,
                                            onClick = {
                                                state.selectCategory(category.id)
                                                expandedSelector = SelectorTarget.None
                                                subPickerFor = null
                                                onUserActivity()
                                            },
                                            onLongClick = {
                                                subPickerFor = category
                                                onUserActivity()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SelectorTarget.Account -> {
                        if (state.accounts.isEmpty()) {
                            Text(
                                "暂无账户",
                                style = EcoType.PageNote,
                                color = c.quiet,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            ChipStrip {
                                state.accounts.forEach { account ->
                                    AccountChip(
                                        account = account,
                                        selected = account.id == state.accountId,
                                        onClick = {
                                            state.selectAccount(account.id)
                                            expandedSelector = SelectorTarget.None
                                            onUserActivity()
                                        },
                                    )
                                }
                            }
                        }
                    }

                    SelectorTarget.None -> Unit
                }

                // 是否计入资产统计：自动入账默认计入；关闭只影响账户余额/资产，账单与收支统计照常
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "计入资产统计",
                            style = EcoType.RowSub.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = c.ink,
                        )
                        Text(
                            "关闭后不影响账户余额与资产，仍计入账单和收支统计",
                            style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                            color = c.quiet,
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    EcoSwitch(
                        checked = !state.excludeFromAssets,
                        onCheckedChange = {
                            state.updateExcludeFromAssets(!it)
                            onUserActivity()
                        },
                    )
                }

                // 是否计入账单和收支统计：自动入账默认计入；关闭后只在账单留一条记录
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "计入账单和收支统计",
                            style = EcoType.RowSub.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = c.ink,
                        )
                        Text(
                            "关闭后只在账单显示一条记录，不计入收支/预算/存款统计",
                            style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                            color = c.quiet,
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    EcoSwitch(
                        checked = state.countInStats,
                        onCheckedChange = {
                            state.updateCountInStats(it)
                            onUserActivity()
                        },
                    )
                }

                if (showInvalid && !state.canSubmit) {
                    Text(
                        "先补齐金额、分类和账户",
                        style = EcoType.PageNote,
                        color = c.amber,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // 底部动作：忽略 · 时间 · 主按钮（已入账=保存，其余=记一笔）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EcoTextAction(
                        text = "忽略",
                        onClick = onIgnore,
                        color = if (askMode) c.clay else c.muted,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        formatOverlayTime(state.timestamp) + " · 自动捕获",
                        style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                        color = c.quiet,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    EcoPrimaryButton(
                        text = primaryLabel,
                        onClick = {
                            onUserActivity()
                            if (state.canSubmit) {
                                onConfirm(
                                    state.amountCents!!,
                                    state.categoryId!!,
                                    state.accountId!!,
                                    state.counterparty,
                                    state.excludeFromAssets,
                                    state.countInStats,
                                )
                            } else {
                                showInvalid = true
                            }
                        },
                        modifier = Modifier.width(108.dp),
                        height = 36.dp,
                    )
                }
            }
        }
    }
}

/** 收起态 select（分类/账户）：左侧彩色瓦片 + 标签/当前值 + 展开箭头 */
@Composable
private fun ConfirmSelect(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier
            .height(38.dp)
            .clip(shape)
            .background(c.surface2)
            .border(1.dp, if (expanded) c.blue.copy(alpha = 0.55f) else c.rule, shape)
            .ecoPressable(scaleTo = 0.98f, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = EcoType.FieldLabel.copy(fontSize = 8.sp, lineHeight = 10.sp),
                color = c.quiet,
                maxLines = 1,
            )
            Text(
                value,
                style = EcoType.Chip,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            EcoIcons.ChevDown,
            contentDescription = null,
            tint = c.quiet,
            modifier = Modifier
                .size(12.dp)
                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
        )
    }
}

/** 展开后的横向滚动选择条 */
@Composable
private fun ChipStrip(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun CategoryChip(
    category: CategoryEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (selected) c.blueSoft else c.surface2)
            .border(1.dp, if (selected) c.blue.copy(alpha = 0.5f) else c.rule, shape)
            .ecoPressable(scaleTo = 0.96f, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EcoCategoryTile(categoryName = category.name, icon = category.icon, size = 22.dp, radius = 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            category.name,
            style = EcoType.Chip,
            color = if (selected) c.blueDeep else c.muted,
            maxLines = 1,
        )
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(
                EcoIcons.Check,
                contentDescription = null,
                tint = c.blue,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun AccountChip(account: AccountEntity, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (selected) c.blueSoft else c.surface2)
            .border(1.dp, if (selected) c.blue.copy(alpha = 0.5f) else c.rule, shape)
            .ecoPressable(scaleTo = 0.96f, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EcoIconTile(
            icon = EcoIcons.Wallet,
            tint = c.blue,
            size = 22.dp,
            radius = 7.dp,
            iconSize = 12.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            account.name,
            style = EcoType.Chip,
            color = if (selected) c.blueDeep else c.muted,
            maxLines = 1,
        )
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(
                EcoIcons.Check,
                contentDescription = null,
                tint = c.blue,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/**
 * 长按一级分类后展开的「小窗口」：就地列出二级分类，也可以继续记在一级分类上。
 * 悬浮窗是系统 overlay 窗口，这里刻意不用 Dialog/BottomSheet（无 Activity token 会 BadToken），
 * 而是卡内嵌一小块带阴影的面板，交互与视觉都与卡内其它选择器一致。
 */
@Composable
private fun SubCategoryPanel(
    parent: CategoryEntity,
    children: List<CategoryEntity>,
    selectedId: Long?,
    onPick: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .shadow(
                elevation = 10.dp,
                shape = shape,
                clip = false,
                ambientColor = c.ink.copy(alpha = 0.10f),
                spotColor = c.ink.copy(alpha = 0.16f),
            )
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.rule, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "「${parent.name}」的二级分类",
                style = EcoType.RowSub.copy(fontWeight = FontWeight.SemiBold),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            EcoTextAction(text = "收起", onClick = onClose, color = c.muted)
        }
        ChipStrip {
            CategoryChip(
                category = parent,
                selected = selectedId == parent.id,
                onClick = { onPick(parent.id) },
            )
            children.forEach { child ->
                CategoryChip(
                    category = child,
                    selected = selectedId == child.id,
                    onClick = { onPick(child.id) },
                )
            }
        }
        if (children.isEmpty()) {
            Text(
                "还没有二级分类（可在「分类」页长按一级分类添加）",
                style = EcoType.PageNote,
                color = c.quiet,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 「已记入 支出 ¥20.06 · 撤销」细条（非阻塞，底部悬浮，几秒后自动消失） */
@Composable
internal fun SavedStripContent(text: String, onUndo: () -> Unit) {
    val c = MaterialTheme.eco
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = SAVED_STRIP_DISMISS_MS.toInt(),
                easing = LinearEasing,
            ),
        )
    }
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .widthIn(min = 210.dp, max = 340.dp)
            .height(42.dp)
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = c.ink.copy(alpha = if (c.isDark) 0.34f else 0.10f),
                spotColor = c.ink.copy(alpha = if (c.isDark) 0.42f else 0.16f),
            )
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.rule, shape),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = EcoType.RowSub.copy(fontWeight = FontWeight.SemiBold),
                color = c.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            EcoTextAction(text = "撤销", onClick = onUndo, color = c.blue)
        }
        // 与原型一致：底部 2dp 细进度条，按窗口的自动收起时长走完
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 3.dp)
                .height(2.dp)
                .clip(CircleShape)
                .background(c.track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.value)
                    .fillMaxHeight()
                    .background(c.blue),
            )
        }
    }
}

/** 分类/账户收起态的展开目标（同一时间只展开一个） */
private enum class SelectorTarget { None, Category, Account }
