package com.controleco.app.capture

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.controleco.app.AccBookApp
import com.controleco.app.BuildConfig
import com.controleco.app.capture.overlay.canSyncOverlayEdits
import com.controleco.app.capture.overlay.ConfirmCardWindow
import com.controleco.app.capture.overlay.DismissOutcome
import com.controleco.app.capture.overlay.dismissOutcome
import com.controleco.app.capture.overlay.OVERLAY_DELAY_MS
import com.controleco.app.capture.overlay.OverlayConditions
import com.controleco.app.capture.overlay.shouldShowOverlay
import com.controleco.app.capture.NotificationRules.ParsedNotification
import com.controleco.app.capture.page.PageCandidate
import com.controleco.app.capture.page.PageFlowTracker
import com.controleco.app.capture.page.PageParseResult
import com.controleco.app.capture.page.PageParserRegistry
import com.controleco.app.capture.page.PageScene
import com.controleco.app.capture.page.PageSnapshot
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.AccountDao
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CaptureDao
import com.controleco.app.data.db.CaptureTraceEntity
import com.controleco.app.data.db.CapturedNotificationEntity
import com.controleco.app.data.db.MerchantRuleDao
import com.controleco.app.data.db.CategoryDao
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingDao
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TraceDao
import com.controleco.app.data.db.TraceOutcome
import com.controleco.app.data.db.TransactionDao
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 采集中心：通知监听、无障碍页面、银行动账短信三条通道的唯一入口。
 * 通道流程一致：原始快照落库（不解析）→ 规则引擎 → 待确认队列。
 * 跨通道去重分两层：
 * - 同源（通知⇄页面，10s 窗口，见 CaptureMerge）：只保留一条，**页面信息优先**；
 * - 异源（微信/支付宝/云闪付 ⇄ 银行短信/推送，±130s，仅支出，见 CrossChannelDedup）：
 *   同一笔卡支付被两侧同时捕获时归并成一条。**两个方向都做**——银行侧后到时并入支付条目
 *   （[findCrossChannelPending]），支付侧后到时回头并入银行条目（[findBankPendingForPayment]）；
 *   两个支付渠道之间、两家银行之间不归并。
 * 捕获后的处置由三个设置开关决定（见各开关的注释）：
 * - 「入账提醒」：捕获时发横幅通知（也是卡片弹不出时的兜底）；
 * - 「悬浮窗入账」：弹出底部确认卡，关闭卡片/长时间无操作视为入账，点「忽略」才不入账；
 * - 「自动入账」：识别到即直接记账（与悬浮窗入账互斥），条目留 AUTO 记录供页面回填。
 * debug 构建可全量记录（设置开关），Logcat tag=ControlECO 便于真实样本调试。
 * 开发者功能「记录记账来源」开启时，另把每次入账相关捕获的原始来源写入溯源表（见 traceCapture）。
 */
object CaptureCenter {

    private const val PREFS_NAME = "settings"
    private const val KEY_CAPTURE_ENABLED = "auto_capture_enabled"
    private const val KEY_A11Y_CAPTURE_ENABLED = "a11y_capture_enabled"
    private const val KEY_REFUND_LINK_ENABLED = "refund_link_enabled"
    private const val KEY_CAPTURE_NOTIFY_ENABLED = "capture_notify_enabled"
    private const val KEY_AUTO_ENTRY_ENABLED = "auto_entry_enabled"
    private const val KEY_DEBUG_CAPTURE_ALL = "debug_capture_all"
    private const val KEY_DEBUG_CAPTURE_ALL_PAGES = "debug_capture_all_pages"
    private const val KEY_TRACE_ENABLED = "capture_trace_enabled"
    private const val KEY_BANK_PUSH_ENABLED = "bank_push_enabled"
    private const val KEY_SMS_CAPTURE_ENABLED = "sms_capture_enabled"
    private const val KEY_OVERLAY_CONFIRM_ENABLED = "overlay_confirm_enabled"
    private const val KEY_AMOUNT_REPLY_ENABLED = "amount_reply_enabled"
    private const val KEY_NEEDS_REAUTH = "needs_reauth_after_update"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"

    /** 点击通知跳转的路由（MainActivity intent extra） */
    const val EXTRA_ROUTE = "controleco.route"
    const val ROUTE_PENDING = "pending"
    const val ROUTE_REFUND_LINK = "refundLink"

    /** 「已自动入账」横幅直达该笔记录的编辑页（route = 前缀 + 交易 id，追加路由，不改既有契约） */
    const val ROUTE_EDIT_PREFIX = "edit/"

    /** 分享导入：route = 前缀 + 文件 uri（ShareImportActivity 落盘后跳转，AppNav 解析并预解析账单） */
    const val ROUTE_IMPORT_FILE_PREFIX = "import?file="

    /** 悬浮确认卡样例（调试路由：弹一张不入库的样例卡，等价开发者功能里的测试入口） */
    const val ROUTE_OVERLAY_TEST = "overlayTest"

    /** 渠道 v2：横幅提醒（IMPORTANCE_HIGH 才会弹屏幕上方横幅）；旧渠道 capture（DEFAULT 静默）删除 */
    const val CHANNEL_CAPTURE = "capture_alert"
    private const val CHANNEL_LEGACY = "capture"
    private const val NOTIF_TAG = "capture"

    /** 「输入金额」通知的动作前缀（同一个意图按 pendingId 区分） */
    private const val ACTION_AMOUNT_REPLY = "com.controleco.app.action.AMOUNT_REPLY"

    /** 同一内容通知的重发窗口（微信/支付宝常会更新同一条通知导致重复回调） */
    private const val REPOST_WINDOW_MS = 10_000L
    private const val RECENT_KEY_CAP = 256

    /** 红包询问卡的延迟：封面页没有支付保护层，尽快弹出，避免用户已经领完/离开 */
    private const val RED_PACKET_PROMPT_DELAY_MS = 600L

    /** 捕获日志保留策略 */
    private const val KEEP_DAYS = 7L
    private const val KEEP_ROWS = 2_000

    /** 溯源记录保留条数：只按条数封顶、不按时间清理（开发者证据），可手动清空 */
    private const val TRACE_KEEP_ROWS = 1_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentKeys = LinkedHashMap<String, Long>()

    /** 稳定页面去重：带页面交易时间的结果页在短 TTL 内只处理一次（跨流程重置也有效） */
    private val recentPageCache = RecentPageCache()

    private lateinit var appContext: Context
    private lateinit var db: AccBookDatabase
    private lateinit var txDao: TransactionDao
    private lateinit var captureDao: CaptureDao
    private lateinit var pendingDao: PendingDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var accountDao: AccountDao
    private lateinit var traceDao: TraceDao
    private lateinit var merchantRuleDao: MerchantRuleDao
    private val initialized get() = this::appContext.isInitialized

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _a11yConnected = MutableStateFlow(false)
    val a11yConnected: StateFlow<Boolean> = _a11yConnected

    private val _overlayDecision = MutableStateFlow<String?>(null)

    /** 最近一次弹卡判定（"已弹出"或未弹原因）：权限页展示，便于回看"为什么没弹卡" */
    val overlayDecision: StateFlow<String?> = _overlayDecision
    private var lastOverlayDecision: String?
        get() = _overlayDecision.value
        set(value) {
            _overlayDecision.value = value
        }

    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt

    /** 本应用是否在前台（MainActivity onResume/onPause 维护）：前台时不弹悬浮卡，用应用内列表 */
    @Volatile
    private var appInForeground = false

