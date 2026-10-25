package com.controleco.app.capture.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money

/**
 * 悬浮确认卡（底部卡片）与「已记入 · 撤销」细条的窗口宿主。
 *
 * 设计要点（见 docs/自动记账捕获机制.md 第二节）：
 * - **底部**卡片：顶部 placement 在 MIUI 上会被压到状态栏下方（v0.4.13 灵动岛失败的原因），
 *   底部卡片天然绕开该问题；
 * - 全屏透明窗口 + `NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN|LAYOUT_INSET_DECOR|WATCH_OUTSIDE_TOUCH`：
 *   **卡片外**的透明区点按 = 关闭卡片（关闭 = 视为入账，卡内当前值经 [showConfirm] 的 onDismiss 交回调用方）；
 *   卡片内部由卡片自己消费点按，空白区不会再误触关闭（P0 修复）；
 * - 窗口可聚焦，卡内输入框可直接调键盘（悬浮条 [showSaved] 仍是 NOT_FOCUSABLE，不抢 IME）；
 * - `addView` 抛异常（无权限/被支付保护层拦截）→ [showConfirm] 返回 false，调用方回落横幅通知
 *   （Toast 可见而卡片不可见 = 被拦截的铁证，v0.4.13 起用这套判定）。
 *
 * UI 全部用本应用自己的 Compose 设计组件（[ConfirmCardContent]）。
 */
internal object ConfirmCardWindow {

