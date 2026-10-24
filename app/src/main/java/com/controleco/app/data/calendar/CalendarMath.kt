package com.controleco.app.data.calendar

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 某一天的收支（日历格子里显示的两行金额 / 月汇总都用它） */
data class DayMoney(
    val incomeCents: Long = 0L,
    val expenseCents: Long = 0L,
) {
    val netCents: Long get() = incomeCents - expenseCents
    val isEmpty: Boolean get() = incomeCents == 0L && expenseCents == 0L
}

/**
 * 日历口径（纯函数，可单测）。
 *
 * - 收入 = 全部收入（含退款到账）；
 * - 支出 = 未被标记「已退款」的支出（与首页/统计一致）。
 */
object CalendarMath {

    fun byDay(rows: List<TransactionRow>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, DayMoney> {
        val map = LinkedHashMap<LocalDate, DayMoney>()
        rows.forEach { row ->
            val date = Instant.ofEpochMilli(row.timestamp).atZone(zone).toLocalDate()
            val old = map[date] ?: DayMoney()
            map[date] = when {
                row.type == TxType.INCOME -> old.copy(incomeCents = old.incomeCents + row.amountCents)
                row.refundMark == RefundMark.REFUNDED_EXPENSE -> old
                else -> old.copy(expenseCents = old.expenseCents + row.amountCents)
            }
        }
        return map
    }

    /** 整月汇总（底部选项下方的月收入 / 月支出 / 月结余） */
    fun totals(rows: List<TransactionRow>): DayMoney {
        var income = 0L
        var expense = 0L
        rows.forEach { row ->
            when {
                row.type == TxType.INCOME -> income += row.amountCents
                row.refundMark == RefundMark.REFUNDED_EXPENSE -> Unit
                else -> expense += row.amountCents
            }
        }
        return DayMoney(income, expense)
    }
}
