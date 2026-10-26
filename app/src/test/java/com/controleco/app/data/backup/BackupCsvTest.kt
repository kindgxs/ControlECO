package com.controleco.app.data.backup

import com.controleco.app.csv.CsvReader
import com.controleco.app.data.db.AccountKind
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSV 账单导入解析单测（修改8）：账户 / 一二级分类 / 账单字段都要能还原。 */
class BackupCsvTest {

    private fun sampleCsv(): String = """
        #ControlECO,backup,1
        ##accounts|账户
        id,name,kind,initialBalanceCents,creditLimitCents,sharedGroupId,isDefault,note
        1,招商储蓄卡,CASH,100000,0,0,1,
        2,招商信用卡,CREDIT,0,500000,2,0,主卡
        3,招商信用卡副卡,CREDIT,0,500000,2,0,
        ##categories|分类
        id,name,type,sortOrder,icon,parentId
        1,餐饮,EXPENSE,0,,
        2,早餐,EXPENSE,0,,1
        3,工资,INCOME,0,,
        ##transactions|交易记录
        时间,类型,分类,金额,资产账户,记录标识,备注,二级分类,币种,编号,来源,退款状态,退款待定,不扣资产,流水号,创建时间,商户,计入统计,退款关联
        2026-10-24 12:03:00,支出,餐饮,25.00,招商信用卡,,午饭,早餐,¥,11,WECHAT,NONE,0,1,,1750000000000,老王包子,0,0
        2026/10/23,收入,工资,"5,000.00",招商储蓄卡,EXT-1,十月工资,,¥,12,MANUAL,NONE,0,0,,1750000000000,,1,0
        2026-10-22,支出,餐饮,100.00,招商信用卡,,退款,早餐,¥,13,ALIPAY,REFUNDED_EXPENSE,0,1,,1750000000000,,,0
    """.trimIndent()

    private fun parse(): BackupData {
        val data = BackupCsv.parseRows(CsvReader.parse(sampleCsv()))
        assertNotNull(data)
        return data!!
    }

    @Test
    fun `账户与分类原样还原`() {
        val data = parse()
        assertEquals(3, data.accounts.size)
        assertEquals(AccountKind.CASH, data.accounts[0].kind)
        assertEquals(AccountKind.CREDIT, data.accounts[1].kind)
        assertEquals(2L, data.accounts[1].sharedGroupId)
        assertTrue(data.accounts[0].isDefault)

        assertEquals(3, data.categories.size)
        assertEquals(1L, data.categories.first { it.name == "早餐" }.parentId)
    }

    @Test
    fun `账单按名称解析到账户与二级分类`() {
        val data = parse()
        assertEquals(3, data.transactions.size)

        val lunch = data.transactions.first { it.id == 11L }
        assertEquals(TxType.EXPENSE, lunch.type)
        assertEquals(2_500L, lunch.amountCents)
        assertEquals(2L, lunch.accountId)
        // 二级分类「早餐」应挂在一级分类「餐饮」下
        val breakfast = data.categories.first { it.id == lunch.categoryId }
        assertEquals("早餐", breakfast.name)
        assertEquals(1L, breakfast.parentId)
        assertEquals(TxSource.WECHAT, lunch.source)
        assertEquals("老王包子", lunch.counterparty)
        assertNull(lunch.externalId)
        // 「计入统计」列：0 = 只在账单显示一条记录；空列回落为计入
        assertFalse(lunch.countInStats)
        assertTrue(data.transactions.first { it.id == 12L }.countInStats)
        assertTrue(data.transactions.first { it.id == 13L }.countInStats)
    }

    @Test
    fun `账单扩展字段_退款与金额千分位`() {
        val data = parse()
        val income = data.transactions.first { it.id == 12L }
        assertEquals(TxType.INCOME, income.type)
        assertEquals(500_000L, income.amountCents)
        assertEquals("EXT-1", income.externalId)

        val refunded = data.transactions.first { it.id == 13L }
        assertEquals(RefundMark.REFUNDED_EXPENSE, refunded.refundMark)
        assertEquals(TxSource.ALIPAY, refunded.source)
    }

