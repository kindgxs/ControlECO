package com.controleco.app.data.budget

import java.time.YearMonth

/**
 * 单月预算：总金额 + 各分类预算（按分类 id）。
 *
 * 按月独立保存（见 [MonthBudgetCodec]），查账时自动取该月自己的设置；
 * 某月没有单独设置时，由调用方回落到全局预算。
 */
data class MonthBudgetPlan(
    val totalCents: Long = 0L,
    val categoryCents: Map<Long, Long> = emptyMap(),
) {
    val hasTotal: Boolean get() = totalCents > 0L

    /** 已分配到各分类的合计 */
    val allocatedCents: Long get() = categoryCents.values.sum()

    fun amountOf(categoryId: Long): Long = categoryCents[categoryId] ?: 0L

    fun withCategory(categoryId: Long, cents: Long): MonthBudgetPlan =
        copy(
            categoryCents = if (cents > 0L) {
                categoryCents + (categoryId to cents)
            } else {
                categoryCents - categoryId
            },
        )
}

/**
 * 按月取预算的纯函数（首页/统计/分类页共用）。
 *
 * 规则：该月有单独设置 → 用它；否则回落到全局预算（日预算模式按该月天数折算）。
 */
object MonthBudgetMath {

    fun totalFor(
        month: YearMonth,
        startDay: Int,
        plans: Map<YearMonth, MonthBudgetPlan>,
        monthlyAmountCents: Long,
        dailyAmountCents: Long,
        dailyMode: Boolean,
    ): Long {
        val plan = plans[month]
        if (plan != null && plan.hasTotal) return plan.totalCents
        return if (dailyMode) {
            dailyAmountCents * MonthPeriod.of(month, startDay).days
        } else {
            monthlyAmountCents
        }
    }

    /** 年视角预算 = 12 个会计月的预算之和（某月单独设置就按设置算） */
    fun totalForYear(
        year: Int,
        startDay: Int,
        plans: Map<YearMonth, MonthBudgetPlan>,
        monthlyAmountCents: Long,
        dailyAmountCents: Long,
        dailyMode: Boolean,
    ): Long = (1..12).sumOf {
        totalFor(YearMonth.of(year, it), startDay, plans, monthlyAmountCents, dailyAmountCents, dailyMode)
    }

    /** 某月的分类预算：金额方案优先取该月单独设置，否则全局分类金额；比例方案按该月月预算折算 */
    fun categoriesFor(
        month: YearMonth,
        startDay: Int,
        plans: Map<YearMonth, MonthBudgetPlan>,
        mode: CategoryBudgetMode,
        globalAmounts: Map<Long, Long>,
        percents: Map<Long, Int>,
        monthlyAmountCents: Long,
        dailyAmountCents: Long,
        dailyMode: Boolean,
    ): Map<Long, Long> {
        if (mode == CategoryBudgetMode.PERCENT) {
            val monthly = totalFor(month, startDay, plans, monthlyAmountCents, dailyAmountCents, dailyMode)
            return percents.mapValues { (_, bp) -> CategoryBudgetMath.centsOf(bp, monthly) }
        }
        val own = plans[month]?.categoryCents
        return if (own != null && own.isNotEmpty()) own else globalAmounts
    }

    /** 年视角分类预算：逐月相加（金额方案）；比例方案按年预算折算 */
    fun categoriesForYear(
        year: Int,
        startDay: Int,
        plans: Map<YearMonth, MonthBudgetPlan>,
        mode: CategoryBudgetMode,
        globalAmounts: Map<Long, Long>,
        percents: Map<Long, Int>,
        monthlyAmountCents: Long,
        dailyAmountCents: Long,
        dailyMode: Boolean,
    ): Map<Long, Long> {
        if (mode == CategoryBudgetMode.PERCENT) {
            val annual = totalForYear(year, startDay, plans, monthlyAmountCents, dailyAmountCents, dailyMode)
            return percents.mapValues { (_, bp) -> CategoryBudgetMath.centsOf(bp, annual) }
        }
        val sum = LinkedHashMap<Long, Long>()
        (1..12).forEach { m ->
            categoriesFor(
                YearMonth.of(year, m), startDay, plans, mode, globalAmounts, percents,
                monthlyAmountCents, dailyAmountCents, dailyMode,
            ).forEach { (id, cents) -> sum[id] = (sum[id] ?: 0L) + cents }
        }
        return sum
    }
}

/**
 * 月预算的持久化文本编解码（纯函数，可单测）。
 *
 * 格式：`v1|<总金额分>|<分类id:金额分,分类id:金额分>`
 * 空分类写空串。任何解析失败都返回 null，让调用方回落到全局预算而不是崩溃。
 */
object MonthBudgetCodec {

    fun keyOf(month: YearMonth): String = "budget_plan_${month.year}-${month.monthValue.toString().padStart(2, '0')}"

    fun encode(plan: MonthBudgetPlan): String {
        val cats = plan.categoryCents.entries
            .filter { it.value > 0L }
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
        return "v1|${plan.totalCents.coerceAtLeast(0L)}|$cats"
    }

    fun decode(text: String?): MonthBudgetPlan? {
        if (text.isNullOrBlank()) return null
        val parts = text.split('|')
        if (parts.size != 3 || parts[0] != "v1") return null
        val total = parts[1].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val cats = LinkedHashMap<Long, Long>()
        if (parts[2].isNotBlank()) {
            parts[2].split(',').forEach { entry ->
                val pair = entry.split(':')
                if (pair.size != 2) return null
                val id = pair[0].toLongOrNull() ?: return null
                val cents = pair[1].toLongOrNull() ?: return null
                if (cents > 0L) cats[id] = cents
            }
        }
        return MonthBudgetPlan(total, cats)
    }
}
