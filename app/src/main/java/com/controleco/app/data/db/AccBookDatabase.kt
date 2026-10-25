package com.controleco.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        CapturedNotificationEntity::class,
        PendingTransactionEntity::class,
        CaptureTraceEntity::class,
        MerchantRuleEntity::class,
        InstallmentPlanEntity::class,
        RecurringPlanEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class AccBookDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun captureDao(): CaptureDao
    abstract fun pendingDao(): PendingDao
    abstract fun traceDao(): TraceDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun planDao(): PlanDao

    companion object {
        /** v2：交易表新增退款标记列 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN refundMark TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        /** v3：通知采集通道——原始捕获日志表 + 待确认队列表 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `captured_notifications` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`bigText` TEXT NOT NULL, " +
                        "`postTime` INTEGER NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, " +
                        "`matchedRule` TEXT NOT NULL, " +
                        "`queued` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_transactions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`counterparty` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`sourcePackage` TEXT NOT NULL, " +
                        "`ruleName` TEXT NOT NULL, " +
                        "`dedupKey` TEXT NOT NULL, " +
                        "`rawNotificationId` INTEGER, " +
                        "`status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_transactions_dedupKey` " +
                        "ON `pending_transactions` (`dedupKey`)",
                )
            }
        }

        /** v4：待确认队列表新增退款标记列（defaultValue 与实体声明一致，保证迁移库与新建库 schema 完全一致） */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN refundMark TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        /** v5：捕获日志表新增通道列，区分通知监听与无障碍页面抓取（defaultValue 与实体声明一致） */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captured_notifications ADD COLUMN source TEXT NOT NULL DEFAULT 'NOTIFICATION'")
            }
        }

        /** v6：分类表新增图标列（emoji / 内置图形 / 上传图片，defaultValue 与实体声明一致） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7：自动记账溯源表（开发者功能「记录记账来源」，默认关闭时零写入） */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `capture_traces` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channel` TEXT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`bigText` TEXT NOT NULL, " +
                        "`capturedAt` INTEGER NOT NULL, " +
                        "`matchedRule` TEXT NOT NULL, " +
                        "`type` TEXT, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`counterparty` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`dedupKey` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_capture_traces_dedupKey` " +
                        "ON `capture_traces` (`dedupKey`)",
                )
            }
        }


        /** v8：商户归类学习规则表（确认/编辑捕获交易时学习，越用越准） */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merchant_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`matchKey` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "`accountId` INTEGER, " +
                        "`hits` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_rules_matchKey_type` " +
                        "ON `merchant_rules` (`matchKey`, `type`)",
                )
            }
        }

        /**
         * v9：待确认条目增加"页面捕获到的支付工具"与"用户是否手改过"两列。
         * - `paymentInstrument`：页面解析出的支付工具（零钱 / 招商银行(1234) / 美团月付…），
         *   入账时据此匹配实际记账账户（"从微信付的"不等于"从微信零钱付的"）；
         * - `userEdited`：用户手动改过该笔后置位，后续页面/银行侧富化不再覆盖用户输入。
         * 两列都带默认值，历史条目照常读取。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN paymentInstrument TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN userEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v10：交易身份与来源实例拆分（本轮正确性修复）。
         * - `transactions.flowId`：页面支付流程标识，和 externalId（数据库幂等键）分开；
         * - `pending_transactions.flowId`：同一页面流程的结果页/详情页共享；
         * - `pending_transactions.notificationInstanceId`：同一条系统通知的更新共享，
         *   不同通知实例即使同分钟同额也不合并；
         * - `pending_transactions.orderId`：页面解析出的交易单号/订单号，跨渠道关联的强证据。
         * 全部列都有默认值，历史账单与待确认条目原样保留；旧记录身份未知时按保守窗口匹配。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN flowId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN flowId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN notificationInstanceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN bankInstanceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN orderId TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v11：退款永远显示、永远按收入记账。
         * - 收入分类补一条「退款」（已存在则不重复插入）；
         * - 历史退款收入（refundMark = REFUND_INCOME）统一改挂到「退款」分类，
         *   否则同一类记录会散落在导入时猜出来的购物/餐饮等分类里。
         * 无 schema 变化（只补数据），旧库升级与新建库的列结构仍逐字一致。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "INSERT INTO categories (name, type, sortOrder, icon) " +
                        "SELECT '${RefundCategory.NAME}', '${TxType.INCOME}', 3, '' " +
                        "WHERE NOT EXISTS (" +
                        "SELECT 1 FROM categories WHERE type = '${TxType.INCOME}' AND name = '${RefundCategory.NAME}')",
                )
                db.execSQL(
                    "UPDATE transactions SET categoryId = (" +
                        "SELECT id FROM categories " +
                        "WHERE type = '${TxType.INCOME}' AND name = '${RefundCategory.NAME}' LIMIT 1) " +
                        "WHERE refundMark = 'REFUND_INCOME' AND EXISTS (" +
                        "SELECT 1 FROM categories WHERE type = '${TxType.INCOME}' AND name = '${RefundCategory.NAME}')",
                )
            }
        }

        /**
         * v12：退款收入可以指名「被退款的原支出」，分类预算才能按 支出 − 退款 算。
         * - `refundLinkId`：退款收入 → 原支出 id（普通收支为 NULL）；
         * - `refundPending`：该退款是否还在等用户指定原支出（进「待确认账单」）。
         *   历史退款一律 0：升级不把旧数据塞进待确认，避免一次冒出几十条要处理的条目。
         * 两列都有默认值/可空，旧库升级与新建库列结构一致。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN refundLinkId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN refundPending INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v13：退款不再与原支出建立关联。
         *
         * 支出只标「已退款」、退款收入只标「已处理」，两者互不指向（见 [TransactionEntity.refundPending]），
         * 所以 v12 加的 `refundLinkId` 列整个删掉。
         *
         * minSdk 26 的 SQLite 是 3.18/3.19，**不支持 `ALTER TABLE ... DROP COLUMN`**（3.35+），
         * 按 Room 的标准做法重建表：建新表 → 原样搬列（保留 id）→ 删旧表 → 改名 → 重建唯一索引。
         * 列定义逐字取自 v12 导出的 schema（`app/schemas/.../12.json`），只去掉 `refundLinkId`。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transactions_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`counterparty` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`externalId` TEXT, " +
                        "`refundMark` TEXT NOT NULL, " +
                        "`refundPending` INTEGER NOT NULL DEFAULT 0, " +
                        "`flowId` TEXT NOT NULL DEFAULT '', " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT )",
                )
                db.execSQL(
                    "INSERT INTO `transactions_new` (`id`, `type`, `amountCents`, `categoryId`, `accountId`, " +
                        "`timestamp`, `note`, `counterparty`, `source`, `externalId`, `refundMark`, " +
                        "`refundPending`, `flowId`, `createdAt`) " +
                        "SELECT `id`, `type`, `amountCents`, `categoryId`, `accountId`, `timestamp`, `note`, " +
                        "`counterparty`, `source`, `externalId`, `refundMark`, `refundPending`, `flowId`, " +
                        "`createdAt` FROM `transactions`",
                )
                db.execSQL("DROP TABLE `transactions`")
                db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_externalId` " +
                        "ON `transactions` (`externalId`)",
                )
            }
        }

        /**
         * v14：二级分类 / 账户默认与共用额度 / 不扣资产标记 / 分期与周期计划表。
         * 全部是新增列或新增表，历史数据原样保留。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN parentId INTEGER")
                db.execSQL("ALTER TABLE accounts ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN creditLimitCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN sharedGroupId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN excludeFromAssets INTEGER NOT NULL DEFAULT 0")
                // 老账户：第一个设为默认，保证记账有稳定的首选账户
                db.execSQL(
                    "UPDATE accounts SET isDefault = 1 WHERE id = (" +
                        "SELECT MIN(id) FROM accounts)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `installment_plans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "`totalPrincipalCents` INTEGER NOT NULL, " +
                        "`totalInterestCents` INTEGER NOT NULL DEFAULT 0, " +
                        "`periods` INTEGER NOT NULL, " +
                        "`firstDateMillis` INTEGER NOT NULL, " +
                        "`deductAsset` INTEGER NOT NULL DEFAULT 1, " +
                        "`note` TEXT NOT NULL, " +
                        "`postedPeriods` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recurring_plans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, " +
                        "`frequency` TEXT NOT NULL, " +
                        "`firstDateMillis` INTEGER NOT NULL, " +
                        "`nextDateMillis` INTEGER NOT NULL, " +
                        "`deductAsset` INTEGER NOT NULL DEFAULT 1, " +
                        "`autoPost` INTEGER NOT NULL DEFAULT 0, " +
                        "`note` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v15：账户分「存款 / 信用卡」；分期计划支持按期限指定「只记账、不扣资产」。
         * 老账户里填过信用额度的自动认成信用卡，其余为存款。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN kind TEXT NOT NULL DEFAULT 'CASH'")
                db.execSQL("UPDATE accounts SET kind = 'CREDIT' WHERE creditLimitCents > 0")
                db.execSQL(
                    "ALTER TABLE installment_plans ADD COLUMN excludeAssetPeriods TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v16：分期 / 周期计划支持「出账时间」与自动入账。
         * - 分期：新增 `postTimeMinutes`（当天分钟数，到点才生成账单）；
         * - 周期：新增重复间隔、结束方式（永不 / 次数 / 日期）、出账时间、已入账次数与不扣资产期数。
         * 全部带默认值，历史计划按原逻辑继续可用（已结束的周期计划由 nextDateMillis 判断）。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE installment_plans ADD COLUMN postTimeMinutes INTEGER NOT NULL DEFAULT 540",
                )
                db.execSQL(
                    "ALTER TABLE recurring_plans ADD COLUMN intervalCount INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE recurring_plans ADD COLUMN postTimeMinutes INTEGER NOT NULL DEFAULT 540",
                )
                db.execSQL(
                    "ALTER TABLE recurring_plans ADD COLUMN endMode TEXT NOT NULL DEFAULT 'NEVER'",
                )
                db.execSQL(
                    "ALTER TABLE recurring_plans ADD COLUMN endCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE recurring_plans ADD COLUMN endDateMillis INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE recurring_plans ADD COLUMN postedCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE recurring_plans ADD COLUMN excludeAssetPeriods TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v17：新增「不计入账单和收支统计」。
         * - 账单：`excludeFromStats`（0 = 计入，1 = 只在账单列表显示）；
         * - 分期 / 周期：`excludeStatsPeriods`（与不扣资产期数同格式）。
         * 全部默认计入，历史数据口径不变。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE installment_plans ADD COLUMN excludeStatsPeriods TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE recurring_plans ADD COLUMN excludeStatsPeriods TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        @Volatile
        private var instance: AccBookDatabase? = null

        fun get(context: Context): AccBookDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AccBookDatabase::class.java,
                    "accbook.db",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                        MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                        MIGRATION_15_16, MIGRATION_16_17,
                    )
                    .build().also { instance = it }
            }
    }
}

/** 首次启动的默认数据：常用账户与收支分类 */
object DefaultData {

    val defaultAccounts = listOf("现金", "微信零钱", "支付宝", "银行卡")

    val defaultExpenseCategories =
        listOf("餐饮", "交通", "购物", "日用", "居住", "娱乐", "医疗", "其他")

    /** 收入分类：「退款」固定排在「其他」之前（退款是常规收入项，其他是兜底项） */
    val defaultIncomeCategories = listOf("工资", "理财", "红包", RefundCategory.NAME, "其他")

    suspend fun seedIfNeeded(db: AccBookDatabase) {
        if (db.accountDao().countAll() == 0) {
            db.accountDao().insertAll(
                defaultAccounts.mapIndexed { i, name ->
                    AccountEntity(name = name, isDefault = i == 0)
                },
            )
        }
        if (db.categoryDao().countAll() == 0) {
            val expense = defaultExpenseCategories.mapIndexed { i, name ->
                CategoryEntity(name = name, type = TxType.EXPENSE, sortOrder = i)
            }
            val income = defaultIncomeCategories.mapIndexed { i, name ->
                CategoryEntity(name = name, type = TxType.INCOME, sortOrder = i)
            }
            db.categoryDao().insertAll(expense + income)
        }
    }
}
