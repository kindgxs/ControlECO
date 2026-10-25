package com.controleco.app.capture.overlay

import com.controleco.app.capture.CaptureDefaults

/**
 * 悬浮确认卡的显示决策与关闭处置（纯函数，便于单测）与时间常量。
 *
 * 卡片是"刚付完款、人还在支付页"场景的快速确认入口：关闭卡片（点 ✕ / 点卡片上方空白 /
 * 长时间不操作）视为入账，只有点「忽略」才不入账；不满足弹出条件时**一律回落横幅通知**
 * （通知是兜底通道，永远保留）。方案取舍见 docs/自动记账捕获机制.md 第二节。
 */

/** 捕获到弹出卡片的延迟：支付保护层在支付页停留期会拦截悬浮窗，稍等更稳（失败仍回落通知） */
internal const val OVERLAY_DELAY_MS = 3_000L

/** 无操作自动关闭的时长（关闭 = 视为入账，用户可点「忽略」明确不入账） */
internal const val OVERLAY_AUTO_DISMISS_MS = 60_000L

/** 确认后的「已记入 · 撤销」细条停留时长 */
internal const val SAVED_STRIP_DISMISS_MS = 6_000L

/** 关闭卡片时的卡内当前值（关闭 = 保留这笔账，用户改过的字段同步回去） */
internal data class DismissSnapshot(
    val amountCents: Long,
    val categoryId: Long?,
    val accountId: Long?,
    val counterparty: String,
    /** 关闭时卡内的「不计入资产统计」选择（自动入账默认 false = 计入） */
    val excludeFromAssets: Boolean = false,
    /** 关闭时卡内的「不计入账单和收支统计」选择（自动入账默认 false = 计入） */
    val excludeFromStats: Boolean = false,
    /** 用户在卡片里改过任何字段（没有改动就不必回写数据库） */
    val edited: Boolean = false,
)

/**
 * 关闭卡片的处置：金额/分类/账户齐备 → 入账；否则保留待确认
 * （金额缺失的条目不能入账，按「入账提醒」开关补发横幅通知）。
 */
internal enum class DismissOutcome { ENTER, KEEP_PENDING }

internal fun dismissOutcome(snapshot: DismissSnapshot): DismissOutcome = when {
    snapshot.amountCents <= 0 -> DismissOutcome.KEEP_PENDING
    snapshot.categoryId == null || snapshot.accountId == null -> DismissOutcome.KEEP_PENDING
    else -> DismissOutcome.ENTER
}

/**
 * "已入账"卡片的编辑能否同步回账目：金额/分类/账户齐备才写回；
 * 卡内金额被清空或分类/账户缺失时**保持原记录不动**（已入账的账不许被改坏）。
 */
internal fun canSyncOverlayEdits(snapshot: DismissSnapshot): Boolean =
    dismissOutcome(snapshot) == DismissOutcome.ENTER

/** 卡片显示条件（四个入参都可能变，弹出前需再判一次） */
internal data class OverlayConditions(
    /** 设置里「悬浮确认卡」开关 */
    val enabled: Boolean,
    /** Settings.canDrawOverlays（悬浮窗权限） */
    val canDrawOverlays: Boolean,
    /** 本应用是否在前台（在前台时应使用应用内的待确认列表，不弹卡） */
    val appInForeground: Boolean,
    /** 屏幕是否点亮 */
    val screenInteractive: Boolean,
    /** 是否已锁屏 */
    val keyguardLocked: Boolean,
    /** 当前前台应用包名（由无障碍服务跟踪；判不出来为 null） */
    val foregroundPackage: String?,
    /** 本次捕获的来源键（包名 / SMS:银行 / 银行推送包名） */
    val sourcePackage: String,
    /**
     * 当前前台应用是否属于"支付/购物上下文"（监听目录内的应用）。
     * 典型场景：京东下单拉起微信支付，支付通知到达时前台是京东而不是微信——
     * 此时仍然应该弹卡（用户在购物/支付语境里，卡片不突兀）。
     */
    val foregroundIsPaymentContext: Boolean = false,
)

/**
 * 系统界面/桌面：支付瞬间的前台窗口常被这些包名占据——支付保护/安全键盘/系统弹层
 * （com.android.systemui、com.miui.securitycenter 等）以及用户付完款后回到的桌面。
 * 它们不代表"用户在别的应用里"，判不出用户身处何方时按"判不出"处理（放行）。
 */
private val systemSurfaceMarkers = listOf(
    "systemui", "launcher", ".home", "securitycenter", "permissioncontroller", "systemuiplugin",
)

internal fun isSystemSurface(packageName: String): Boolean =
    systemSurfaceMarkers.any { packageName.contains(it, ignoreCase = true) }

internal fun shouldShowOverlay(c: OverlayConditions): Boolean = when {
    !c.enabled || !c.canDrawOverlays -> false
    c.appInForeground -> false
    !c.screenInteractive || c.keyguardLocked -> false
    // 判不出前台应用（无障碍未开）→ 放行
    c.foregroundPackage == null -> true
    // 前台是系统弹层/桌面（支付保护、安全键盘、付完回桌面）→ 等于判不出，放行
    isSystemSurface(c.foregroundPackage) -> true
    // 人还在来源应用（最常见的场景）
    c.foregroundPackage == c.sourcePackage -> true
    // 银行侧（短信/银行推送/云闪付）到达时用户常不在来源应用里（在收银台/别处），不做前台匹配
    CaptureDefaults.isBankSource(c.sourcePackage) -> true
    // 用户在别的支付/购物应用里（京东→拉起微信支付这种跳转支付的常见流）→ 仍在支付语境，允许弹卡
    c.foregroundIsPaymentContext -> true
    else -> false
}
