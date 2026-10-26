package com.controleco.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.controleco.app.csv.CsvReader
import com.controleco.app.csv.XlsxReader
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.AccountKind
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.InstallmentPlanEntity
import com.controleco.app.data.db.MerchantRuleEntity
import com.controleco.app.data.db.RecurringPlanEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import org.json.JSONArray
import org.json.JSONObject

/** 一份完整备份的内存形态（JSON / CSV / XLS 共用） */
internal data class BackupData(
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val installments: List<InstallmentPlanEntity> = emptyList(),
    val recurring: List<RecurringPlanEntity> = emptyList(),
    /** 自动入账的商户归类（越用越准的学习结果），含分类/账户指向 */
    val merchantRules: List<MerchantRuleEntity> = emptyList(),
)

/**
 * 备份 / 还原：把一个账本完整地写成一个 JSON 文件，再原样恢复。
 *
 * 内容：
 * - accounts（含类型、默认账户、信用卡额度、共用额度组）
 * - categories（含一级 / 二级分类、排序、图标）
 * - transactions（全部账单，含退款标记与「不扣资产」标记）
 * - installment_plans / recurring_plans
 * - merchant_rules（自动入账的商户归类学习结果）
 * - settings（SharedPreferences 里的全部设置；卡面图片的本地 URI 不导出）
 *
 * 恢复时先清空当前账本再重建，id 原样保留，所以分类/账户的关联不会断。
 * CSV / XLS 账单由 [BackupCsv] 负责，本类提供共用的读写与格式识别。
 */
object BackupStore {

    private const val APP_TAG = "ControlECO"
    /**
     * 备份格式版本：跟随数据库版本。
     * v17：账单 / 分期 / 周期多了「计入账单和收支统计」。
     * v18：分期 / 周期多了「计入统计生效期数」（countInStatsPeriods）。
     * 导入不看这个值（缺字段一律按默认值还原），只用于识别备份出自哪一代数据结构。
     */
    private const val FORMAT_VERSION = 18

    /** 不参与导出的设置键（本地文件路径，换设备后用不了） */
    private val EXCLUDED_SETTINGS = setOf(
        "card_image_uri",
    )

    private val EXCLUDED_PREFIXES = listOf(
        "card_image_",
    )

    /** 读出整本账（不含设置） */
    internal suspend fun collect(db: AccBookDatabase): BackupData {
        val accountDao = db.accountDao()
        val categoryDao = db.categoryDao()
        val txDao = db.transactionDao()
        val planDao = db.planDao()
        return BackupData(
            accounts = accountDao.getAll(),
            categories = categoryDao.getAll(),
            transactions = txDao.getAllEntities(),
            installments = planDao.getAllInstallments(),
            recurring = planDao.getAllRecurring(),
            merchantRules = db.merchantRuleDao().getAll(),
        )
    }

    /** 用备份内容替换本机账本（先清空再写入）@return 恢复的账单条数 */
    internal suspend fun apply(db: AccBookDatabase, data: BackupData): Int {
        val accountDao = db.accountDao()
        val categoryDao = db.categoryDao()
        val txDao = db.transactionDao()
        val planDao = db.planDao()
        val merchantRuleDao = db.merchantRuleDao()

        db.withTransaction {
            // 先删账单，再删分类/账户，避免外键 RESTRICT 挡住
            txDao.deleteAll()
            planDao.clearInstallments()
            planDao.clearRecurring()
            categoryDao.deleteAll()
            accountDao.deleteAll()
            merchantRuleDao.clearAll()

            if (data.accounts.isNotEmpty()) accountDao.insertAll(data.accounts)
            if (data.categories.isNotEmpty()) categoryDao.insertAll(data.categories)
            if (data.transactions.isNotEmpty()) txDao.insertAll(data.transactions)
            data.installments.forEach { planDao.insertInstallment(it) }
            data.recurring.forEach { planDao.insertRecurring(it) }
            // id 原样保留：商户归类里的分类/账户指向不会因为还原而错位
            data.merchantRules.forEach { merchantRuleDao.upsert(it) }
        }
        return data.transactions.size
    }

