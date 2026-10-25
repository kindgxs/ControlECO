package com.controleco.app.data.plan

import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.InstallmentPlanEntity
import com.controleco.app.data.db.RecurringPlanEntity
import com.controleco.app.data.db.TransactionDao
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.util.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * 分期 / 周期计划的自动入账（纯数据层，ViewModel 与闹钟广播共用）。
 *
 * 关键约束：
 * - 只有到达「计划日期 + 出账时间」才生成账单，绝不提前；
 * - 每笔账单带唯一 `externalId`（`PLAN_INST:<计划 id>:<期数>` / `PLAN_REC:<计划 id>:<次数>`），
 *   即使进程在"写账单"和"更新计划进度"之间被杀，重跑也不会重复生成；
 * - 单次补记有上限（[MAX_POSTS_PER_RUN]），长时间未打开 App 也不会一次写爆数据库。
 */
object PlanAutoPoster {

    /** 单次最多补记多少笔（防止极端情况下的循环/写库风暴） */
    const val MAX_POSTS_PER_RUN = 400

    /** 串行化补记，避免闹钟与前台同时触发时重复读改写 */
    private val mutex = Mutex()

    /** 补记所有到点的分期与周期账单，返回新生成的笔数 */
    suspend fun postDue(
        db: AccBookDatabase,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int = mutex.withLock {
        val planDao = db.planDao()
        var posted = 0

        planDao.getAllInstallments().forEach { plan ->
            if (posted >= MAX_POSTS_PER_RUN || plan.postedPeriods >= plan.periods) return@forEach
            var guard = 0
            while (posted < MAX_POSTS_PER_RUN && guard < MAX_POSTS_PER_RUN) {
                guard++
                val current = planDao.getInstallment(plan.id) ?: break
                val ok = try {
                    postInstallmentNext(db, current, now, zone)
                } catch (e: Exception) {
                    AppLog.e("plan", "auto installment #${plan.id} failed", e)
                    false
                }
                if (!ok) break
                posted++
            }
        }

        planDao.getAllRecurring().forEach { plan ->
            if (posted >= MAX_POSTS_PER_RUN) return@forEach
            var guard = 0
            while (posted < MAX_POSTS_PER_RUN && guard < MAX_POSTS_PER_RUN) {
                guard++
                val current = planDao.getRecurring(plan.id) ?: break
                val ok = try {
                    postRecurringNext(db, current, now, zone)
                } catch (e: Exception) {
                    AppLog.e("plan", "auto recurring #${plan.id} failed", e)
                    false
                }
                if (!ok) break
                posted++
            }
        }

        if (posted > 0) AppLog.i("plan", "auto posted $posted due entries")
        posted
    }

    /** 分期下一期是否到点并已入账；未到点 / 已记完返回 false */
    suspend fun postInstallmentNext(
        db: AccBookDatabase,
        plan: InstallmentPlanEntity,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (plan.postedPeriods >= plan.periods) return false
        val index = plan.postedPeriods + 1
        val firstDate = RecurringMath.dateOf(plan.firstDateMillis, zone)
        val dueAt = InstallmentMath.periodMillis(firstDate, index, plan.postTimeMinutes, zone)
        if (now < dueAt) return false

        val amount = InstallmentMath.periodTotal(
            plan.totalPrincipalCents, plan.totalInterestCents, plan.periods, index,
        )
        if (amount > 0L) {
            insertOnce(
                db.transactionDao(),
                TransactionEntity(
                    type = PLAN_TX_TYPE,
                    amountCents = amount,
                    categoryId = plan.categoryId,
                    accountId = plan.accountId,
                    timestamp = dueAt,
                    note = plan.note.ifBlank { "${plan.title} 第${index}/${plan.periods}期" },
                    counterparty = plan.title,
                    source = TxSource.MANUAL,
                    externalId = "PLAN_INST:${plan.id}:$index",
                    excludeFromAssets = InstallmentMath.excludesAsset(
                        plan.deductAsset, plan.excludeAssetPeriods, plan.periods, index,
                    ),
                    excludeFromStats = InstallmentMath.excludesStats(
                        plan.excludeStatsPeriods, plan.periods, index,
                    ),
                ),
            )
        }
        db.planDao().updateInstallment(plan.copy(postedPeriods = index))
        AppLog.i("plan", "installment #${plan.id} auto posted period $index")
        return true
    }

    /** 周期下一笔是否到点并已入账；未到点 / 已结束返回 false */
    suspend fun postRecurringNext(
        db: AccBookDatabase,
        plan: RecurringPlanEntity,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (plan.nextDateMillis <= 0L || plan.nextDateMillis >= RecurringMath.NO_NEXT) return false
        if (plan.nextDateMillis > now) return false

        val endMode = RecurringEndMode.of(plan.endMode)
        val occurrenceDate = RecurringMath.dateOf(plan.nextDateMillis, zone)

        // 计划在创建后就已经超过结束条件：直接标记结束，不再补旧账
        val reached = when (endMode) {
            RecurringEndMode.COUNT -> plan.endCount > 0 && plan.postedCount >= plan.endCount
            RecurringEndMode.DATE -> plan.endDateMillis > 0L &&
                occurrenceDate.isAfter(RecurringMath.dateOf(plan.endDateMillis, zone))
            RecurringEndMode.NEVER -> false
        }
        if (reached) {
            db.planDao().updateRecurring(plan.copy(nextDateMillis = RecurringMath.NO_NEXT))
            return false
        }

        val index = plan.postedCount + 1
        if (plan.amountCents > 0L) {
            insertOnce(
                db.transactionDao(),
                TransactionEntity(
                    type = plan.type,
                    amountCents = plan.amountCents,
                    categoryId = plan.categoryId,
                    accountId = plan.accountId,
                    timestamp = plan.nextDateMillis,
                    note = plan.note.ifBlank { plan.title },
                    counterparty = plan.title,
                    source = TxSource.MANUAL,
                    externalId = "PLAN_REC:${plan.id}:$index",
                    excludeFromAssets = InstallmentMath.excludesAssetAt(
                        plan.deductAsset, plan.excludeAssetPeriods, index,
                    ),
                    excludeFromStats = InstallmentMath.excludesStatsAt(
                        plan.excludeStatsPeriods, index,
                    ),
                ),
            )
        }

        val frequency = RecurringFrequency.of(plan.frequency)
        val nextDate = RecurringMath.next(
            occurrenceDate, frequency, plan.intervalCount.coerceAtLeast(1),
        )
        val ended = RecurringMath.endedAfter(
            endMode, plan.endCount, plan.endDateMillis, index, nextDate, zone,
        )
        val nextMillis = if (ended) {
            RecurringMath.NO_NEXT
        } else {
            RecurringMath.millisAtMinutes(nextDate, plan.postTimeMinutes, zone)
        }
        db.planDao().updateRecurring(
            plan.copy(postedCount = index, nextDateMillis = nextMillis),
        )
        AppLog.i("plan", "recurring #${plan.id} auto posted #$index, next=$nextDate")
        return true
    }

    /** 下一次需要唤醒的时刻（毫秒）；没有待入账计划返回 null */
    suspend fun nextDueAt(
        db: AccBookDatabase,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        var earliest: Long? = null

        db.planDao().getAllInstallments().forEach { plan ->
            if (plan.postedPeriods >= plan.periods) return@forEach
            val index = plan.postedPeriods + 1
            val date = InstallmentMath.periodDate(RecurringMath.dateOf(plan.firstDateMillis, zone), index)
            val at = RecurringMath.millisAtMinutes(date, plan.postTimeMinutes, zone)
            if (earliest == null || at < earliest!!) earliest = at
        }
        db.planDao().getAllRecurring().forEach { plan ->
            val at = plan.nextDateMillis
            if (at <= 0L || at >= RecurringMath.NO_NEXT) return@forEach
            if (earliest == null || at < earliest!!) earliest = at
        }

        return earliest?.coerceAtLeast(now + 1_000L)
    }

    /** 唯一键防重：进程在写账单与更新进度之间被杀时，重跑也不会多记一笔 */
    private suspend fun insertOnce(txDao: TransactionDao, tx: TransactionEntity) {
        val key = tx.externalId
        if (key != null && txDao.findIdByExternalId(key) != null) return
        txDao.insert(tx)
    }
}
