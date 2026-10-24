package com.controleco.app.data.budget

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按月预算的存储与回落规则单测（功能5）。 */
class MonthlyBudgetTest {

    @Test
    fun `编码解码往返_分类按 id 升序`() {
        val plan = MonthBudgetPlan(
            totalCents = 500_000L,
            categoryCents = mapOf(3L to 30_000L, 1L to 120_000L),
        )
        val text = MonthBudgetCodec.encode(plan)
        assertEquals("v1|500000|1:120000,3:30000", text)
        assertEquals(plan, MonthBudgetCodec.decode(text))
    }

    @Test
    fun `分类金额为 0 时不写入文本_解码也不保留`() {
        val text = MonthBudgetCodec.encode(
            MonthBudgetPlan(totalCents = 100_000L, categoryCents = mapOf(1L to 0L, 2L to 50_000L)),
        )
        assertEquals("v1|100000|2:50000", text)
        assertEquals(mapOf(2L to 50_000L), MonthBudgetCodec.decode(text)!!.categoryCents)
    }

    @Test
    fun `空计划编码为空分类段_可正常解码`() {
        val text = MonthBudgetCodec.encode(MonthBudgetPlan())
        assertEquals("v1|0|", text)
        val decoded = MonthBudgetCodec.decode(text)!!
        assertEquals(0L, decoded.totalCents)
        assertTrue(decoded.categoryCents.isEmpty())
    }

    @Test
    fun `非法文本返回 null_由调用方回落全局预算`() {
        assertNull(MonthBudgetCodec.decode(null))
        assertNull(MonthBudgetCodec.decode(""))
        assertNull(MonthBudgetCodec.decode("v2|1|"))
        assertNull(MonthBudgetCodec.decode("v1|abc|"))
        assertNull(MonthBudgetCodec.decode("v1|100|1"))
        assertNull(MonthBudgetCodec.decode("v1|100|x:10"))
    }

    @Test
    fun `存储键按月区分`() {
        assertEquals("budget_plan_2026-01", MonthBudgetCodec.keyOf(YearMonth.of(2026, 1)))
        assertEquals("budget_plan_2026-10", MonthBudgetCodec.keyOf(YearMonth.of(2026, 10)))
    }

    @Test
    fun `某月有独立设置时优先使用它`() {
        val plans = mapOf(
            YearMonth.of(2026, 10) to MonthBudgetPlan(totalCents = 800_000L),
        )
        assertEquals(
            800_000L,
            MonthBudgetMath.totalFor(
                month = YearMonth.of(2026, 10),
                startDay = 1,
                plans = plans,
                monthlyAmountCents = 300_000L,
                dailyAmountCents = 10_000L,
                dailyMode = false,
            ),
        )
        // 没设置的月份回落全局
        assertEquals(
            300_000L,
            MonthBudgetMath.totalFor(
                month = YearMonth.of(2026, 11),
                startDay = 1,
                plans = plans,
                monthlyAmountCents = 300_000L,
                dailyAmountCents = 10_000L,
                dailyMode = false,
            ),
        )
    }

    @Test
    fun `没设置月时_日预算模式按当月天数折算`() {
        // 2026-02 有 28 天
        assertEquals(
            280_000L,
            MonthBudgetMath.totalFor(
                month = YearMonth.of(2026, 2),
                startDay = 1,
                plans = emptyMap(),
                monthlyAmountCents = 300_000L,
                dailyAmountCents = 10_000L,
                dailyMode = true,
            ),
        )
    }

    @Test
    fun `分类预算_金额模式优先取当月设置`() {
        val plans = mapOf(
            YearMonth.of(2026, 10) to MonthBudgetPlan(categoryCents = mapOf(1L to 80_000L)),
        )
        assertEquals(
            mapOf(1L to 80_000L),
            MonthBudgetMath.categoriesFor(
                month = YearMonth.of(2026, 10),
                startDay = 1,
                plans = plans,
                mode = CategoryBudgetMode.AMOUNT,
                globalAmounts = mapOf(1L to 50_000L, 2L to 20_000L),
                percents = emptyMap(),
                monthlyAmountCents = 300_000L,
                dailyAmountCents = 10_000L,
                dailyMode = false,
            ),
        )
        // 没有当月设置 → 全局分类金额
        assertEquals(
            mapOf(1L to 50_000L, 2L to 20_000L),
            MonthBudgetMath.categoriesFor(
                month = YearMonth.of(2026, 11),
                startDay = 1,
                plans = plans,
                mode = CategoryBudgetMode.AMOUNT,
                globalAmounts = mapOf(1L to 50_000L, 2L to 20_000L),
                percents = emptyMap(),
                monthlyAmountCents = 300_000L,
                dailyAmountCents = 10_000L,
                dailyMode = false,
            ),
        )
    }

    @Test
    fun `分类预算_比例模式按当月月预算折算`() {
        val plans = mapOf(
            YearMonth.of(2026, 10) to MonthBudgetPlan(totalCents = 500_000L),
        )
        assertEquals(
            mapOf(1L to 250_000L),
            MonthBudgetMath.categoriesFor(
                month = YearMonth.of(2026, 10),
                startDay = 1,
                plans = plans,
                mode = CategoryBudgetMode.PERCENT,
                globalAmounts = emptyMap(),
                percents = mapOf(1L to 5_000), // 50%
                monthlyAmountCents = 300_000L,
                dailyAmountCents = 10_000L,
                dailyMode = false,
            ),
        )
    }

    @Test
    fun `年视角分类预算逐月相加`() {
        val plans = mapOf(
            YearMonth.of(2026, 1) to MonthBudgetPlan(categoryCents = mapOf(1L to 100_000L)),
        )
        val year = MonthBudgetMath.categoriesForYear(
            year = 2026,
            startDay = 1,
            plans = plans,
            mode = CategoryBudgetMode.AMOUNT,
            globalAmounts = mapOf(1L to 50_000L),
            percents = emptyMap(),
            monthlyAmountCents = 300_000L,
            dailyAmountCents = 10_000L,
            dailyMode = false,
        )
        // 1 月独立 100_000 + 其余 11 个月全局 50_000
        assertEquals(100_000L + 50_000L * 11, year[1L])
    }

    @Test
    fun `空计划 hasTotal 为 false`() {
        assertFalse(MonthBudgetPlan().hasTotal)
        assertTrue(MonthBudgetPlan(totalCents = 1L).hasTotal)
    }
}