    @Test
    fun `未知账户名自动建账户_不丢账单`() {
        // 只改账单行里的账户名，账户区不变
        val text = sampleCsv().replace(",招商信用卡,", ",新卡,")
        val data = BackupCsv.parseRows(CsvReader.parse(text))!!
        val used = data.transactions.map { it.accountId }.toSet()
        assertTrue(used.all { id -> data.accounts.any { it.id == id } })
        assertTrue(data.accounts.any { it.name == "新卡" })
    }

    @Test
    fun `非备份文件返回空`() {
        assertNull(BackupCsv.parseRows(CsvReader.parse("日期,金额\n2026-10-24,1.00")))
    }

    @Test
    fun `商户归类随备份还原_重复项去重`() {
        val text = sampleCsv() + "\n" + """
            ##merchant_rules|商户归类
            id,matchKey,type,categoryId,accountId,hits,updatedAt
            1,老王包子,EXPENSE,2,2,3,1750000000000
            1,老王包子,EXPENSE,2,2,5,1750000000000
            2,滴滴出行,EXPENSE,1,,1,1750000000000
        """.trimIndent()
        val data = BackupCsv.parseRows(CsvReader.parse(text))!!
        assertEquals(2, data.merchantRules.size)

        val bun = data.merchantRules.first { it.matchKey == "老王包子" }
        assertEquals(TxType.EXPENSE, bun.type)
        // 分类/账户按「名称 → 新 id」映射后的结果（二级分类「早餐」= 2，「招商信用卡」= 2）
        assertEquals(2L, bun.categoryId)
        assertEquals(2L, bun.accountId)
        assertEquals(3, bun.hits)

        val didi = data.merchantRules.first { it.matchKey == "滴滴出行" }
        assertEquals(1L, didi.categoryId)
        assertNull(didi.accountId)
    }

    @Test
    fun `分期计划随 CSV 还原`() {
        val text = sampleCsv() + "\n" + """
            ##installments|分期计划
            id,title,accountId,categoryId,totalPrincipalCents,totalInterestCents,periods,firstDate,postTime,deductAsset,excludeAssetPeriods,postedPeriods,note,countInStats,countInStatsPeriods
            1,手机分期,2,1,600000,0,6,2026-01-10,09:00,1,,3,,1,1-3
            2,免息分期,2,1,120000,0,3,2026-03-15,09:00,0,1-2,1,只记账,0,
        """.trimIndent()
        val data = BackupCsv.parseRows(CsvReader.parse(text))!!
        assertEquals(2, data.installments.size)

        val phone = data.installments.first { it.title == "手机分期" }
        assertEquals(2L, phone.accountId)
        assertEquals(1L, phone.categoryId)
        assertEquals(600_000L, phone.totalPrincipalCents)
        assertEquals(6, phone.periods)
        assertEquals(3, phone.postedPeriods)
        assertTrue(phone.deductAsset)
        assertEquals("1-3", phone.countInStatsPeriods)

        val interestFree = data.installments.first { it.title == "免息分期" }
        assertFalse(interestFree.deductAsset)
        assertEquals("1-2", interestFree.excludeAssetPeriods)
        assertFalse(interestFree.countInStats)
    }

    @Test
    fun `周期计划随 CSV 还原`() {
        val text = sampleCsv() + "\n" + """
            ##recurring|周期计划
            id,title,type,amountCents,categoryId,accountId,frequency,intervalCount,firstDate,nextDate,postTime,deductAsset,autoPost,endMode,endCount,endDate,postedCount,excludeAssetPeriods,note,countInStats,countInStatsPeriods
            1,房租,EXPENSE,300000,1,1,MONTHLY,1,2026-01-01,2026-11-01,09:00,1,0,NEVER,0,,11,,1,
        """.trimIndent()
        val data = BackupCsv.parseRows(CsvReader.parse(text))!!
        assertEquals(1, data.recurring.size)

        val rent = data.recurring.first()
        assertEquals("房租", rent.title)
        assertEquals(TxType.EXPENSE, rent.type)
        assertEquals(300_000L, rent.amountCents)
        assertEquals(11, rent.postedCount)
        assertEquals("MONTHLY", rent.frequency)
        assertTrue(rent.deductAsset)
    }
}