    private const val TAG = "Overlay"

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())

    private var confirmView: ComposeView? = null
    private var confirmHost: OverlayComposeHost? = null
    private var savedView: ComposeView? = null
    private var savedHost: OverlayComposeHost? = null

    private var autoDismiss: Runnable? = null
    private var savedDismiss: Runnable? = null
    private var dismissCallback: ((DismissSnapshot) -> Unit)? = null

    /** 卡片当前展示的待确认条目 id（-1 = 未展示） */
    var showingPendingId: Long = -1L
        private set

    /** 卡片内容状态（编辑中的金额/分类/账户都在这） */
    val state = ConfirmCardState()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isVisible(): Boolean = confirmView != null || savedView != null

    // ---------- 确认卡 ----------

    /**
     * 展示确认卡。返回 false = 窗口没弹出来（无权限/被拦截），调用方必须回落通知。
     * [onConfirm] 的五个参数：金额分、分类 id、账户 id、商户/备注、是否不计入资产统计；
     * [onDismiss] = 关闭（✕ / 点卡片**外**的透明区 / 标题与把手下拉 / 60s 无操作），携带卡内当前值
     * —— 关闭视为入账，由调用方按 [dismissOutcome] 决定落账或保留待确认。
     */
    fun showConfirm(
        pending: PendingTransactionEntity,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        learnedRules: List<com.controleco.app.data.db.MerchantRuleEntity> = emptyList(),
        alreadyEntered: Boolean = false,
        askMode: Boolean = false,
        onConfirm: (
            amountCents: Long,
            categoryId: Long,
            accountId: Long,
            counterparty: String,
            excludeFromAssets: Boolean,
            excludeFromStats: Boolean,
        ) -> Unit,
        onIgnore: () -> Unit,
        onDismiss: (DismissSnapshot) -> Unit,
    ): Boolean {
        if (!this::appContext.isInitialized) return false
        removeConfirmWindow() // 重入时先清旧窗口（新的一笔直接替换展示）

        state.bind(pending, categories, accounts, learnedRules)

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val host = OverlayComposeHost()
        val view = buildOverlayComposeView(appContext, host) {
            ConfirmCardContent(
                state = state,
                alreadyEntered = alreadyEntered,
                askMode = askMode,
                onConfirm = { amountCents, categoryId, accountId, counterparty, excludeFromAssets, excludeFromStats ->
                    onConfirm(
                        amountCents, categoryId, accountId, counterparty,
                        excludeFromAssets, excludeFromStats,
                    )
                },
                onIgnore = onIgnore,
                onDismiss = { handleDismiss() },
                // P0：任何触摸/拖动/字段编辑都重置 60s 自动收起计时，避免编辑中途被收起
                onUserActivity = { noteUserActivity() },
            )
        }
        // 注意：确认卡内有可编辑的金额 / 商户备注，窗口必须保持可聚焦（否则输入法无法弹出），
        // 因此这里不加 FLAG_NOT_FOCUSABLE；「已记入 · 撤销」细条才是 NOT_FOCUSABLE。
        // 卡外点击由 ConfirmCardContent 的透明遮罩处理，卡内点击由卡片自己消费。
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        return try {
            wm.addView(view, params)
            host.attach()
            confirmView = view
            confirmHost = host
            showingPendingId = pending.id
            dismissCallback = onDismiss
            scheduleAutoDismiss()
            AppLog.i(TAG, "confirm card shown pending=#${pending.id} ${pending.ruleName}")
            true
        } catch (e: Exception) {
            // 无悬浮窗权限 / 被支付保护层拦截：调用方回落通知（Toast 可见而卡片不可见 = 被拦截的铁证）
            AppLog.w(TAG, "confirm card addView failed, fallback to notification", e)
            runCatching { host.destroy() }
            false
        }
    }

    /**
     * 后续捕获把同笔条目富化后（页面合并/跨通道归并）刷新卡片；
     * 自动回填只在用户没有手改过该字段时生效（用户手改过的值优先）。
     */
    fun updatePending(
        pending: PendingTransactionEntity,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        learnedRules: List<com.controleco.app.data.db.MerchantRuleEntity> = emptyList(),
    ) {
        if (confirmView == null || showingPendingId != pending.id) return
        state.mergeFrom(pending, categories, accounts, learnedRules)
        AppLog.i(TAG, "confirm card updated pending=#${pending.id}")
    }

    /**
     * 移除确认卡，**不触发** onDismiss（数据不动）：进入应用、关闭「悬浮窗入账」开关等场景用。
     * 返回 true = 之前确实有卡片在展示。用户主动关闭（✕/卡片外空白/下拉/超时）走 [handleDismiss]。
     */
    fun dismissConfirm(): Boolean {
        val wasVisible = confirmView != null
        removeConfirmWindow()
        return wasVisible
    }

    // ---------- 已记入 · 撤销 细条 ----------

    /** 确认入账后的非阻塞细条：底部小条 + 撤销按钮，几秒后自动消失 */
    fun showSaved(type: TxType, amountCents: Long, onUndo: () -> Unit) {
        if (!this::appContext.isInitialized) return
        removeSavedWindow()

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val host = OverlayComposeHost()
        val view = buildOverlayComposeView(appContext, host) {
            SavedStripContent(
                text = "已记入 " + (if (type == TxType.EXPENSE) "支出 " else "收入 ") + Money.formatYuan(amountCents),
                onUndo = {
                    onUndo()
                    removeSavedWindow()
                },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // 原型：底部 96dp 悬浮，不压住系统手势区
            y = dp(96)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching {
            wm.addView(view, params)
            host.attach()
            savedView = view
            savedHost = host
            val r = Runnable { removeSavedWindow() }
            savedDismiss = r
            handler.postDelayed(r, SAVED_STRIP_DISMISS_MS)
            AppLog.i(TAG, "saved strip shown")
        }.onFailure {
            AppLog.w(TAG, "saved strip addView failed", it)
            runCatching { host.destroy() }
        }
    }

    /** 收起全部窗口（进入应用、屏幕关闭等场景） */
    fun dismissAll() {
        removeConfirmWindow()
        removeSavedWindow()
    }

    // ---------- 内部 ----------

    /**
     * ✕ / 卡片外空白 / 把标题（或把手）向下拖 / 长时间无操作：关闭卡片。
     * 卡内当前值（含"是否改过"标记）打包成 [DismissSnapshot] 交给 onDismiss；
     * 窗口先移除再回调（回调可能要弹「已记入」细条）。
     */
    private fun handleDismiss() {
        val cb = dismissCallback ?: return
        val snapshot = DismissSnapshot(
            amountCents = state.amountCents ?: 0L,
            categoryId = state.categoryId,
            accountId = state.accountId,
            counterparty = state.counterparty,
            excludeFromAssets = state.excludeFromAssets,
            excludeFromStats = state.excludeFromStats,
            edited = state.userEdited,
        )
        if (dismissConfirm()) cb(snapshot)
    }

    private fun scheduleAutoDismiss() {
        autoDismiss?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            AppLog.i(TAG, "confirm card auto dismiss (no interaction, treated as entered)")
            handleDismiss()
        }
        autoDismiss = r
        handler.postDelayed(r, OVERLAY_AUTO_DISMISS_MS)
    }

    /**
     * 卡片内有交互（改金额/备注、点分类/账户、拖动、任意触摸）时重置自动收起计时，
     * 避免编辑中被 60s 超时收起。由 [ConfirmCardContent] 的 onUserActivity 逐次触发。
     */
    fun noteUserActivity() {
        if (confirmView != null) scheduleAutoDismiss()
    }

    private fun removeConfirmWindow() {
        autoDismiss?.let { handler.removeCallbacks(it) }
        autoDismiss = null
        dismissCallback = null
        val view = confirmView ?: return
        confirmView = null
        showingPendingId = -1L
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeViewImmediate(view) }.onFailure { AppLog.w(TAG, "remove confirm view failed", it) }
        runCatching { confirmHost?.destroy() }
        confirmHost = null
    }

    private fun removeSavedWindow() {
        savedDismiss?.let { handler.removeCallbacks(it) }
        savedDismiss = null
        val view = savedView ?: return
        savedView = null
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeViewImmediate(view) }.onFailure { AppLog.w(TAG, "remove saved view failed", it) }
        runCatching { savedHost?.destroy() }
        savedHost = null
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()
}
