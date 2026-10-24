package com.controleco.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.InstallmentPlanEntity
import com.controleco.app.data.db.RecurringPlanEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份 / 还原：把一个账本完整地写成一个 JSON 文件，再原样恢复。
 *
 * 内容：
 * - accounts（含默认账户、信用卡额度、共用额度组）
 * - categories（含一级 / 二级分类、排序、图标）
 * - transactions（全部账单，含退款标记与「不扣资产」标记）
 * - installment_plans / recurring_plans
 * - settings（SharedPreferences 里的全部设置；卡面图片的本地 URI 不导出）
 *
 * 恢复时先清空当前账本再重建，id 原样保留，所以分类/账户的关联不会断。
 */
object BackupStore {

    private const val APP_TAG = "ControlECO"
    private const val FORMAT_VERSION = 14

    /** 不参与导出的设置键（本地文件路径，换设备后用不了） */
    private val EXCLUDED_SETTINGS = setOf(
        "card_image_uri",
    )

    private val EXCLUDED_PREFIXES = listOf(
        "card_image_",
    )

    suspend fun exportJson(context: Context, db: AccBookDatabase, prefs: SharedPreferences): String {
        val accountDao = db.accountDao()
        val categoryDao = db.categoryDao()
        val txDao = db.transactionDao()
        val planDao = db.planDao()

        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("format", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        root.put("accounts", JSONArray().apply {
            accountDao.getAll().forEach { put(accountJson(it)) }
        })
        root.put("categories", JSONArray().apply {
            categoryDao.getAll().forEach { put(categoryJson(it)) }
        })
        root.put("transactions", JSONArray().apply {
            txDao.getAllEntities().forEach { put(transactionJson(it)) }
        })
        root.put("installments", JSONArray().apply {
            planDao.getAllInstallments().forEach { put(installmentJson(it)) }
        })
        root.put("recurring", JSONArray().apply {
            planDao.getAllRecurring().forEach { put(recurringJson(it)) }
        })
        root.put("settings", settingsJson(prefs))
        return root.toString()
    }

    /** @return 恢复的账单条数 */
    suspend fun importJson(
        context: Context,
        db: AccBookDatabase,
        prefs: SharedPreferences,
        text: String,
    ): Int {
        val root = JSONObject(text)
        if (root.optString("app") != APP_TAG) {
            throw IllegalArgumentException("不是 ControlECO 的备份文件")
        }
        val accounts = root.optJSONArray("accounts").toEntities(::accountOf)
        val categories = root.optJSONArray("categories").toEntities(::categoryOf)
        val transactions = root.optJSONArray("transactions").toEntities(::transactionOf)
        val installments = root.optJSONArray("installments").toEntities(::installmentOf)
        val recurring = root.optJSONArray("recurring").toEntities(::recurringOf)

        val accountDao = db.accountDao()
        val categoryDao = db.categoryDao()
        val txDao = db.transactionDao()
        val planDao = db.planDao()

        db.withTransaction {
            // 先删账单，再删分类/账户，避免外键 RESTRICT 挡住
            txDao.deleteAll()
            planDao.clearInstallments()
            planDao.clearRecurring()
            categoryDao.deleteAll()
            accountDao.deleteAll()

            if (accounts.isNotEmpty()) accountDao.insertAll(accounts)
            if (categories.isNotEmpty()) categoryDao.insertAll(categories)
            if (transactions.isNotEmpty()) txDao.insertAll(transactions)
            installments.forEach { planDao.insertInstallment(it) }
            recurring.forEach { planDao.insertRecurring(it) }
        }

        // 设置（在数据库成功之后再覆盖，避免恢复失败时把设置也弄丢）
        val settings = root.optJSONObject("settings")
        if (settings != null) restoreSettings(prefs, settings)
        return transactions.size
    }

    // ---------- 实体 → JSON ----------

    private fun accountJson(a: AccountEntity) = JSONObject().apply {
        put("id", a.id)
        put("name", a.name)
        put("initialBalanceCents", a.initialBalanceCents)
        put("note", a.note)
        put("createdAt", a.createdAt)
        put("isDefault", a.isDefault)
        put("creditLimitCents", a.creditLimitCents)
        put("sharedGroupId", a.sharedGroupId)
    }

    private fun categoryJson(c: CategoryEntity) = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("type", c.type.name)
        put("sortOrder", c.sortOrder)
        put("icon", c.icon)
        put("parentId", c.parentId ?: JSONObject.NULL)
    }

    private fun transactionJson(t: TransactionEntity) = JSONObject().apply {
        put("id", t.id)
        put("type", t.type.name)
        put("amountCents", t.amountCents)
        put("categoryId", t.categoryId)
        put("accountId", t.accountId)
        put("timestamp", t.timestamp)
        put("note", t.note)
        put("counterparty", t.counterparty)
        put("source", t.source.name)
        put("externalId", t.externalId ?: JSONObject.NULL)
        put("refundMark", t.refundMark.name)
        put("refundPending", t.refundPending)
        put("flowId", t.flowId)
        put("excludeFromAssets", t.excludeFromAssets)
        put("createdAt", t.createdAt)
    }

    private fun installmentJson(p: InstallmentPlanEntity) = JSONObject().apply {
        put("id", p.id)
        put("title", p.title)
        put("accountId", p.accountId)
        put("categoryId", p.categoryId)
        put("totalPrincipalCents", p.totalPrincipalCents)
        put("totalInterestCents", p.totalInterestCents)
        put("periods", p.periods)
        put("firstDateMillis", p.firstDateMillis)
        put("deductAsset", p.deductAsset)
        put("note", p.note)
        put("postedPeriods", p.postedPeriods)
        put("createdAt", p.createdAt)
    }

