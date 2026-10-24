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
    fun `不扣资产期数支持区间写法`() {
        // 1-3
        assertEquals(setOf(1, 2, 3), InstallmentMath.parseExcludedPeriods("1-3", 12))
        // 1-3,6
        assertEquals(setOf(1, 2, 3, 6), InstallmentMath.parseExcludedPeriods("1-3,6", 12))
        // 多个区间 + 全角标点 / 波浪号
        assertEquals(setOf(1, 2, 3, 5, 6, 7), InstallmentMath.parseExcludedPeriods("1－3，5~7", 12))
        // 超出总期数的部分被裁掉，反向区间忽略
        assertEquals(setOf(10, 11, 12), InstallmentMath.parseExcludedPeriods("10-20", 12))
        assertEquals(emptySet<Int>(), InstallmentMath.parseExcludedPeriods("5-3", 12))
    }

    @Test
    fun `区间编码与解析互逆`() {
        assertEquals("1-3,6", InstallmentMath.encodeExcludedPeriods(setOf(1, 2, 3, 6), 12))
        assertEquals("3,4", InstallmentMath.encodeExcludedPeriods(setOf(3, 4), 12))
        assertEquals("1,3", InstallmentMath.encodeExcludedPeriods(setOf(1, 3), 12))
        val set = InstallmentMath.parseExcludedPeriods("1-3,6,9-10", 12)
        assertEquals(setOf(1, 2, 3, 6, 9, 10), set)
        assertEquals(set, InstallmentMath.parseExcludedPeriods(InstallmentMath.encodeExcludedPeriods(set, 12), 12))
    }

    @Test
    fun `不扣资产期数非法输入校验`() {
        assertFalse(InstallmentMath.hasInvalidExcludedPeriods("", 12))
        assertFalse(InstallmentMath.hasInvalidExcludedPeriods("1-3,6", 12))
        assertTrue(InstallmentMath.hasInvalidExcludedPeriods("0", 12))
        assertTrue(InstallmentMath.hasInvalidExcludedPeriods("13", 12))
        assertTrue(InstallmentMath.hasInvalidExcludedPeriods("5-3", 12))
        assertTrue(InstallmentMath.hasInvalidExcludedPeriods("1-", 12))
        assertTrue(InstallmentMath.hasInvalidExcludedPeriods("a", 12))
    }

    @Test
    fun `不扣资产判断_总开关与指定期数`() {
        assertFalse(InstallmentMath.excludesAsset(true, "", 12, 1))
        assertTrue(InstallmentMath.excludesAsset(false, "", 12, 1))
        assertTrue(InstallmentMath.excludesAsset(true, "2,4", 12, 4))
        assertFalse(InstallmentMath.excludesAsset(true, "2,4", 12, 3))
    }

    @Test
    fun `周期记账_每年与每N天`() {
        assertEquals(
            LocalDate.of(2027, 10, 24),
            RecurringMath.next(LocalDate.of(2026, 10, 24), RecurringFrequency.YEARLY),
        )
        // 2/29 的每年顺延落到非闰年的 2/28
        assertEquals(
            LocalDate.of(2025, 2, 28),
            RecurringMath.next(LocalDate.of(2024, 2, 29), RecurringFrequency.YEARLY),
        )
        assertEquals(
            LocalDate.of(2026, 10, 27),
            RecurringMath.next(LocalDate.of(2026, 10, 24), RecurringFrequency.EVERY_N_DAYS, interval = 3),
        )
        // 间隔非法时按 1 天处理
        assertEquals(
            LocalDate.of(2026, 10, 25),
            RecurringMath.next(LocalDate.of(2026, 10, 24), RecurringFrequency.EVERY_N_DAYS, interval = 0),
        )
        assertEquals("每 3 天", RecurringMath.describe(RecurringFrequency.EVERY_N_DAYS, 3))
        assertEquals("每月", RecurringMath.describe(RecurringFrequency.MONTHLY, 5))
    }

    @Test
    fun `周期记账_结束方式判断`() {
        val next = LocalDate.of(2026, 11, 12)
        assertFalse(
            RecurringMath.endedAfter(RecurringEndMode.NEVER, 0, 0L, 100, next),
        )
        // 按次数：已记满结束
        assertTrue(RecurringMath.endedAfter(RecurringEndMode.COUNT, 3, 0L, 3, next))
        assertFalse(RecurringMath.endedAfter(RecurringEndMode.COUNT, 3, 0L, 2, next))
        // 次数非法（0）时不结束
        assertFalse(RecurringMath.endedAfter(RecurringEndMode.COUNT, 0, 0L, 100, next))
        // 按日期：下一次已超过结束日
        val endMillis = LocalDate.of(2026, 10, 31)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(RecurringMath.endedAfter(RecurringEndMode.DATE, 0, endMillis, 5, next))
        assertFalse(
            RecurringMath.endedAfter(
                RecurringEndMode.DATE, 0, endMillis, 5, LocalDate.of(2026, 10, 31),
            ),
        )
        // 未设置结束日期不结束
        assertFalse(RecurringMath.endedAfter(RecurringEndMode.DATE, 0, 0L, 5, next))
    }

    @Test
    fun `出账时间与分钟换算`() {
        assertEquals(540, 9 * 60)
        assertEquals("09:00", InstallmentMath.timeText(540))
        assertEquals("00:00", InstallmentMath.timeText(-10))
        assertEquals("23:59", InstallmentMath.timeText(10_000))
        assertEquals("12:05", InstallmentMath.timeText(12 * 60 + 5))
    }

    @Test
    fun `未出账分期金额_不含已出账期数`() {
        // 1200 元分 3 期，无利息：已记 1 期 → 剩余 2 期 = 800 元
        assertEquals(80_000L, InstallmentMath.unpostedTotal(120_000L, 0L, 3, 1))
        assertEquals(120_000L, InstallmentMath.unpostedTotal(120_000L, 0L, 3, 0))
        assertEquals(0L, InstallmentMath.unpostedTotal(120_000L, 0L, 3, 3))
        assertEquals(0L, InstallmentMath.unpostedTotal(120_000L, 0L, 0, 0))
        // 含利息：总还款 = 本金 + 利息
        assertEquals(
            InstallmentMath.totalRepayment(120_000L, 43_200L),
            InstallmentMath.unpostedTotal(120_000L, 43_200L, 6, 0),
        )
    }

    @Test
    fun `周期记账不扣资产序号解析`() {
        assertEquals(setOf(1, 2, 3, 6), InstallmentMath.parseExcludedIndexes("1-3,6"))
        assertTrue(InstallmentMath.excludesAssetAt(true, "2", 2))
        assertFalse(InstallmentMath.excludesAssetAt(true, "2", 3))
        assertTrue(InstallmentMath.excludesAssetAt(false, "", 9999))
        // 超过上限的序号忽略
        assertEquals(emptySet<Int>(), InstallmentMath.parseExcludedIndexes("10001"))
    }

    @Test
    fun `结束方式解析_未知值回落永不结束`() {
        assertEquals(RecurringEndMode.NEVER, RecurringEndMode.of(null))
        assertEquals(RecurringEndMode.NEVER, RecurringEndMode.of("WHATEVER"))
        assertEquals(RecurringEndMode.COUNT, RecurringEndMode.of("COUNT"))
        assertEquals(RecurringEndMode.DATE, RecurringEndMode.of("DATE"))
    }
}
