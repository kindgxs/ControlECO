package com.controleco.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 交易方向 */
enum class TxType { EXPENSE, INCOME }

/** 捕获通道：通知监听 / 无障碍页面抓取 / 银行动账短信（捕获日志共用一张表，用 source 区分） */
enum class CaptureChannel { NOTIFICATION, A11Y, SMS }

/** 交易来源：手动输入 / 微信账单 / 支付宝账单 / 通知捕获（含页面/短信/银行推送，外部凭去重键区分）/ 银行动账 */
enum class TxSource { MANUAL, WECHAT, ALIPAY, NOTIFICATION, BANK }

/** 账户类型：存款（现金 / 储蓄卡 / 电子钱包）或信用卡 */
enum class AccountKind(val label: String) {
    CASH("存款"),
    CREDIT("信用卡"),
}

/**
 * 退款标记：
 * - NONE：普通记录（用户把待处理的退款「记为收入」后也回到 NONE）
 * - REFUNDED_EXPENSE：被全额退款的支出（钱已退回，另有对应的退款收入记录）
 * - REFUND_INCOME：退款到账收入（微信"XXX-退款"收入行 / 支付宝"退款成功"行）
 * 退款记录始终入库、始终显示、始终计入汇总。
 */
enum class RefundMark { NONE, REFUNDED_EXPENSE, REFUND_INCOME }

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val initialBalanceCents: Long = 0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 账户类型：存款 / 信用卡（信用卡才有额度与共用额度） */
    @ColumnInfo(defaultValue = "CASH")
    val kind: AccountKind = AccountKind.CASH,
    /** 默认账户（记账时优先选中；全局最多一个，0 = 否） */
    @ColumnInfo(defaultValue = "0")
    val isDefault: Boolean = false,
    /** 信用卡额度（分）：0 = 非信用卡 / 未设置；共用额度组内所有卡共用这个上限 */
    @ColumnInfo(defaultValue = "0")
    val creditLimitCents: Long = 0,
    /** 共用额度组：0 = 不共用；同组的账户视为同一张卡的额度/欠款（值 = 组内任意账户 id） */
    @ColumnInfo(defaultValue = "0")
    val sharedGroupId: Long = 0,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: TxType,
    val sortOrder: Int = 0,
    /**
     * 自定义图标（见 data/CategoryIcon）："" = 按分类名取内置图形；
     * "EMOJI:🍜" / "GLYPH:BURGER" / "IMAGE:cat_icon_<ts>.jpg"
     */
    @ColumnInfo(defaultValue = "")
    val icon: String = "",
    /** 二级分类：所属一级分类 id；null = 一级分类（只支持两级） */
    @ColumnInfo(defaultValue = "NULL")
    val parentId: Long? = null,
)

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["externalId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType,
    val amountCents: Long,
    val categoryId: Long,
    val accountId: Long,
    val timestamp: Long,
    val note: String = "",
    val counterparty: String = "",
    val source: TxSource = TxSource.MANUAL,
    /** 外部单号（微信交易单号/支付宝交易号/捕获去重键），用于账单重复导入与捕获去重；手动记录为 null */
    val externalId: String? = null,
    val refundMark: RefundMark = RefundMark.NONE,
    /**
     * 退款收入是否还没被用户处理（决定它是否出现在「待确认账单」里）。
     *
     * **退款与原支出互不关联**：支出只被打上 [RefundMark.REFUNDED_EXPENSE]（已退款、不再计入支出），
     * 退款收入只被打上「已处理」（`refundPending = false`、照常计入收入），两者之间没有 id 关系。
     * 账单导入时账单自己就把该标的都标好了，所以导入的退款 = false，不打扰用户。
     *
     * - 账单导入的退款收入 = false；
     * - 通知/页面捕获的退款收入 = true，直到用户在待确认里选中被退款的支出或选择「记为收入」；
     * - 迁移 v12 之前入库的历史退款 = false（不打扰用户）。
     */
    @ColumnInfo(defaultValue = "0")
    val refundPending: Boolean = false,
    /**
     * 页面捕获的支付流程标识（仅自动捕获的账单有值）：同一笔交易从结果页到详情页共享同一个值，
     * 便于事后追踪"这笔账属于哪一次支付流程"。通知/短信/手动记录为空串。
     */
    @ColumnInfo(defaultValue = "")
    val flowId: String = "",
    /** 只记账、不扣资产（分期/周期记账的「已记期数」可选）：余额统计跳过该笔 */
    @ColumnInfo(defaultValue = "0")
    val excludeFromAssets: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 信用卡分期计划：按「期」生成账单，每期 = 本金 + 利息（见 InstallmentMath） */
@Entity(tableName = "installment_plans")
data class InstallmentPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val accountId: Long,
    val categoryId: Long,
    /** 分期总金额（本金，分） */
    val totalPrincipalCents: Long,
    /** 总利息（分）：0 = 免息 */
    @ColumnInfo(defaultValue = "0")
    val totalInterestCents: Long = 0,
    val periods: Int,
    val firstDateMillis: Long,
    /** true = 每期入账同时扣资产；false = 只记账（余额不变） */
    @ColumnInfo(defaultValue = "1")
    val deductAsset: Boolean = true,
    /** 单独指定「只记账、不扣资产」的期数：逗号分隔（例 "1,3,12"）；空 = 只按 deductAsset */
    @ColumnInfo(defaultValue = "")
    val excludeAssetPeriods: String = "",
    val note: String = "",
    /** 已记期数（0..periods）：下一期的序号 = 已记期数 + 1 */
    @ColumnInfo(defaultValue = "0")
    val postedPeriods: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 周期记账计划：按天/周/月自动入账 */
@Entity(tableName = "recurring_plans")
data class RecurringPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val type: TxType,
    val amountCents: Long,
    val categoryId: Long,
    val accountId: Long,
    /** DAILY / WEEKLY / MONTHLY */
    val frequency: String,
    val firstDateMillis: Long,
    /** 下次入账时间（毫秒） */
    val nextDateMillis: Long,
    @ColumnInfo(defaultValue = "1")
    val deductAsset: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val autoPost: Boolean = false,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 待确认条目状态（确认/丢弃后条目即删除，状态仅用于极端情况的残留清理）
 * - AUTO：自动入账（设置开启）已直接落账的条目，不进待确认列表，
 *   只用于页面通道随后捕获同笔时回填更全的商户/支付方式；次日清理。
 * - ASK：等待用户明确确认（微信红包领取页）。不属于自动记账候选：
 *   **不进入跨通道合并池**，也不会被"全部确认"批量入账；用户点「记一笔」才落账。
 */
enum class PendingStatus { PENDING, CONFIRMED, DISCARDED, AUTO, ASK }

/** 原始捕获日志：采集层立即落库的原始快照，不解析；兼做调试证据与"未匹配样本池"，定期清理 */
@Entity(tableName = "captured_notifications")
data class CapturedNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val postTime: Long,
    val receivedAt: Long = System.currentTimeMillis(),
    /** 捕获通道：通知监听 / 无障碍页面抓取 */
    @ColumnInfo(defaultValue = "NOTIFICATION")
    val source: CaptureChannel = CaptureChannel.NOTIFICATION,
    /** 命中的规则名；空 = 未匹配 */
    val matchedRule: String = "",
    /** 是否已进入待确认队列 */
    val queued: Boolean = false,
)

