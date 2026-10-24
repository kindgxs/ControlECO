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
