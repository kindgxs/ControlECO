package com.controleco.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 列表页一行：交易 + 关联分类/账户名（categoryIcon = 分类自定义图标规格） */
data class TransactionRow(
    val id: Long,
    val type: TxType,
    val amountCents: Long,
    val timestamp: Long,
    val note: String,
    val counterparty: String,
    val source: TxSource,
    val categoryId: Long,
    val categoryName: String,
    val categoryIcon: String = "",
    val accountId: Long,
    val accountName: String,
    val refundMark: RefundMark,
    /** 退款收入是否还没被用户处理（true = 出现在「待确认账单」里）。退款与原支出互不关联，没有 id 指向 */
    val refundPending: Boolean = false,
    /**
     * 是否计入账单和收支统计：false = 只在账单列表显示这一条，
     * 不计入收支合计 / 分类统计 / 预算 / 存款等所有统计。
     */
    val countInStats: Boolean = true,
    /** 入库时间：待确认列表把捕获条目与退款条目按这个排（而不是业务时间） */
    val createdAt: Long = 0,
)

data class TypeTotalRow(
    val type: TxType,
    val totalCents: Long,
)

data class CategorySumRow(
    val categoryId: Long,
    val categoryName: String,
    val totalCents: Long,
)

/** 账户余额行：初始金额 + 该账户交易净额（支出为负） */
data class AccountBalanceRow(
    val accountId: Long,
    val initialBalanceCents: Long,
    val signedSumCents: Long,
)

@Dao
interface TransactionDao {

