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
    YEARLY("每年"),
    EVERY_N_DAYS("每N天"),
    ;

    companion object {
        fun of(value: String?): RecurringFrequency =
            entries.firstOrNull { it.name == value } ?: MONTHLY
    }
}

/** 周期记账的结束方式 */
enum class RecurringEndMode(val label: String) {
    NEVER("永不结束"),
    COUNT("按次数结束"),
    DATE("按日期结束"),
    ;

    companion object {
        fun of(value: String?): RecurringEndMode =
            entries.firstOrNull { it.name == value } ?: NEVER
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

    /** 第 index 期的出账时间戳：计划日期 + 当天出账时间 */
    fun periodMillis(
        firstDate: LocalDate,
        index: Int,
        postTimeMinutes: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = RecurringMath.millisAt(periodDate(firstDate, index), zone, timeOf(postTimeMinutes))

    /** 尚未出账的期数合计（未出账分期金额；不计入账户流水） */
    fun unpostedTotal(
        totalPrincipalCents: Long,
        totalInterestCents: Long,
        periods: Int,
        postedPeriods: Int,
    ): Long {
        if (periods <= 0 || postedPeriods >= periods) return 0L
        var sum = 0L
        for (index in (postedPeriods + 1)..periods) {
            sum += periodTotal(totalPrincipalCents, totalInterestCents, periods, index)
        }
        return sum
    }

    /** 分钟数 → 当天时间（0..1439，越界自动夹取） */
    fun timeOf(minutes: Int): LocalTime {
        val m = minutes.coerceIn(0, 24 * 60 - 1)
        return LocalTime.of(m / 60, m % 60)
    }

    /** 分钟数 → "HH:mm" */
    fun timeText(minutes: Int): String = timeOf(minutes).let { "%02d:%02d".format(it.hour, it.minute) }

    /**
     * 当前处于第几期（1 起）：按首期日期和今天自动推算，不依赖手动记录的期数。
     *
     * 首期还没到 → 第 1 期（等首期入账）；之后每过一个「还款日」前进一期，
     * 停在总期数上。短月（29/30/31 日）按 [periodDate] 的月末规则比较。
     */
    fun currentPeriod(firstDate: LocalDate, today: LocalDate, periods: Int): Int {
        if (periods <= 0) return 1
        var index = 1
        while (index < periods && !today.isBefore(periodDate(firstDate, index + 1))) {
            index++
        }
        return index
    }

    /**
     * 解析「不扣资产的期数」文本：既支持单期，也支持区间。
     *
     * 例：`1,3,12`、`1-3`、`1-3,6`、`1-3,5-7`。
     * 逗号支持中英文；区间用 `-`、`~` 或全角 `－`。非法 / 越界项直接忽略。
     */
    fun parseExcludedPeriods(text: String?, periods: Int): Set<Int> {
        if (text.isNullOrBlank() || periods <= 0) return emptySet()
        val result = LinkedHashSet<Int>()
        text.replace('，', ',').replace('－', '-').replace('~', '-')
            .split(',')
            .forEach { token ->
                val part = token.trim()
                if (part.isEmpty()) return@forEach
                val dash = part.indexOf('-')
                if (dash < 0) {
                    part.toIntOrNull()?.takeIf { it in 1..periods }?.let { result += it }
                } else {
                    val from = part.substring(0, dash).trim().toIntOrNull() ?: return@forEach
                    val to = part.substring(dash + 1).trim().toIntOrNull() ?: return@forEach
                    if (from <= to) {
                        (from.coerceAtLeast(1)..to.coerceAtMost(periods)).forEach { result += it }
                    }
                }
            }
        return result
    }

    /**
     * 把期数集合编码成存储文本：连续 3 期以上压成区间（`1-3,6`），其余逗号分隔。
     * 这样与 [parseExcludedPeriods] 完全互逆，输入 `1,2,3` 也会存成 `1-3`。
     */
    fun encodeExcludedPeriods(periodsSet: Set<Int>, periods: Int): String {
        val sorted = periodsSet.filter { it in 1..periods }.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val out = ArrayList<String>()
        var start = sorted.first()
        var prev = start
        sorted.drop(1).forEach { value ->
            if (value == prev + 1) {
                prev = value
            } else {
                out += mergeRange(start, prev)
                start = value
                prev = value
            }
        }
        out += mergeRange(start, prev)
        return out.joinToString(",")
    }

    /** 单期 → "3"；两期 → "3,4"；三期及以上 → "3-5" */
    private fun mergeRange(from: Int, to: Int): String = when {
        to - from >= 2 -> "$from-$to"
        from == to -> "$from"
        else -> "$from,$to"
    }

    /** 文本里是否含有无法解析的片段（UI 校验用；空文本视为合法） */
    fun hasInvalidExcludedPeriods(text: String?, periods: Int): Boolean {
        if (text.isNullOrBlank() || periods <= 0) return false
        return text.replace('，', ',').replace('－', '-').replace('~', '-')
            .split(',')
            .any { token ->
                val part = token.trim()
                if (part.isEmpty()) {
                    false
                } else {
                    val dash = part.indexOf('-')
                    if (dash < 0) {
                        part.toIntOrNull()?.let { it in 1..periods } != true
                    } else {
                        val from = part.substring(0, dash).trim().toIntOrNull()
                        val to = part.substring(dash + 1).trim().toIntOrNull()
                        from == null || to == null || from > to || from < 1 || to > periods
                    }
                }
            }
    }

    /** 该期是否「只记账、不扣资产」：整体开关关闭，或该期被单独指定 */
    fun excludesAsset(deductAsset: Boolean, excludePeriodsText: String?, periods: Int, index: Int): Boolean =
        !deductAsset || index in parseExcludedPeriods(excludePeriodsText, periods)

    /** 周期记账没有固定总次数：不扣资产序号按一个足够大的上限解析（1..10000） */
    fun parseExcludedIndexes(text: String?): Set<Int> =
        parseExcludedPeriods(text, MAX_PERIOD_INDEX)

    /** 周期记账第 index 次是否只记账、不扣资产 */
    fun excludesAssetAt(deductAsset: Boolean, excludePeriodsText: String?, index: Int): Boolean =
        !deductAsset || index in parseExcludedIndexes(excludePeriodsText)

    const val MAX_PERIOD_INDEX = 10_000
}

/** 周期记账的下一次日期（纯函数，可单测） */
object RecurringMath {

    /** nextDateMillis 的「已结束」哨兵值：排到列表最后，且永远不会到期 */
    const val NO_NEXT: Long = Long.MAX_VALUE

    fun next(
        from: LocalDate,
        frequency: RecurringFrequency,
        interval: Int = 1,
    ): LocalDate = when (frequency) {
        RecurringFrequency.DAILY -> from.plusDays(1)
        RecurringFrequency.WEEKLY -> from.plusWeeks(1)
        RecurringFrequency.MONTHLY -> {
            val target = YearMonth.from(from).plusMonths(1)
            target.atDay(from.dayOfMonth.coerceAtMost(target.lengthOfMonth()))
        }
        RecurringFrequency.YEARLY -> {
            val target = YearMonth.from(from).plusYears(1)
            target.atDay(from.dayOfMonth.coerceAtMost(target.lengthOfMonth()))
        }
        RecurringFrequency.EVERY_N_DAYS -> from.plusDays(interval.coerceAtLeast(1).toLong())
    }

    /**
     * 从 [from] 往前推进 [count] 个周期后的日期（count 可能很大，直接循环有上限保护）。
     */
    fun advance(
        from: LocalDate,
        frequency: RecurringFrequency,
        count: Long,
        interval: Int = 1,
    ): LocalDate {
        var d = from
        var left = count.coerceIn(0L, 5_000L)
        while (left > 0) {
            d = next(d, frequency, interval)
            left--
        }
        return d
    }

    /** 计划日期（LocalDate）→ 当天业务时间戳（保留计划里配置的时分；这里固定用 09:00） */
    fun millisAt(date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), time: LocalTime = LocalTime.of(9, 0)): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    /** 分钟数（0..1439）→ 当天时间戳 */
    fun millisAtMinutes(
        date: LocalDate,
        postTimeMinutes: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = millisAt(date, zone, InstallmentMath.timeOf(postTimeMinutes))

    fun dateOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** 还差多少天到期（负数 = 已过期） */
    fun daysUntil(nextMillis: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        ChronoUnit.DAYS.between(today, dateOf(nextMillis, zone))

    /** 频率 + 间隔的文字说明（例：每 3 天 / 每月） */
    fun describe(frequency: RecurringFrequency, interval: Int): String =
        if (frequency == RecurringFrequency.EVERY_N_DAYS) {
            "每 ${interval.coerceAtLeast(1)} 天"
        } else {
            frequency.label
        }

    /** 这一次入账后是否到达结束条件（按次数 / 按日期） */
    fun endedAfter(
        mode: RecurringEndMode,
        endCount: Int,
        endDateMillis: Long,
        postedTotal: Int,
        nextDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = when (mode) {
        RecurringEndMode.NEVER -> false
        RecurringEndMode.COUNT -> endCount in 1..Int.MAX_VALUE && postedTotal >= endCount
        RecurringEndMode.DATE -> {
            if (endDateMillis <= 0L) {
                false
            } else {
                val endDate = dateOf(endDateMillis, zone)
                nextDate.isAfter(endDate)
            }
        }
    }
}

/** 分期 / 周期的入账方向：一律记为支出（信用卡分期与固定支出场景） */
val PLAN_TX_TYPE: TxType = TxType.EXPENSE