    /** 无障碍服务跟踪到的当前前台应用包名（未开无障碍时为 null）：悬浮卡的前台匹配用 */
    @Volatile
    private var foregroundPackage: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val db = (appContext as AccBookApp).db
        this.db = db
        txDao = db.transactionDao()
        captureDao = db.captureDao()
        pendingDao = db.pendingDao()
        categoryDao = db.categoryDao()
        accountDao = db.accountDao()
        traceDao = db.traceDao()
        merchantRuleDao = db.merchantRuleDao()
        ConfirmCardWindow.init(appContext)
        reconcileEntryModePrefs()
        createNotificationChannel()
        cleanup()
    }

    /**
     * 启动时校正入账方式（升级迁移，纯判定见 [reconcileEntryModes]）：
     * 旧版本两个开关彼此独立，机内可能同时为开（互斥只在切换时生效）——
     * 按定稿默认取向（自动入账优先）清掉悬浮窗入账；
     * 例外：悬浮窗是显式开启、自动入账从未设置过（旧版默认关）时，尊重用户的悬浮窗选择。
     */
    private fun reconcileEntryModePrefs() {
        val p = prefs()
        val fix = entryModeFix(
            autoSet = p.contains(KEY_AUTO_ENTRY_ENABLED),
            overlaySet = p.contains(KEY_OVERLAY_CONFIRM_ENABLED),
            autoEntry = isAutoEntryEnabled(),
            overlayConfirm = isOverlayConfirmEnabled(),
        )
        when (fix) {
            EntryModeFix.DISABLE_OVERLAY ->
                p.edit().putBoolean(KEY_OVERLAY_CONFIRM_ENABLED, false).apply()
                    .also { AppLog.i("Overlay", "entry modes reconciled: overlayConfirm=false (auto entry wins)") }
            EntryModeFix.DISABLE_AUTO ->
                p.edit().putBoolean(KEY_AUTO_ENTRY_ENABLED, false).apply()
                    .also { AppLog.i("Ntf", "entry modes reconciled: autoEntry=false (explicit overlay mode)") }
            EntryModeFix.NONE -> Unit
        }
    }

    private fun createNotificationChannel() {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.deleteNotificationChannel(CHANNEL_LEGACY)
            val channel = android.app.NotificationChannel(
                CHANNEL_CAPTURE,
                "记账提醒",
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "捕获到动账或退款时提醒确认；点开可改分类、账户或忽略"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }.onFailure { AppLog.w("Ntf", "create channel failed", it) }
    }

    /** 系统通知（横幅）：点击跳转 App 内对应页面（该笔编辑页 / 待确认队列 / 登记退款页）；可带动作按钮 */
    private fun postCaptureNotification(
        title: String,
        text: String,
        route: String,
        notifId: Int = title.hashCode(),
        action: androidx.core.app.NotificationCompat.Action? = null,
    ) {
        if (!isCaptureNotifyEnabled()) return
        if (!canPostNotifications()) {
            AppLog.i("Ntf", "notify skipped (POST_NOTIFICATIONS not granted)")
            return
        }
        val intent = android.content.Intent(appContext, com.controleco.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ROUTE, route)
        val pi = android.app.PendingIntent.getActivity(
            appContext,
            (NOTIF_TAG + route + title + text).hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = androidx.core.app.NotificationCompat.Builder(appContext, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
        action?.let { builder.addAction(it) }
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(appContext).notify(NOTIF_TAG, notifId, builder.build())
            AppLog.i("Ntf", "capture notification posted: $title")
        }.onFailure { AppLog.w("Ntf", "post notification failed", it) }
    }

    /** 待确认入账通知的确定性 id（同一笔被处理时按 id 撤销通知） */
    private fun pendingNotifId(dedupKey: String): Int = "pending|$dedupKey".hashCode()

    /** 已入账通知的确定性 id（与旧「已自动入账」共用同一 id 方案） */
    private fun entryNotifId(dedupKey: String): Int = "auto|$dedupKey".hashCode()

    /** 撤销捕获提醒通知（卡片里确认/忽略后调用；通知没发过则静默失败） */
    private fun cancelCaptureNotification(notifId: Int) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(appContext).cancel(NOTIF_TAG, notifId)
        }.onFailure { AppLog.w("Ntf", "cancel notification failed", it) }
    }

    private fun pendingNotifText(sourceLabel: String, amountCents: Long, counterparty: String): String =
        "$sourceLabel ${Money.formatYuan(amountCents)}" +
            (if (counterparty.isNotBlank()) " · $counterparty" else "") +
            " · 点开确认入账"

    /**
     * 捕获入队后的提醒（卡片/通知互斥）：
     * 能弹悬浮确认卡就弹卡（人还在支付页，关掉卡片即按卡内值入账），否则回落横幅通知
     * （IMPORTANCE_HIGH，点击进待确认队列；受「入账提醒」开关控制）。
     * 两条路径的确定化 id 一致，同一笔处理时可直接撤销。
     */
    private suspend fun announceCapture(
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        sourceLabel: String,
        counterparty: String,
        paymentMethod: String?,
        dedupKey: String,
        ruleName: String,
        txTime: Long?,
        note: String,
        pendingId: Long = -1L,
    ) {
        if (pendingId > 0) {
            val pending = pendingDao.getById(pendingId)
            if (pending != null &&
                tryShowConfirmCard(pending, alreadyEntered = false, isRefund, sourceLabel, counterparty, dedupKey)
            ) {
                return
            }
        }
        postPendingNotification(type, isRefund, amountCents, sourceLabel, counterparty, dedupKey)
    }

    /**
     * 已入账后的提醒（卡片/通知互斥）：
     * - 悬浮窗入账：弹「已记入（可改/可删）」卡片；卡片弹不出来账也在——这正是"先入账后弹卡"的意义；
     * - 入账提醒：卡片没弹（或没开悬浮窗）时发「已记入一笔 X」通知（点击进该笔编辑页，附「撤销」）。
     */
    private suspend fun announceEntry(
        entered: Entered,
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        sourceLabel: String,
        counterparty: String,
        dedupKey: String,
    ) {
        val pending = if (entered.pendingId > 0) pendingDao.getById(entered.pendingId) else null
        if (pending != null &&
            tryShowConfirmCard(pending, alreadyEntered = true, isRefund, sourceLabel, counterparty, dedupKey)
        ) {
            return
        }
        postEntryNotification(entered.txId, type, isRefund, amountCents, counterparty, sourceLabel, dedupKey)
    }

    /** 横幅通知（卡片不可用/收起后的兜底提醒；「已自动入账」等其它提醒另走 postCaptureNotification） */
    private fun postPendingNotification(
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        sourceLabel: String,
        counterparty: String,
        dedupKey: String,
        pendingId: Long = -1L,
    ) {
        val kind = when {
            isRefund -> "退款"
            type == TxType.EXPENSE -> "支出"
            else -> "收入"
        }
        val text = if (amountCents > 0) {
            pendingNotifText(sourceLabel, amountCents, counterparty)
        } else {
            "$sourceLabel 金额待补 · 点开补录或忽略"
        }
        postCaptureNotification(
            "有一笔${kind}待确认",
            text,
            ROUTE_PENDING,
            pendingNotifId(dedupKey),
            action = pendingId.takeIf { it > 0 }?.let { ignoreAction(IgnoreTarget(it, dedupKey)) },
        )
    }

    /**
     * 「已记入一笔账」通知（入账提醒的常态）：告知账已记好，**不是**"去待确认列表处理"的入口——
     * 点击进该笔的编辑页，另附「撤销」一键删除。
     */
    private fun postEntryNotification(
        txId: Long,
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        counterparty: String,
        sourceLabel: String,
        dedupKey: String,
    ) {
        if (txId <= 0) return
        val kind = when {
            isRefund -> "退款"
            type == TxType.EXPENSE -> "支出"
            else -> "收入"
        }
        val who = counterparty.ifBlank { sourceLabel }
        postCaptureNotification(
            "已记好一笔$kind",
            Money.formatYuan(amountCents) + " · $who · 点开可修改",
            "$ROUTE_EDIT_PREFIX$txId",
            entryNotifId(dedupKey),
            action = undoEntryAction(txId, dedupKey),
        )
    }

    // ---------- 金额待补录（方向可辨、金额缺失） ----------

    /** 「忽略」动作的目标（通知栏一键丢弃用） */
    private data class IgnoreTarget(val pendingId: Long, val dedupKey: String)

    /**
     * 方向可辨、金额缺失 → 入队 0 元条目 + 发「输入金额」通知。
     * 0 元条目不参与「全部确认」（见 LedgerViewModel），必须补录或忽略。
     */
    private suspend fun handleAmountMissing(
        packageName: String,
        rawId: Long,
        postTime: Long,
        title: String,
        text: String,
        bigText: String,
        hint: NotificationRules.DirectionHint,
        notificationKey: String? = null,
        eventStamp: Long = 0L,
    ) {
        val keyTime = if (postTime > 0) postTime else System.currentTimeMillis()
        val identity = notificationInstanceId(notificationKey, eventStamp, hint.type, false)
        val dedupKey = dedupKeyWithIdentity(
            NotificationRules.dedupKey(packageName, hint.type, false, 0L, keyTime),
            identity,
        )
        val pendingId = pendingDao.insert(
            PendingTransactionEntity(
                type = hint.type,
                amountCents = 0L,
                timestamp = keyTime,
                note = hint.note,
                sourcePackage = packageName,
                ruleName = hint.ruleName,
                dedupKey = dedupKey,
                rawNotificationId = rawId,
                notificationInstanceId = identity.orEmpty(),
            ),
        )
        captureDao.updateMatch(rawId, hint.ruleName + "·金额待补", true)
        AppLog.i("Ntf", "amount missing, queued stub #$pendingId ${hint.ruleName} pkg=$packageName")
        if (pendingId > 0) {
            postAmountRequestNotification(pendingId, dedupKey, hint)
        }
        traceCapture(
            channel = CaptureChannel.NOTIFICATION,
            packageName = packageName,
            title = title,
            text = text,
            bigText = bigText,
            capturedAt = keyTime,
            matchedRule = hint.ruleName,
            type = hint.type,
            amountCents = 0,
            note = hint.note,
            dedupKey = dedupKey,
            outcome = TraceOutcome.PENDING,
        )
    }

    /** 「输入金额」通知：RemoteInput 直接回复（先落库后回填，零跳转） + 「忽略」一键丢弃 */
    private fun postAmountRequestNotification(
        pendingId: Long,
        dedupKey: String,
        hint: NotificationRules.DirectionHint,
    ) {
        if (!isCaptureNotifyEnabled() || !canPostNotifications()) return
        val replyIntent = android.content.Intent(appContext, AmountReplyReceiver::class.java).apply {
            action = "$ACTION_AMOUNT_REPLY.$pendingId"
            putExtra(AmountReplyReceiver.EXTRA_PENDING_ID, pendingId)
            putExtra(AmountReplyReceiver.EXTRA_DEDUP_KEY, dedupKey)
        }
        // RemoteInput 要求可变 PendingIntent（Android 12+ 硬要求）；接收器里做组件来源校验兜底
        val replyPi = android.app.PendingIntent.getBroadcast(
            appContext,
            ("amount|$dedupKey").hashCode(),
            replyIntent,
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remoteInput = androidx.core.app.RemoteInput.Builder(AmountReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("输入金额")
            .build()
        val contentIntent = android.content.Intent(appContext, com.controleco.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ROUTE, ROUTE_PENDING)
        val contentPi = android.app.PendingIntent.getActivity(
            appContext,
            ("amount-content|$dedupKey").hashCode(),
            contentIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val kind = if (hint.type == TxType.EXPENSE) "支出" else "收入"
        val builder = androidx.core.app.NotificationCompat.Builder(appContext, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle("${hint.sourceLabel} 有一笔${kind}金额待补")
            .setContentText("直接在通知里输入金额，或点进待确认列表处理")
            .setContentIntent(contentPi)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(
                androidx.core.app.NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_save,
                    "输入金额",
                    replyPi,
                ).addRemoteInput(remoteInput).build(),
            )
            .addAction(ignoreAction(IgnoreTarget(pendingId, dedupKey)))
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(appContext)
                .notify(NOTIF_TAG, pendingNotifId(dedupKey), builder.build())
        }.onFailure { AppLog.w("Ntf", "post amount request failed", it) }
    }

    /** 「忽略」action：广播到 CaptureActionReceiver（显式组件、非导出） */
    private fun ignoreAction(
        target: IgnoreTarget,
    ): androidx.core.app.NotificationCompat.Action {
        val intent = android.content.Intent(appContext, CaptureActionReceiver::class.java).apply {
            action = CaptureActionReceiver.ACTION_IGNORE
            putExtra(CaptureActionReceiver.EXTRA_PENDING_ID, target.pendingId)
            putExtra(CaptureActionReceiver.EXTRA_DEDUP_KEY, target.dedupKey)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            appContext,
            ("ignore|${target.dedupKey}").hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return androidx.core.app.NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "忽略", pi).build()
    }

    /** 「撤销」action（已入账通知）：一键删除这笔已记的账（显式组件、非导出） */
    private fun undoEntryAction(txId: Long, dedupKey: String): androidx.core.app.NotificationCompat.Action {
        val intent = android.content.Intent(appContext, CaptureActionReceiver::class.java).apply {
            action = CaptureActionReceiver.ACTION_UNDO
            putExtra(CaptureActionReceiver.EXTRA_TX_ID, txId)
            putExtra(CaptureActionReceiver.EXTRA_DEDUP_KEY, dedupKey)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            appContext,
            ("undo|$dedupKey").hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return androidx.core.app.NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "撤销", pi).build()
    }

    /** 通知栏「撤销」入口（CaptureActionReceiver 调用）：删除已入账的那笔 + 对应 AUTO 条目 */
    fun undoEntryFromNotification(txId: Long, dedupKey: String) {
        if (!initialized) return
        scope.launch {
            runCatching { if (txId > 0) txDao.deleteById(txId) }
                .onFailure { AppLog.w("Ntf", "undo entry delete failed", it) }
            if (dedupKey.isNotBlank()) {
                pendingDao.deleteByDedupKey(dedupKey)
                if (dedupKey.isNotBlank()) AppLog.d("pending", "entry removed key=$dedupKey")
                cancelCaptureNotification(entryNotifId(dedupKey))
                if (ConfirmCardWindow.showingPendingId > 0 &&
                    ConfirmCardWindow.showingPendingId == pendingIdOfDedup(dedupKey)
                ) {
                    mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                }
            }
            toast("已撤销")
            AppLog.i("Ntf", "undo entry from notification tx=#$txId key=$dedupKey")
        }
    }

    private suspend fun pendingIdOfDedup(dedupKey: String): Long =
        pendingDao.getMergeable().firstOrNull { it.dedupKey == dedupKey }?.id ?: -1L

    /** 通知栏「忽略」入口（CaptureActionReceiver 调用） */
    fun ignoreFromNotification(pendingId: Long, dedupKey: String) {
        if (!initialized) return
        scope.launch {
            if (pendingId > 0) {
                PendingActions.discard(pendingDao, pendingId)
            } else {
                pendingDao.deleteByDedupKey(dedupKey)
            }
            // 条目已丢弃：释放去重键，同分钟桶里的下一笔真实交易可以复用
            if (dedupKey.isNotBlank()) AppLog.d("pending", "pending removed key=$dedupKey")
            cancelCaptureNotification(pendingNotifId(dedupKey))
            if (ConfirmCardWindow.showingPendingId == pendingId) {
                mainHandler.post { ConfirmCardWindow.dismissConfirm() }
            }
            AppLog.i("Ntf", "ignored from notification pending#$pendingId key=$dedupKey")
        }
    }

    /** 金额补录结果（供通知栏回执与调试） */
    sealed interface AmountUpdateResult {
        data class Ok(val amountCents: Long, val newDedupKey: String) : AmountUpdateResult
        object NotFound : AmountUpdateResult
        object Invalid : AmountUpdateResult
    }

    /**
     * 把补录金额写回 0 元占位条：重算去重键（保留分钟桶）。
     * 若新键对应的条目已存在（同笔的完整捕获先到了）→ 删除占位条，以既有条目为准。
     */
    fun applyPendingAmount(pendingId: Long, amountCents: Long, onResult: (AmountUpdateResult) -> Unit) {
        if (!initialized) return
        if (amountCents <= 0 || amountCents > NotificationRules.MAX_AMOUNT_CENTS) {
            onResult(AmountUpdateResult.Invalid)
            return
        }
        scope.launch {
            try {
                val stub = pendingDao.getById(pendingId)
                if (stub == null || stub.amountCents != 0L) {
                    onResult(AmountUpdateResult.NotFound)
                    return@launch
                }
                val newKey = dedupKeyWithAmount(stub.dedupKey, amountCents)
                if (newKey == null) {
                    onResult(AmountUpdateResult.Invalid)
                    return@launch
                }
                if (pendingDao.countByDedupKey(newKey) > 0) {
                    // 同笔的完整捕获已入队：占位条退场
                    pendingDao.deleteById(pendingId)
                    AppLog.d("pending", "stub superseded key=${stub.dedupKey}")
                    AppLog.i("Ntf", "amount stub superseded by existing entry key=$newKey")
                    onResult(AmountUpdateResult.NotFound)
                    return@launch
                }
                pendingDao.updateAmountAndKey(pendingId, amountCents, newKey)
                AppLog.i("Ntf", "amount backfilled pending#$pendingId -> ${Money.formatYuan(amountCents)}")
                onResult(AmountUpdateResult.Ok(amountCents, newKey))
            } catch (e: Exception) {
                AppLog.e("Ntf", "apply pending amount failed", e)
                onResult(AmountUpdateResult.Invalid)
            }
        }
    }

    /** 通知栏回执：撤销「输入金额」通知，补录成功后重新提醒（可弹卡，走正常待确认流程） */
    fun onAmountReplied(pendingId: Long, stubDedupKey: String, result: AmountUpdateResult) {
        if (!initialized) return
        when (result) {
            is AmountUpdateResult.Ok -> {
                cancelCaptureNotification(pendingNotifId(stubDedupKey))
                toast("已补录 " + Money.formatYuan(result.amountCents))
                scope.launch {
                    val row = pendingDao.getById(pendingId) ?: return@launch
                    announceCapture(
                        type = row.type,
                        isRefund = row.refundMark == RefundMark.REFUND_INCOME,
                        amountCents = row.amountCents,
                        sourceLabel = NotificationRules.sourceLabel(row.sourcePackage),
                        counterparty = row.counterparty,
                        paymentMethod = null,
                        dedupKey = row.dedupKey,
                        ruleName = row.ruleName,
                        txTime = row.timestamp,
                        note = row.note,
                        pendingId = row.id,
                    )
                }
            }
            AmountUpdateResult.NotFound -> {
                cancelCaptureNotification(pendingNotifId(stubDedupKey))
                toast("这条已处理过")
            }
            AmountUpdateResult.Invalid -> toast("金额无效，请重新输入")
        }
    }

    /** 完整捕获到达时清掉同笔的"金额待补"占位条（占位条只用于兜底提醒，不保留） */
    private suspend fun supersedeAmountStubs(packageName: String, type: TxType, timeMillis: Long) {
        val removed = pendingDao.deleteAmountStubs(packageName, type, timeMillis - 10 * 60_000, timeMillis + 60_000)
        if (removed > 0) AppLog.i("Ntf", "superseded $removed amount stub(s) pkg=$packageName")
    }

    // ---------- 悬浮确认卡（卡片/通知互斥） ----------

    /**
     * 悬浮窗入账（默认关：定稿默认取向是自动入账；授权了悬浮窗权限才生效，未授权自动回落通知）：
     * 捕获时弹出底部确认卡——关闭卡片（✕/点空白/长时间不操作）视为入账，点「忽略」才不入账。
     * 与「自动入账」互斥（开启本项时自动关闭自动入账）。
     */
    fun isOverlayConfirmEnabled(): Boolean = prefs().getBoolean(KEY_OVERLAY_CONFIRM_ENABLED, false)

    fun setOverlayConfirmEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_OVERLAY_CONFIRM_ENABLED, enabled).apply()
        if (enabled) {
            prefs().edit().putBoolean(KEY_AUTO_ENTRY_ENABLED, false).apply() // 互斥：关掉自动入账
        } else {
            mainHandler.post { ConfirmCardWindow.dismissAll() }
        }
        AppLog.i("Overlay", "overlayConfirmEnabled=$enabled (autoEntry=${isAutoEntryEnabled()})")
    }

    fun canDrawOverlays(): Boolean = initialized && Settings.canDrawOverlays(appContext)

    /** MainActivity 前台变化：进入前台立即收起卡片（应用内用待确认列表，卡片悬在本应用上会挡操作） */
    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
        if (foreground) mainHandler.post { ConfirmCardWindow.dismissAll() }
    }

    /** 通知点击进入应用时收起卡片（同 setAppForeground，兜底路由入口） */
    fun onRouteOpened(route: String) {
        AppLog.d("Overlay", "route opened: $route")
        mainHandler.post { ConfirmCardWindow.dismissAll() }
    }

    /** 无障碍服务上报前台应用（悬浮卡只在"人还在支付页"时弹） */
    fun noteForegroundPackage(packageName: String) {
        foregroundPackage = packageName
    }

    /**
     * 卡片显示条件（每次弹出前重新判定：延迟期间用户可能已锁屏/切走/进入本应用）。
     * [requireForegroundMatch] = false 时忽略"前台匹配"这一条（延迟前预检用：此刻的前台窗口
     * 可能还是支付保护/安全键盘等系统弹层，等 3 秒后再看用户到底在哪）。
     * 不满足时把原因写进 [lastOverlayDecision]，权限页/日志可直接回看"为什么没弹卡"。
     */
    private fun overlayConditionsOk(
        sourcePackage: String,
        requireForegroundMatch: Boolean,
        forcePrompt: Boolean = false,
    ): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val fg = foregroundPackage
        // 红包确认卡是"用户必须做选择"的提示，不受「悬浮窗入账」开关约束；
        // 仍然要求悬浮窗权限、屏幕点亮、非锁屏、非本应用前台（这些是系统硬条件）。
        val enabled = forcePrompt || isOverlayConfirmEnabled()
        val canDraw = Settings.canDrawOverlays(appContext)
        val screenOn = pm.isInteractive
        val locked = km.isKeyguardLocked
        val matchable = requireForegroundMatch && fg != null
        val contextMatch = fg != null && CaptureAppCatalog.isWatched(appContext, fg)
        val ok = shouldShowOverlay(
            OverlayConditions(
                enabled = enabled,
                canDrawOverlays = canDraw,
                appInForeground = appInForeground,
                screenInteractive = screenOn,
                keyguardLocked = locked,
                foregroundPackage = if (requireForegroundMatch) fg else null,
                sourcePackage = sourcePackage,
                foregroundIsPaymentContext = contextMatch,
            ),
        )
        if (!ok) {
            val reason = when {
                !enabled -> "「悬浮窗入账」开关未开"
                !canDraw -> "未授权悬浮窗权限"
                appInForeground -> "本应用在前台（用应用内列表）"
                !screenOn -> "屏幕未点亮"
                locked -> "已锁屏"
                matchable -> "当时前台是 $fg，与捕获来源 $sourcePackage 不符"
                else -> "条件不满足"
            }
            lastOverlayDecision = reason
            AppLog.i(
                "Overlay",
                "conditions not met: $reason (enabled=$enabled canDraw=$canDraw appInForeground=$appInForeground " +
                    "screen=$screenOn locked=$locked fg=$fg source=$sourcePackage)",
            )
        }
        return ok
    }

    /**
     * 尝试弹确认卡。返回 true = 卡片已展示（本次不再发通知）。
     * [alreadyEntered] = true（悬浮窗入账的常态）：卡片是"已入账，可改/可删"；
     * false：待确认条目（金额待补 / 无可推测分类 / 三个开关全关的完全手动模式）。
     * 延迟 [OVERLAY_DELAY_MS] 再弹：支付保护层在支付页停留期会拦截悬浮窗（阶段三旧结论），
     * 延迟后重新判定条件与条目（可能已被处理）；`addView` 失败同样回落通知。
     * 红包询问卡（[askMode]）用更短的 [RED_PACKET_PROMPT_DELAY_MS]：封面页没有支付保护层。
     */
    private suspend fun tryShowConfirmCard(
        pending: PendingTransactionEntity,
        alreadyEntered: Boolean,
        isRefund: Boolean,
        sourceLabel: String,
        counterparty: String,
        dedupKey: String,
        forcePrompt: Boolean = false,
        askMode: Boolean = false,
    ): Boolean {
        // 预检只判"硬条件"（开关/权限/本应用前台/屏幕），不看前台匹配：支付完成瞬间的前台窗口
        // 可能还是支付保护/安全键盘等系统弹层，等 3 秒后再看用户到底在哪（实测：预检查前台匹配会把卡拒掉）
        if (!overlayConditionsOk(pending.sourcePackage, requireForegroundMatch = false, forcePrompt = forcePrompt)) return false
        delay(if (askMode) RED_PACKET_PROMPT_DELAY_MS else OVERLAY_DELAY_MS)
        if (!overlayConditionsOk(pending.sourcePackage, requireForegroundMatch = true, forcePrompt = forcePrompt)) return false
        // 延迟期间条目可能已被确认/丢弃/被同笔富化改写（dedupKey 变化即视为已处理）
        val fresh = pendingDao.getById(pending.id)
        if (fresh == null || fresh.dedupKey != dedupKey) {
            lastOverlayDecision = "等待期间条目已处理"
            return false
        }
        val categories = categoryDao.getAll()
        val accounts = accountDao.getAll()
        val learned = merchantRuleDao.getAll()
        val shown = showCardOnMain(
            fresh, categories, accounts, learned, alreadyEntered, askMode,
            isRefund, sourceLabel, counterparty, dedupKey,
        )
        lastOverlayDecision = if (shown) "已弹出" else "系统拦截了悬浮窗（支付保护等）"
        AppLog.i("Overlay", if (shown) "confirm card shown for #${pending.id}" else "confirm card unavailable, fallback to notification")
        return shown
    }

    /**
     * 富化后补一次"卡片优先"提醒：同笔被后续通道捕获（页面合并/跨通道归并）时，
     * 若卡片没在展示就重新判定一次——首次尝试可能因当时的系统弹层/时机被拒，
     * 而此刻用户往往已回到结果页。卡片成功则撤销早先的横幅通知（同一笔不重复打扰）。
     */
    private suspend fun retryCardIfNoCard(pendingId: Long) {
        if (ConfirmCardWindow.showingPendingId == pendingId) return
        val pending = pendingDao.getById(pendingId) ?: return
        val alreadyEntered = pending.status == PendingStatus.AUTO
        val shown = tryShowConfirmCard(
            pending = pending,
            alreadyEntered = alreadyEntered,
            isRefund = pending.refundMark == RefundMark.REFUND_INCOME,
            sourceLabel = NotificationRules.sourceLabelOrRule(pending.sourcePackage, pending.ruleName),
            counterparty = pending.counterparty,
            dedupKey = pending.dedupKey,
        )
        if (shown) {
            cancelCaptureNotification(
                if (alreadyEntered) entryNotifId(pending.dedupKey) else pendingNotifId(pending.dedupKey),
            )
        }
    }

    /** WindowManager 必须在主线程操作；DAO 数据已在 IO 侧取好 */
    private suspend fun showCardOnMain(
        pending: PendingTransactionEntity,
        categories: List<com.controleco.app.data.db.CategoryEntity>,
        accounts: List<com.controleco.app.data.db.AccountEntity>,
        learned: List<com.controleco.app.data.db.MerchantRuleEntity>,
        alreadyEntered: Boolean,
        askMode: Boolean,
        isRefund: Boolean,
        sourceLabel: String,
        counterparty: String,
        dedupKey: String,
    ): Boolean = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val shown = ConfirmCardWindow.showConfirm(
                pending = pending,
                categories = categories,
                accounts = accounts,
                learnedRules = learned,
                alreadyEntered = alreadyEntered,
                askMode = askMode,
                onConfirm = { amountCents, categoryId, accountId, counterpartyText, excludeFromAssets ->
                    // 已入账模式：「保存」= 同步修改（细条可撤销整笔）；待确认模式：「记一笔」= 入账
                    mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                    scope.launch {
                        if (alreadyEntered) {
                            saveEnteredEdits(pending, amountCents, categoryId, accountId, counterpartyText, excludeFromAssets, withStrip = true)
                        } else {
                            confirmFromOverlay(pending, amountCents, categoryId, accountId, counterpartyText, excludeFromAssets)
                        }
                    }
                },
                onIgnore = {
                    mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                    scope.launch {
                        if (alreadyEntered) ignoreEntered(pending) else ignoreFromOverlay(pending.id, pending.dedupKey)
                    }
                },
                onDismiss = { snapshot ->
                    // 卡片已移除，这里再调一次是幂等兜底
                    mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                    val catId = snapshot.categoryId
                    val accId = snapshot.accountId
                    if (alreadyEntered) {
                        // 关闭 = 保留这笔已入账；用户改过的字段同步回去（没改过就不写库）
                        if (snapshot.edited && canSyncOverlayEdits(snapshot) && catId != null && accId != null) {
                            AppLog.i("Overlay", "card dismissed -> sync edits #${pending.id} ${Money.formatYuan(snapshot.amountCents)}")
                            scope.launch {
                                saveEnteredEdits(
                                    pending, snapshot.amountCents, catId, accId,
                                    snapshot.counterparty, snapshot.excludeFromAssets, withStrip = true,
                                )
                            }
                        } else {
                            AppLog.i("Overlay", "card dismissed -> entry kept #${pending.id} (edited=${snapshot.edited})")
                        }
                    } else if (askMode) {
                        // 红包询问卡：关闭/超时都只收起卡片，绝不入账；条目继续留在待确认列表。
                        AppLog.i("Overlay", "ask card dismissed -> keep pending #${pending.id}")
                    } else if (dismissOutcome(snapshot) == DismissOutcome.ENTER && catId != null && accId != null) {
                        // 待确认条目：关闭 = 视为入账（旧语义）
                        AppLog.i("Overlay", "card dismissed -> enter #${pending.id} ${Money.formatYuan(snapshot.amountCents)}")
                        scope.launch {
                            confirmFromOverlay(
                                pending, snapshot.amountCents, catId, accId,
                                snapshot.counterparty, snapshot.excludeFromAssets,
                            )
                        }
                    } else {
                        AppLog.i("Overlay", "card dismissed -> keep pending #${pending.id} (amount/category/account incomplete)")
                        scope.launch {
                            postPendingNotification(pending.type, isRefund, pending.amountCents, sourceLabel, counterparty, dedupKey, pending.id)
                        }
                    }
                },
            )
            if (cont.isActive) cont.resume(shown)
        }
    }

    /** 已入账卡片的「保存」/关闭同步：卡内当前值写回交易与 AUTO 条目（细条可撤销整笔） */
    private suspend fun saveEnteredEdits(
        pending: PendingTransactionEntity,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
        excludeFromAssets: Boolean,
        withStrip: Boolean,
    ) {
        val ok = runCatching {
            PendingActions.updateEntered(
                db, txDao, pendingDao, merchantRuleDao, pending,
                categoryId, accountId, counterparty, amountCents, excludeFromAssets,
            )
        }.onFailure { AppLog.e("Overlay", "sync entered edits failed", it) }.getOrDefault(false)
        if (!ok) {
            toast("这笔已被撤销，未更新")
            return
        }
        cancelCaptureNotification(entryNotifId(pending.dedupKey))
        toast("已更新")
        if (withStrip) {
            mainHandler.post {
                ConfirmCardWindow.showSaved(pending.type, amountCents) {
                    scope.launch {
                        PendingActions.undoEntered(db, txDao, pendingDao, pending.dedupKey)
                        toast("已撤销")
                    }
                }
            }
        }
    }

    /** 已入账卡片的「忽略」：删除这笔账（含 AUTO 条目），一条点击纠错 */
    private suspend fun ignoreEntered(pending: PendingTransactionEntity) {
        val removed = PendingActions.undoEntered(db, txDao, pendingDao, pending.dedupKey)
        pendingDao.deleteById(pending.id)
        cancelCaptureNotification(entryNotifId(pending.dedupKey))
        toast(if (removed) "已忽略，这笔未记账" else "这笔已被撤销")
        AppLog.i("Overlay", "ignored (entry deleted=$removed) #${pending.id} key=${pending.dedupKey}")
    }

    /** 卡片确认入账：写账 + 撤销细条 + 撤销即删除该笔 */
    private suspend fun confirmFromOverlay(
        pending: PendingTransactionEntity,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
        excludeFromAssets: Boolean = false,
    ) {
        val txId = try {
            PendingActions.confirm(
                db, txDao, pendingDao, merchantRuleDao, pending,
                categoryId, accountId, counterparty, amountCents, excludeFromAssets,
            )
        } catch (e: Exception) {
            AppLog.e("Overlay", "confirm from overlay failed", e)
            toast("入账失败：${e.message ?: "未知错误"}")
            return
        }
        cancelCaptureNotification(pendingNotifId(pending.dedupKey))
        val kindType = pending.type
        toast("已记入")
        if (txId == null) return // 已入过账（重复通知/双通道竞态）
        mainHandler.post {
            ConfirmCardWindow.showSaved(kindType, amountCents) {
                scope.launch {
                    runCatching { txDao.deleteById(txId) }
                        .onSuccess { AppLog.i("Overlay", "undo confirmed entry tx=#$txId") }
                        .onFailure { AppLog.w("Overlay", "undo failed", it) }
                    toast("已撤销")
                }
            }
        }
    }

    /** 卡片「忽略」：丢弃待确认条目（误报纠错，一条点击） */
    private suspend fun ignoreFromOverlay(pendingId: Long, dedupKey: String) {
        PendingActions.discard(pendingDao, pendingId)
        if (dedupKey.isNotBlank()) AppLog.d("Overlay", "pending ignored key=$dedupKey")
        cancelCaptureNotification(pendingNotifId(dedupKey))
        AppLog.i("Overlay", "ignored from card #$pendingId")
    }

    /** 条目被后续捕获富化后刷新卡片（用户已改过的字段由卡片状态自行保护） */
    private suspend fun refreshCardIfShowing(pendingId: Long) {
        if (ConfirmCardWindow.showingPendingId != pendingId) return
        val fresh = pendingDao.getById(pendingId) ?: return
        val categories = categoryDao.getAll()
        val accounts = accountDao.getAll()
        val learned = merchantRuleDao.getAll()
        mainHandler.post { ConfirmCardWindow.updatePending(fresh, categories, accounts, learned) }
    }

    /** debug：立即或延迟弹一张样例卡片（配置页开发者功能用；不落库） */
    fun showOverlayTest(delayMs: Long, onResult: (String) -> Unit) {
        if (!initialized) return
        if (!isOverlayConfirmEnabled()) {
            onResult("「悬浮确认卡」开关未开：设置 → 权限 → 悬浮确认卡")
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            onResult("未授权悬浮窗权限：设置 → 权限 → 悬浮确认卡 → 去授权")
            return
        }
        scope.launch {
            val categories = categoryDao.getAll()
            val accounts = accountDao.getAll()
            if (delayMs > 0) delay(delayMs)
            mainHandler.post {
                val sample = PendingTransactionEntity(
                    id = -1L,
                    type = TxType.EXPENSE,
                    amountCents = 1L,
                    timestamp = System.currentTimeMillis(),
                    counterparty = "测试商户（样例，不入账）",
                    note = "悬浮确认卡测试",
                    sourcePackage = NotificationRules.WECHAT,
                    ruleName = "微信·支出/消费（测试）",
                    dedupKey = "overlay-test",
                )
                val shown = ConfirmCardWindow.showConfirm(
                    pending = sample,
                    categories = categories,
                    accounts = accounts,
                    learnedRules = emptyList(),
                    alreadyEntered = true, // 样例按真实形态展示（"已记入一笔支出"，改/删都只是 Toast）
                    onConfirm = { _, _, _, _, _ ->
                        mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                        toast("测试卡片：保存（样例不入账）")
                    },
                    onIgnore = {
                        mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                        toast("测试卡片：忽略（样例不入账）")
                    },
                    onDismiss = {
                        mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                        toast("测试卡片：关闭（样例不入账）")
                    },
                )
                // Toast 是系统级窗口不受支付保护层拦截：Toast 可见而卡片不可见 = 卡片被系统/支付保护层拦截
                toast(if (shown) "悬浮确认卡已弹出（若看不到，看本 Toast：卡片被支付保护层拦截）" else "卡片弹不出来：检查悬浮窗权限/支付保护")
            }
        }
    }

    fun markConnected(connected: Boolean) {
        _connected.value = connected
        AppLog.i("Ntf", "listener connected=$connected")
    }

    /**
     * 收到通知即证明监听活着（进程被杀后状态可能显示未连接/假死）：直接自愈为已连接，
     * 修掉"状态显示已连接但实际假死"与"实际活着而状态为假"两类误报。
     */
    fun markAliveFromNotification() {
        if (!_connected.value) {
            _connected.value = true
            AppLog.i("Ntf", "listener self-healed (notification received)")
        }
    }

    /** 监听重连成功：清掉"更新后需重新授权"提示 */
    fun onListenerHealthy() {
        if (isReauthNeeded()) {
            clearReauthNeeded()
            AppLog.i("Ntf", "reauth flag cleared (listener healthy)")
        }
    }

    // ---------- 更新后需重新授权提示（MIUI 等 ROM 更新应用后通知使用权会静默失效） ----------

    fun isReauthNeeded(): Boolean = initialized && prefs().getBoolean(KEY_NEEDS_REAUTH, false)

    private fun setReauthNeeded(needed: Boolean) {
        prefs().edit().putBoolean(KEY_NEEDS_REAUTH, needed).apply()
    }

    fun clearReauthNeeded() = setReauthNeeded(false)

    /**
     * 覆盖安装后调用（PackageReplacedReceiver）：versionCode 变化即视为"应用更新了"，
     * 若通知监听有授权则提示复查——系统不保证更新后监听仍被绑定（小米系尤为常见）。
     */
    fun checkAppUpdated(versionCode: Int) {
        if (!initialized) return
        val last = prefs().getInt(KEY_LAST_VERSION_CODE, 0)
        if (last == versionCode) return
        prefs().edit().putInt(KEY_LAST_VERSION_CODE, versionCode).apply()
        if (last != 0 && wasListenerGranted()) {
            setReauthNeeded(true)
            AppLog.w("Ntf", "app updated ($last -> $versionCode), reauth hint set")
        }
    }

    private fun wasListenerGranted(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)

    fun markA11yConnected(connected: Boolean) {
        _a11yConnected.value = connected
        AppLog.i("Page", "a11y service connected=$connected")
    }

    fun isCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_CAPTURE_ENABLED, true)

    fun setCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Ntf", "captureEnabled=$enabled")
        AccBookAccessibilityService.refreshServiceInfo()
    }

    fun isA11yCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_A11Y_CAPTURE_ENABLED, true)

    fun setA11yCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_A11Y_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Page", "a11yCaptureEnabled=$enabled")
        AccBookAccessibilityService.refreshServiceInfo()
    }

    fun isRefundLinkEnabled(): Boolean =
        prefs().getBoolean(KEY_REFUND_LINK_ENABLED, true)

    fun setRefundLinkEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_REFUND_LINK_ENABLED, enabled).apply()
        AppLog.i("Ntf", "refundLinkEnabled=$enabled")
    }

    fun isCaptureNotifyEnabled(): Boolean =
        prefs().getBoolean(KEY_CAPTURE_NOTIFY_ENABLED, true)

    fun setCaptureNotifyEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_CAPTURE_NOTIFY_ENABLED, enabled).apply()
        AppLog.i("Ntf", "captureNotifyEnabled=$enabled")
    }

    /**
     * 自动入账（默认开：定稿默认取向）：识别到付款后按推测分类/账户直接记账，不再逐笔确认。
     * 与「悬浮窗入账」互斥（开启本项时自动关闭悬浮窗入账并收起已弹出的卡片）。
     */
    fun isAutoEntryEnabled(): Boolean =
        prefs().getBoolean(KEY_AUTO_ENTRY_ENABLED, true)

    fun setAutoEntryEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_AUTO_ENTRY_ENABLED, enabled).apply()
        if (enabled) {
            prefs().edit().putBoolean(KEY_OVERLAY_CONFIRM_ENABLED, false).apply() // 互斥：关掉悬浮窗入账
            mainHandler.post { ConfirmCardWindow.dismissAll() }
        }
        AppLog.i("Ntf", "autoEntryEnabled=$enabled (overlay=${isOverlayConfirmEnabled()})")
    }

    /**
     * 银行 App 动账推送（默认开）：银行 App 的动账通知与动账短信同源同形，
     * 走 BankLedgerRules 的「正文【银行名】签名」闸门 + 银行包名短名单兜底。
     * 不需要额外权限（走既有通知监听授权）。
     */
    fun isBankPushEnabled(): Boolean =
        prefs().getBoolean(KEY_BANK_PUSH_ENABLED, true)

    fun setBankPushEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_BANK_PUSH_ENABLED, enabled).apply()
        AppLog.i("Sms", "bankPushEnabled=$enabled")
    }

    /**
     * 银行动账短信（默认关）：银行卡直接刷卡/被扣款而没有任何 App 通知时，短信是唯一线索。
     * 只收新到短信（RECEIVE_SMS），不读历史、不解码彩信、非默认短信应用；
     * 非银行号码与无银行签名的短信在采集层丢弃（内容不入库）。
     */
    fun isSmsCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_SMS_CAPTURE_ENABLED, false)

    fun setSmsCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_SMS_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Sms", "smsCaptureEnabled=$enabled")
    }

    /** 是否已授予收短信权限（安卓 13+ 侧载同样需运行时授权） */
    fun hasSmsPermission(): Boolean =
        initialized && androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.RECEIVE_SMS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 金额待补录（默认开）：方向可辨但金额缺失的通知（如无金额的支付骨架通知）不再直接丢弃，
     * 入队 0 元条目并发「输入金额」通知，用户下拉通知栏直接打字补录。
     */
    fun isAmountReplyEnabled(): Boolean = prefs().getBoolean(KEY_AMOUNT_REPLY_ENABLED, true)

    fun setAmountReplyEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_AMOUNT_REPLY_ENABLED, enabled).apply()
        AppLog.i("Ntf", "amountReplyEnabled=$enabled")
    }

    /** 横幅通知提醒确认入账（安卓 13+ 需 POST_NOTIFICATIONS 运行时授权） */
    fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    fun isDebugCaptureAll(isDebugBuild: Boolean): Boolean =
        prefs().getBoolean(KEY_DEBUG_CAPTURE_ALL, isDebugBuild)

    fun setDebugCaptureAll(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_CAPTURE_ALL, enabled).apply()
        AppLog.i("Ntf", "debugCaptureAll=$enabled")
    }

    /**
     * 捕获所有页面（调试，默认关）：开启后无障碍通道记录**所有应用**的页面快照
     * （配置已不限包名，由服务内运行时判断放行），用于采样新应用的支付页扩规则。
     */
    fun isDebugCaptureAllPages(isDebugBuild: Boolean): Boolean =
        prefs().getBoolean(KEY_DEBUG_CAPTURE_ALL_PAGES, false)

    fun setDebugCaptureAllPages(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_CAPTURE_ALL_PAGES, enabled).apply()
        AppLog.i("Page", "debugCaptureAllPages=$enabled")
        AccBookAccessibilityService.refreshServiceInfo()
    }

    /**
     * 自动记账溯源（开发者功能，默认关）：开启后每次与入账有关的捕获都把
     * 原始来源（通知原文 / 页面快照）连同解析结果与处置方式留档，供回查"这笔账从哪来"。
     * 关闭时零写入（连判断都只有一次 SharedPreferences 读取）。
     */
    fun isTraceEnabled(): Boolean =
        prefs().getBoolean(KEY_TRACE_ENABLED, false)

    fun setTraceEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_TRACE_ENABLED, enabled).apply()
        AppLog.i("Trace", "captureTraceEnabled=$enabled")
    }

    /**
     * 溯源落库：原始来源 + 解析结果 + 处置方式。写入失败只记日志，绝不影响入账主流程
     * （开发者功能不能成为记账链路的故障点）。
     */
    private suspend fun traceCapture(
        channel: CaptureChannel,
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        capturedAt: Long,
        matchedRule: String,
        type: TxType?,
        amountCents: Long,
        counterparty: String = "",
        note: String = "",
        dedupKey: String,
        outcome: TraceOutcome,
    ) {
        if (!isTraceEnabled()) return
        runCatching {
            traceDao.insert(
                CaptureTraceEntity(
                    channel = channel,
                    packageName = packageName,
                    title = title.take(200),
                    text = text.take(4000),
                    bigText = bigText.take(4000),
                    capturedAt = capturedAt,
                    matchedRule = matchedRule,
                    type = type,
                    amountCents = amountCents,
                    counterparty = counterparty,
                    note = note,
                    dedupKey = dedupKey,
                    outcome = outcome,
                ),
            )
        }.onFailure { AppLog.w("Trace", "trace insert failed", it) }
    }

    private fun cleanup() {
        scope.launch {
            captureDao.deleteOlderThan(System.currentTimeMillis() - KEEP_DAYS * 24 * 3_600_000)
            captureDao.keepNewest(KEEP_ROWS)
            traceDao.keepNewest(TRACE_KEEP_ROWS)
            // 自动入账条目只在合并窗口（10s）内有意义，留一天足够，避免长期堆积
            pendingDao.deleteAutoOlderThan(System.currentTimeMillis() - 24 * 3_600_000)
            merchantRuleDao.keepNewest(MerchantRules.MAX_RULES)
        }
    }

    // ---------- 通道一：通知监听 ----------

    /** 由通知监听服务回调（主线程）；数据库操作全部转移到 IO */
    fun onNotificationPosted(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        postTime: Long,
        isDebugBuild: Boolean,
        groupEnriched: Boolean = false,
        notificationKey: String? = null,
        systemPostTime: Long = 0L,
    ) {
        _lastEventAt.value = System.currentTimeMillis()

        if (!initialized || packageName == appContext.packageName) return
        if (title.isBlank() && text.isBlank() && bigText.isBlank()) return

        // debug 构建全量 Logcat（用户要求：便于真实样本测试与排查）
        AppLog.d("Ntf", "onPosted pkg=$packageName title=[$title] text=[$text] bigText=[$bigText] postTime=$postTime")

        // 重发抑制必须带通知实例与原始发布时间：应用更新同一条通知时两者不变；
        // 另一笔同额交易通常换了实例或发布时间。旧逻辑只看文本，会把 10 秒内的第二笔同额交易静默吞掉。
        val eventStamp = if (postTime > 0) postTime else systemPostTime
        val key = "$packageName|${notificationKey.orEmpty()}|$eventStamp|$title|$text|$bigText"
        val now = SystemClock.elapsedRealtime()
        synchronized(recentKeys) {
            val prev = recentKeys[key]
            if (prev != null && now - prev < REPOST_WINDOW_MS) {
                AppLog.d("Ntf", "skip repost (+${now - prev}ms) pkg=$packageName key=${notificationKey.orEmpty()}")
                return
            }
            recentKeys[key] = now
            while (recentKeys.size > RECENT_KEY_CAP) {
                val eldest = recentKeys.keys.first()
                recentKeys.remove(eldest)
            }
        }

        // 采集范围 = 规则认识的来源 ∩ 用户开启的目录应用（设置页「监听的 App」）
        val interested = NotificationRules.isInterested(packageName) &&
            CaptureAppCatalog.isWatched(appContext, packageName)
        val captureAll = isDebugCaptureAll(isDebugBuild)
        // 银行 App 动账推送：不在支付白名单里，走「正文【银行名】签名 / 银行包名短名单」两道线索。
        // 是否真的入库要在解析通过后决定（银行 App 的营销/通知类推送极密集，未命中不入库）。
        // 与 debug 全量记录无关：签名线索在就尝试解析，避免采样模式下银行通道失效。
        val bankCandidate = !interested && isBankPushEnabled() && isCaptureEnabled() &&
            CaptureAppCatalog.isWatchedOrUnknown(appContext, packageName) &&
            (BankLedgerRules.detectBankName("$title $text $bigText") != null ||
                BankLedgerRules.bankNameOfPackage(packageName) != null)
        if (!interested && !captureAll && !bankCandidate) {
            AppLog.d("Ntf", "skip (not interested) pkg=$packageName title=[$title]")
            return
        }

        scope.launch {
            try {
                // 通知的 when 不保证等于真实发布时间：零值/未来/一天前一概回落到系统 postTime/采集时刻，
                // 避免异常时间污染分钟去重桶与账单日期
                val sourceAt = resolveNotificationTime(postTime, systemPostTime, System.currentTimeMillis())
                if (!interested) {
                    // 银行推送优先：签名闸门命中即走银行通道；未命中且是 debug 全量模式时，
                    // 仍留原始快照（扩规则的样本池），否则直接丢弃
                    if (bankCandidate && handleBankPush(packageName, title, text, bigText, sourceAt)) return@launch
                    if (!captureAll) return@launch
                    val rawOnlyId = captureDao.insert(
                        CapturedNotificationEntity(
                            packageName = packageName,
                            title = title,
                            text = text,
                            bigText = bigText,
                            postTime = sourceAt,
                        ),
                    )
                    AppLog.d("Ntf", "captured #$rawOnlyId (debug all) pkg=$packageName title=[$title]")
                    return@launch
                }
                val rawId = captureDao.insert(
                    CapturedNotificationEntity(
                        packageName = packageName,
                        title = title,
                        text = text,
                        bigText = bigText,
                        postTime = sourceAt,
                    ),
                )
                AppLog.d("Ntf", "captured #$rawId pkg=$packageName title=[$title]")
                if (sourceAt != postTime) {
                    AppLog.w("Ntf", "notification time abnormal postTime=$postTime -> $sourceAt pkg=$packageName")
                }

                if (!interested || !isCaptureEnabled()) return@launch

                val parsed = NotificationRules.parse(packageName, title, text, bigText, sourceAt)
                if (parsed == null) {
                    AppLog.d("Ntf", "no rule matched pkg=$packageName title=[$title] text=[$text]")
                    if (interested && isCaptureEnabled()) {
                        // 无金额的退款到账通知（微信折叠通知实测样本）→ 转退款待处理提醒
                        if (NotificationRules.isAmountlessRefundNotice(packageName, title, text, bigText)) {
                            handleRefundNotice(packageName, rawId, sourceAt, title, text, bigText)
                            return@launch
                        }
                        // 方向可辨但金额缺失（如支付骨架通知）→ 入队 0 元条目 + 通知栏「输入金额」补录
                        val hint = NotificationRules.parseDirectionOnly(packageName, title, text, bigText)
                        if (hint != null && isAmountReplyEnabled()) {
                            handleAmountMissing(
                                packageName, rawId, sourceAt, title, text, bigText, hint,
                                notificationKey = notificationKey, eventStamp = eventStamp,
                            )
                        }
                    }
                    return@launch
                }
                // 同笔的"金额待补"占位条被完整捕获取代（占位条只用于兜底提醒，不保留）
                supersedeAmountStubs(packageName, parsed.type, sourceAt)
                // 通知实例身份：同一条通知的更新共享；另一条同额通知即使同分钟也不会被并掉
                val notificationIdentity = notificationInstanceId(
                    notificationKey, eventStamp, parsed.type, parsed.isRefund,
                )
                val dedupKey = dedupKeyWithIdentity(parsed.dedupKey, notificationIdentity)
                // 同一条通知的旧条目可能已经跨过 10s 窗口：按持久化实例身份直接认作重复。
                // 但金额/方向不同说明这是同一条通知被更新成了另一笔（或待补金额已回填），
                // 不能因为实例相同就吞掉——继续走融合/入队。
                val sameInstance = notificationIdentity?.let { pendingDao.findByNotificationInstance(it) }
                val sameTransaction = sameInstance != null &&
                    sameInstance.amountCents == parsed.amountCents &&
                    sameInstance.type == parsed.type &&
                    (sameInstance.refundMark == RefundMark.REFUND_INCOME) == parsed.isRefund
                if (sameInstance != null && sameTransaction) {
                    AppLog.d("Ntf", "skip duplicate notification instance #${sameInstance.id} dedup=$dedupKey")
                    traceCapture(
                        channel = CaptureChannel.NOTIFICATION,
                        packageName = packageName,
                        title = title, text = text, bigText = bigText,
                        capturedAt = sourceAt,
                        matchedRule = parsed.ruleName,
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        note = parsed.note,
                        dedupKey = sameInstance.dedupKey,
                        outcome = TraceOutcome.SKIPPED_DUPLICATE,
                    )
                    return@launch
                }
                // 跨通道去重（10s 窗口）：页面通道先捕获/自动入账了同一笔 → 通知侧信息更少，不覆盖。
                // 通知侧严格比金额：同 App 内 10s 内两笔金额不同的付款不能被并成一笔（丢记录）
                val mergeable = pendingDao.getMergeable()
                val related = pickRelatedPending(
                    mergeable, packageName, parsed.type, parsed.amountCents,
                    System.currentTimeMillis(), flowIdentity = notificationIdentity,
                )
                if (related != null) {
                    AppLog.d("Ntf", "skip duplicate ${dedupKey} (page side won, related #${related.pending.id})")
                    mergeNotificationIntoExisting(related.pending, parsed, notificationIdentity, sourceAt)
                    traceCapture(
                        channel = CaptureChannel.NOTIFICATION,
                        packageName = packageName,
                        title = title, text = text, bigText = bigText,
                        capturedAt = sourceAt,
                        matchedRule = parsed.ruleName,
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        note = parsed.note,
                        dedupKey = related.pending.dedupKey,
                        outcome = TraceOutcome.SKIPPED_DUPLICATE,
                    )
                    return@launch
                }
                nearbyAmountConflict(
                    mergeable, packageName, parsed.type, parsed.amountCents,
                    System.currentTimeMillis(),
                )?.let { conflict ->
                    AppLog.i(
                        "Ntf",
                        "amount conflict kept separate: notify=${Money.formatYuan(parsed.amountCents)} " +
                            "existing=${Money.formatYuan(conflict.amountCents)} #${conflict.id} ${parsed.ruleName}",
                    )
                }
                // 银行动账 ⇄ 支付渠道（仅支出）：银行短信/推送先到时，本通知并入银行侧条目，
                // 不能因为包名不同就记成第二笔。必须有可核对线索（银行名/卡尾号 ⇄ 渠道名）。
                val bankRelated = findBankPendingForPayment(
                    packageName = packageName,
                    type = parsed.type,
                    amountCents = parsed.amountCents,
                    isRefund = parsed.isRefund,
                    paymentText = listOf(title, parsed.note, parsed.ruleName)
                        .filter { it.isNotBlank() }.joinToString(" "),
                    eventTime = sourceAt,
                    incomingIdentity = notificationIdentity,
                    mergeable = mergeable,
                )
                if (bankRelated != null) {
                    AppLog.i(
                        "Dedup",
                        "bank entry wins: ${parsed.ruleName} ${Money.formatYuan(parsed.amountCents)} " +
                            "-> #${bankRelated.id} ${bankRelated.sourcePackage}",
                    )
                    mergeNotificationIntoExisting(bankRelated, parsed, notificationIdentity, sourceAt)
                    captureDao.updateMatch(rawId, parsed.ruleName + "·并入银行动账", true)
                    traceCapture(
                        channel = CaptureChannel.NOTIFICATION,
                        packageName = packageName,
                        title = title, text = text, bigText = bigText,
                        capturedAt = sourceAt,
                        matchedRule = parsed.ruleName,
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        note = parsed.note,
                        dedupKey = bankRelated.dedupKey,
                        outcome = TraceOutcome.MERGED_CROSS,
                    )
                    return@launch
                }
                // 跨应用链路（电商/外卖页面 + 微信/支付宝/银行动账）：只有页面披露的支付渠道
                // 能与通知来源对上时才认作同一笔；证据不足保持独立记录
                val crossApp = findCrossAppRelatedForNotification(
                    packageName, parsed.type, parsed.amountCents, parsed.isRefund,
                    parsed.ruleName, parsed.note, sourceAt, notificationIdentity, mergeable,
                )
                if (crossApp != null) {
                    AppLog.i("Ntf", "cross-app link ${parsed.ruleName} -> #${crossApp.id} ${crossApp.sourcePackage}")
                    mergeNotificationIntoExisting(crossApp, parsed, notificationIdentity, sourceAt)
                    captureDao.updateMatch(rawId, parsed.ruleName + "·跨应用关联", true)
                    traceCapture(
                        channel = CaptureChannel.NOTIFICATION,
                        packageName = packageName,
                        title = title, text = text, bigText = bigText,
                        capturedAt = sourceAt,
                        matchedRule = parsed.ruleName,
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        note = parsed.note,
                        dedupKey = crossApp.dedupKey,
                        outcome = TraceOutcome.MERGED_CROSS_APP,
                    )
                    return@launch
                }
                // 入账方式：任一"入账"开关开启即先直接入账（三种方式全关才退回待确认的完全手动模式）
                if (directEntryEnabled()) {
                    val entered = enterEntry(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        timestamp = sourceAt,
                        counterparty = "",
                        note = parsed.note,
                        sourcePackage = packageName,
                        dedupKey = dedupKey,
                        ruleName = parsed.ruleName,
                        isRefund = parsed.isRefund,
                        notificationInstanceId = notificationIdentity.orEmpty(),
                    )
                    if (entered != null) {
                        captureDao.updateMatch(rawId, parsed.ruleName, true)
                        AppLog.i(
                            "Ntf",
                            "entered: ${parsed.ruleName} ${parsed.type} ${Money.formatYuan(parsed.amountCents)}" +
                                (if (parsed.isRefund) " [退款]" else "") + " pkg=$packageName",
                        )
                        traceCapture(
                            channel = CaptureChannel.NOTIFICATION,
                            packageName = packageName,
                            title = title, text = text, bigText = bigText,
                            capturedAt = sourceAt,
                            matchedRule = parsed.ruleName,
                            type = parsed.type,
                            amountCents = parsed.amountCents,
                            note = parsed.note,
                            dedupKey = dedupKey,
                            outcome = TraceOutcome.AUTO_ENTRY,
                        )
                        announceEntry(
                            entered = entered,
                            type = parsed.type,
                            isRefund = parsed.isRefund,
                            amountCents = parsed.amountCents,
                            sourceLabel = NotificationRules.sourceLabel(packageName),
                            counterparty = "",
                            dedupKey = dedupKey,
                        )
                        return@launch
                    }
                }
                val pendingId = pendingDao.insert(
                    PendingTransactionEntity(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        timestamp = sourceAt,
                        note = parsed.note,
                        sourcePackage = packageName,
                        ruleName = parsed.ruleName,
                        dedupKey = dedupKey,
                        rawNotificationId = rawId,
                        refundMark = if (parsed.isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                        notificationInstanceId = notificationIdentity.orEmpty(),
                    ),
                )
                captureDao.updateMatch(rawId, parsed.ruleName, true)
                AppLog.i(
                    "Ntf",
                    "queued pending: ${parsed.ruleName} ${parsed.type} ${Money.formatYuan(parsed.amountCents)}" +
                        (if (parsed.isRefund) " [退款]" else "") + " pkg=$packageName",
                )
                traceCapture(
                    channel = CaptureChannel.NOTIFICATION,
                    packageName = packageName,
                    title = title, text = text, bigText = bigText,
                    capturedAt = sourceAt,
                    matchedRule = parsed.ruleName,
                    type = parsed.type,
                    amountCents = parsed.amountCents,
                    note = parsed.note,
                    dedupKey = dedupKey,
                    outcome = TraceOutcome.PENDING,
                )
                announceCapture(
                    type = parsed.type,
                    isRefund = parsed.isRefund,
                    amountCents = parsed.amountCents,
                    sourceLabel = NotificationRules.sourceLabel(packageName),
                    counterparty = "",
                    paymentMethod = null,
                    dedupKey = dedupKey,
                    ruleName = parsed.ruleName,
                    txTime = null,
                    note = parsed.note,
                    pendingId = pendingId,
                )
            } catch (e: Exception) {
                AppLog.e("Ntf", "capture handling failed", e)
            }
        }
    }

    // ---------- 通道三：银行动账（短信 / 银行 App 推送，共用解析与入队） ----------

    /**
     * 短信通道入口（由 SmsCaptureReceiver 在 IO 协程里调用，goAsync 保证写完才 finish）。
     * 隐私闸门在最前面：既不是已知银行号码、正文里也没有银行签名 → 立即丢弃，不解析不入库
     * （银行动账短信才有记账价值，私人短信一律不碰）。
     */
    suspend fun onSmsReceived(sender: String, body: String, eventTime: Long) {
        if (!initialized || body.isBlank()) return
        if (!isSmsCaptureEnabled() || !isCaptureEnabled()) {
            AppLog.d("Sms", "skip (sms capture disabled)")
            return
        }
        val normalized = NotificationRules.normalize(body)
        val bankName = BankLedgerRules.detectBankName(normalized)
        if (!BankLedgerRules.isBankSender(sender) && bankName == null) {
            AppLog.d("Sms", "skip (not a bank message) sender=$sender")
            return
        }
        val sourceKey = NotificationRules.SMS_SOURCE_PREFIX + (bankName ?: sender.takeLast(5))
        // 先落原始快照（只有银行侧短信会走到这里）；未匹配的银行短信留在捕获日志里供扩规则
        val rawId = captureDao.insert(
            CapturedNotificationEntity(
                packageName = sourceKey,
                title = "短信 $sender",
                text = normalized.take(4000),
                postTime = eventTime,
                source = CaptureChannel.SMS,
            ),
        )
        val ledger = BankLedgerRules.parse(normalized, sourceKey, "短信", eventTime, bankName)
        if (ledger == null) {
            AppLog.i("Sms", "bank sms not matched (#$rawId) sender=$sender text=[${normalized.take(160)}]")
            return
        }
        enqueueBankLedger(ledger, rawId, eventTime, CaptureChannel.SMS)
    }

    /**
     * 银行 App 动账推送：与短信共用 BankLedgerRules。推送没有号码闸门，
     * 用「正文签名 + 包名短名单」两道线索；解析不通过就不落快照（营销推送不入库）。
     * 返回 true = 命中并已走入账流程。
     */
    private suspend fun handleBankPush(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        postTime: Long,
    ): Boolean {
        val body = NotificationRules.normalize(
            listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString(" "),
        )
        val fallbackBankName = BankLedgerRules.bankNameOfPackage(packageName)
        val ledger = BankLedgerRules.parse(body, packageName, "通知", postTime, fallbackBankName)
        if (ledger == null) {
            AppLog.d("Ntf", "bank push not matched pkg=$packageName body=[${body.take(160)}]")
            return false
        }
        val rawId = captureDao.insert(
            CapturedNotificationEntity(
                packageName = packageName,
                title = title,
                text = text,
                bigText = bigText,
                postTime = postTime,
            ),
        )
        enqueueBankLedger(ledger, rawId, postTime, CaptureChannel.NOTIFICATION)
        return true
    }

    /** 银行动账统一入队：跨通道归并 → 自动入账 → 待确认（与通知/页面通道同一套下游） */
    private suspend fun enqueueBankLedger(
        ledger: BankLedgerRules.BankLedger,
        rawId: Long,
        eventTime: Long,
        channel: CaptureChannel,
    ) {
        val sourceAt = sanitizeEventTime(eventTime, System.currentTimeMillis())
        // 银行事件身份：同一条短信/推送重复投递共享；同分钟同额的两笔真实动账互不覆盖
        val bankIdentity = bankInstanceId(
            ledger.sourceKey, sourceAt, ledger.type, ledger.amountCents, ledger.isRefund, ledger.cardTail,
        )
        val dedupKey = dedupKeyWithIdentity(ledger.dedupKey, bankIdentity)
        val sameInstance = pendingDao.findByBankInstance(bankIdentity)
        if (sameInstance != null) {
            AppLog.d("Sms", "skip duplicate bank event #${sameInstance.id} key=$dedupKey")
            traceCapture(
                channel = channel,
                packageName = ledger.sourceKey,
                title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
                text = ledger.note,
                bigText = "",
                capturedAt = sourceAt,
                matchedRule = ledger.ruleName,
                type = ledger.type,
                amountCents = ledger.amountCents,
                counterparty = ledger.merchant,
                note = ledger.note,
                dedupKey = sameInstance.dedupKey,
                outcome = TraceOutcome.SKIPPED_DUPLICATE,
            )
            return
        }

        // 跨通道归并（±130s）：支付侧已捕获同笔 → 银行侧只补充账户线索，
        // 不再产生第二条。跨应用关联必须有页面披露的银行卡线索，金额+时间不够。
        val related = findCrossChannelPending(ledger, sourceAt, bankIdentity)
        if (related != null) {
            mergeBankInfoIntoPending(related, ledger, bankIdentity)
            // 同笔的悬浮卡正在展示时同步刷新（银行侧只补账户线索）；没在展示就补试一次弹卡
            refreshCardIfShowing(related.id)
            retryCardIfNoCard(related.id)
            captureDao.updateMatch(rawId, ledger.ruleName + "·并入待确认", true)
            traceCapture(
                channel = channel,
                packageName = ledger.sourceKey,
                title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
                text = ledger.note,
                bigText = "",
                capturedAt = sourceAt,
                matchedRule = ledger.ruleName,
                type = ledger.type,
                amountCents = ledger.amountCents,
                counterparty = ledger.merchant,
                note = ledger.note,
                dedupKey = related.dedupKey,
                outcome = TraceOutcome.MERGED_CROSS,
            )
            return
        }

        // 入账方式：任一"入账"开关开启即先直接入账（与通知/页面通道同一套口径）
        if (directEntryEnabled()) {
            val entered = enterEntry(
                type = ledger.type,
                amountCents = ledger.amountCents,
                timestamp = sourceAt,
                counterparty = ledger.merchant,
                note = ledger.note,
                sourcePackage = ledger.sourceKey,
                dedupKey = dedupKey,
                ruleName = ledger.ruleName,
                isRefund = ledger.isRefund,
                source = TxSource.BANK,
                bankName = ledger.bankName,
                notificationInstanceId = bankIdentity,
            )
            if (entered != null) {
                captureDao.updateMatch(rawId, ledger.ruleName, true)
                AppLog.i(
                    "Sms",
                    "entered: ${ledger.ruleName} ${ledger.type} ${Money.formatYuan(ledger.amountCents)}" +
                        (if (ledger.isRefund) " [退款]" else "") + " ${ledger.displayLabel}",
                )
                traceCapture(
                    channel = channel,
                    packageName = ledger.sourceKey,
                    title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
                    text = ledger.note,
                    bigText = "",
                    capturedAt = sourceAt,
                    matchedRule = ledger.ruleName,
                    type = ledger.type,
                    amountCents = ledger.amountCents,
                    counterparty = ledger.merchant,
                    note = ledger.note,
                    dedupKey = dedupKey,
                    outcome = TraceOutcome.AUTO_ENTRY,
                )
                announceEntry(
                    entered = entered,
                    type = ledger.type,
                    isRefund = ledger.isRefund,
                    amountCents = ledger.amountCents,
                    sourceLabel = ledger.displayLabel,
                    counterparty = ledger.merchant,
                    dedupKey = dedupKey,
                )
                return
            }
        }

        val bankPendingId = pendingDao.insert(
            PendingTransactionEntity(
                type = ledger.type,
                amountCents = ledger.amountCents,
                timestamp = sourceAt,
                counterparty = ledger.merchant,
                note = ledger.note,
                sourcePackage = ledger.sourceKey,
                ruleName = ledger.ruleName,
                dedupKey = dedupKey,
                rawNotificationId = rawId,
                refundMark = if (ledger.isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                notificationInstanceId = bankIdentity,
            ),
        )
        captureDao.updateMatch(rawId, ledger.ruleName, true)
        AppLog.i(
            "Sms",
            "queued pending: ${ledger.ruleName} ${ledger.type} ${Money.formatYuan(ledger.amountCents)}" +
                " 账户=${ledger.accountLabel}",
        )
        traceCapture(
            channel = channel,
            packageName = ledger.sourceKey,
            title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
            text = ledger.note,
            bigText = "",
            capturedAt = sourceAt,
            matchedRule = ledger.ruleName,
            type = ledger.type,
            amountCents = ledger.amountCents,
            counterparty = ledger.merchant,
            note = ledger.note,
            dedupKey = dedupKey,
            outcome = TraceOutcome.PENDING,
        )
        announceCapture(
            type = ledger.type,
            isRefund = ledger.isRefund,
            amountCents = ledger.amountCents,
            sourceLabel = ledger.displayLabel,
            counterparty = ledger.merchant,
            paymentMethod = null,
            dedupKey = dedupKey,
            ruleName = ledger.ruleName,
            txTime = sourceAt,
            note = ledger.note,
            pendingId = bankPendingId,
        )
    }

    /**
     * 跨通道归并候选：只在有**可核对线索**时认作同一笔。
     *
     * - 电商/外卖页面：页面支付方式必须能对上银行名/卡尾号（[CrossAppLinker]）；
     * - 微信/支付宝/云闪付条目：条目备注或支付方式里出现该银行名/卡尾号，
     *   或银行侧文本点名了该渠道（"在【财付通-微信支付-微信转账】发生快捷支付扣款"）；
     * - 金额相同、时间接近但没有任何渠道线索 → 不归并（两笔真实交易并成一笔 = 丢记录）。
     * 多个候选同时命中视为证据不足，保持独立记录。
     */
    private suspend fun findCrossChannelPending(
        ledger: BankLedgerRules.BankLedger,
        eventTime: Long,
        bankIdentity: String,
    ): PendingTransactionEntity? {
        val bankEvidence = CrossAppLinker.Evidence(
            sourcePackage = ledger.sourceKey,
            amountCents = ledger.amountCents,
            type = ledger.type,
            isRefund = ledger.isRefund,
            time = eventTime,
            counterparty = ledger.merchant,
            instrument = ledger.accountLabel,
            note = ledger.note,
            ruleName = ledger.ruleName,
        )
        val mergeable = pendingDao.getMergeable().filter { flowCompatible(it, bankIdentity) }
        val pageCandidates = mergeable.filter { it.sourcePackage in NotificationRules.checkoutPackages }
            .filter { CrossAppLinker.isSamePurchase(pageEvidenceOf(it), bankEvidence) }
        if (pageCandidates.size == 1) return pageCandidates.single()
        if (pageCandidates.size > 1) {
            AppLog.w("Sms", "cross-app ambiguous (${pageCandidates.size} page entries), keep separate ${ledger.ruleName}")
            return null
        }

        val incoming = CrossChannelDedup.Signature(
            amountCents = ledger.amountCents,
            type = ledger.type,
            isRefund = ledger.isRefund,
            source = ledger.sourceKey,
            time = eventTime,
        )
        val payCandidates = mergeable.filter { candidate ->
            CrossChannelDedup.isSameTransaction(
                CrossChannelDedup.signatureOf(
                    amountCents = candidate.amountCents,
                    type = candidate.type,
                    refundMark = candidate.refundMark,
                    source = candidate.sourcePackage,
                    time = candidate.timestamp,
                ),
                incoming,
            ) && payEntryLinksBank(candidate, ledger)
        }
        if (payCandidates.size == 1) return payCandidates.single()
        if (payCandidates.size > 1) {
            AppLog.w("Sms", "bank link ambiguous (${payCandidates.size} payment entries), keep separate ${ledger.ruleName}")
        } else if (mergeable.any { it.amountCents == ledger.amountCents && it.type == ledger.type }) {
            AppLog.i("Sms", "bank entry kept separate (no bank/card evidence) ${ledger.ruleName}")
        }
        return null
    }

    /**
     * 支付类条目里是否出现了可核对线索：支付侧写了银行名/卡尾号，
     * **或**银行侧文本点名了该支付渠道（"在【财付通-微信支付-微信转账】发生快捷支付扣款"）。
     * 两个方向任一命中即可认定是同一笔卡支付，见 [CrossChannelDedup.payBankLinked]。
     */
    private fun payEntryLinksBank(
        candidate: PendingTransactionEntity,
        ledger: BankLedgerRules.BankLedger,
    ): Boolean {
        if (!CrossChannelDedup.isPayChannelSide(candidate.sourcePackage)) return false
        val payText = listOf(candidate.paymentInstrument, candidate.counterparty, candidate.note, candidate.ruleName)
            .filter { it.isNotBlank() }.joinToString(" ")
        val bankText = listOf(ledger.note, ledger.merchant, ledger.ruleName)
            .filter { it.isNotBlank() }.joinToString(" ")
        return CrossChannelDedup.payBankLinked(
            paymentText = payText,
            bankText = bankText,
            bankName = ledger.bankName,
            cardTail = ledger.cardTail,
            paySource = candidate.sourcePackage,
        )
    }

    /**
     * 既有银行侧条目里用来核对线索的文本（备注/商户/卡信息）。
     *
     * **刻意不含 ruleName**：银行条目被页面回填后 ruleName 会改写成页面侧的（如「微信·支付成功页」），
     * 拿它当"银行侧点名了渠道"的证据就成了自己证明自己；银行名另走 [bankEntryName]。
     */
    private fun bankEntryText(entry: PendingTransactionEntity): String =
        listOf(entry.note, entry.counterparty, entry.paymentInstrument)
            .filter { it.isNotBlank() }.joinToString(" ")

    /** 既有银行侧条目的银行名：优先来源键/包名，其次规则名与正文里的签名 */
    private fun bankEntryName(entry: PendingTransactionEntity): String =
        CrossAppLinker.bankNameOf(pageEvidenceOf(entry))
            ?: BankLedgerRules.detectBankName(bankEntryText(entry))
            ?: ""

    /**
     * 支付渠道侧（微信/支付宝/云闪付）本笔**后到**时，找回先到的银行侧条目。
     *
     * [findCrossChannelPending] 只覆盖"银行侧后到"这一个方向；银行推送/短信往往比页面更快，
     * 那时先落库的是银行条目，后到的支付页/通知若不回头找它，就会记成第二笔
     * （真实事故：微信转账 ¥200 支付成功页 + 招行「快捷支付扣款 200.00」）。
     *
     * 只处理**支出**（收入方向微信/支付宝收的是零钱/余额，不会同时产生银行入账）；
     * 且必须有可核对线索（[CrossChannelDedup.payBankLinked]）。
     */
    private fun findBankPendingForPayment(
        packageName: String,
        type: TxType,
        amountCents: Long,
        isRefund: Boolean,
        paymentText: String,
        eventTime: Long,
        incomingIdentity: String?,
        mergeable: List<PendingTransactionEntity>,
    ): PendingTransactionEntity? {
        if (type != TxType.EXPENSE || isRefund) return null
        if (!CrossChannelDedup.isPayChannelSide(packageName)) return null
        val incoming = CrossChannelDedup.Signature(
            amountCents = amountCents,
            type = type,
            isRefund = isRefund,
            source = packageName,
            time = eventTime,
        )
        val candidates = mergeable.filter { entry ->
            CrossChannelDedup.isSameTransaction(
                CrossChannelDedup.signatureOf(
                    amountCents = entry.amountCents,
                    type = entry.type,
                    refundMark = entry.refundMark,
                    source = entry.sourcePackage,
                    time = entry.timestamp,
                ),
                incoming,
            ) &&
                flowCompatible(entry, incomingIdentity, packageName) &&
                CrossChannelDedup.payBankLinked(
                    paymentText = paymentText,
                    bankText = bankEntryText(entry),
                    bankName = bankEntryName(entry),
                    cardTail = com.controleco.app.capture.page.PaymentInstrumentParser
                        .tailOf(entry.paymentInstrument).takeIf { it.isNotEmpty() },
                    paySource = packageName,
                )
        }
        if (candidates.size > 1) {
            AppLog.w(
                "Dedup",
                "bank/pay ambiguous (${candidates.size} bank entries), keep separate " +
                    "$packageName ${Money.formatYuan(amountCents)}",
            )
            return null
        }
        val hit = candidates.singleOrNull()
        if (hit == null && mergeable.any {
                it.type == type && it.amountCents == amountCents &&
                    CrossChannelDedup.isBankLedgerSide(it.sourcePackage)
            }
        ) {
            AppLog.i(
                "Dedup",
                "bank entry kept separate (no channel/card evidence) " +
                    "$packageName ${Money.formatYuan(amountCents)}",
            )
        }
        return hit
    }

    /**
     * 银行侧信息并入既有条目：支付侧信息更全（商户/支付方式），页面未手改时追加银行侧账户线索
     * （"银行 工商银行(1234)"），不改金额/时间/方向（金额全等才可能命中）。
     * 用户手动改过的条目只记录银行事件身份，不改备注/商户/账户。
     * 目标若是自动入账条目，同步回填交易本身，并在能唯一匹配账户时更新账户。
     */
    private suspend fun mergeBankInfoIntoPending(
        existing: PendingTransactionEntity,
        ledger: BankLedgerRules.BankLedger,
        bankIdentity: String,
    ) {
        val extra = "银行 ${ledger.accountLabel}"
        val note = when {
            existing.userEdited -> existing.note
            existing.note.contains(ledger.accountLabel) -> existing.note
            existing.note.isBlank() -> extra
            else -> "${existing.note} · $extra".take(300)
        }
        val counterparty = existing.counterparty.ifBlank { ledger.merchant }
        val instrument = existing.paymentInstrument.ifBlank { ledger.accountLabel }
        db.withTransaction {
            pendingDao.updateFromPage(
                existing.id,
                existing.amountCents,
                existing.timestamp,
                counterparty,
                note,
                existing.ruleName,
            )
            if (existing.paymentInstrument.isBlank()) {
                pendingDao.updatePaymentInstrument(existing.id, instrument)
            }
            pendingDao.updateBankInstanceIfBlank(existing.id, bankIdentity)
            if (existing.status == PendingStatus.AUTO && !existing.userEdited) {
                txDao.updateCapturedByExternalId(
                    "NTF:${existing.dedupKey}",
                    existing.amountCents,
                    existing.timestamp,
                    counterparty,
                    note,
                )
                syncAutoEntryAccount(existing, instrument)
            }
        }
        AppLog.i(
            "Sms",
            "merged bank info into " +
                (if (existing.status == PendingStatus.AUTO) "auto entry" else "pending") +
                " #${existing.id} ${ledger.ruleName} 账户=${ledger.accountLabel}" +
                (if (existing.userEdited) " [用户已手改，仅记录银行身份]" else ""),
        )
    }

    /**
     * 页面/银行补充可信支付工具后，重新匹配正式账单的账户。
     * 只在唯一/明确匹配时更新；银行卡工具不允许回落"渠道账户"（否则会把招商银行卡的账记到微信零钱）。
     */
    private suspend fun syncAutoEntryAccount(existing: PendingTransactionEntity, instrumentLabel: String) {
        if (instrumentLabel.isBlank()) return
        val accountId = CaptureDefaults.trustedAccountId(
            accountDao.getAll(), existing.sourcePackage, instrumentLabel,
        ) ?: return
        txDao.updateAccountByExternalId("NTF:${existing.dedupKey}", accountId)
        AppLog.i("Page", "account updated #${existing.id} ${existing.sourcePackage} -> #$accountId ($instrumentLabel)")
    }

    /**
     * 通知 → 页面方向的补充：页面先到时只补页面缺失的字段（更接近真实交易时刻的发布时间），
     * 绝不覆盖页面已确认的金额/商户/支付方式；同时补记通知实例身份。
     */
    private suspend fun mergeNotificationIntoExisting(
        existing: PendingTransactionEntity,
        parsed: ParsedNotification,
        notificationIdentity: String?,
        notificationTime: Long,
    ) {
        val fixedTime = mergeNotificationInfo(existing, notificationTime)
        db.withTransaction {
            if (fixedTime != null) {
                pendingDao.updateFromPage(
                    existing.id, existing.amountCents, fixedTime,
                    existing.counterparty, existing.note, existing.ruleName,
                )
                if (existing.status == PendingStatus.AUTO) {
                    txDao.updateCapturedByExternalId(
                        "NTF:${existing.dedupKey}",
                        existing.amountCents,
                        fixedTime,
                        existing.counterparty,
                        existing.note,
                    )
                }
            }
            if (!notificationIdentity.isNullOrEmpty()) {
                pendingDao.updateNotificationInstanceIfBlank(existing.id, notificationIdentity)
            }
        }
        if (fixedTime != null) {
            AppLog.i("Ntf", "enriched entry #${existing.id} with notification time")
            refreshCardIfShowing(existing.id)
        }
        AppLog.d("Ntf", "notification merged into #${existing.id} rule=${parsed.ruleName} identity=$notificationIdentity")
    }

    /**
     * 通知路径的跨应用关联：既有条目必须是电商/外卖页面，且页面支付方式能对上通知来源。
     * 没有渠道线索、或候选不唯一时不合并（安全优先）。
     */
    private suspend fun findCrossAppRelatedForNotification(
        packageName: String,
        type: TxType,
        amountCents: Long,
        isRefund: Boolean,
        ruleName: String,
        note: String,
        eventTime: Long,
        notificationIdentity: String?,
        mergeable: List<PendingTransactionEntity>,
    ): PendingTransactionEntity? {
        if (packageName in NotificationRules.checkoutPackages) return null
        if (CrossChannelDedup.channelClass(packageName) == CrossChannelDedup.ChannelClass.UNKNOWN) return null
        val incoming = CrossAppLinker.Evidence(
            sourcePackage = packageName,
            amountCents = amountCents,
            type = type,
            isRefund = isRefund,
            time = eventTime,
            note = note,
            ruleName = ruleName,
        )
        val candidates = mergeable.filter { candidate ->
            candidate.sourcePackage in NotificationRules.checkoutPackages &&
                flowCompatible(candidate, notificationIdentity, packageName) &&
                CrossAppLinker.isSamePurchase(pageEvidenceOf(candidate), incoming)
        }
        if (candidates.size > 1) {
            AppLog.w("Ntf", "cross-app ambiguous (${candidates.size} page entries), keep separate $ruleName")
            return null
        }
        return candidates.singleOrNull()?.also { hit ->
            val reason = CrossAppLinker.linkReason(pageEvidenceOf(hit), incoming)
            AppLog.i(
                "Dedup",
                "cross-app same transaction (notification): ${NotificationRules.sourceLabel(packageName)} " +
                    "${Money.formatYuan(amountCents)} -> #${hit.id} ${hit.sourcePackage} reason=$reason",
            )
        }
    }

    /**
     * 页面路径的跨应用关联：**电商/外卖页面**与已捕获的支付应用（微信/支付宝）/银行条目是否同一笔。
     *
     * 真实链路只有一条方向：用户在微信/支付宝里付款 → 该通道先捕获 → 用户返回（或切回）
     * 电商/外卖应用 → 商户结果页随后被捕获。因此这里只处理"本条是商户页、对侧是支付/银行"。
     *
     * 判重口径（2026-10-19 调整，见 [CrossAppLinker.linkReason]）：
     * - 有明确渠道线索（页面写"微信支付/支付宝"、银行名/卡尾号）→ 130 秒内可关联；
     * - 没有渠道线索时，两条链路在 [CrossAppLinker.SAME_AMOUNT_WINDOW_MS] 内捕获到
     *   **完全相同金额**（如美团结果页 + 微信支付页/通知）即视为同一笔；
     * - 两个商户页面之间、两个支付通知之间永不按同额合并；
     * - 同一应用内的两笔同额订单由页面流程身份隔离（[flowCompatible] 按 incomingPackage 比较），
     *   不需要在这里另加闸门。
     */
    private suspend fun findCrossAppRelatedForPage(
        packageName: String,
        candidate: PageCandidate,
        pageFlowIdentity: String,
        capturedAt: Long,
        mergeable: List<PendingTransactionEntity>,
    ): PendingTransactionEntity? {
        if (packageName !in NotificationRules.checkoutPackages) return null
        val pageEvidence = pageEvidenceOf(candidate, packageName, capturedAt)

        val candidates = mergeable.filter { existing ->
            existing.sourcePackage != packageName &&
                CrossChannelDedup.channelClass(existing.sourcePackage) != CrossChannelDedup.ChannelClass.UNKNOWN &&
                flowCompatible(existing, pageFlowIdentity, packageName) &&
                kotlin.math.abs(existing.timestamp - pageEvidence.time) <= CrossAppLinker.WINDOW_MS &&
                CrossAppLinker.isSamePurchase(pageEvidence, pendingEvidenceOf(existing))
        }
        if (candidates.size > 1) {
            AppLog.w(
                "Page",
                "cross-app ambiguous (${candidates.size} entries: " +
                    candidates.joinToString { it.sourcePackage } +
                    "), keep separate ${candidate.ruleName}",
            )
            return null
        }
        val hit = candidates.singleOrNull()
        if (hit == null) {
            // 诊断：窗口内有同额的另一条链路，但没有通过证据闸门（商户/渠道/流程冲突）。
            // 这类"看起来像同一笔却没合并"的情形最容易在真机上被误判成漏判，必须留痕。
            val nearMiss = mergeable.filter { existing ->
                existing.sourcePackage != packageName &&
                    existing.amountCents == candidate.amountCents && existing.type == candidate.type &&
                    kotlin.math.abs(existing.timestamp - pageEvidence.time) <= CrossAppLinker.WINDOW_MS &&
                    CrossChannelDedup.channelClass(existing.sourcePackage) !=
                    CrossChannelDedup.ChannelClass.UNKNOWN
            }
            if (nearMiss.isNotEmpty()) {
                AppLog.i(
                    "Dedup",
                    "cross-app same amount kept separate: page=${candidate.ruleName} " +
                        "${Money.formatYuan(candidate.amountCents)} candidates=" +
                        nearMiss.joinToString { "#${it.id}/${it.sourcePackage}" } +
                        " pageFlow=$pageFlowIdentity",
                )
            }
            return null
        }
        val reason = CrossAppLinker.linkReason(pageEvidence, pendingEvidenceOf(hit))
        AppLog.i(
            "Dedup",
            "cross-app same transaction: page=${candidate.ruleName} ${Money.formatYuan(candidate.amountCents)} " +
                "-> #${hit.id} ${hit.sourcePackage} reason=$reason " +
                "pageFlow=$pageFlowIdentity",
        )
        return hit
    }

    private fun pageEvidenceOf(pending: PendingTransactionEntity): CrossAppLinker.Evidence =
        CrossAppLinker.Evidence(
            sourcePackage = pending.sourcePackage,
            amountCents = pending.amountCents,
            type = pending.type,
            isRefund = pending.refundMark == RefundMark.REFUND_INCOME,
            time = pending.timestamp,
            counterparty = pending.counterparty,
            instrument = pending.paymentInstrument,
            note = pending.note,
            ruleName = pending.ruleName,
            orderId = pending.orderId,
        )

    private fun pendingEvidenceOf(pending: PendingTransactionEntity): CrossAppLinker.Evidence =
        pageEvidenceOf(pending)

    private fun pageEvidenceOf(
        candidate: PageCandidate,
        sourcePackage: String,
        capturedAt: Long,
    ): CrossAppLinker.Evidence = CrossAppLinker.Evidence(
        sourcePackage = sourcePackage,
        amountCents = candidate.amountCents,
        type = candidate.type,
        isRefund = candidate.isRefund,
        time = candidate.txTime ?: capturedAt,
        counterparty = candidate.counterparty,
        instrument = candidate.instrument?.label ?: candidate.paymentMethod.orEmpty(),
        note = candidate.note,
        ruleName = candidate.ruleName,
        orderId = candidate.orderId.orEmpty(),
    )

    // ---------- 退款待处理提醒（无金额退款通知） ----------

    /** 微信"退款到账通知"没有金额，无法直接入队：发通知提醒用户进 App 选被退款的账单 */
    private suspend fun handleRefundNotice(
        packageName: String,
        rawId: Long,
        postTime: Long,
        title: String,
        text: String,
        bigText: String,
    ) {
        captureDao.updateMatch(rawId, "退款·待处理", false)
        AppLog.i("Ntf", "amountless refund notice -> refund reminder pkg=$packageName")
        // 溯源记录不产生账目：dedupKey 用提醒 id 的同一构造，永远不会和交易对上（状态固定为"仅提醒"）
        traceCapture(
            channel = CaptureChannel.NOTIFICATION,
            packageName = packageName,
            title = title, text = text, bigText = bigText,
            capturedAt = if (postTime > 0) postTime else System.currentTimeMillis(),
            matchedRule = "退款·待处理",
            type = null,
            amountCents = 0,
            dedupKey = "refund|$packageName|$postTime",
            outcome = TraceOutcome.REFUND_NOTICE,
        )
        if (!isRefundLinkEnabled()) return
        val label = NotificationRules.sourceLabel(packageName)
        val notifId = ("refund|$packageName|$postTime").hashCode()
        postCaptureNotification(
            "${label}退款到账",
            "点开选择被退款的账单，记为退款收入",
            ROUTE_REFUND_LINK,
            notifId,
        )
    }

    // ---------- 通道二：无障碍页面抓取 ----------

    /**
     * 由无障碍服务回调（采集线程）：处理一份结构化页面快照。
     *
     * 调用链（页面通道的唯一入口）：
     * `PageParserRegistry.parse`（按包名分派专属解析器）→ 页面候选 → 与既有条目融合 → 入账/待确认。
     * 旧的"通用关键词解析 + 按包名白名单再过滤一次"入口已删除，不存在第二个页面解析入口。
     */
    fun onPageCaptured(snapshot: PageSnapshot, isDebugBuild: Boolean) {
        _lastEventAt.value = System.currentTimeMillis()

        if (!initialized || snapshot.lines.isEmpty()) return
        val packageName = snapshot.packageName
        // 「捕获所有页面（调试）」优先于监听目录：这个开关的目的就是采样任意应用的页面扩规则
        val captureAll = isDebugCaptureAllPages(isDebugBuild)
        if (!captureAll && packageName != appContext.packageName &&
            !CaptureAppCatalog.pageCaptureAllowed(appContext, packageName)
        ) {
            AppLog.d("Page", "skip (app not watched) pkg=$packageName")
            return
        }
        // 总开关 + 页面通道开关：两个都要看（此前总开关没有约束页面通道，自测发现的缺陷）
        if (!isCaptureEnabled()) {
            AppLog.d("Page", "skip (capture disabled) pkg=$packageName")
            return
        }
        if (!isA11yCaptureEnabled() && !captureAll) {
            AppLog.d("Page", "skip (a11y capture disabled) pkg=$packageName")
            return
        }

        scope.launch {
            try {
                handlePageSnapshot(snapshot, captureAll, isDebugBuild)
            } catch (e: Exception) {
                AppLog.e("Page", "page capture handling failed", e)
            }
        }
    }

    /** 页面快照的处理主体：解析 → 留样 → 融合 → 入账 */
    private suspend fun handlePageSnapshot(snapshot: PageSnapshot, captureAll: Boolean, isDebugBuild: Boolean) {
        val packageName = snapshot.packageName
        val capturedAt = snapshot.captureTime

        val flows = PageFlowTracker.shared
        flows.evictExpired()
        val flow = flows.flow(packageName)
        val result = PageParserRegistry.parse(snapshot, flow)
        val candidate = (result as? PageParseResult.Candidate)?.candidate
        // 用户离开了上一笔的结果页（桌面/聊天/列表/其它页面）：之后再次出现同一结果页才可能是新的一笔。
        // 只在终结态生效，重复采样同一结果页不会触发新流程（见 PageFlow.markEmitted）。
        if (result is PageParseResult.NotATradePage ||
            (result is PageParseResult.SceneOnly && result.scene == PageScene.LIST)
        ) {
            // 加载中/网络异常这类过渡页不算"离开"：否则结果页重渲染后会被当成新的一笔
            if (!looksLikeTransientPage(snapshot)) {
                flow.noteLeftTradePage(capturedAt)
            }
        }

        val interesting = candidate != null || isInterestingPage(snapshot, result)
        if (!interesting && !captureAll) {
            AppLog.d("Page", "skip (not interesting) pkg=$packageName lines=${snapshot.lines.size}")
            return
        }

        // 支付类页面快照进 INFO 日志（华为默认隐藏 DEBUG 级日志）；文本做卡尾号遮罩后再落日志与样本库。
        // 日志里只对"交易相关页面"给足正文（调规则要看它）：无关页面（桌面/系统界面/聊天）只给短摘要——
        // 通知栏正文、桌面图标清单属于用户隐私，不该进日志（样本库仍在本地留全量，供扩规则）。
        val snapshotText = NotificationRules.maskCardTails(snapshot.lines.joinToString(" | ")).take(4000)
        // 注意不要写成 `(result as? SceneOnly)?.scene != UNKNOWN`：转换失败时左边是 null，
        // `null != UNKNOWN` 恒为真，反而把无关页面判成交易相关（真机日志里踩到过）
        val tradeRelated = candidate != null ||
            (result is PageParseResult.SceneOnly && result.scene != PageScene.UNKNOWN) ||
            PageParserRegistry.hasDedicatedParser(packageName)
        val logLimit = if (tradeRelated || isDebugBuild) 600 else 120
        if (candidate != null) {
            AppLog.i(
                "Page",
                "page matched pkg=$packageName rule=${candidate.ruleName} scene=${candidate.scene} " +
                    "state=${candidate.tradeState} ${Money.formatYuan(candidate.amountCents)} " +
                    "收款方=${candidate.counterparty} 支付方式=${candidate.paymentMethod ?: "-"} " +
                    "ask=${candidate.requiresUserConfirmation} " +
                    "texts=[${snapshotText.take(logLimit)}]",
            )
        } else {
            AppLog.i(
                "Page",
                "page scene pkg=$packageName " +
                    "detail=${(result as? PageParseResult.SceneOnly)?.let { "${it.scene}/${it.tradeState} ${it.reason}" } ?: "-"} " +
                    "texts=[${snapshotText.take(logLimit)}]",
            )
        }

        val rawId = captureDao.insert(
            CapturedNotificationEntity(
                packageName = packageName,
                title = candidate?.ruleName ?: "页面快照",
                text = snapshotText,
                postTime = capturedAt,
                source = CaptureChannel.A11Y,
            ),
        )
        if (candidate == null) {
            captureDao.updateMatch(rawId, "页面快照", false)
            return
        }

        if (!isA11yCaptureEnabled()) {
            // 采样模式（页面抓取关 + 调试全量开）：只留证据，不入队不弹窗
            captureDao.updateMatch(rawId, candidate.ruleName, false)
            AppLog.i("Page", "skip enqueue（设置页「自动记录支付页面」开关关闭）${candidate.ruleName}")
            return
        }

        // 微信红包领取页：不走自动入账/融合，落 ASK 待确认并弹悬浮卡让用户决定。
        // 放在融合与入账之前，确保自动入账开关开着也不会把红包猜成账。
        if (candidate.requiresUserConfirmation) {
            queueUserConfirmation(candidate, packageName, rawId, snapshotText, capturedAt)
            return
        }

        // 稳定页面去重：带页面交易时间的候选（收款到账页/详情页）在短 TTL 内只处理一次。
        // 交易时间不随捕获分钟/流程重置变化，避免同一页面在下一分钟或重新走一笔后被再次入账。
        // 没有交易时间的成功页不缓存：两笔同额真实交易的页面文本可能完全相同，需由流程身份区分。
        val pageTxTime = candidate.txTime
        if (pageTxTime != null && pageTxTime > 0 &&
            recentPageCache.markIfDuplicate(
                stablePageKey(
                    packageName = packageName,
                    type = candidate.type,
                    isRefund = candidate.isRefund,
                    ruleName = candidate.ruleName,
                    amountCents = candidate.amountCents,
                    txTime = pageTxTime,
                ),
            )
        ) {
            captureDao.updateMatch(rawId, candidate.ruleName, false)
            AppLog.i(
                "Page",
                "skip duplicate stable page pkg=$packageName rule=${candidate.ruleName} " +
                    "${Money.formatYuan(candidate.amountCents)} txTime=$pageTxTime",
            )
            return
        }

        // 同笔的"金额待补"占位条被完整页面捕获取代（占位条只用于兜底提醒，不保留）
        supersedeAmountStubs(packageName, candidate.type, capturedAt)
        // 去重键：页面候选首次生成就带流程身份（p:<uuid>），持久化后不再依赖内存分配器；
        // 同一流程复用同一个键，不同流程即使同分钟同额也不会互相覆盖。
        val flowIdentity = flow.flowInstanceId
        val dedupKey = dedupKeyWithIdentity(candidate.dedupKey, flowIdentity)

        // 融合（页面 → 通知 / 同流程的后续页面）：命中即合并，不产生第二条。
        // 金额不同一律不合并（CR-003）：页面金额是否锚定只说明该金额可信，
        // 不能证明 5.20 与 7.50 是同一笔；金额不同的页面独立成条，交由用户确认。
        val mergeable = pendingDao.getMergeable()
        val related = pickRelatedPendingForPage(
            mergeable, packageName, candidate.type, candidate.amountCents, capturedAt, flowIdentity,
            orderId = candidate.orderId,
            txTime = candidate.txTime,
        )
        if (related != null) {
            mergePageIntoExisting(related, candidate, rawId, snapshotText, capturedAt, flowIdentity)
            return
        }
        // 页面明确给出交易时间（账单详情页）：进程重启后 flowId 已变化，仍按"同来源+同方向+同额+
        // 交易时间几乎一致"认回既有条目，避免重新打开同一笔详情时重复入账（CR-002/T18）
        if (candidate.scene == PageScene.TRANSACTION_DETAIL && candidate.txTime != null) {
            pickByExplicitTime(
                mergeable, packageName, candidate.type, candidate.amountCents,
                candidate.txTime, candidate.isRefund,
            )?.let { sameByTime ->
                AppLog.i("Page", "same transaction by explicit time #${sameByTime.pending.id} ${candidate.ruleName}")
                mergePageIntoExisting(sameByTime, candidate, rawId, snapshotText, capturedAt, flowIdentity)
                return
            }
        }
        // 银行动账 ⇄ 支付渠道页面（仅支出）：银行短信/推送先到、本页后到 → 回填既有银行侧条目，
        // 不另记一笔（页面信息最全：商户/支付工具/时间）。
        val bankRelated = findBankPendingForPayment(
            packageName = packageName,
            type = candidate.type,
            amountCents = candidate.amountCents,
            isRefund = candidate.isRefund,
            paymentText = listOf(
                candidate.counterparty,
                candidate.note,
                candidate.paymentMethod.orEmpty(),
                candidate.instrument?.label.orEmpty(),
                candidate.ruleName,
            ).filter { it.isNotBlank() }.joinToString(" "),
            eventTime = candidate.txTime ?: capturedAt,
            incomingIdentity = flowIdentity,
            mergeable = mergeable,
        )
        if (bankRelated != null) {
            AppLog.i(
                "Dedup",
                "cross-channel bank merge: page=${candidate.ruleName} " +
                    "${Money.formatYuan(candidate.amountCents)} -> #${bankRelated.id} ${bankRelated.sourcePackage}",
            )
            // 银行侧条目保留自己的正文（"在【财付通-微信支付-微信转账】…"这类渠道线索就在里面），
            // 页面信息追加在后面——与反向合并（mergeBankInfoIntoPending 把银行线索追加到支付条目）对称
            mergePageIntoExisting(
                RelatedPending(bankRelated, sameAmount = true),
                candidate.copy(note = combineNotes(candidate.note, bankRelated.note)),
                rawId, snapshotText, capturedAt, flowIdentity,
                crossApp = true,
            )
            return
        }
        nearbyAmountConflict(
            mergeable, packageName, candidate.type, candidate.amountCents, capturedAt,
        )?.let { conflict ->
            AppLog.i(
                "Page",
                "amount conflict kept separate: page=${Money.formatYuan(candidate.amountCents)} " +
                    "existing=${Money.formatYuan(conflict.amountCents)} #${conflict.id} ${candidate.ruleName}",
            )
        }
        // 跨应用：电商/外卖页面 + 微信/支付宝通知/银行动账。
        // 明确渠道线索（130s）或同一笔记在两条链路上短时同额捕获（60s）都算同一笔。
        val crossApp = findCrossAppRelatedForPage(packageName, candidate, flowIdentity, capturedAt, mergeable)
        if (crossApp != null) {
            AppLog.i("Page", "cross-app link ${candidate.ruleName} -> #${crossApp.id} ${crossApp.sourcePackage}")
            mergePageIntoExisting(
                RelatedPending(crossApp, sameAmount = true),
                candidate, rawId, snapshotText, capturedAt, flowIdentity,
                crossApp = true,
            )
            return
        }

        // 页面信息最全：任一入账开关开启即直接入账
        if (directEntryEnabled()) {
            val entered = enterEntry(
                type = candidate.type,
                amountCents = candidate.amountCents,
                timestamp = candidate.txTime ?: capturedAt,
                counterparty = candidate.counterparty,
                note = candidate.note,
                sourcePackage = packageName,
                dedupKey = dedupKey,
                ruleName = candidate.ruleName,
                isRefund = candidate.isRefund,
                source = CaptureDefaults.txSource(packageName),
                paymentInstrument = candidate.instrument?.label,
                flowId = flowIdentity,
                orderId = candidate.orderId.orEmpty(),
            )
            if (entered != null) {
                captureDao.updateMatch(rawId, candidate.ruleName, true)
                AppLog.i(
                    "Page",
                    "entered: ${candidate.ruleName} ${Money.formatYuan(candidate.amountCents)} " +
                        "收款方=${candidate.counterparty} 支付方式=${candidate.paymentMethod ?: "-"}",
                )
                traceCapture(
                    channel = CaptureChannel.A11Y,
                    packageName = packageName,
                    title = "页面快照",
                    text = snapshotText,
                    bigText = "",
                    capturedAt = capturedAt,
                    matchedRule = candidate.ruleName,
                    type = candidate.type,
                    amountCents = candidate.amountCents,
                    counterparty = candidate.counterparty,
                    note = candidate.note,
                    dedupKey = dedupKey,
                    outcome = TraceOutcome.AUTO_ENTRY,
                )
                announceEntry(
                    entered = entered,
                    type = candidate.type,
                    isRefund = candidate.isRefund,
                    amountCents = candidate.amountCents,
                    sourceLabel = candidate.sourceLabel,
                    counterparty = candidate.counterparty,
                    dedupKey = dedupKey,
                )
                return
            }
        }

        // enterEntry 返回 null（最近同额预检命中/无可推测账户）时仍插入 pending：
        // 60 秒内的两笔真实同额交易（如两笔 1.00）必须保留为待确认，不能丢——
        // 真正的"同一稳定页面重复"已由上面的 recentPageCache 在融合/入账前拦掉。
        val pendingId = pendingDao.insert(
            PendingTransactionEntity(
                type = candidate.type,
                amountCents = candidate.amountCents,
                timestamp = candidate.txTime ?: capturedAt,
                counterparty = candidate.counterparty,
                note = candidate.note,
                sourcePackage = packageName,
                ruleName = candidate.ruleName,
                dedupKey = dedupKey,
                rawNotificationId = rawId,
                refundMark = if (candidate.isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                paymentInstrument = candidate.instrument?.label.orEmpty(),
                flowId = flowIdentity,
                orderId = candidate.orderId.orEmpty(),
            ),
        )
        captureDao.updateMatch(rawId, candidate.ruleName, true)
        AppLog.i(
            "Page",
            "queued pending: ${candidate.ruleName} ${Money.formatYuan(candidate.amountCents)} " +
                "收款方=${candidate.counterparty} 支付方式=${candidate.paymentMethod ?: "-"}",
        )
        traceCapture(
            channel = CaptureChannel.A11Y,
            packageName = packageName,
            title = "页面快照",
            text = snapshotText,
            bigText = "",
            capturedAt = capturedAt,
            matchedRule = candidate.ruleName,
            type = candidate.type,
            amountCents = candidate.amountCents,
            counterparty = candidate.counterparty,
            note = candidate.note,
            dedupKey = dedupKey,
            outcome = TraceOutcome.PENDING,
        )
        announceCapture(
            type = candidate.type,
            isRefund = candidate.isRefund,
            amountCents = candidate.amountCents,
            sourceLabel = candidate.sourceLabel,
            counterparty = candidate.counterparty,
            paymentMethod = candidate.paymentMethod,
            dedupKey = dedupKey,
            ruleName = candidate.ruleName,
            txTime = candidate.txTime,
            note = candidate.note,
            pendingId = pendingId,
        )
    }

    /**
     * 微信红包领取页：落一条 ASK 待确认条目并弹悬浮卡询问用户。
     *
     * 红包不再自动记账：金额只作为卡片预填值，用户点「记一笔」才入账，
     * 点「忽略」删除条目，关闭/超时只收起卡片（条目留在待确认列表）。
     * ASK 不进入跨通道合并池，因此不会被同额转账/收款自动认领。
     * 卡片弹不出来（无悬浮窗权限/前台不匹配/被支付保护层拦截）时回落横幅通知。
     */
    private suspend fun queueUserConfirmation(
        candidate: PageCandidate,
        packageName: String,
        rawId: Long,
        snapshotText: String,
        capturedAt: Long,
    ) {
        // 每次询问用独立身份：同一分钟、同额的两个红包不会撞去重键，也不会与页面流程互相认领。
        val promptIdentity = "r:" + java.util.UUID.randomUUID().toString()
        val dedupKey = dedupKeyWithIdentity(candidate.dedupKey, promptIdentity)
        val pendingId = pendingDao.insert(
            PendingTransactionEntity(
                type = candidate.type,
                amountCents = candidate.amountCents,
                timestamp = capturedAt,
                counterparty = candidate.counterparty,
                note = candidate.note,
                sourcePackage = packageName,
                ruleName = candidate.ruleName,
                dedupKey = dedupKey,
                rawNotificationId = rawId,
                paymentInstrument = candidate.instrument?.label.orEmpty(),
                flowId = promptIdentity,
                orderId = candidate.orderId.orEmpty(),
                status = PendingStatus.ASK,
            ),
        )
        if (pendingId <= 0) {
            captureDao.updateMatch(rawId, candidate.ruleName, false)
            AppLog.w("Page", "user confirmation queue failed: ${candidate.ruleName} key=$dedupKey")
            return
        }
        captureDao.updateMatch(rawId, candidate.ruleName, true)
        AppLog.i(
            "Page",
            "queued red packet confirmation: #$pendingId ${candidate.ruleName} " +
                "${Money.formatYuan(candidate.amountCents)} 对方=${candidate.counterparty} " +
                "ask=${candidate.requiresUserConfirmation}",
        )
        traceCapture(
            channel = CaptureChannel.A11Y,
            packageName = packageName,
            title = "页面快照",
            text = snapshotText,
            bigText = "",
            capturedAt = capturedAt,
            matchedRule = candidate.ruleName,
            type = candidate.type,
            amountCents = candidate.amountCents,
            counterparty = candidate.counterparty,
            note = candidate.note,
            dedupKey = dedupKey,
            outcome = TraceOutcome.PENDING,
        )
        val pending = pendingDao.getById(pendingId) ?: return
        val shown = tryShowConfirmCard(
            pending = pending,
            alreadyEntered = false,
            isRefund = false,
            sourceLabel = candidate.sourceLabel,
            counterparty = candidate.counterparty,
            dedupKey = dedupKey,
            forcePrompt = true,
            askMode = true,
        )
        if (!shown) {
            // 延迟期间条目可能已被用户处理；只对仍然存在的条目发兜底提醒。
            if (pendingDao.getById(pending.id) != null) postUserConfirmationNotification(pending)
        }
    }

    /** 红包询问卡无法展示时的兜底：点击进待确认列表，附「忽略」动作 */
    private fun postUserConfirmationNotification(pending: PendingTransactionEntity) {
        val text = if (pending.amountCents > 0) {
            "检测到红包 ${Money.formatYuan(pending.amountCents)} · 点开确认是否记账"
        } else {
            "检测到红包领取页 · 点开补录金额或忽略"
        }
        postCaptureNotification(
            "微信红包待确认",
            text,
            ROUTE_PENDING,
            pendingNotifId(pending.dedupKey),
            action = ignoreAction(IgnoreTarget(pending.id, pending.dedupKey)),
        )
    }

    /**
     * 页面候选并入既有条目（页面信息优先）。
     * 覆盖金额/时间/商户/备注/支付工具；用户手改过的条目只补空白（见 mergePageInfo）；
     * 已自动入账的条目同步回填交易本身。
     *
     * [crossApp] = 本次是跨应用（商户页 ⇄ 支付应用页/通知）的同一笔：溯源里单独标记，
     * 便于区分"同应用结果页/详情页回填"与"两个应用的对账结果"。
     */
    private suspend fun mergePageIntoExisting(
        related: RelatedPending,
        candidate: PageCandidate,
        rawId: Long,
        snapshotText: String,
        capturedAt: Long,
        pageFlowIdentity: String,
        crossApp: Boolean = false,
    ) {
        val existing = related.pending
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = candidate.amountCents,
            pageTxTime = candidate.txTime,
            pageCounterparty = candidate.counterparty,
            pageNote = candidate.note,
            pagePaymentMethod = candidate.paymentMethod,
            pageRuleName = candidate.ruleName,
            pageInstrument = candidate.instrument?.label.orEmpty(),
        )
        val autoEntered = existing.status == PendingStatus.AUTO
        db.withTransaction {
            pendingDao.updateFromPage(
                existing.id, merged.amountCents, merged.timestamp, merged.counterparty, merged.note, merged.ruleName,
            )
            if (merged.paymentInstrument.isNotBlank() && merged.paymentInstrument != existing.paymentInstrument) {
                pendingDao.updatePaymentInstrument(existing.id, merged.paymentInstrument)
            }
            pendingDao.updateFlowIdIfBlank(existing.id, pageFlowIdentity)
            candidate.orderId?.takeIf { it.isNotBlank() }?.let {
                pendingDao.updateOrderIdIfBlank(existing.id, it)
            }
            if (autoEntered && !existing.userEdited) {
                // 自动入账的那笔：把页面信息同步回交易本身，并补上流程标识/账户
                txDao.updateCapturedByExternalId(
                    "NTF:${existing.dedupKey}",
                    merged.amountCents,
                    merged.timestamp,
                    merged.counterparty,
                    merged.note,
                )
                txDao.updateFlowIdByExternalId("NTF:${existing.dedupKey}", pageFlowIdentity)
                if (merged.paymentInstrument.isNotBlank()) {
                    syncAutoEntryAccount(existing, merged.paymentInstrument)
                }
            }
            captureDao.updateMatch(rawId, candidate.ruleName, true)
        }
        AppLog.i(
            "Page",
            (if (autoEntered) "filled auto entry #" else "merged page info into pending #") +
                "${existing.id} ${candidate.ruleName} ${Money.formatYuan(candidate.amountCents)}" +
                (if (existing.userEdited) " [用户已手改，仅补空白]" else ""),
        )
        // 溯源：本页捕获的字段是**合并后真正生效的值**，附上页面原始快照，
        // 键取同笔条目的 dedupKey——金额被页面改写时通知侧的键已对不上交易，只有这个键能解析到账目
        traceCapture(
            channel = CaptureChannel.A11Y,
            packageName = existing.sourcePackage,
            title = "页面快照",
            text = snapshotText,
            bigText = "",
            capturedAt = capturedAt,
            matchedRule = merged.ruleName,
            type = candidate.type,
            amountCents = merged.amountCents,
            counterparty = merged.counterparty,
            note = merged.note,
            dedupKey = existing.dedupKey,
            outcome = if (crossApp) TraceOutcome.MERGED_CROSS_APP else TraceOutcome.MERGED_PAGE,
        )
        // 页面富化：卡片在展示就刷新内容，没在展示就按"卡片优先"再试一次
        refreshCardIfShowing(existing.id)
        retryCardIfNoCard(existing.id)
    }

    /** 过渡页（加载/网络异常/空白）不构成"用户已离开结果页"的证据 */
    private fun looksLikeTransientPage(snapshot: PageSnapshot): Boolean {
        if (snapshot.lines.size < 3) return true
        val joined = snapshot.joined
        return listOf("加载中", "正在加载", "请稍候", "网络异常", "网络不给力", "重试", "刷新中").any {
            joined.contains(it)
        }
    }

    /**
     * 页面是否值得进捕获日志（未匹配样本池）：解析器判定的交易场景，或页面命中交易锚点。
     * 聊天/朋友圈等噪音页不进库，避免捕获日志被无障碍事件刷爆。
     */
    private fun isInterestingPage(snapshot: PageSnapshot, result: PageParseResult): Boolean {        if (result is PageParseResult.SceneOnly && result.scene != PageScene.UNKNOWN) return true
        val joined = snapshot.joined
        return pageInterestingMarkers.any { joined.contains(it) }
    }

    private val pageInterestingMarkers = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "账单详情", "交易详情", "订单详情",
        "收款成功", "退款成功", "转账成功", "付款方式", "支付方式", "交易单号", "订单编号",
    )

    /** 直接入账结果：新交易 id + 对应的 AUTO 条目 id（卡片与通知都按它们工作） */
    private data class Entered(val txId: Long, val pendingId: Long)

    /** 三种入账方式（入账提醒/悬浮窗入账/自动入账）任一开启即"先直接入账"；全关 = 完全手动模式 */
    private fun directEntryEnabled(): Boolean =
        com.controleco.app.capture.directEntryEnabled(
            autoEntry = isAutoEntryEnabled(),
            overlayConfirm = isOverlayConfirmEnabled(),
            captureNotify = isCaptureNotifyEnabled(),
        )

    /**
     * 直接入账（三种入账方式共用）：按推测分类/账户先落账——这就是"先入账"的落点，
     * 后续的卡片（改/删）与通知（提醒/撤销）都围绕这笔已有账目工作，卡片弹不出来账也不会丢。
     * 条目以 AUTO 状态留在合并池里——页面通道随后捕获同笔时回填更全的信息。
     * 返回 null = 没有可用的分类/账户、重复预检命中或写入失败，调用方回落待确认流程（不丢单）。
     */
    private suspend fun enterEntry(
        type: TxType,
        amountCents: Long,
        timestamp: Long,
        counterparty: String,
        note: String,
        sourcePackage: String,
        dedupKey: String,
        ruleName: String,
        isRefund: Boolean = false,
        source: TxSource = TxSource.NOTIFICATION,
        bankName: String? = null,
        paymentInstrument: String? = null,
        flowId: String = "",
        notificationInstanceId: String = "",
        orderId: String = "",
    ): Entered? {
        val learned = merchantRuleDao.getAll()
        val categories = categoryDao.getAll()
        val accounts = accountDao.getAll()
        val categoryId = CaptureDefaults.categoryId(
            categories, type, counterparty.ifBlank { note },
            learnedRules = learned, counterparty = counterparty, note = note,
            isRefund = isRefund,
        )
        // 账户：页面解析出的支付工具（"招商银行(1234)"/"零钱"）优先参与匹配——
        // "从微信付的"不等于"从微信零钱付的"，匹配不到才回落渠道兜底
        val accountId = CaptureDefaults.accountId(
            accounts, sourcePackage, bankName,
            learnedAccountId = MerchantRules.accountIdFrom(learned, accounts, type, counterparty, note),
            instrumentLabel = paymentInstrument,
        )
        if (categoryId == null || accountId == null) {
            AppLog.w("Ntf", "direct entry skipped (no category/account to use)")
            return null
        }
        // 重复预检（±60s 同数据库校验）：同来源+同方向+同金额在窗口内已有条目（待确认或已入账）
        // → 不静默入账，回落"入队 + 提醒"（重复通知/分钟边界重发不会造成双记账，可疑的第二笔仍让用户看到）
        if (hasRecentSameEntry(
                pendingDao.getMergeable(), sourcePackage, type, amountCents, System.currentTimeMillis(),
                txTime = timestamp,
            )
        ) {
            AppLog.i("Ntf", "direct entry skipped (recent same entry) $sourcePackage $type ${Money.formatYuan(amountCents)}")
            return null
        }
        return try {
            // 正式交易与 AUTO 关联条目必须在同一事务里写入：任一步失败整体回滚，
            // 不会留下"有账单、没有关联条目"或相反的半成品状态。
            val (txId, pendingId) = db.withTransaction {
                val insertedTxId = txDao.insert(
                    TransactionEntity(
                        type = type,
                        amountCents = amountCents,
                        categoryId = categoryId,
                        accountId = accountId,
                        timestamp = timestamp,
                        note = note.ifBlank { ruleName },
                        counterparty = counterparty,
                        source = source,
                        externalId = "NTF:$dedupKey",
                        refundMark = if (isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                        // 退款收入还不知道退的是哪一笔支出 → 进「待确认账单」等用户指认
                        refundPending = isRefund,
                        flowId = flowId,
                    ),
                )
                val insertedPendingId = pendingDao.insert(
                    PendingTransactionEntity(
                        type = type,
                        amountCents = amountCents,
                        timestamp = timestamp,
                        counterparty = counterparty,
                        note = note,
                        sourcePackage = sourcePackage,
                        ruleName = ruleName,
                        dedupKey = dedupKey,
                        refundMark = if (isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                        status = PendingStatus.AUTO,
                        paymentInstrument = paymentInstrument.orEmpty(),
                        flowId = flowId,
                        notificationInstanceId = notificationInstanceId,
                        orderId = orderId,
                    ),
                )
                // dedupKey 唯一索引冲突时 @Insert(IGNORE) 返回 -1：必须回滚交易插入，
                // 不能把冲突静默当成成功（CR-008）
                requireEntryAssociation(insertedPendingId)
                insertedTxId to insertedPendingId
            }
            AppLog.i(
                "Ntf",
                "entered #$txId ${Money.formatYuan(amountCents)} dedup=$dedupKey" +
                    (paymentInstrument?.let { " 支付工具=$it" } ?: ""),
            )
            Entered(txId, pendingId)
        } catch (e: Exception) {
            // externalId/待确认键唯一索引冲突 = 这笔已经入过账（重复通知/双通道）：
            // 找回原交易与关联条目，卡片/通知仍可用
            if (e is EntryAssociationException || e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                val existing = txDao.findIdByExternalId("NTF:$dedupKey")
                val existingPending = pendingDao.findByDedupKey(dedupKey)
                AppLog.i(
                    "Ntf",
                    "direct entry skipped (already recorded) dedup=$dedupKey " +
                        "tx=$existing pending=${existingPending?.id}",
                )
                if (existing != null || existingPending != null) {
                    Entered(existing ?: -1L, existingPending?.id ?: -1L)
                } else {
                    null
                }
            } else {
                AppLog.w("Ntf", "direct entry failed, fallback to pending", e)
                null
            }
        }
    }

    // ---------- 通知测试（debug）----------

    /** debug：延迟 20 秒发一条样例捕获通知（验证通知通道可用性与点击跳转） */
    fun scheduleNotifyTest(onResult: (String) -> Unit) {
        if (!initialized) return
        if (!canPostNotifications()) {
            onResult("未授权通知权限：设置页「动账通知提醒」→ 申请权限")
            return
        }
        mainHandler.postDelayed({
            postCaptureNotification("测试通知（易控钱包）", "这是一条样例动账提醒 · 点击应进入待确认队列", ROUTE_PENDING)
            toast("延迟测试：通知已发送")
        }, 20_000L)
        onResult("已安排：20 秒后发送测试通知（可切到任意界面等待）")
    }

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