/**
 * 自动记账溯源：一次捕获事件对入账流程的处置结果（仅在开发者功能「记录记账来源」开启时记录）。
 * 注意不是每条捕获都有溯源记录——只有"与入账有关"的捕获才记（未识别/未感兴趣的走捕获日志）。
 * 枚举存字符串：新增取值不需要 Room 迁移。
 */
enum class TraceOutcome {
    /** 自动入账：捕获后直接落账 */
    AUTO_ENTRY,

    /** 进入待确认队列（等用户确认） */
    PENDING,

    /** 页面信息合并：同笔已由另一通道入账/入队，本次捕获把页面富化信息回填过去 */
    MERGED_PAGE,

    /**
     * 跨应用同一笔：电商/外卖页面与支付应用（微信/支付宝）页面/通知被判定为同一笔
     * （明确渠道线索，或两条链路短时同额）。与 [MERGED_PAGE] 分开是为了在溯源里
     * 一眼看出"这笔是两个应用的对账结果"，而不是同一应用内的结果页/详情页回填。
     */
    MERGED_CROSS_APP,

    /** 跨通道归并：同笔已由另一通道（支付类⇄银行类）捕获，本次银行侧信息并入既有条目 */
    MERGED_CROSS,

    /** 重复捕获：同笔已由另一通道处理，本次不再入账 */
    SKIPPED_DUPLICATE,

    /** 无金额退款通知：只发提醒，等用户手动关联退款（不产生账目） */
    REFUND_NOTICE,
}

/**
 * 自动记账溯源记录（开发者功能，默认关闭时零写入）：
 * 每次与入账有关的捕获，把**原始来源**（通知 title/text/bigText 或页面文本快照）
 * 与解析结果、处置方式一并留档，供日后回查"这笔账是从哪个页面/通知来的"。
 * 与捕获日志（7 天/2000 条滚动清理）不同：这是**每笔入账的持久证据**，
 * 只按条数封顶（最近 1000 条）+ 手动清空。
 * 与账目的关联靠 [dedupKey]（交易 externalId = "NTF:" + dedupKey），
 * 因此确认入账/回填/删除都能被同一把钥匙解析出最新状态。
 */
