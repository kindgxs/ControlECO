package com.controleco.app.data.plan

import com.controleco.app.data.db.TxType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 周期记账的频率 */
enum class RecurringFrequency(val label: String) {
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    ;

    companion object {
        fun of(value: String?): RecurringFrequency =
            entries.firstOrNull { it.name == value } ?: MONTHLY
    }
}

/**
 * 分期金额拆分（纯函数，可单测）。
 *
 * 每期 = 本金份额 + 利息份额，尾部余数全部落进最后一期，
 * 保证 Σ每期本金 = 总本金、Σ每期利息 = 总利息（不会因为四舍五入丢分）。
 */
object InstallmentMath {

    /** 常用信用卡分期费率：每期 0.60%（万分比 60） */
    const val DEFAULT_RATE_BP = 60

    const val MIN_PERIODS = 1
    const val MAX_PERIODS = 60

    /** 总利息 = 本金 × 每期费率 × 期数（用户留空时自动算） */
    fun autoInterestCents(principalCents: Long, periods: Int, rateBp: Int = DEFAULT_RATE_BP): Long {
        if (principalCents <= 0L || periods <= 0 || rateBp <= 0) return 0L
        return principalCents * rateBp.toLong() * periods.toLong() / 10_000L
    }

    /** 第 index 期（1 起）的本金份额 */
    fun principalOf(totalCents: Long, periods: Int, index: Int): Long {
        if (periods <= 0 || totalCents <= 0L) return 0L
        val i = index.coerceIn(1, periods)
        val base = totalCents / periods
        return if (i == periods) totalCents - base * (periods - 1).toLong() else base
    }

    /** 第 index 期（1 起）的利息份额 */
    fun interestOf(totalCents: Long, periods: Int, index: Int): Long {
        if (periods <= 0 || totalCents <= 0L) return 0L
        val i = index.coerceIn(1, periods)
        val base = totalCents / periods
        return if (i == periods) totalCents - base * (periods - 1).toLong() else base
    }

    /** 每期本金（展示用） */
    fun perPrincipal(totalCents: Long, periods: Int): Long =
        if (periods <= 0) 0L else totalCents / periods

    /** 每期利息（展示用） */
    fun perInterest(totalCents: Long, periods: Int): Long =
        if (periods <= 0) 0L else totalCents / periods

    /** 第 index 期合计（本金 + 利息） */
    fun periodTotal(totalPrincipalCents: Long, totalInterestCents: Long, periods: Int, index: Int): Long =
        principalOf(totalPrincipalCents, periods, index) + interestOf(totalInterestCents, periods, index)

    /** 第 index 期（1 起）的计划日期：按首期日期的"日"逐月顺延（短月自动落到月末） */
    fun periodDate(firstDate: LocalDate, index: Int): LocalDate {
        if (index <= 1) return firstDate
        val target = YearMonth.from(firstDate).plusMonths((index - 1).toLong())
        return target.atDay(firstDate.dayOfMonth.coerceAtMost(target.lengthOfMonth()))
    }

    /** 分期总还款额 */
    fun totalRepayment(totalPrincipalCents: Long, totalInterestCents: Long): Long =
        totalPrincipalCents.coerceAtLeast(0L) + totalInterestCents.coerceAtLeast(0L)
}

/** 周期记账的下一次日期（纯函数，可单测） */
object RecurringMath {

    fun next(from: LocalDate, frequency: RecurringFrequency): LocalDate = when (frequency) {
        RecurringFrequency.DAILY -> from.plusDays(1)
        RecurringFrequency.WEEKLY -> from.plusWeeks(1)
        RecurringFrequency.MONTHLY -> {
            val target = YearMonth.from(from).plusMonths(1)
            target.atDay(from.dayOfMonth.coerceAtMost(target.lengthOfMonth()))
        }
    }

    /**
     * 从 [from] 往前推进 [count] 个周期后的日期（count 可能很大，直接循环有上限保护）。
     */
    fun advance(from: LocalDate, frequency: RecurringFrequency, count: Long): LocalDate {
        var d = from
        var left = count.coerceIn(0L, 5_000L)
        while (left > 0) {
            d = next(d, frequency)
            left--
        }
        return d
    }

    /** 计划日期（LocalDate）→ 当天业务时间戳（保留计划里配置的时分；这里固定用 09:00） */
    fun millisAt(date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), time: LocalTime = LocalTime.of(9, 0)): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    fun dateOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** 还差多少天到期（负数 = 已过期） */
    fun daysUntil(nextMillis: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        ChronoUnit.DAYS.between(today, dateOf(nextMillis, zone))
}

/** 分期 / 周期的入账方向：一律记为支出（信用卡分期与固定支出场景） */
val PLAN_TX_TYPE: TxType = TxType.EXPENSE