    suspend fun exportJson(context: Context, db: AccBookDatabase, prefs: SharedPreferences): String {
        val data = collect(db)

        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("format", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        root.put("accounts", JSONArray().apply {
            data.accounts.forEach { put(accountJson(it)) }
        })
        root.put("categories", JSONArray().apply {
            data.categories.forEach { put(categoryJson(it)) }
        })
        root.put("transactions", JSONArray().apply {
            data.transactions.forEach { put(transactionJson(it)) }
        })
        root.put("installments", JSONArray().apply {
            data.installments.forEach { put(installmentJson(it)) }
        })
        root.put("recurring", JSONArray().apply {
            data.recurring.forEach { put(recurringJson(it)) }
        })
        root.put("merchantRules", JSONArray().apply {
            data.merchantRules.forEach { put(merchantRuleJson(it)) }
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
        val data = BackupData(
            accounts = root.optJSONArray("accounts").toEntities(::accountOf),
            categories = root.optJSONArray("categories").toEntities(::categoryOf),
            transactions = root.optJSONArray("transactions").toEntities(::transactionOf),
            installments = root.optJSONArray("installments").toEntities(::installmentOf),
            recurring = root.optJSONArray("recurring").toEntities(::recurringOf),
            merchantRules = root.optJSONArray("merchantRules").toEntities(::merchantRuleOf),
        )
        val count = apply(db, data)

        // 设置（在数据库成功之后再覆盖，避免恢复失败时把设置也弄丢）
        root.optJSONObject("settings")?.let { restoreSettings(prefs, it) }
        return count
    }

    /**
     * 按文件内容自动识别格式并导入：
     * - ZIP 魔数（PK）→ Excel .xlsx
     * - 以 #ControlECO 开头 → CSV 账单
     * - 其余按 JSON 备份处理
     */
    suspend fun importFile(
        context: Context,
        db: AccBookDatabase,
        prefs: SharedPreferences,
        bytes: ByteArray,
    ): Int {
        if (XlsxReader.isXlsx(bytes)) {
            return BackupCsv.importRows(db, prefs, XlsxReader.parse(bytes))
        }
        val text = CsvReader.decode(bytes).removePrefix("\uFEFF").trimStart()
        if (text.startsWith(BackupCsv.MARKER_PREFIX)) {
            return BackupCsv.importRows(db, prefs, CsvReader.parse(text))
        }
        if (!text.startsWith("{")) {
            throw IllegalArgumentException("不支持的文件格式，请选择 JSON / CSV / .xlsx 备份")
        }
        return importJson(context, db, prefs, text)
    }

    // ---------- 实体 → JSON ----------

    private fun accountJson(a: AccountEntity) = JSONObject().apply {
        put("id", a.id)
        put("name", a.name)
        put("kind", a.kind.name)
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
        put("countInStats", t.countInStats)
        put("refundLinkId", t.refundLinkId ?: JSONObject.NULL)
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
        put("excludeAssetPeriods", p.excludeAssetPeriods)
        put("countInStats", p.countInStats)
        put("countInStatsPeriods", p.countInStatsPeriods)
        put("postTimeMinutes", p.postTimeMinutes)
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
        put("intervalCount", p.intervalCount)
        put("firstDateMillis", p.firstDateMillis)
        put("nextDateMillis", p.nextDateMillis)
        put("deductAsset", p.deductAsset)
        put("autoPost", p.autoPost)
        put("postTimeMinutes", p.postTimeMinutes)
        put("endMode", p.endMode)
        put("endCount", p.endCount)
        put("endDateMillis", p.endDateMillis)
        put("postedCount", p.postedCount)
        put("excludeAssetPeriods", p.excludeAssetPeriods)
        put("countInStats", p.countInStats)
        put("countInStatsPeriods", p.countInStatsPeriods)
        put("note", p.note)
        put("createdAt", p.createdAt)
    }

    private fun merchantRuleJson(r: MerchantRuleEntity) = JSONObject().apply {
        put("id", r.id)
        put("matchKey", r.matchKey)
        put("type", r.type.name)
        put("categoryId", r.categoryId)
        put("accountId", r.accountId ?: JSONObject.NULL)
        put("hits", r.hits)
        put("updatedAt", r.updatedAt)
    }

    /** 全部设置 → {"key": {"t": 类型, "v": 值}}；CSV 备份也复用这一份 */
    internal fun settingsJson(prefs: SharedPreferences) = JSONObject().apply {
        prefs.all.forEach { (key, value) ->
            if (key in EXCLUDED_SETTINGS || EXCLUDED_PREFIXES.any { key.startsWith(it) }) return@forEach
            when (value) {
                is Boolean -> put(key, JSONObject().put("t", "b").put("v", value))
                is Int -> put(key, JSONObject().put("t", "i").put("v", value))
                is Long -> put(key, JSONObject().put("t", "l").put("v", value))
                is Float -> put(key, JSONObject().put("t", "f").put("v", value.toDouble()))
                is String -> put(key, JSONObject().put("t", "s").put("v", value))
                // StringSet（如「监听的 App」名单）以数组形式保存
                is Set<*> -> put(
                    key,
                    JSONObject()
                        .put("t", "ss")
                        .put("v", JSONArray().apply { value.forEach { item -> put(item?.toString() ?: "") } }),
                )
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
        kind = if (o.has("kind")) {
            enumOf<AccountKind>(o.optString("kind"), AccountKind.CASH)
        } else {
            // 旧备份没有 kind：有额度的一律视为信用卡（与 v15 迁移口径一致）
            if (o.optLong("creditLimitCents") > 0L) AccountKind.CREDIT else AccountKind.CASH
        },
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
        countInStats = o.optBoolean("countInStats", true),
        refundLinkId = if (o.isNull("refundLinkId")) null else o.optLong("refundLinkId"),
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
        excludeAssetPeriods = o.optString("excludeAssetPeriods"),
        countInStats = o.optBoolean("countInStats", true),
        countInStatsPeriods = o.optString("countInStatsPeriods"),
        postTimeMinutes = o.optInt("postTimeMinutes", 540),
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
        intervalCount = o.optInt("intervalCount", 1),
        firstDateMillis = o.optLong("firstDateMillis"),
        nextDateMillis = o.optLong("nextDateMillis"),
        deductAsset = o.optBoolean("deductAsset", true),
        autoPost = o.optBoolean("autoPost"),
        postTimeMinutes = o.optInt("postTimeMinutes", 540),
        endMode = o.optString("endMode", "NEVER"),
        endCount = o.optInt("endCount"),
        endDateMillis = o.optLong("endDateMillis"),
        postedCount = o.optInt("postedCount"),
        excludeAssetPeriods = o.optString("excludeAssetPeriods"),
        countInStats = o.optBoolean("countInStats", true),
        countInStatsPeriods = o.optString("countInStatsPeriods"),
        note = o.optString("note"),
        createdAt = o.optLong("createdAt"),
    )

    private fun merchantRuleOf(o: JSONObject) = MerchantRuleEntity(
        id = o.optLong("id"),
        matchKey = o.optString("matchKey"),
        type = enumOf<TxType>(o.optString("type"), TxType.EXPENSE),
        categoryId = o.optLong("categoryId"),
        accountId = if (o.isNull("accountId")) null else o.optLong("accountId"),
        hits = o.optInt("hits", 1),
        updatedAt = o.optLong("updatedAt"),
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

    internal fun restoreSettings(prefs: SharedPreferences, settings: JSONObject) {
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
                "ss" -> {
                    val array = wrapper.optJSONArray("v")
                    val set = LinkedHashSet<String>()
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            (array.opt(i) as? String)?.takeIf { it.isNotBlank() }?.let { set += it }
                        }
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }
}
