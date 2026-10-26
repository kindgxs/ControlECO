package com.controleco.app.data.deposit

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 存款分析（纯函数，可单测）。
 *
 * 口径（与首页/统计一致）：
 * - 支出 = 未被标记「已退款」的支出；
 * - 收入 = 全部收入（含退款到账）；
 * - **当月实际新增存款 = 当月收入 − 当月支出**（可为负）。
 */
object DepositMath {

    data class MonthSaving(
        val month: YearMonth,
        val incomeCents: Long,
        val expenseCents: Long,
    ) {
        val savingCents: Long get() = incomeCents - expenseCents

        /** 达成比例（0..1+）：目标 ≤ 0 时返回 null（未设目标） */
        fun ratio(goalCents: Long): Float? =
            if (goalCents <= 0L) null else savingCents.toFloat() / goalCents
    }

    /** 单笔可计入支出的金额（不计入统计 / 已退款 / 已配对 都排除） */
    fun expenseCentsOf(row: TransactionRow): Long =
        if (row.type == TxType.EXPENSE && row.countInStats
            && row.refundMark != RefundMark.REFUNDED_EXPENSE
            && row.refundLinkId == null
        ) row.amountCents else 0L

    fun incomeCentsOf(row: TransactionRow): Long =
        if (row.type == TxType.INCOME && row.countInStats && row.refundLinkId == null) row.amountCents else 0L

    /**
     * 已配对账单的净额：支出金额 − 退款金额。
     * - 正数 → 计入支出（退款少于付款，只统计差额）；
     * - 零 → 两笔相抵，不影响收支；
     * - 负数 → 计入收入（退款多于付款）。
     * 同一 refundLinkId 若有多条，支出与退款分别求和后再相减。
     */
    fun linkedNetCents(rows: List<TransactionRow>, linkId: Long): Long {
        val group = rows.filter { it.refundLinkId == linkId }
        val expense = group.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents }
        val refund = group.filter { it.type == TxType.INCOME }.sumOf { it.amountCents }
        return expense - refund
    }

    /** 按自然月汇总（用于存款趋势；未做年份过滤，调用方传多少算多少） */
    fun monthlySavings(
        rows: List<TransactionRow>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthSaving> {
        val buckets = LinkedHashMap<YearMonth, LongArray>() // [income, expense]
        rows.forEach { row ->
            val month = YearMonth.from(
                java.time.Instant.ofEpochMilli(row.timestamp).atZone(zone).toLocalDate(),
            )
            val acc = buckets.getOrPut(month) { LongArray(2) }
            acc[0] += incomeCentsOf(row)
            acc[1] += expenseCentsOf(row)
        }
        // 已配对账单按净额叠加：净额归到「支出」所在月（退款跨月时仍以支出月为准）
        rows.mapNotNull { it.refundLinkId }.distinct().forEach { linkId ->
            val group = rows.filter { it.refundLinkId == linkId }
            val expenseRow = group.firstOrNull { it.type == TxType.EXPENSE }
            val net = linkedNetCents(rows, linkId)
            val month = expenseRow?.let {
                YearMonth.from(java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate())
            } ?: group.firstOrNull()?.let {
                YearMonth.from(java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate())
            } ?: return@forEach
            val acc = buckets.getOrPut(month) { LongArray(2) }
            if (net >= 0L) acc[1] += net else acc[0] += -net
        }
        return buckets.entries
            .sortedBy { it.key }
            .map { (month, acc) -> MonthSaving(month, acc[0], acc[1]) }
    }

    /** 指定区间的收入 / 支出合计 */
    fun rangeSaving(rows: List<TransactionRow>, start: LocalDate, endExclusive: LocalDate, zone: ZoneId = ZoneId.systemDefault()): MonthSaving {
        val a = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val b = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        var income = 0L
        var expense = 0L
        rows.forEach { row ->
            if (row.timestamp in a until b) {
                income += incomeCentsOf(row)
                expense += expenseCentsOf(row)
            }
        }
        // 已配对账单按净额叠加（只算支出落在本区间的配对）
        rows.mapNotNull { it.refundLinkId }.distinct().forEach { linkId ->
            val group = rows.filter { it.refundLinkId == linkId }
            val expenseRow = group.firstOrNull { it.type == TxType.EXPENSE }
            if (expenseRow != null && expenseRow.timestamp in a until b) {
                val net = linkedNetCents(rows, linkId)
                if (net >= 0L) expense += net else income += -net
            }
        }
        return MonthSaving(YearMonth.from(start), income, expense)
    }

    /**
     * 连续 [count] 个月的趋势（含结束月，缺数据的月补 0），便于柱状图稳定渲染。
     */
    fun trendEndingAt(savings: List<MonthSaving>, endMonth: YearMonth, count: Int): List<MonthSaving> {
        if (count <= 0) return emptyList()
        val map = savings.associateBy { it.month }
        return (count - 1 downTo 0).map { back ->
            val m = endMonth.minusMonths(back.toLong())
            map[m] ?: MonthSaving(m, 0L, 0L)
        }
    }

    /** 指定区间（含首尾月）的连续趋势；区间内缺数据的月补 0，首月早于结束月时按月递增 */
    fun trendBetween(
        savings: List<MonthSaving>,
        startMonth: YearMonth,
        endMonth: YearMonth,
    ): List<MonthSaving> {
        if (startMonth.isAfter(endMonth)) return emptyList()
        val map = savings.associateBy { it.month }
        val months = generateSequence(startMonth) { if (it < endMonth) it.plusMonths(1) else null }
        return months.map { month -> map[month] ?: MonthSaving(month, 0L, 0L) }.toList()
    }
}
