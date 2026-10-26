package com.controleco.app.data.deposit

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 存款分析纯函数单测（功能7）。 */
class DepositMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun row(
        type: TxType,
        cents: Long,
        date: LocalDate,
        refund: RefundMark = RefundMark.NONE,
        countInStats: Boolean = true,
        refundLinkId: Long = 0L,
        linkedRefundCents: Long = 0L,
        linkedExpenseCents: Long = 0L,
    ) = TransactionRow(
        id = 0,
        type = type,
        amountCents = cents,
        timestamp = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        note = "",
        counterparty = "",
        source = TxSource.MANUAL,
        categoryId = 1,
        categoryName = "分类",
        accountId = 1,
        accountName = "账户",
        refundMark = refund,
        countInStats = countInStats,
        refundLinkId = refundLinkId,
        linkedRefundCents = linkedRefundCents,
        linkedExpenseCents = linkedExpenseCents,
    )

    @Test
    fun `已退款的支出不计入存款口径`() {
        assertEquals(0L, DepositMath.expenseCentsOf(row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 1), RefundMark.REFUNDED_EXPENSE)))
        assertEquals(10_000L, DepositMath.expenseCentsOf(row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 1))))
        assertEquals(0L, DepositMath.expenseCentsOf(row(TxType.INCOME, 10_000, LocalDate.of(2026, 10, 1))))
    }

    @Test
    fun `不计入统计的收支不计入存款口径`() {
        assertEquals(0L, DepositMath.expenseCentsOf(row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 1), countInStats = false)))
        assertEquals(0L, DepositMath.incomeCentsOf(row(TxType.INCOME, 10_000, LocalDate.of(2026, 10, 1), countInStats = false)))
    }

    @Test
    fun `当月新增存款等于收入减支出_退款收入照常计入`() {
        val rows = listOf(
            row(TxType.INCOME, 1_000_000, LocalDate.of(2026, 10, 5)),
            row(TxType.INCOME, 5_000, LocalDate.of(2026, 10, 6), RefundMark.REFUND_INCOME),
            row(TxType.EXPENSE, 300_000, LocalDate.of(2026, 10, 7)),
            row(TxType.EXPENSE, 20_000, LocalDate.of(2026, 10, 8), RefundMark.REFUNDED_EXPENSE),
        )
        val saving = DepositMath.rangeSaving(rows, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), zone)
        assertEquals(1_005_000L, saving.incomeCents)
        assertEquals(300_000L, saving.expenseCents)
        assertEquals(705_000L, saving.savingCents)
    }

    @Test
    fun `区间外的账单不计入`() {
        val rows = listOf(
            row(TxType.INCOME, 100_000, LocalDate.of(2026, 9, 30)),
            row(TxType.INCOME, 50_000, LocalDate.of(2026, 10, 1)),
        )
        val saving = DepositMath.rangeSaving(rows, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), zone)
        assertEquals(50_000L, saving.incomeCents)
    }

    @Test
    fun `按自然月汇总并排序`() {
        val rows = listOf(
            row(TxType.EXPENSE, 30_000, LocalDate.of(2026, 9, 10)),
            row(TxType.INCOME, 100_000, LocalDate.of(2026, 9, 1)),
            row(TxType.INCOME, 80_000, LocalDate.of(2026, 10, 1)),
        )
        val months = DepositMath.monthlySavings(rows, zone)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), months.map { it.month })
        assertEquals(70_000L, months[0].savingCents)
        assertEquals(80_000L, months[1].savingCents)
    }

    @Test
    fun `趋势补齐缺失月份_含结束月`() {
        val savings = listOf(
            DepositMath.MonthSaving(YearMonth.of(2026, 8), 100_000, 20_000),
            DepositMath.MonthSaving(YearMonth.of(2026, 10), 50_000, 0),
        )
        val trend = DepositMath.trendEndingAt(savings, YearMonth.of(2026, 10), 3)
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)), trend.map { it.month })
        assertEquals(80_000L, trend[0].savingCents)
        assertEquals(0L, trend[1].savingCents)
        assertEquals(50_000L, trend[2].savingCents)
    }

    @Test
    fun `达成比例_未设目标返回 null`() {
        val saving = DepositMath.MonthSaving(YearMonth.of(2026, 10), 100_000, 20_000)
        assertNull(saving.ratio(0L))
        assertEquals(1f, saving.ratio(80_000L)!!, 0.0001f)
    }

    @Test
    fun `关联退款等额抵消_收支均不计入`() {
        val expense = row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 1), linkedRefundCents = 10_000)
        val refund = row(TxType.INCOME, 10_000, LocalDate.of(2026, 10, 2), RefundMark.REFUND_INCOME, refundLinkId = 1, linkedExpenseCents = 10_000)
        assertEquals(0L, DepositMath.expenseCentsOf(expense))
        assertEquals(0L, DepositMath.incomeCentsOf(refund))
    }

    @Test
    fun `关联退款部分抵扣_只计差额`() {
        val expense = row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 1), linkedRefundCents = 4_000)
        assertEquals(6_000L, DepositMath.expenseCentsOf(expense))
    }

    @Test
    fun `关联退款超额_支出为0_超出部分计入收入`() {
        val expense = row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 1), linkedRefundCents = 12_000)
        val refund = row(TxType.INCOME, 12_000, LocalDate.of(2026, 10, 2), RefundMark.REFUND_INCOME, refundLinkId = 1, linkedExpenseCents = 10_000)
        assertEquals(0L, DepositMath.expenseCentsOf(expense))
        assertEquals(2_000L, DepositMath.incomeCentsOf(refund))
    }
}