    private fun recurringJson(p: RecurringPlanEntity) = JSONObject().apply {
        put("id", p.id)
        put("title", p.title)
        put("type", p.type.name)
        put("amountCents", p.amountCents)
        put("categoryId", p.categoryId)
        put("accountId", p.accountId)
        put("frequency", p.frequency)
        put("firstDateMillis", p.firstDateMillis)
        put("nextDateMillis", p.nextDateMillis)
        put("deductAsset", p.deductAsset)
        put("autoPost", p.autoPost)
        put("note", p.note)
        put("createdAt", p.createdAt)
    }

    private fun settingsJson(prefs: SharedPreferences) = JSONObject().apply {
        prefs.all.forEach { (key, value) ->
            if (key in EXCLUDED_SETTINGS || EXCLUDED_PREFIXES.any { key.startsWith(it) }) return@forEach
            when (value) {
                is Boolean -> put(key, JSONObject().put("t", "b").put("v", value))
                is Int -> put(key, JSONObject().put("t", "i").put("v", value))
                is Long -> put(key, JSONObject().put("t", "l").put("v", value))
                is Float -> put(key, JSONObject().put("t", "f").put("v", value.toDouble()))
                is String -> put(key, JSONObject().put("t", "s").put("v", value))
            }
        }
    }

    // ---------- JSON → 实体 ----------

    private fun accountOf(o: JSONObject) = AccountEntity(
        id = o.optLong("id"),
        name = o.optString("name"),
        initialBalanceCents = o.optLong("initialBalanceCents"),
        note = o.optString("note"),
        createdAt = o.optLong("createdAt"),
        isDefault = o.optBoolean("isDefault"),
        creditLimitCents = o.optLong("creditLimitCents"),
        sharedGroupId = o.optLong("sharedGroupId"),
    )

    private fun categoryOf(o: JSONObject) = CategoryEntity(
        id = o.optLong("id"),
        name = o.optString("name"),
        type = enumOf<TxType>(o.optString("type"), TxType.EXPENSE),
        sortOrder = o.optInt("sortOrder"),
        icon = o.optString("icon"),
        parentId = if (o.isNull("parentId")) null else o.optLong("parentId"),
    )

    private fun transactionOf(o: JSONObject) = TransactionEntity(
        id = o.optLong("id"),
        type = enumOf<TxType>(o.optString("type"), TxType.EXPENSE),
        amountCents = o.optLong("amountCents"),
        categoryId = o.optLong("categoryId"),
        accountId = o.optLong("accountId"),
        timestamp = o.optLong("timestamp"),
        note = o.optString("note"),
        counterparty = o.optString("counterparty"),
        source = enumOf<TxSource>(o.optString("source"), TxSource.MANUAL),
        externalId = if (o.isNull("externalId")) null else o.optString("externalId"),
        refundMark = enumOf<RefundMark>(o.optString("refundMark"), RefundMark.NONE),
        refundPending = o.optBoolean("refundPending"),
        flowId = o.optString("flowId"),
        excludeFromAssets = o.optBoolean("excludeFromAssets"),
        createdAt = o.optLong("createdAt"),
    )

    private fun installmentOf(o: JSONObject) = InstallmentPlanEntity(
        id = o.optLong("id"),
        title = o.optString("title"),
        accountId = o.optLong("accountId"),
        categoryId = o.optLong("categoryId"),
        totalPrincipalCents = o.optLong("totalPrincipalCents"),
        totalInterestCents = o.optLong("totalInterestCents"),
        periods = o.optInt("periods", 1),
        firstDateMillis = o.optLong("firstDateMillis"),
        deductAsset = o.optBoolean("deductAsset", true),
        note = o.optString("note"),
        postedPeriods = o.optInt("postedPeriods"),
        createdAt = o.optLong("createdAt"),
    )

    private fun recurringOf(o: JSONObject) = RecurringPlanEntity(
        id = o.optLong("id"),
        title = o.optString("title"),
        type = enumOf<TxType>(o.optString("type"), TxType.EXPENSE),
        amountCents = o.optLong("amountCents"),
        categoryId = o.optLong("categoryId"),
        accountId = o.optLong("accountId"),
        frequency = o.optString("frequency", "MONTHLY"),
        firstDateMillis = o.optLong("firstDateMillis"),
        nextDateMillis = o.optLong("nextDateMillis"),
        deductAsset = o.optBoolean("deductAsset", true),
        autoPost = o.optBoolean("autoPost"),
        note = o.optString("note"),
        createdAt = o.optLong("createdAt"),
    )

    private fun <T> JSONArray?.toEntities(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val result = ArrayList<T>(length())
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            runCatching { result += map(obj) }
        }
        return result
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun restoreSettings(prefs: SharedPreferences, settings: JSONObject) {
        val editor = prefs.edit().clear()
        settings.keys().forEach { key ->
            val wrapper = settings.optJSONObject(key) ?: return@forEach
            val value = wrapper.opt("v")
            when (wrapper.optString("t")) {
                "b" -> editor.putBoolean(key, wrapper.optBoolean("v"))
                "i" -> editor.putInt(key, wrapper.optInt("v"))
                "l" -> editor.putLong(key, wrapper.optLong("v"))
                "f" -> editor.putFloat(key, (value as? Number)?.toFloat() ?: 0f)
                "s" -> editor.putString(key, wrapper.optString("v"))
            }
        }
        editor.apply()
    }
}