    /**
     * 区间内的流水。退款记录（`REFUNDED_EXPENSE` / `REFUND_INCOME`）始终参与查询与统计——
     * "显示退款记录"开关已删除，退款永远作为收入显示，被退款的原支出照常作为支出显示。
     */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, c.icon AS categoryIcon,
           a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark, t.refundPending AS refundPending,
           t.countInStats AS countInStats,
           t.createdAt AS createdAt
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.timestamp >= :startMillis AND t.timestamp < :endMillis
           ORDER BY t.timestamp DESC, t.id DESC"""
    )
    fun observeRows(startMillis: Long, endMillis: Long): Flow<List<TransactionRow>>

    /** 最早一笔账单的时间戳（存款页「全部显示」用它决定起始月）；没有账单时为 null */
    @Query("SELECT MIN(timestamp) FROM transactions")
    fun observeEarliestTimestamp(): Flow<Long?>

    @Query(
        """SELECT type AS type, SUM(amountCents) AS totalCents FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis
             AND countInStats = 1
           GROUP BY type"""
    )
    fun observeTypeTotals(startMillis: Long, endMillis: Long): Flow<List<TypeTotalRow>>

    /**
     * 本期「实际支出」：**未被标记「已退款」的支出账单**之和。
     *
     * 退款收入只作为收入存在，**不再从支出里相减**——退款是成对的记录：
     * 原支出被标「已退款」后直接不计入支出，退款那笔照常计入收入。
     * 首页「本月支出」与统计页「分类预算与占比」的实际支出都用这一条口径。
     */
    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis
             AND type = 'EXPENSE' AND refundMark != 'REFUNDED_EXPENSE'
             AND countInStats = 1"""
    )
    fun observeSpendingTotal(startMillis: Long, endMillis: Long): Flow<Long>

    /**
     * 本期分类支出：同一口径按分类分组（排除已退款的支出）。
     * 各分类之和恒等于 [observeSpendingTotal]，圆环与清单不会互相对不上。
     */
    @Query(
        """SELECT c.id AS categoryId, c.name AS categoryName, SUM(t.amountCents) AS totalCents
           FROM transactions t JOIN categories c ON t.categoryId = c.id
           WHERE t.timestamp >= :startMillis AND t.timestamp < :endMillis
             AND t.type = 'EXPENSE' AND t.refundMark != 'REFUNDED_EXPENSE'
             AND t.countInStats = 1
           GROUP BY c.id, c.name
           ORDER BY totalCents DESC"""
    )
    fun observeSpendingCategorySums(startMillis: Long, endMillis: Long): Flow<List<CategorySumRow>>

    @Query(
        """SELECT a.id AS accountId, a.initialBalanceCents AS initialBalanceCents,
           COALESCE(SUM(CASE WHEN t.excludeFromAssets = 1 THEN 0
                             WHEN t.type = 'EXPENSE' THEN -t.amountCents ELSE t.amountCents END), 0) AS signedSumCents
           FROM accounts a LEFT JOIN transactions t ON t.accountId = a.id
           GROUP BY a.id"""
    )
    fun observeBalances(): Flow<List<AccountBalanceRow>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    /** 自动入账后页面通道捕获同笔：按捕获去重键（externalId = "NTF:" + dedupKey）回填更全的页面信息 */
    @Query(
        """UPDATE transactions
           SET amountCents = :amountCents, timestamp = :timestamp,
               counterparty = :counterparty, note = :note
           WHERE externalId = :externalId"""
    )
    suspend fun updateCapturedByExternalId(externalId: String, amountCents: Long, timestamp: Long, counterparty: String, note: String)

    /** 悬浮卡"先入账、后修改"：按捕获去重键找回该笔交易（externalId = "NTF:" + dedupKey） */
    @Query("SELECT id FROM transactions WHERE externalId = :externalId LIMIT 1")
    suspend fun findIdByExternalId(externalId: String): Long?

    /**
     * 删除某计划自动入账的账单（删除计划时可选回滚）。
     * 计划账单的 externalId 形如 `PLAN_INST:<计划id>:<期数>` / `PLAN_REC:<计划id>:<次数>`，
     * 用前缀通配匹配即可精确圈定该计划。
     */
    @Query("DELETE FROM transactions WHERE externalId LIKE :pattern")
    suspend fun deleteByExternalIdPattern(pattern: String): Int

    /**
     * 页面补充了可信支付方式后重新匹配账户：只更新账户，不动金额/分类/商户（用户编辑保护由调用方判断）。
     * 账户余额与统计由 Room 的 Flow 自动刷新。
     */
    @Query("UPDATE transactions SET accountId = :accountId WHERE externalId = :externalId")
    suspend fun updateAccountByExternalId(externalId: String, accountId: Long)

    /** 页面通道回填时把支付流程标识写到正式交易（可追踪"这笔账属于哪次支付流程"） */
    @Query("UPDATE transactions SET flowId = :flowId WHERE externalId = :externalId AND flowId = ''")
    suspend fun updateFlowIdByExternalId(externalId: String, flowId: String)

    /** 悬浮卡编辑同步：金额/分类/账户/商户/是否计入资产/是否计入统计回写已入账的交易（「忽略」与「撤销」走删除） */
    @Query(
        """UPDATE transactions
           SET amountCents = :amountCents, categoryId = :categoryId,
               accountId = :accountId, counterparty = :counterparty,
               excludeFromAssets = :excludeFromAssets, countInStats = :countInStats
           WHERE id = :id"""
    )
    suspend fun updateEnteredFields(
        id: Long,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
        excludeFromAssets: Boolean,
        countInStats: Boolean,
    )

    /** 登记退款的候选：近 60 天还没标「已退款」的支出（供"无金额退款通知→选被退款的账单"使用） */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, c.icon AS categoryIcon,
           a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark, t.refundPending AS refundPending,
           t.countInStats AS countInStats,
           t.createdAt AS createdAt
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.type = 'EXPENSE' AND t.refundMark = 'NONE' AND t.timestamp >= :since
           ORDER BY t.timestamp DESC LIMIT 50"""
    )
    suspend fun recentUnrefundedExpenses(since: Long): List<TransactionRow>

    // ---------- 待处理的退款收入（已入账，只等用户处理） ----------

    /**
     * 「待确认账单」里的退款：通知/页面捕获来的、用户还没处理的退款收入。
     * 账单导入的退款 `refundPending = 0`（账单自己已经把该标的都标好了），不会出现在这里。
     */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, c.icon AS categoryIcon,
           a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark, t.refundPending AS refundPending,
           t.countInStats AS countInStats,
           t.createdAt AS createdAt
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.refundPending = 1
           ORDER BY t.timestamp DESC, t.id DESC"""
    )
    fun observePendingRefunds(): Flow<List<TransactionRow>>

    /** 待处理退款条数（个人页「待确认」胶囊与「全部确认」计数用） */
    @Query("SELECT COUNT(*) FROM transactions WHERE refundPending = 1")
    fun observePendingRefundCount(): Flow<Int>

    /**
     * 同金额的支出候选（待处理退款 sheet 用）：只列**金额相同、还没被标已退款**的支出，新的在前。
     * 不做"这笔退款配哪笔支出"的判定：选中只是给这笔支出打「已退款」标记，两笔记录之间不留关联。
     */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, c.icon AS categoryIcon,
           a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark, t.refundPending AS refundPending,
           t.countInStats AS countInStats,
           t.createdAt AS createdAt
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.type = 'EXPENSE' AND t.amountCents = :amountCents
             AND t.refundMark != 'REFUNDED_EXPENSE'
           ORDER BY t.timestamp DESC LIMIT 50"""
    )
    suspend fun expenseCandidatesByAmount(amountCents: Long): List<TransactionRow>

    /** 把退款标成「已处理」（只对还没处理的退款生效，重复点击/并发不会覆盖） */
    @Query("UPDATE transactions SET refundPending = 0 WHERE id = :refundId AND refundPending = 1")
    suspend fun clearRefundPending(refundId: Long)

    /**
     * 给原支出打「已退款」标记 = 这笔钱不再计入支出。
     * 已有标记的不覆盖；部分退款（账单状态写明只退了一部分）调用方不会调它。
     */
    @Query("UPDATE transactions SET refundMark = 'REFUNDED_EXPENSE' WHERE id = :expenseId AND refundMark = 'NONE'")
    suspend fun markExpenseRefunded(expenseId: Long)

    /** 「记为收入」：去掉退款标记，当成普通收入记账（这笔退款不再有"待处理"状态） */
    @Query(
        """UPDATE transactions SET refundMark = 'NONE', refundPending = 0
           WHERE id = :refundId"""
    )
    suspend fun markRefundAsIncome(refundId: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countByCategory(categoryId: Long): Int

    /** 删除分类时把其下流水改挂到兜底分类 */
    @Query("UPDATE transactions SET categoryId = :toCategoryId WHERE categoryId = :fromCategoryId")
    suspend fun reassignCategory(fromCategoryId: Long, toCategoryId: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId")
    suspend fun countByAccount(accountId: Long): Int

    // ---------- 批量删除（账单页） ----------

    /** 范围内记录数（含退款标记记录，批量删除不区分显示开关） */
    @Query(
        """SELECT COUNT(*) FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis"""
    )
    suspend fun countByRange(startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM transactions ORDER BY id")
    suspend fun getAllEntities(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun countAll(): Int

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM transactions WHERE timestamp >= :startMillis AND timestamp < :endMillis")
    suspend fun deleteByRange(startMillis: Long, endMillis: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(txs: List<TransactionEntity>): List<Long>

    @Insert
    suspend fun insertAll(txs: List<TransactionEntity>)

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 备份还原：清空重建（调用方先清 transactions，避免外键 RESTRICT） */
    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Query("UPDATE categories SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE categories SET parentId = :parentId WHERE id = :id")
    suspend fun updateParent(id: Long, parentId: Long?)
}

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Insert
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 备份还原：清空重建（调用方先清 transactions，避免外键 RESTRICT） */
    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    /** 默认账户全局唯一：先把所有账户的默认标记清掉 */
    @Query("UPDATE accounts SET isDefault = 0")
    suspend fun clearDefaults()

    @Query("UPDATE accounts SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)
}

@Dao
interface PlanDao {

    @Query("SELECT * FROM installment_plans ORDER BY firstDateMillis DESC, id DESC")
    fun observeInstallments(): Flow<List<InstallmentPlanEntity>>

    @Query("SELECT * FROM installment_plans")
    suspend fun getAllInstallments(): List<InstallmentPlanEntity>

    @Query("SELECT * FROM installment_plans WHERE id = :id")
    suspend fun getInstallment(id: Long): InstallmentPlanEntity?

    @Insert
    suspend fun insertInstallment(plan: InstallmentPlanEntity): Long

    @Update
    suspend fun updateInstallment(plan: InstallmentPlanEntity)

    @Query("DELETE FROM installment_plans WHERE id = :id")
    suspend fun deleteInstallment(id: Long)

    @Query("DELETE FROM installment_plans")
    suspend fun clearInstallments()

    @Query("SELECT * FROM recurring_plans ORDER BY nextDateMillis ASC, id ASC")
    fun observeRecurring(): Flow<List<RecurringPlanEntity>>

    @Query("SELECT * FROM recurring_plans")
    suspend fun getAllRecurring(): List<RecurringPlanEntity>

    @Query("SELECT * FROM recurring_plans WHERE id = :id")
    suspend fun getRecurring(id: Long): RecurringPlanEntity?

    @Insert
    suspend fun insertRecurring(plan: RecurringPlanEntity): Long

    @Update
    suspend fun updateRecurring(plan: RecurringPlanEntity)

    @Query("DELETE FROM recurring_plans WHERE id = :id")
    suspend fun deleteRecurring(id: Long)

    @Query("DELETE FROM recurring_plans")
    suspend fun clearRecurring()
}

@Dao
interface CaptureDao {

    @Insert
    suspend fun insert(notification: CapturedNotificationEntity): Long

    @Query("UPDATE captured_notifications SET matchedRule = :rule, queued = :queued WHERE id = :id")
    suspend fun updateMatch(id: Long, rule: String, queued: Boolean)

    @Query("SELECT * FROM captured_notifications ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CapturedNotificationEntity>>

    @Query("SELECT * FROM captured_notifications WHERE source = :source ORDER BY id DESC LIMIT :limit")
    fun observeRecentBySource(source: CaptureChannel, limit: Int): Flow<List<CapturedNotificationEntity>>

    @Query("DELETE FROM captured_notifications WHERE receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM captured_notifications WHERE id NOT IN (SELECT id FROM captured_notifications ORDER BY id DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM captured_notifications")
    suspend fun clearAll()
}

@Dao
interface PendingDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pending: PendingTransactionEntity): Long

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE dedupKey = :dedupKey")
    suspend fun countByDedupKey(dedupKey: String): Int

    @Query("SELECT * FROM pending_transactions WHERE dedupKey = :dedupKey LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): PendingTransactionEntity?

    @Query("SELECT * FROM pending_transactions WHERE id = :id")
    suspend fun getById(id: Long): PendingTransactionEntity?

    @Query("SELECT * FROM pending_transactions WHERE status IN ('PENDING', 'ASK') ORDER BY createdAt DESC")
    fun observePending(): Flow<List<PendingTransactionEntity>>

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE status IN ('PENDING', 'ASK')")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_transactions WHERE status IN ('PENDING', 'ASK')")
    suspend fun getAllPending(): List<PendingTransactionEntity>

    /** 跨通道合并池：待确认 + 自动入账条目（AUTO 只用于页面信息回填，不进列表）；ASK 必须由用户决定 */
    @Query("SELECT * FROM pending_transactions WHERE status IN ('PENDING', 'AUTO') ORDER BY createdAt DESC LIMIT 200")
    suspend fun getMergeable(): List<PendingTransactionEntity>

    /** 页面信息优先回填：覆盖金额/时间/商户/备注/规则（跨通道同一笔，页面侧信息更全） */
    @Query(
        """UPDATE pending_transactions
           SET amountCents = :amountCents, timestamp = :timestamp, counterparty = :counterparty,
               note = :note, ruleName = :ruleName
           WHERE id = :id"""
    )
    suspend fun updateFromPage(id: Long, amountCents: Long, timestamp: Long, counterparty: String, note: String, ruleName: String)

    /** 页面捕获到的支付工具（账户匹配用；只在非空时写入，见 CaptureCenter） */
    @Query("UPDATE pending_transactions SET paymentInstrument = :instrument WHERE id = :id")
    suspend fun updatePaymentInstrument(id: Long, instrument: String)

    /** 用户手改过该笔：后续自动富化不再覆盖金额/商户/时间（用户输入优先） */
    @Query("UPDATE pending_transactions SET userEdited = 1 WHERE id = :id")
    suspend fun markUserEdited(id: Long)

    /** 页面融合时补记支付流程标识（已有值不覆盖；跨渠道条目允许同时保留通知实例标识） */
    @Query("UPDATE pending_transactions SET flowId = :flowId WHERE id = :id AND flowId = ''")
    suspend fun updateFlowIdIfBlank(id: Long, flowId: String)

    /** 通知融合时补记通知实例标识（已有值不覆盖） */
    @Query("UPDATE pending_transactions SET notificationInstanceId = :instanceId WHERE id = :id AND notificationInstanceId = ''")
    suspend fun updateNotificationInstanceIfBlank(id: Long, instanceId: String)

    /** 银行侧融合时补记银行事件标识（已有值不覆盖） */
    @Query("UPDATE pending_transactions SET bankInstanceId = :instanceId WHERE id = :id AND bankInstanceId = ''")
    suspend fun updateBankInstanceIfBlank(id: Long, instanceId: String)

    /** 页面融合时补记订单号（已有值不覆盖） */
    @Query("UPDATE pending_transactions SET orderId = :orderId WHERE id = :id AND orderId = ''")
    suspend fun updateOrderIdIfBlank(id: Long, orderId: String)

    /** 同一通知实例的既有条目：跨过时间窗口也能确认是"同一条通知的更新"，避免重复入队 */
    @Query(
        """SELECT * FROM pending_transactions
           WHERE notificationInstanceId = :instanceId AND status IN ('PENDING', 'AUTO')
           ORDER BY createdAt DESC LIMIT 1""",
    )
    suspend fun findByNotificationInstance(instanceId: String): PendingTransactionEntity?

    /** 同一银行事件（同一条短信/推送）的既有条目：跨过时间窗口也能确认是重复投递 */
    @Query(
        """SELECT * FROM pending_transactions
           WHERE bankInstanceId = :instanceId AND status IN ('PENDING', 'AUTO')
           ORDER BY createdAt DESC LIMIT 1""",
    )
    suspend fun findByBankInstance(instanceId: String): PendingTransactionEntity?

    /** 金额待补录回填：写金额并重算去重键（来源段/方向段/分钟桶不变） */
    @Query("UPDATE pending_transactions SET amountCents = :amountCents, dedupKey = :dedupKey WHERE id = :id")
    suspend fun updateAmountAndKey(id: Long, amountCents: Long, dedupKey: String)

    /** 完整捕获到达时清掉同笔的 0 元占位条（返回删除条数） */
    @Query(
        """DELETE FROM pending_transactions
           WHERE status = 'PENDING' AND amountCents = 0
             AND sourcePackage = :sourcePackage AND type = :type
             AND timestamp BETWEEN :from AND :to"""
    )
    suspend fun deleteAmountStubs(sourcePackage: String, type: TxType, from: Long, to: Long): Int

    /** 自动入账条目的合并窗口很短，次日清理（dedupKey 仍是交易的 externalId，删除不影响去重） */
    @Query("DELETE FROM pending_transactions WHERE status = 'AUTO' AND createdAt < :cutoff")
    suspend fun deleteAutoOlderThan(cutoff: Long)

    @Query("DELETE FROM pending_transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_transactions WHERE dedupKey = :dedupKey")
    suspend fun deleteByDedupKey(dedupKey: String)
}

@Dao
interface MerchantRuleDao {

    @Query("SELECT * FROM merchant_rules")
    suspend fun getAll(): List<MerchantRuleEntity>

    /** 管理页列表（最近学习的在前） */
    @Query("SELECT * FROM merchant_rules ORDER BY updatedAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT COUNT(*) FROM merchant_rules")
    suspend fun countAll(): Int

    @Query("SELECT * FROM merchant_rules WHERE matchKey = :matchKey AND type = :type LIMIT 1")
    suspend fun find(matchKey: String, type: TxType): MerchantRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRuleEntity): Long

    @Query("DELETE FROM merchant_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM merchant_rules")
    suspend fun clearAll()

    /** 条数封顶：按最近学习保留 */
    @Query("DELETE FROM merchant_rules WHERE id NOT IN (SELECT id FROM merchant_rules ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)
}

/**
 * 溯源列表行：原始快照 + 该笔入账的当前状态（联查解析，不在溯源表里冗余存状态）。 * - [linkedTxId] 非空 = 已入账的账单 id（externalId = "NTF:" + dedupKey，唯一索引保证最多一条；
 *   账单被删除后解析为空，溯源记录本身仍保留作证据）；
 * - [pendingCount] > 0 = 仍在待确认队列里（只数 PENDING 条目；自动入账留下的 AUTO 记录不算，它在合并池里只是回填用）。
 */
data class CaptureTraceRow(
    @Embedded val trace: CaptureTraceEntity,
    val linkedTxId: Long? = null,
    val pendingCount: Int = 0,
)

@Dao
interface TraceDao {

    @Insert
    suspend fun insert(trace: CaptureTraceEntity): Long

    @Query(
        """SELECT tr.*,
           (SELECT t.id FROM transactions t WHERE t.externalId = 'NTF:' || tr.dedupKey LIMIT 1) AS linkedTxId,
           (SELECT COUNT(*) FROM pending_transactions p
              WHERE p.dedupKey = tr.dedupKey AND p.status = 'PENDING') AS pendingCount
           FROM capture_traces tr ORDER BY tr.id DESC LIMIT :limit"""
    )
    fun observeRecent(limit: Int): Flow<List<CaptureTraceRow>>

    @Query("SELECT COUNT(*) FROM capture_traces")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM capture_traces WHERE id NOT IN (SELECT id FROM capture_traces ORDER BY id DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM capture_traces")
    suspend fun clearAll()
}
