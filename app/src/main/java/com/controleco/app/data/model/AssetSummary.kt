package com.controleco.app.data.model

/**
 * 资产总览：净资产 = 存款 − 负债。
 *
 * - 账户余额为正 → 计入存款；
 * - 账户余额为负 → 计入负债（取绝对值展示）。
 */
data class AssetSummary(
    val depositCents: Long,
    val liabilityCents: Long,
    val accountCount: Int,
) {
    val netCents: Long get() = depositCents - liabilityCents

    companion object {
        val EMPTY = AssetSummary(0L, 0L, 0)
    }
}

/** 资产计算用的账户快照：期初余额 + 流水净额 = 当前余额 */
data class AssetAccountSnapshot(
    val id: Long,
    val sharedGroupId: Long,
    val initialBalanceCents: Long,
    val flowCents: Long,
) {
    val balanceCents: Long get() = initialBalanceCents + flowCents
}

/**
 * 资产口径（纯函数，可单测）。
 *
 * 共用额度组（同一张信用额度下的多张卡）只算一次资产：
 * 组内各卡余额相加后再判断是存款还是负债；但组内每张卡展示的流水相同
 * ——账单扣卡 1 时，卡 2 的流水同步变化。
 */
object AssetMath {

    fun summarize(accounts: List<AssetAccountSnapshot>): AssetSummary {
        var deposit = 0L
        var liability = 0L
        accounts.groupBy { groupKeyOf(it) }.values.forEach { members ->
            val balance = if (members.first().sharedGroupId != 0L) {
                members.sumOf { it.balanceCents }
            } else {
                members.first().balanceCents
            }
            if (balance >= 0L) deposit += balance else liability += -balance
        }
        return AssetSummary(deposit, liability, accounts.size)
    }

    /** 每个账户展示用的流水：共用额度组内所有卡的流水合并（各卡显示一致） */
    fun displayedFlows(accounts: List<AssetAccountSnapshot>): Map<Long, Long> =
        accounts.groupBy { groupKeyOf(it) }.values.flatMap { members ->
            val flow = members.sumOf { it.flowCents }
            members.map { it.id to flow }
        }.toMap()

    /** 组 key：共用组用组 id，独立账户用负 id（避免与组 id 撞） */
    private fun groupKeyOf(account: AssetAccountSnapshot): Long =
        if (account.sharedGroupId != 0L) account.sharedGroupId else -account.id
}
