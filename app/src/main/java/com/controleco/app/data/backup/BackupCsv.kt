package com.controleco.app.data.backup

import android.content.SharedPreferences
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
import com.controleco.app.data.plan.InstallmentMath
import com.controleco.app.data.plan.RecurringMath
import com.controleco.app.util.Money
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CSV / Excel 账单备份（修改8）。
 *
 * 一个文件里用 `##section` 分段，依次包含：
 * 账户、一级 + 二级分类、交易记录（时间/类型/分类/金额/资产账户/记录标识/备注/二级分类/币种，
 * 末尾附带编号、来源、退款、流水号等还原所需的扩展列）、分期计划、周期计划、商户归类、
 * 预算信息、存款信息（按月收支汇总，只读）、全部设置。
 *
 * 导出带 UTF-8 BOM，Excel 直接双击打开不乱码；导入同时接受 .csv 与 .xlsx
 * （[com.controleco.app.csv.XlsxReader] 解析 Excel 另存的文件）。
 */
object BackupCsv {

    const val MARKER_PREFIX = "#ControlECO"

    private const val MARKER = "#ControlECO,backup,1"

    private const val KEY_MONTHLY_BUDGET = "monthly_budget_cents"
    private const val KEY_DAILY_MODE = "budget_daily_mode"
    private const val KEY_DAILY_BUDGET = "daily_budget_cents"
    private const val KEY_DEPOSIT_GOAL = "deposit_goal_cents"
    private const val KEY_DEPOSIT_MONTH_GOAL_PREFIX = "deposit_goal_month_"
    private const val KEY_DEPOSIT_PLAN_START = "deposit_plan_start_month"
    private const val KEY_DEPOSIT_RANGE_MONTHS = "deposit_range_months"
    private const val KEY_DEPOSIT_SHOW_ALL = "deposit_show_all_months"
    private const val KEY_MONTH_START_DAY = "month_start_day"
    private const val KEY_BUDGET_PLAN_PREFIX = "budget_plan_"
    private const val KEY_CAT_BUDGET_PREFIX = "category_budget_"
    private const val KEY_CAT_BUDGET_PCT_PREFIX = "category_budget_pct_"
    private const val KEY_CAT_BUDGET_MODE = "category_budget_mode"

    private val TX_HEADER = listOf(
        "时间", "类型", "分类", "金额", "资产账户", "记录标识", "备注", "二级分类", "币种",
        "编号", "来源", "退款状态", "退款待定", "不扣资产", "流水号", "创建时间", "商户", "计入统计", "退款关联",
    )

