package com.controleco.app.data.plan

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分期 / 周期记账纯函数单测（功能2）。 */
class PlanMathTest {

    @Test
    fun `总利息按每期费率与期数自动计算`() {
        // ¥10,000 分 12 期，每期 0.60% → 720.00
        assertEquals(72_000L, InstallmentMath.autoInterestCents(1_000_000L, 12))
        assertEquals(0L, InstallmentMath.autoInterestCents(0L, 12))
        assertEquals(0L, InstallmentMath.autoInterestCents(1_000_000L, 0))
    }

    @Test
    fun `每期本金之和等于总本金_余数落最后一期`() {
        val principal = 100_000L
        val periods = 3
        val parts = (1..periods).map { InstallmentMath.principalOf(principal, periods, it) }
        assertEquals(principal, parts.sum())
        assertEquals(33_333L, parts[0])
        assertEquals(33_334L, parts[2])
    }

    @Test
    fun `每期利息之和等于总利息`() {
        val interest = 72_000L
        val periods = 12
        val parts = (1..periods).map { InstallmentMath.interestOf(interest, periods, it) }
        assertEquals(interest, parts.sum())
    }

    @Test
    fun `每期合计等于本金份额加利息份额`() {
        val periods = 6
        assertEquals(
            20_000L + 7_200L,
            InstallmentMath.periodTotal(120_000L, 43_200L, periods, 1),
        )
        assertEquals(
            120_000L + 43_200L,
            (1..periods).sumOf { InstallmentMath.periodTotal(120_000L, 43_200L, periods, it) },
        )
    }

    @Test
    fun `分期日期逐月顺延_短月落到月末`() {
        assertEquals(LocalDate.of(2026, 1, 31), InstallmentMath.periodDate(LocalDate.of(2026, 1, 31), 1))
        assertEquals(LocalDate.of(2026, 2, 28), InstallmentMath.periodDate(LocalDate.of(2026, 1, 31), 2))
        assertEquals(LocalDate.of(2026, 3, 31), InstallmentMath.periodDate(LocalDate.of(2026, 1, 31), 3))
    }

    @Test
    fun `周期记账_下一次日期`() {
        assertEquals(
            LocalDate.of(2026, 10, 25),
            RecurringMath.next(LocalDate.of(2026, 10, 24), RecurringFrequency.DAILY),
        )
        assertEquals(
            LocalDate.of(2026, 10, 31),
            RecurringMath.next(LocalDate.of(2026, 10, 24), RecurringFrequency.WEEKLY),
        )
        assertEquals(
            LocalDate.of(2026, 11, 30),
            RecurringMath.next(LocalDate.of(2026, 10, 31), RecurringFrequency.MONTHLY),
        )
    }

    @Test
    fun `周期推进 count 次`() {
        val from = LocalDate.of(2026, 1, 31)
        assertEquals(from, RecurringMath.advance(from, RecurringFrequency.MONTHLY, 0))
        assertEquals(LocalDate.of(2026, 2, 28), RecurringMath.advance(from, RecurringFrequency.MONTHLY, 1))
        // 2/28 的下一次仍是月末：3/28（以当前日期为基准继续顺延）
        assertEquals(LocalDate.of(2026, 3, 28), RecurringMath.advance(from, RecurringFrequency.MONTHLY, 2))
    }

    @Test
    fun `周期频率解析_未知值回落每月`() {
        assertEquals(RecurringFrequency.MONTHLY, RecurringFrequency.of(null))
        assertEquals(RecurringFrequency.MONTHLY, RecurringFrequency.of("WHATEVER"))
        assertEquals(RecurringFrequency.WEEKLY, RecurringFrequency.of("WEEKLY"))
    }

    @Test
    fun `当前期数按首期日期与今天自动推算`() {
        val first = LocalDate.of(2026, 1, 10)
        assertEquals(1, InstallmentMath.currentPeriod(first, LocalDate.of(2026, 1, 9), 12))
        assertEquals(1, InstallmentMath.currentPeriod(first, LocalDate.of(2026, 1, 10), 12))
        assertEquals(1, InstallmentMath.currentPeriod(first, LocalDate.of(2026, 2, 9), 12))
        assertEquals(2, InstallmentMath.currentPeriod(first, LocalDate.of(2026, 2, 10), 12))
        // 超过最后一期时停在总期数
        assertEquals(12, InstallmentMath.currentPeriod(first, LocalDate.of(2027, 6, 1), 12))
        assertEquals(1, InstallmentMath.currentPeriod(first, LocalDate.of(2026, 1, 10), 0))
    }

    @Test
    fun `指定不扣资产的期数解析与编码`() {
        assertEquals(setOf(1, 3, 12), InstallmentMath.parseExcludedPeriods(" 1, 3,12 ", 12))
        assertEquals(emptySet<Int>(), InstallmentMath.parseExcludedPeriods("", 12))
        // 越界期数被忽略
        assertEquals(setOf(1, 12), InstallmentMath.parseExcludedPeriods("0,1,13,12", 12))
        assertEquals("1,3,12", InstallmentMath.encodeExcludedPeriods(setOf(12, 1, 3), 12))
        assertEquals("", InstallmentMath.encodeExcludedPeriods(setOf(13), 12))
    }

    @Test
    fun `不扣资产判断_总开关与指定期数`() {
        assertFalse(InstallmentMath.excludesAsset(true, "", 12, 1))
        assertTrue(InstallmentMath.excludesAsset(false, "", 12, 1))
        assertTrue(InstallmentMath.excludesAsset(true, "2,4", 12, 4))
        assertFalse(InstallmentMath.excludesAsset(true, "2,4", 12, 3))
    }
}
