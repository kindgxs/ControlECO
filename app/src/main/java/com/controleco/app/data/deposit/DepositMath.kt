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

    /** 单笔可计入支出的金额：不计入统计 或 已退款支出 = 0；已关联退款按差额计 */
    fun expenseCentsOf(row: TransactionRow): Long =
        if (row.type == TxType.EXPENSE && row.countInStats && row.refundMark != RefundMark.REFUNDED_EXPENSE) {
            maxOf(0L, row.amountCents - row.linkedRefundCents)
        } else 0L

    /** 单笔可计入收入的金额：不计入统计 = 0；已关联退款只计超出原支出的部分 */
    fun incomeCentsOf(row: TransactionRow): Long =
        if (row.type == TxType.INCOME && row.countInStats) {
            if (row.refundLinkId > 0) maxOf(0L, row.amountCents - row.linkedExpenseCents) else row.amountCents
        } else 0L

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
