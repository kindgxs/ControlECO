package com.controleco.app.capture

import androidx.room.withTransaction
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.MerchantRuleDao
import com.controleco.app.data.db.PendingDao
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionDao
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.util.AppLog

/**
 * 待确认条目的入账/丢弃动作（采集层与 UI 层共用）。
 *
 * 为什么抽共享层：悬浮确认卡没有 Activity/ViewModel 宿主，确认动作必须在采集层独立可执行；
 * 待确认列表（ViewModel）也要做同一件事。放一处，保证两侧口径一致——
 * externalId 规则、来源标记（TxSource）、退款标记、重复插入的处理方式、**商户归类学习**都只有一份。
 */
internal object PendingActions {

    /**
     * 确认入账：写交易（externalId = "NTF:" + dedupKey，唯一索引兜底防重）+ 删除待确认条目
     * + 学习商户归类（越用越准）。
     * 返回新交易 id；若该笔已经入过账（重复通知/双通道竞态），唯一索引冲突按"已记录"处理，
     * 返回 null 且不抛异常、不重复写账。
     */
    suspend fun confirm(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        merchantRuleDao: MerchantRuleDao? = null,
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String = item.counterparty,
        amountCents: Long = item.amountCents,
        /** 不计入资产统计：自动入账默认 false（计入） */
        excludeFromAssets: Boolean = false,
        /** 计入账单和收支统计：自动入账默认 true（计入） */
        countInStats: Boolean = true,
    ): Long? {
        val externalId = "NTF:${item.dedupKey}"
        val counterpartyValue = counterparty.trim().ifBlank { item.counterparty }
        val txId = try {
            db.withTransaction {
                val id = txDao.insert(
                    TransactionEntity(
                        type = item.type,
                        amountCents = amountCents,
                        categoryId = categoryId,
                        accountId = accountId,
                        timestamp = item.timestamp,
                        note = item.note.ifBlank { item.ruleName },
                        counterparty = counterpartyValue,
                        source = CaptureDefaults.txSource(item.sourcePackage),
                        externalId = externalId,
                        refundMark = item.refundMark,
                        // 捕获来的退款收入同样要用户指认原支出（进「待确认账单」）
                        refundPending = item.refundMark == RefundMark.REFUND_INCOME,
                        flowId = item.flowId,
                        excludeFromAssets = excludeFromAssets,
                        countInStats = countInStats,
                    ),
                )
                pendingDao.deleteById(item.id)
                id
            }
        } catch (e: Exception) {
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                // 已经入过账（重复通知/双通道竞态）：清理残留待确认条目即可
                pendingDao.deleteById(item.id)
                AppLog.i("pending", "already recorded, skip duplicate externalId=$externalId")
                null
            } else {
                throw e
            }
        }
        if (txId != null) {
            merchantRuleDao?.let {
                MerchantRules.learn(it, item.type, categoryId, accountId, counterpartyValue, item.note)
            }
            AppLog.i("pending", "confirmed #${item.id} externalId=$externalId tx=$txId")
        }
        return txId
    }

    /** 丢弃（误报纠错）：只删待确认条目，不产生账目 */
    suspend fun discard(pendingDao: PendingDao, id: Long) {
        pendingDao.deleteById(id)
        AppLog.i("pending", "discarded #$id")
    }

    /**
     * 已入账条目的编辑同步（悬浮卡"先入账、后修改"）：卡内当前值写回交易与 AUTO 条目，
     * 并学习商户归类（与 [confirm] 同一口径）。
     *
     * 同时给条目打上"用户已手改"标记：此后页面/银行侧的自动富化不再覆盖金额/商户/时间——
     * 用户输入优先于自动解析（见 CaptureMerge.mergePageInfo）。
     * 返回 false = 找不到该笔交易（可能已被撤销），调用方只需静默收场。
     */
    suspend fun updateEntered(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        merchantRuleDao: MerchantRuleDao? = null,
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
        amountCents: Long,
        excludeFromAssets: Boolean = false,
        /** 计入账单和收支统计：默认 true（计入） */
        countInStats: Boolean = true,
    ): Boolean {
        val externalId = "NTF:${item.dedupKey}"
        val txId = txDao.findIdByExternalId(externalId) ?: return false
        val counterpartyValue = counterparty.trim().ifBlank { item.counterparty }
        // 用数据库里的最新备注/时间：卡片打开后页面可能又补了信息，用户只改了金额/商户，
        // 不能拿打开卡片时的旧快照把后到的富化结果覆盖回去
        val fresh = pendingDao.getById(item.id) ?: item
        db.withTransaction {
            txDao.updateEnteredFields(
                txId, amountCents, categoryId, accountId, counterpartyValue, excludeFromAssets, countInStats,
            )
            // AUTO 条目同步：页面/银行通道后续富化以它为准，不能留旧值；并置位"用户已手改"
            pendingDao.updateFromPage(
                item.id, amountCents, fresh.timestamp, counterpartyValue, fresh.note, fresh.ruleName,
            )
            pendingDao.markUserEdited(item.id)
        }
        merchantRuleDao?.let {
            MerchantRules.learn(it, item.type, categoryId, accountId, counterpartyValue, item.note)
        }
        AppLog.i("pending", "updated entered tx=#$txId amount=$amountCents externalId=$externalId (user edited)")
        return true
    }

    /** 撤销已入账条目（悬浮卡「忽略」/ 通知「撤销」）：删除交易与对应 AUTO 条目，不留残影 */
    suspend fun undoEntered(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        dedupKey: String,
    ): Boolean {
        val externalId = "NTF:$dedupKey"
        val txId = txDao.findIdByExternalId(externalId)
        db.withTransaction {
            if (txId != null) txDao.deleteById(txId)
            pendingDao.deleteByDedupKey(dedupKey)
        }
        AppLog.i("pending", "undo entered tx=#$txId externalId=$externalId")
        return txId != null
    }
}