    private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyy年M月d日"),
    )

    // ---------- 导出 ----------

    suspend fun exportCsv(db: AccBookDatabase, prefs: SharedPreferences): String {
        val data = BackupStore.collect(db)
        val zone = ZoneId.systemDefault()
        val currency = prefs.getString("currency_symbol", Money.DEFAULT_SYMBOL) ?: Money.DEFAULT_SYMBOL
        val accountById = data.accounts.associateBy { it.id }
        val categoryById = data.categories.associateBy { it.id }

        val out = Writer()
        out.marker(MARKER)

        out.marker("##accounts|账户")
        out.row("id", "name", "kind", "initialBalanceCents", "creditLimitCents", "sharedGroupId", "isDefault", "note")
        data.accounts.forEach { a ->
            out.row(
                a.id.toString(), a.name, a.kind.name, a.initialBalanceCents.toString(),
                a.creditLimitCents.toString(), a.sharedGroupId.toString(),
                if (a.isDefault) "1" else "0", a.note,
            )
        }

        out.marker("##categories|分类")
        out.row("id", "name", "type", "sortOrder", "icon", "parentId")
        data.categories.forEach { c ->
            out.row(
                c.id.toString(), c.name, c.type.name, c.sortOrder.toString(), c.icon,
                c.parentId?.toString().orEmpty(),
            )
        }

        out.marker("##transactions|交易记录")
        out.row(TX_HEADER)
        data.transactions.sortedBy { it.timestamp }.forEach { t ->
            val category = categoryById[t.categoryId]
            val parent = category?.parentId?.let { categoryById[it] }
            out.row(
                DATE_TIME_FORMAT.format(Instant.ofEpochMilli(t.timestamp).atZone(zone)),
                typeLabel(t.type),
                parent?.name ?: category?.name.orEmpty(),
                Money.formatCents(t.amountCents),
                accountById[t.accountId]?.name.orEmpty(),
                t.externalId.orEmpty(),
                t.note,
                if (category?.parentId != null) category.name else "",
                currency,
                t.id.toString(),
                t.source.name,
                t.refundMark.name,
                if (t.refundPending) "1" else "0",
                if (t.excludeFromAssets) "1" else "0",
                t.flowId,
                t.createdAt.toString(),
                t.counterparty,
                if (t.countInStats) "1" else "0",
                t.refundLinkId?.toString() ?: "",
            )
        }

        out.marker("##installments|分期计划")
        out.row(
            "id", "title", "accountId", "categoryId", "totalPrincipalCents", "totalInterestCents",
            "periods", "firstDate", "postTime", "deductAsset", "excludeAssetPeriods", "postedPeriods", "note",
            "countInStats", "countInStatsPeriods",
        )
        data.installments.forEach { p ->
            out.row(
                p.id.toString(), p.title, p.accountId.toString(), p.categoryId.toString(),
                p.totalPrincipalCents.toString(), p.totalInterestCents.toString(),
                p.periods.toString(),
                Instant.ofEpochMilli(p.firstDateMillis).atZone(zone).toLocalDate().toString(),
                InstallmentMath.timeText(p.postTimeMinutes),
                if (p.deductAsset) "1" else "0",
                p.excludeAssetPeriods,
                p.postedPeriods.toString(),
                p.note,
                if (p.countInStats) "1" else "0",
                p.countInStatsPeriods,
            )
        }

        out.marker("##recurring|周期计划")
        out.row(
            "id", "title", "type", "amountCents", "categoryId", "accountId", "frequency",
            "intervalCount", "firstDate", "nextDate", "postTime", "deductAsset", "autoPost",
            "endMode", "endCount", "endDate", "postedCount", "excludeAssetPeriods", "note",
            "countInStats", "countInStatsPeriods",
        )
        data.recurring.forEach { p ->
            out.row(
                p.id.toString(), p.title, p.type.name, p.amountCents.toString(),
                p.categoryId.toString(), p.accountId.toString(), p.frequency,
                p.intervalCount.toString(),
                Instant.ofEpochMilli(p.firstDateMillis).atZone(zone).toLocalDate().toString(),
                if (p.nextDateMillis >= RecurringMath.NO_NEXT) {
                    ""
                } else {
                    Instant.ofEpochMilli(p.nextDateMillis).atZone(zone).toLocalDate().toString()
                },
                InstallmentMath.timeText(p.postTimeMinutes),
                if (p.deductAsset) "1" else "0",
                if (p.autoPost) "1" else "0",
                p.endMode,
                p.endCount.toString(),
                if (p.endDateMillis > 0L) {
                    Instant.ofEpochMilli(p.endDateMillis).atZone(zone).toLocalDate().toString()
                } else {
                    ""
                },
                p.postedCount.toString(),
                p.excludeAssetPeriods,
                p.note,
                if (p.countInStats) "1" else "0",
                p.countInStatsPeriods,
            )
        }

        out.marker("##merchant_rules|商户归类")
        out.row("id", "matchKey", "type", "categoryId", "accountId", "hits", "updatedAt")
        data.merchantRules.forEach { r ->
            out.row(
                r.id.toString(), r.matchKey, r.type.name, r.categoryId.toString(),
                r.accountId?.toString().orEmpty(), r.hits.toString(), r.updatedAt.toString(),
            )
        }

        writeBudgets(out, prefs, data.categories)
        writeDeposits(out, data.transactions, zone)
        writeDepositSettings(out, prefs)
        writeSettings(out, prefs)
        return out.toString()
    }

    private fun writeBudgets(out: Writer, prefs: SharedPreferences, categories: List<CategoryEntity>) {
        val categoryName = categories.associate { it.id to it.name }
        out.marker("##budgets|预算信息")
        out.row("键", "说明", "值")

        // 读取一律走类型安全的取值：历史版本 / 旧备份可能把数值键存成字符串，
        // 直接用 prefs.getLong 会抛 ClassCastException（String cannot be cast to Long）
        out.row(KEY_MONTHLY_BUDGET, "全局月预算(元)", Money.formatCents(longPref(prefs, KEY_MONTHLY_BUDGET)))
        out.row(KEY_DAILY_MODE, "日预算模式", if (boolPref(prefs, KEY_DAILY_MODE)) "1" else "0")
        out.row(KEY_DAILY_BUDGET, "日预算(元)", Money.formatCents(longPref(prefs, KEY_DAILY_BUDGET)))
        out.row(KEY_DEPOSIT_GOAL, "存款目标(元)", Money.formatCents(longPref(prefs, KEY_DEPOSIT_GOAL)))
        out.row(KEY_MONTH_START_DAY, "月首日", intPref(prefs, KEY_MONTH_START_DAY, 1).toString())
        out.row(KEY_CAT_BUDGET_MODE, "分类预算模式", prefs.all[KEY_CAT_BUDGET_MODE]?.toString().orEmpty())

        prefs.all.keys
            .filter { it.startsWith(KEY_BUDGET_PLAN_PREFIX) }
            .sorted()
            .forEach { key ->
                out.row(
                    key,
                    "${key.removePrefix(KEY_BUDGET_PLAN_PREFIX)} 月预算",
                    prefs.all[key]?.toString().orEmpty(),
                )
            }
        prefs.all.keys
            // category_budget_mode 也以 category_budget_ 开头，但它是字符串键，不能按金额读
            .filter {
                it.startsWith(KEY_CAT_BUDGET_PREFIX) &&
                    !it.startsWith(KEY_CAT_BUDGET_PCT_PREFIX) &&
                    it != KEY_CAT_BUDGET_MODE
            }
            .sorted()
            .forEach { key ->
                val id = key.removePrefix(KEY_CAT_BUDGET_PREFIX).toLongOrNull()
                val name = id?.let { categoryName[it] }
                out.row(key, "分类预算${name?.let { " · $it" } ?: ""}(元)", Money.formatCents(longPref(prefs, key)))
            }
        prefs.all.keys
            .filter { it.startsWith(KEY_CAT_BUDGET_PCT_PREFIX) }
            .sorted()
            .forEach { key ->
                val id = key.removePrefix(KEY_CAT_BUDGET_PCT_PREFIX).toLongOrNull()
                val name = id?.let { categoryName[it] }
                out.row(key, "分类预算占比${name?.let { " · $it" } ?: ""}(‱)", intPref(prefs, key, 0).toString())
            }
    }

    private fun writeDeposits(out: Writer, transactions: List<TransactionEntity>, zone: ZoneId) {
        out.marker("##deposits|存款信息")
        out.row("月份", "收入", "支出", "净存款")
        val buckets = LinkedHashMap<YearMonth, LongArray>()
        transactions.forEach { t ->
            val month = YearMonth.from(Instant.ofEpochMilli(t.timestamp).atZone(zone).toLocalDate())
            val acc = buckets.getOrPut(month) { LongArray(2) }
            if (t.type == TxType.INCOME) {
                acc[0] += t.amountCents
            } else if (t.refundMark != RefundMark.REFUNDED_EXPENSE) {
                acc[1] += t.amountCents
            }
        }
        buckets.toSortedMap().forEach { (month, acc) ->
            out.row(
                month.toString(),
                Money.formatCents(acc[0]),
                Money.formatCents(acc[1]),
                Money.formatCents(acc[0] - acc[1]),
            )
        }
    }

    /**
     * 存款设置：全局目标、按月目标、存款计划起始月、显示范围、是否显示全部月份。
     * 这些键在 settings 段也会导出，这里单独列出便于人工核对；导入时以本段为准还原。
     */
    private fun writeDepositSettings(out: Writer, prefs: SharedPreferences) {
        out.marker("##deposit_settings|存款设置")
        out.row("键", "说明", "值")
        out.row(
            KEY_DEPOSIT_GOAL, "全局存款目标(元)",
            Money.formatCents(longPref(prefs, KEY_DEPOSIT_GOAL)),
        )
        prefs.all.keys
            .filter { it.startsWith(KEY_DEPOSIT_MONTH_GOAL_PREFIX) }
            .sorted()
            .forEach { key ->
                out.row(
                    key,
                    "${key.removePrefix(KEY_DEPOSIT_MONTH_GOAL_PREFIX)} 月存款目标(元)",
                    Money.formatCents(longPref(prefs, key)),
                )
            }
        out.row(
            KEY_DEPOSIT_PLAN_START, "存款计划起始月",
            prefs.getString(KEY_DEPOSIT_PLAN_START, null).orEmpty(),
        )
        out.row(
            KEY_DEPOSIT_RANGE_MONTHS, "显示范围(月)",
            intPref(prefs, KEY_DEPOSIT_RANGE_MONTHS, 12).toString(),
        )
        out.row(
            KEY_DEPOSIT_SHOW_ALL, "显示全部月份",
            if (boolPref(prefs, KEY_DEPOSIT_SHOW_ALL)) "1" else "0",
        )
    }

    private fun writeSettings(out: Writer, prefs: SharedPreferences) {
        out.marker("##settings|设置")
        out.row("键", "类型", "值")
        val settings = BackupStore.settingsJson(prefs)
        settings.keys().asSequence().sorted().forEach { key ->
            val wrapper = settings.optJSONObject(key) ?: return@forEach
            out.row(key, wrapper.optString("t"), wrapper.opt("v")?.toString().orEmpty())
        }
    }

    // ---------- 导入 ----------

    suspend fun importRows(
        db: AccBookDatabase,
        prefs: SharedPreferences,
        rows: List<List<String>>,
    ): Int {
        val sections = splitSections(rows)
            ?: throw IllegalArgumentException("不是 ControlECO 的 CSV 备份文件")
        val data = buildData(sections)
        val count = BackupStore.apply(db, data)
        // 设置与预算都在数据库写入成功之后再覆盖，避免中途失败把设置弄丢
        applySettings(prefs, toSection(sections["settings"]))
        applyBudgets(prefs, toSection(sections["budgets"]))
        applyDepositSettings(prefs, toSection(sections["deposit_settings"]))
        return count
    }

    /** 仅供单测：把 CSV 行解析成账本内容（不落库、不改设置） */
    internal fun parseRows(rows: List<List<String>>): BackupData? {
        val sections = splitSections(rows) ?: return null
        return buildData(sections)
    }

    private fun buildData(sections: Map<String, List<List<String>>>): BackupData {
        val zone = ZoneId.systemDefault()

        val accountSection = toSection(sections["accounts"])
        val categorySection = toSection(sections["categories"])
        val transactionSection = toSection(sections["transactions"])
        val installmentSection = toSection(sections["installments"])
        val recurringSection = toSection(sections["recurring"])
        val merchantRuleSection = toSection(sections["merchant_rules"])

        val rawAccounts = accountSection?.let { sec -> sec.rows.mapNotNull { parseAccount(sec, it) } }.orEmpty()
        val rawCategories = categorySection?.let { sec -> sec.rows.mapNotNull { parseCategory(sec, it) } }.orEmpty()
        val (accounts, accountIdMap) = normalizeAccounts(rawAccounts)
        val (categories, categoryIdMap) = normalizeCategories(rawCategories)

        val accountList = accounts.toMutableList()
        var nextAccountId = (accountList.maxOfOrNull { it.id } ?: 0L) + 1
        val categoryList = categories.toMutableList()
        var nextCategoryId = (categoryList.maxOfOrNull { it.id } ?: 0L) + 1

        fun resolveAccount(name: String): Long {
            val clean = name.ifBlank { "现金" }
            accountList.firstOrNull { it.name == clean }?.let { return it.id }
            val created = AccountEntity(id = nextAccountId++, name = clean)
            accountList += created
            return created.id
        }

        fun findTop(type: TxType, name: String): CategoryEntity? =
            categoryList.firstOrNull { it.parentId == null && it.type == type && it.name == name }

        fun findChild(parentId: Long, name: String): CategoryEntity? =
            categoryList.firstOrNull { it.parentId == parentId && it.name == name }

        fun newCategory(name: String, type: TxType, parentId: Long?): CategoryEntity {
            val sort = (categoryList
                .filter { it.type == type && it.parentId == parentId }
                .maxOfOrNull { it.sortOrder } ?: -1) + 1
            val created = CategoryEntity(id = nextCategoryId++, name = name, type = type, sortOrder = sort, parentId = parentId)
            categoryList += created
            return created
        }

        /** 分类列 = 一级分类名；二级分类列 = 二级分类名（可选） */
        fun resolveCategory(parentName: String, childName: String, type: TxType): Long {
            if (childName.isNotBlank() && parentName.isNotBlank()) {
                val parent = findTop(type, parentName) ?: newCategory(parentName, type, null)
                return (findChild(parent.id, childName) ?: newCategory(childName, type, parent.id)).id
            }
            val single = childName.ifBlank { parentName }
            if (single.isBlank()) {
                return (findTop(type, "未分类") ?: newCategory("未分类", type, null)).id
            }
            val existing = findTop(type, single)
                ?: categoryList.firstOrNull { it.parentId != null && it.type == type && it.name == single }
            return (existing ?: newCategory(single, type, null)).id
        }

        val rawTransactions = transactionSection?.let { sec ->
            sec.rows.mapNotNull { row ->
                val type = typeOf(sec.cell(row, "类型"))
                val timestamp = parseTimestamp(sec.cell(row, "时间"), zone) ?: return@mapNotNull null
                val amount = amountOf(sec.cell(row, "金额")) ?: return@mapNotNull null
                TransactionEntity(
                    id = sec.cell(row, "编号").toLongOrNull() ?: 0L,
                    type = type,
                    amountCents = amount,
                    categoryId = resolveCategory(sec.cell(row, "分类"), sec.cell(row, "二级分类"), type),
                    accountId = resolveAccount(sec.cell(row, "资产账户")),
                    timestamp = timestamp,
                    note = sec.cell(row, "备注"),
                    counterparty = sec.cell(row, "商户"),
                    source = enumOf(sec.cell(row, "来源"), TxSource.MANUAL),
                    externalId = sec.cell(row, "记录标识").ifBlank { null },
                    refundMark = enumOf(sec.cell(row, "退款状态"), RefundMark.NONE),
                    refundPending = boolOf(sec.cell(row, "退款待定")),
                    flowId = sec.cell(row, "流水号"),
                    excludeFromAssets = boolOf(sec.cell(row, "不扣资产")),
                    countInStats = boolOf(sec.cell(row, "计入统计"), fallback = true),
                    refundLinkId = sec.cell(row, "退款关联").toLongOrNull(),
                    createdAt = sec.cell(row, "创建时间").toLongOrNull() ?: timestamp,
                )
            }
        }.orEmpty()

        // 同一份文件里重复的 id / 记录标识只保留第一条，否则主键与外键唯一索引会整批失败
        val usedTxIds = HashSet<Long>()
        val seenExternalIds = HashSet<String>()
        val transactions = rawTransactions
            .filter { t -> t.externalId?.let { seenExternalIds.add(it) } ?: true }
            .map { t -> if (t.id > 0 && usedTxIds.add(t.id)) t else t.copy(id = 0) }

        val usedInstallmentIds = HashSet<Long>()
        val installments = installmentSection?.let { sec ->
            sec.rows.mapNotNull { row ->
                val principal = sec.cell(row, "totalPrincipalCents").toLongOrNull() ?: return@mapNotNull null
                val accountId = sec.cell(row, "accountId").toLongOrNull() ?: return@mapNotNull null
                val categoryId = sec.cell(row, "categoryId").toLongOrNull() ?: return@mapNotNull null
                val rawId = sec.cell(row, "id").toLongOrNull() ?: 0L
                InstallmentPlanEntity(
                    id = if (rawId > 0 && usedInstallmentIds.add(rawId)) rawId else 0L,
                    title = sec.cell(row, "title"),
                    accountId = accountIdMap[accountId] ?: accountId,
                    categoryId = categoryIdMap[categoryId] ?: categoryId,
                    totalPrincipalCents = principal,
                    totalInterestCents = sec.cell(row, "totalInterestCents").toLongOrNull() ?: 0L,
                    periods = sec.cell(row, "periods").toIntOrNull() ?: 1,
                    firstDateMillis = parseTimestamp(sec.cell(row, "firstDate"), zone) ?: return@mapNotNull null,
                    deductAsset = boolOf(sec.cell(row, "deductAsset"), fallback = true),
                    excludeAssetPeriods = sec.cell(row, "excludeAssetPeriods"),
                    countInStats = boolOf(sec.cell(row, "countInStats"), fallback = true),
                    countInStatsPeriods = sec.cell(row, "countInStatsPeriods"),
                    postTimeMinutes = parseMinutes(sec.cell(row, "postTime")) ?: 540,
                    postedPeriods = sec.cell(row, "postedPeriods").toIntOrNull() ?: 0,
                    note = sec.cell(row, "note"),
                )
            }
        }.orEmpty()

        val usedRecurringIds = HashSet<Long>()
        val recurring = recurringSection?.let { sec ->
            sec.rows.mapNotNull { row ->
                val amount = sec.cell(row, "amountCents").toLongOrNull() ?: return@mapNotNull null
                val accountId = sec.cell(row, "accountId").toLongOrNull() ?: return@mapNotNull null
                val categoryId = sec.cell(row, "categoryId").toLongOrNull() ?: return@mapNotNull null
                val first = parseTimestamp(sec.cell(row, "firstDate"), zone) ?: return@mapNotNull null
                val rawId = sec.cell(row, "id").toLongOrNull() ?: 0L
                val nextText = sec.cell(row, "nextDate").trim()
                val next = if (nextText.isEmpty()) {
                    RecurringMath.NO_NEXT
                } else {
                    parseTimestamp(nextText, zone) ?: first
                }
                RecurringPlanEntity(
                    id = if (rawId > 0 && usedRecurringIds.add(rawId)) rawId else 0L,
                    title = sec.cell(row, "title"),
                    type = enumOf(sec.cell(row, "type"), TxType.EXPENSE),
                    amountCents = amount,
                    categoryId = categoryIdMap[categoryId] ?: categoryId,
                    accountId = accountIdMap[accountId] ?: accountId,
                    frequency = sec.cell(row, "frequency").ifBlank { "MONTHLY" },
                    intervalCount = sec.cell(row, "intervalCount").toIntOrNull()?.coerceIn(1, 3650) ?: 1,
                    firstDateMillis = first,
                    nextDateMillis = next,
                    deductAsset = boolOf(sec.cell(row, "deductAsset"), fallback = true),
                    autoPost = boolOf(sec.cell(row, "autoPost")),
                    postTimeMinutes = parseMinutes(sec.cell(row, "postTime")) ?: 540,
                    endMode = sec.cell(row, "endMode").ifBlank { "NEVER" },
                    endCount = sec.cell(row, "endCount").toIntOrNull() ?: 0,
                    endDateMillis = parseTimestamp(sec.cell(row, "endDate"), zone) ?: 0L,
                    postedCount = sec.cell(row, "postedCount").toIntOrNull() ?: 0,
                    excludeAssetPeriods = sec.cell(row, "excludeAssetPeriods"),
                    countInStats = boolOf(sec.cell(row, "countInStats"), fallback = true),
                    countInStatsPeriods = sec.cell(row, "countInStatsPeriods"),
                    note = sec.cell(row, "note"),
                )
            }
        }.orEmpty()

        // 商户归类：(matchKey, type) 唯一，同一份文件里重复的只保留第一条
        val usedRuleIds = HashSet<Long>()
        val seenRuleKeys = HashSet<String>()
        val merchantRules = merchantRuleSection?.let { sec ->
            sec.rows.mapNotNull { row ->
                val matchKey = sec.cell(row, "matchKey")
                if (matchKey.isBlank()) return@mapNotNull null
                val type = enumOf(sec.cell(row, "type"), TxType.EXPENSE)
                if (!seenRuleKeys.add("$matchKey|${type.name}")) return@mapNotNull null
                val categoryId = sec.cell(row, "categoryId").toLongOrNull() ?: return@mapNotNull null
                val rawId = sec.cell(row, "id").toLongOrNull() ?: 0L
                MerchantRuleEntity(
                    id = if (rawId > 0 && usedRuleIds.add(rawId)) rawId else 0L,
                    matchKey = matchKey,
                    type = type,
                    categoryId = categoryIdMap[categoryId] ?: categoryId,
                    accountId = sec.cell(row, "accountId").toLongOrNull()?.let { accountIdMap[it] ?: it },
                    hits = sec.cell(row, "hits").toIntOrNull() ?: 1,
                    updatedAt = sec.cell(row, "updatedAt").toLongOrNull() ?: 0L,
                )
            }
        }.orEmpty()

        return BackupData(
            accounts = accountList,
            categories = categoryList,
            transactions = transactions,
            installments = installments,
            recurring = recurring,
            merchantRules = merchantRules,
        )
    }

    private fun parseAccount(sec: Section, row: List<String>): AccountEntity? {
        val name = sec.cell(row, "name")
        if (name.isBlank()) return null
        val creditLimit = sec.cell(row, "creditLimitCents").toLongOrNull() ?: 0L
        return AccountEntity(
            id = sec.cell(row, "id").toLongOrNull() ?: 0L,
            name = name,
            kind = enumOf(sec.cell(row, "kind"), if (creditLimit > 0L) AccountKind.CREDIT else AccountKind.CASH),
            initialBalanceCents = sec.cell(row, "initialBalanceCents").toLongOrNull() ?: 0L,
            creditLimitCents = creditLimit,
            sharedGroupId = sec.cell(row, "sharedGroupId").toLongOrNull() ?: 0L,
            isDefault = boolOf(sec.cell(row, "isDefault")),
            note = sec.cell(row, "note"),
        )
    }

    private fun parseCategory(sec: Section, row: List<String>): CategoryEntity? {
        val name = sec.cell(row, "name")
        if (name.isBlank()) return null
        return CategoryEntity(
            id = sec.cell(row, "id").toLongOrNull() ?: 0L,
            name = name,
            type = enumOf(sec.cell(row, "type"), TxType.EXPENSE),
            sortOrder = sec.cell(row, "sortOrder").toIntOrNull() ?: 0,
            icon = sec.cell(row, "icon"),
            parentId = sec.cell(row, "parentId").toLongOrNull(),
        )
    }

    private fun normalizeAccounts(raw: List<AccountEntity>): Pair<List<AccountEntity>, Map<Long, Long>> {
        var next = (raw.maxOfOrNull { it.id } ?: 0L) + 1
        val used = HashSet<Long>()
        val idMap = HashMap<Long, Long>()
        val result = raw.map { account ->
            val newId = if (account.id > 0 && used.add(account.id)) account.id else (next++).also { used.add(it) }
            if (account.id > 0) idMap[account.id] = newId
            account.copy(id = newId)
        }
        return result to idMap
    }

    private fun normalizeCategories(raw: List<CategoryEntity>): Pair<List<CategoryEntity>, Map<Long, Long>> {
        var next = (raw.maxOfOrNull { it.id } ?: 0L) + 1
        val used = HashSet<Long>()
        val idMap = HashMap<Long, Long>()
        val firstPass = raw.map { category ->
            val newId = if (category.id > 0 && used.add(category.id)) category.id else (next++).also { used.add(it) }
            if (category.id > 0) idMap[category.id] = newId
            category.copy(id = newId)
        }
        // 父分类 id 可能被重新分配过，二级分类的 parentId 跟着改
        val result = firstPass.map { category ->
            category.copy(parentId = category.parentId?.let { idMap[it] ?: it })
        }
        return result to idMap
    }

    // ---------- 设置 / 预算 ----------

    private fun applySettings(prefs: SharedPreferences, section: Section?) {
        if (section == null || section.rows.isEmpty()) return
        val settings = JSONObject()
        section.rows.forEach { row ->
            val key = secCell(section, row, "键")
            if (key.isBlank()) return@forEach
            val type = secCell(section, row, "类型").ifBlank { "s" }
            val raw = secCell(section, row, "值")
            val wrapper = JSONObject().put("t", type)
            when (type) {
                "b" -> wrapper.put("v", boolOf(raw))
                "i" -> wrapper.put("v", raw.toIntOrNull() ?: 0)
                "l" -> wrapper.put("v", raw.toLongOrNull() ?: 0L)
                "f" -> wrapper.put("v", raw.toDoubleOrNull() ?: 0.0)
                // 字符串集合（监听的 App 名单等）：CSV 里存 JSON 数组文本
                "ss" -> wrapper.put("v", runCatching { JSONArray(raw) }.getOrElse { JSONArray() })
                else -> wrapper.put("v", raw)
            }
            settings.put(key, wrapper)
        }
        BackupStore.restoreSettings(prefs, settings)
    }

    private fun applyBudgets(prefs: SharedPreferences, section: Section?) {
        if (section == null) return
        val editor = prefs.edit()
        section.rows.forEach { row ->
            val key = secCell(section, row, "键")
            if (key.isBlank()) return@forEach
            val value = secCell(section, row, "值")
            when {
                key.startsWith(KEY_CAT_BUDGET_PCT_PREFIX) -> {
                    val pct = value.toIntOrNull()
                    if (pct == null) editor.remove(key) else editor.putInt(key, pct)
                }
                key == KEY_MONTHLY_BUDGET || key == KEY_DAILY_BUDGET || key == KEY_DEPOSIT_GOAL ||
                    (key.startsWith(KEY_CAT_BUDGET_PREFIX) && !key.startsWith(KEY_CAT_BUDGET_PCT_PREFIX) &&
                        key != KEY_CAT_BUDGET_MODE) -> {
                    val cents = amountOf(value)
                    if (cents == null) editor.remove(key) else editor.putLong(key, cents)
                }
                key == KEY_DAILY_MODE -> editor.putBoolean(key, boolOf(value))
                key == KEY_MONTH_START_DAY -> value.toIntOrNull()?.let { editor.putInt(key, it) }
                key.startsWith(KEY_BUDGET_PLAN_PREFIX) ||
                    key == KEY_CAT_BUDGET_MODE -> if (value.isBlank()) editor.remove(key) else editor.putString(key, value)
            }
        }
        editor.apply()
    }

    /**
     * 还原存款设置：全局目标、按月目标、存款计划起始月、显示范围、是否显示全部月份。
     * 旧备份没有本段时，settings 段里的同名键已在 [applySettings] 还原；本段存在时以本段为准。
     */
    private fun applyDepositSettings(prefs: SharedPreferences, section: Section?) {
        if (section == null || section.rows.isEmpty()) return
        val editor = prefs.edit()
        section.rows.forEach { row ->
            val key = secCell(section, row, "键")
            if (key.isBlank()) return@forEach
            val value = secCell(section, row, "值")
            when {
                key == KEY_DEPOSIT_GOAL || key.startsWith(KEY_DEPOSIT_MONTH_GOAL_PREFIX) -> {
                    val cents = amountOf(value)
                    if (cents == null || cents <= 0L) editor.remove(key) else editor.putLong(key, cents)
                }
                key == KEY_DEPOSIT_PLAN_START ->
                    if (value.isBlank()) editor.remove(key) else editor.putString(key, value)
                key == KEY_DEPOSIT_RANGE_MONTHS ->
                    value.toIntOrNull()?.takeIf { it in 1..1200 }?.let { editor.putInt(key, it) }
                key == KEY_DEPOSIT_SHOW_ALL -> editor.putBoolean(key, boolOf(value))
            }
        }
        editor.apply()
    }

    // ---------- 通用 ----------

    private class Writer {
        private val sb = StringBuilder()

        fun marker(text: String) {
            sb.append(text).append('\n')
        }

        fun row(cells: List<String>) {
            sb.append(cells.joinToString(",") { escape(it) }).append('\n')
        }

        fun row(vararg cells: String) = row(cells.toList())

        override fun toString(): String = sb.toString()
    }

    private class Section(
        private val header: Map<String, Int>,
        val rows: List<List<String>>,
    ) {
        fun cell(row: List<String>, name: String): String {
            val index = header[name] ?: return ""
            return row.getOrNull(index)?.trim().orEmpty()
        }
    }

    private fun splitSections(rows: List<List<String>>): Map<String, List<List<String>>>? {
        var found = false
        val sections = LinkedHashMap<String, MutableList<List<String>>>()
        var current: MutableList<List<String>>? = null
        rows.forEach { row ->
            val first = row.firstOrNull()?.trim().orEmpty()
            when {
                first.startsWith(MARKER_PREFIX) -> found = true
                first.startsWith("##") -> {
                    val name = first.removePrefix("##").substringBefore('|').substringBefore(' ').lowercase()
                    current = sections.getOrPut(name) { mutableListOf() }
                }
                row.all { it.isBlank() } -> Unit
                else -> current?.add(row)
            }
        }
        return if (found) sections else null
    }

    private fun toSection(rows: List<List<String>>?): Section? {
        if (rows.isNullOrEmpty()) return null
        val header = HashMap<String, Int>()
        rows.first().forEachIndexed { index, cell -> header[cell.trim()] = index }
        return Section(header, rows.drop(1))
    }

    private fun secCell(section: Section, row: List<String>, name: String): String {
        val direct = section.cell(row, name)
        if (direct.isNotBlank()) return direct
        // 兼容只有两列（键,值）的手工 CSV
        return if (name == "值") row.getOrNull(1)?.trim().orEmpty() else direct
    }

    private fun escape(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            value.first().isWhitespace() || value.last().isWhitespace()
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun typeLabel(type: TxType): String = if (type == TxType.INCOME) "收入" else "支出"

    private fun typeOf(text: String): TxType {
        val value = text.trim()
        return when {
            value.equals("收入", true) || value.equals("INCOME", true) -> TxType.INCOME
            value.equals("支出", true) || value.equals("EXPENSE", true) -> TxType.EXPENSE
            value.contains("收入") -> TxType.INCOME
            else -> TxType.EXPENSE
        }
    }

    private fun boolOf(text: String, fallback: Boolean = false): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return fallback
        return value == "1" || value.equals("true", true) || value == "是" ||
            value == "开启" || value == "已扣"
    }

    /** 类型安全的设置读取：无论历史数据存成 Long / Int / String 都不会抛 ClassCastException */
    private fun longPref(prefs: SharedPreferences, key: String, fallback: Long = 0L): Long =
        when (val value = prefs.all[key]) {
            is Long -> value
            is Int -> value.toLong()
            is Float -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: fallback
            else -> fallback
        }

    private fun intPref(prefs: SharedPreferences, key: String, fallback: Int = 0): Int =
        longPref(prefs, key, fallback.toLong()).let { raw ->
            if (raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt() else fallback
        }

    private fun boolPref(prefs: SharedPreferences, key: String, fallback: Boolean = false): Boolean =
        when (val value = prefs.all[key]) {
            is Boolean -> value
            is String -> value == "1" || value.equals("true", true)
            is Number -> value.toLong() != 0L
            else -> fallback
        }

    /** 金额文本 → 分（取绝对值；去掉货币符号与空格） */
    private fun amountOf(text: String): Long? {
        val cleaned = text.filter { it.isDigit() || it == '.' || it == '-' || it == ',' }
        if (cleaned.isEmpty()) return null
        return Money.parseCents(cleaned)?.let { kotlin.math.abs(it) }
    }

    /** "HH:mm" 或分钟数 → 当天分钟数（0..1439）；无法解析返回 null */
    private fun parseMinutes(text: String): Int? {
        val value = text.trim()
        if (value.isEmpty()) return null
        if (!value.contains(':')) return value.toIntOrNull()?.takeIf { it in 0..1439 }
        val parts = value.split(':')
        if (parts.size < 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun parseTimestamp(text: String, zone: ZoneId): Long? {
        val value = text.trim()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.takeIf { it >= 1_000_000_000_000L }?.let { return it }
        val normalized = value.replace('T', ' ')
        DATE_FORMATS.forEach { format ->
            runCatching { LocalDateTime.parse(normalized, format) }.getOrNull()?.let {
                return it.atZone(zone).toInstant().toEpochMilli()
            }
            runCatching { LocalDate.parse(normalized, format) }.getOrNull()?.let {
                return it.atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(value?.trim(), true) } ?: fallback
}
