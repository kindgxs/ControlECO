package com.controleco.app.data.calendar

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 日历收支口径单测（功能1）：收入含退款到账、支出排除已退款。 */
class CalendarMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun row(
        type: TxType,
        cents: Long,
        date: LocalDate,
        refund: RefundMark = RefundMark.NONE,
    ) = TransactionRow(
        id = 0,
        type = type,
        amountCents = cents,
        timestamp = date.atTime(9, 30).atZone(zone).toInstant().toEpochMilli(),
        note = "",
        counterparty = "",
        source = TxSource.MANUAL,
        categoryId = 1,
        categoryName = "分类",
        accountId = 1,
        accountName = "账户",
        refundMark = refund,
    )

    @Test
    fun `按天汇总收入与支出`() {
        val day = LocalDate.of(2026, 10, 24)
        val rows = listOf(
            row(TxType.INCOME, 100_000, day),
            row(TxType.EXPENSE, 30_000, day),
            row(TxType.EXPENSE, 20_000, day),
        )
        val byDay = CalendarMath.byDay(rows, zone)[day]!!
        assertEquals(100_000L, byDay.incomeCents)
        assertEquals(50_000L, byDay.expenseCents)
        assertEquals(50_000L, byDay.netCents)
    }

    @Test
    fun `已退款的支出不计入日历支出_退款收入照常计入`() {
        val day = LocalDate.of(2026, 10, 24)
        val rows = listOf(
            row(TxType.EXPENSE, 30_000, day, RefundMark.REFUNDED_EXPENSE),
            row(TxType.INCOME, 30_000, day, RefundMark.REFUND_INCOME),
        )
        val byDay = CalendarMath.byDay(rows, zone)[day]!!
        assertEquals(30_000L, byDay.incomeCents)
        assertEquals(0L, byDay.expenseCents)
    }

    @Test
    fun `月汇总与按天汇总口径一致`() {
        val rows = listOf(
            row(TxType.INCOME, 100_000, LocalDate.of(2026, 10, 1)),
            row(TxType.EXPENSE, 30_000, LocalDate.of(2026, 10, 2)),
            row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 3), RefundMark.REFUNDED_EXPENSE),
        )
        val totals = CalendarMath.totals(rows)
        assertEquals(100_000L, totals.incomeCents)
        assertEquals(30_000L, totals.expenseCents)
        assertEquals(70_000L, totals.netCents)
    }

    @Test
    fun `没有账单的日期不出现在结果里`() {
        val rows = listOf(row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 5)))
        assertEquals(setOf(LocalDate.of(2026, 10, 5)), CalendarMath.byDay(rows, zone).keys)
    }
}
