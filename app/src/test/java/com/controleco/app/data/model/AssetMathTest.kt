package com.controleco.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 资产口径单测（修改2）：共用额度组的流水在每张卡上镜像，但资产只算一次。 */
class AssetMathTest {

    @Test
    fun `共用额度组_组内余额合并后只计一次`() {
        val accounts = listOf(
            AssetAccountSnapshot(id = 1, sharedGroupId = 7, initialBalanceCents = 0, flowCents = -100_000),
            AssetAccountSnapshot(id = 2, sharedGroupId = 7, initialBalanceCents = 0, flowCents = -50_000),
        )
        val summary = AssetMath.summarize(accounts)
        assertEquals(0L, summary.depositCents)
        assertEquals(150_000L, summary.liabilityCents)
        assertEquals(-150_000L, summary.netCents)
        assertEquals(2, summary.accountCount)
    }

    @Test
    fun `独立账户_正负余额分别计入存款与负债`() {
        val accounts = listOf(
            AssetAccountSnapshot(id = 1, sharedGroupId = 0, initialBalanceCents = 200_000, flowCents = 0),
            AssetAccountSnapshot(id = 2, sharedGroupId = 0, initialBalanceCents = 0, flowCents = -50_000),
        )
        val summary = AssetMath.summarize(accounts)
        assertEquals(200_000L, summary.depositCents)
        assertEquals(50_000L, summary.liabilityCents)
        assertEquals(150_000L, summary.netCents)
    }

    @Test
    fun `共用额度组_每张卡展示同一份合并流水`() {
        val accounts = listOf(
            AssetAccountSnapshot(id = 1, sharedGroupId = 7, initialBalanceCents = 0, flowCents = -100_000),
            AssetAccountSnapshot(id = 2, sharedGroupId = 7, initialBalanceCents = 0, flowCents = -50_000),
            AssetAccountSnapshot(id = 3, sharedGroupId = 0, initialBalanceCents = 0, flowCents = 20_000),
        )
        val flows = AssetMath.displayedFlows(accounts)
        assertEquals(-150_000L, flows[1])
        assertEquals(-150_000L, flows[2])
        assertEquals(20_000L, flows[3])
    }
}