@Entity(
    tableName = "capture_traces",
    indices = [Index(value = ["dedupKey"])],
)
data class CaptureTraceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 本次捕获的通道：通知监听 / 无障碍页面抓取 */
    val channel: CaptureChannel,
    val packageName: String,
    /** 通知通道 = 通知标题；页面通道 = "页面快照" */
    val title: String = "",
    /** 通知通道 = 正文（含折叠明细行）；页面通道 = 页面文本节点（" | " 拼接） */
    val text: String = "",
    val bigText: String = "",
    /** 来源时刻：通知 postTime / 页面捕获时刻 */
    val capturedAt: Long,
    val matchedRule: String = "",
    /** 解析出的方向金额（退款提醒类无方向金额为 null/0）；页面合并时取页面侧（权威）值 */
    val type: TxType? = null,
    val amountCents: Long = 0,
    val counterparty: String = "",
    val note: String = "",
    /** 本次处置对应的入账键（合并/重复时指向另一通道那笔的键，保证解析到同一账目） */
    val dedupKey: String,
    val outcome: TraceOutcome,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 商户归类学习规则（"越用越准"）：用户确认过几次"瑞幸咖啡"→餐饮之后，下次捕获自动预选。
 *
 * 学习时机：确认待确认条目（PendingActions）/ 编辑捕获来的交易；**不从手动记账学习**
 * （手写的一次性备注容易污染规则）。键是归一化后的商户/备注词（[MerchantRules.keyOf]），
 * 平台通用词与纯数字不入库；条数封顶（见 CaptureCenter.cleanup）。
 * 不做外键：分类被删后规则只是匹配不上（应用规则时会校验分类仍然存在）。
 */
@Entity(
    tableName = "merchant_rules",
    indices = [Index(value = ["matchKey", "type"], unique = true)],
)
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 归一化后的商户/备注键（小写、去空白与标点，截断） */
    val matchKey: String,
    /** 方向：同一商户名在收/支两侧的归类可能不同，分开学 */
    val type: TxType,
    val categoryId: Long,
    /** 学习到的账户（可选：确认时用户选的账户） */
    val accountId: Long? = null,
    /** 命中次数（确认几次后认定稳定，展示用） */
    val hits: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 自动捕获、待用户确认的交易；确认后转为交易（externalId = "NTF:" + dedupKey 防重） */
@Entity(
    tableName = "pending_transactions",
    indices = [Index(value = ["dedupKey"], unique = true)],
)
data class PendingTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType,
    val amountCents: Long,
    val timestamp: Long,
    val counterparty: String = "",
    val note: String = "",
    val sourcePackage: String,
    val ruleName: String,
    /** 去重键：来源包名 + 方向 + 金额 + 分钟级时间 */
    val dedupKey: String,
    val rawNotificationId: Long? = null,
    /** 退款标记：通知捕获的退款到账收入打 REFUND_INCOME，与账单导入口径一致 */
    @ColumnInfo(defaultValue = "NONE")
    val refundMark: RefundMark = RefundMark.NONE,
    /**
     * 页面捕获到的支付工具（"零钱" / "招商银行(1234)" / "美团月付"…）：
     * 入账时用于把"从哪个渠道付的"换算成"实际从哪个账户出的"（见 page/PaymentAccountResolver）。
     * 只存展示文本（含卡尾号），不存完整卡号。
     */
    @ColumnInfo(defaultValue = "")
    val paymentInstrument: String = "",
    /**
     * 用户是否手动改过这笔（悬浮卡保存 / 待确认确认时置位）：
     * 置位后页面与银行侧的后续富化不再覆盖金额/商户/时间——用户输入优先于自动解析。
     */
    @ColumnInfo(defaultValue = "0")
    val userEdited: Boolean = false,
    /**
     * 本笔所属的页面支付流程标识（`p:<uuid>`）。由页面解析器在整条支付流程内共享：
     * 同一笔的确认页/结果页/详情页相同，另一笔同额交易必然不同。通知/短信侧为空。
     *
     * 注意它的作用域是**单个应用**：微信的 `p:xxx` 与美团的 `p:yyy` 永不相等，
     * 跨应用合并时不能用它否定"同一笔"（见 CaptureMerge.flowCompatible 的 incomingPackage）。
     */
    @ColumnInfo(defaultValue = "")
    val flowId: String = "",
    /**
     * 通知实例标识（`n:<摘要>`）：同一个 StatusBarNotification 的重复回调/更新相同，
     * 另一条即使在同分钟同额也必然不同。用于隔离"纯通知模式下的两笔同额交易"。
     */
    @ColumnInfo(defaultValue = "")
    val notificationInstanceId: String = "",
    /**
     * 银行事件标识（`b:<摘要>`）：同一条短信/银行推送重复投递相同。
     * 与通知实例分开存，一笔交易可以同时关联"支付通知实例"和"银行动账事件"。
     */
    @ColumnInfo(defaultValue = "")
    val bankInstanceId: String = "",
    /** 页面解析出的交易单号/订单号（可获得时）：跨渠道关联的最强证据之一 */
    @ColumnInfo(defaultValue = "")
    val orderId: String = "",
    val status: PendingStatus = PendingStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
)
