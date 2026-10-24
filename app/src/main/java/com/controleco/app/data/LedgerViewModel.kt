package com.controleco.app.data

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.controleco.app.AccBookApp
import com.controleco.app.BuildConfig
import com.controleco.app.capture.AccBookAccessibilityService
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.capture.CaptureDefaults
import com.controleco.app.capture.NotificationRules
import com.controleco.app.capture.MerchantRules
import com.controleco.app.capture.PendingActions
import com.controleco.app.capture.ScreenReaderAccess
import com.controleco.app.csv.BillParsers
import com.controleco.app.data.db.AccountBalanceRow
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.AccountKind
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CaptureTraceRow
import com.controleco.app.data.db.CapturedNotificationEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.InstallmentPlanEntity
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RecurringPlanEntity
import com.controleco.app.data.db.RefundCategory
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TypeTotalRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.data.backup.BackupCsv
import com.controleco.app.data.backup.BackupStore
import com.controleco.app.data.budget.Budget
import com.controleco.app.data.budget.CategoryBudgetMath
import com.controleco.app.data.budget.CategoryBudgetMode
import com.controleco.app.data.budget.MonthBudgetCodec
import com.controleco.app.data.budget.MonthBudgetMath
import com.controleco.app.data.budget.MonthBudgetPlan
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.CategoryTree
import com.controleco.app.data.SubcategoryRollupMode
import com.controleco.app.data.deposit.DepositMath
import com.controleco.app.data.model.AssetAccountSnapshot
import com.controleco.app.data.model.AssetMath
import com.controleco.app.data.model.AssetSummary
import com.controleco.app.data.model.BillSource
import com.controleco.app.data.plan.PLAN_TX_TYPE
import com.controleco.app.data.plan.InstallmentMath
import com.controleco.app.data.plan.PlanAutoPoster
import com.controleco.app.data.plan.PlanScheduler
import com.controleco.app.data.plan.RecurringEndMode
import com.controleco.app.data.plan.RecurringFrequency
import com.controleco.app.data.plan.RecurringMath
import com.controleco.app.data.model.ImportCommitResult
import com.controleco.app.data.model.ParsedBill
import com.controleco.app.update.AppUpdate
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 阶段一的全局 ViewModel：月份切换为流水/统计共用状态；
 * 后续阶段（自动记账）在此扩展捕获通道状态。
 */
class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as AccBookApp).db
    private val txDao = db.transactionDao()
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val pendingDao = db.pendingDao()
    private val captureDao = db.captureDao()
    private val traceDao = db.traceDao()
    private val merchantRuleDao = db.merchantRuleDao()
    private val planDao = db.planDao()

    private val zone: ZoneId = ZoneId.systemDefault()

    // ---------- 设置：显示退款记录 ----------

    private val prefs = getApplication<AccBookApp>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 预算：会计月首日 / 日·月预算 / 生效月预算 ----------

    /** 会计月首日（1..28）：决定"本月"的区间与全 App 按月统计的口径（见 MonthPeriod） */
    private val _monthStartDay = MutableStateFlow(
        MonthPeriod.clampStartDay(prefs.getInt(KEY_MONTH_START_DAY, 1)),
    )
    val monthStartDay: StateFlow<Int> = _monthStartDay

    fun setMonthStartDay(day: Int) {
        val v = MonthPeriod.clampStartDay(day)
        prefs.edit().putInt(KEY_MONTH_START_DAY, v).apply()
        _monthStartDay.value = v
        AppLog.i("settings", "monthStartDay=$v")
    }

    /**
     * "今天"（跨零点或从后台回来时由 [refreshToday] 刷新）。
     * 预算周期、今日净支出等一切"以今天为准"的口径都读它，避免零点后还停在昨天。
     */
    private val todayRef = MutableStateFlow(LocalDate.now(zone))

    /** 首页 ON_RESUME 时调用：跨天后把"今天"的统计范围换过来 */
    fun refreshToday() {
        val now = LocalDate.now(zone)
        if (now != todayRef.value) {
            todayRef.value = now
            AppLog.i("home", "today=$now")
        }
        catchUpDuePlans()
    }

    /** 回到前台 / 分期页停留时补记到点的分期与周期账单 */
    fun catchUpDuePlans() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                PlanAutoPoster.postDue(db, zone = zone)
                PlanScheduler.schedule(getApplication(), db)
            }
        }
    }

    /** 当前会计月区间（随"今天 / 月首日"滚动） */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPeriod: StateFlow<MonthPeriod> = combine(todayRef, _monthStartDay) { d, s ->
        MonthPeriod.of(d, s)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value),
    )

    /** 当前会计月的"月标签"（账单/统计页的当前高亮与「回到本月」用；首日 ≠ 1 时可能不是本月） */
    val currentAccountingMonth: StateFlow<YearMonth> = currentPeriod
        .map { YearMonth.from(it.start) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MonthPeriod.monthOf(LocalDate.now(zone), _monthStartDay.value),
        )

    /** 预算按日设置（默认关）：开启后输入日预算，月预算 = 日预算 × 本期天数 */
    private val _dailyBudgetMode = MutableStateFlow(prefs.getBoolean(KEY_DAILY_BUDGET_MODE, false))
    val dailyBudgetMode: StateFlow<Boolean> = _dailyBudgetMode

    private val _monthlyBudgetAmount = MutableStateFlow(prefs.getLong(KEY_MONTHLY_BUDGET, 0L))
    private val _dailyBudgetAmount = MutableStateFlow(prefs.getLong(KEY_DAILY_BUDGET, 0L))

    /** 日预算金额（0 = 未设置；是否生效看 [dailyBudgetMode]） */
    val dailyBudgetCents: StateFlow<Long> = _dailyBudgetAmount

    // ---------- 设置：按月预算（功能5） ----------

    /** 各月独立预算（key = 会计月标签）；没有单独设置的月份回落到全局预算 */
    private val _budgetPlans = MutableStateFlow(loadBudgetPlans())
    val budgetPlans: StateFlow<Map<YearMonth, MonthBudgetPlan>> = _budgetPlans

    private fun loadBudgetPlans(): Map<YearMonth, MonthBudgetPlan> = prefs.all.entries
        .mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_BUDGET_PLAN_PREFIX)) return@mapNotNull null
            val ym = parseMonthKey(key.removePrefix(KEY_BUDGET_PLAN_PREFIX)) ?: return@mapNotNull null
            val plan = MonthBudgetCodec.decode(value as? String) ?: return@mapNotNull null
            ym to plan
        }
        .toMap()

    private fun parseMonthKey(text: String): YearMonth? = runCatching {
        val parts = text.split('-')
        YearMonth.of(parts[0].toInt(), parts[1].toInt())
    }.getOrNull()

    /** 当前（今天所在）会计月的独立预算；没有则 null */
    fun planOf(month: YearMonth): MonthBudgetPlan? = _budgetPlans.value[month]

    /** 某月的生效预算（含全局回落；纯函数，统计页也直接用 MonthBudgetMath） */
    fun budgetOf(month: YearMonth): Long = MonthBudgetMath.totalFor(
        month,
        _monthStartDay.value,
        _budgetPlans.value,
        _monthlyBudgetAmount.value,
        _dailyBudgetAmount.value,
        _dailyBudgetMode.value,
    )

    /** 保存某月总预算（cents ≤ 0 视为删除该月独立预算，回到全局） */
    fun setMonthlyBudgetFor(month: YearMonth, cents: Long) {
        val old = _budgetPlans.value[month] ?: MonthBudgetPlan()
        val next = old.copy(totalCents = cents.coerceAtLeast(0L))
        val editor = prefs.edit()
        if (next.totalCents <= 0L && next.categoryCents.isEmpty()) {
            editor.remove(MonthBudgetCodec.keyOf(month))
        } else {
            editor.putString(MonthBudgetCodec.keyOf(month), MonthBudgetCodec.encode(next))
        }
        editor.apply()
        _budgetPlans.value = loadBudgetPlans()
        AppLog.i("settings", "monthBudget[$month]=${Money.formatYuan(cents)}")
    }

    /** 保存某月的分类预算（cents ≤ 0 = 清除该分类在该月的设置） */
    fun setCategoryBudgetFor(month: YearMonth, categoryId: Long, cents: Long) {
        val old = _budgetPlans.value[month] ?: MonthBudgetPlan()
        val next = old.withCategory(categoryId, cents)
        val editor = prefs.edit()
        if (next.totalCents <= 0L && next.categoryCents.isEmpty()) {
            editor.remove(MonthBudgetCodec.keyOf(month))
        } else {
            editor.putString(MonthBudgetCodec.keyOf(month), MonthBudgetCodec.encode(next))
        }
        editor.apply()
        _budgetPlans.value = loadBudgetPlans()
    }

    /** 生效的月预算：该月单独设置的总额 → 优先；否则按日模式 = 日预算 × 本期天数，或全局月金额 */
    val monthlyBudgetCents: StateFlow<Long> = combine(
        _monthlyBudgetAmount, _dailyBudgetAmount, _dailyBudgetMode, currentPeriod, _budgetPlans,
    ) { amount, daily, dailyMode, period, plans ->
        MonthBudgetMath.totalFor(
            YearMonth.from(period.start), _monthStartDay.value, plans, amount, daily, dailyMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), effectiveMonthlyBudget())

    /** 全局月预算（不含某月独立设置）：统计页计算"查看月份的回落值"用 */
    val globalMonthlyBudgetCents: StateFlow<Long> = combine(
        _monthlyBudgetAmount, _dailyBudgetAmount, _dailyBudgetMode, currentPeriod,
    ) { amount, daily, dailyMode, period ->
        if (dailyMode) daily * period.days else amount
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), effectiveMonthlyBudget())

    private fun effectiveMonthlyBudget(): Long {
        val month = YearMonth.from(MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value).start)
        return MonthBudgetMath.totalFor(
            month,
            _monthStartDay.value,
            _budgetPlans.value,
            _monthlyBudgetAmount.value,
            _dailyBudgetAmount.value,
            _dailyBudgetMode.value,
        )
    }

    /** 月预算输入（金额模式）：cents ≤ 0 视为未设置；日预算按本期天数自动折算 */
    fun setMonthlyBudget(cents: Long) {
        prefs.edit().putLong(KEY_MONTHLY_BUDGET, cents).apply()
        _monthlyBudgetAmount.value = cents
        syncDailyFromMonthly()
        AppLog.i("settings", "monthlyBudget=${Money.formatYuan(cents)}")
    }

    /** 日预算输入（按日模式）：cents ≤ 0 视为未设置 */
    fun setDailyBudget(cents: Long) {
        prefs.edit().putLong(KEY_DAILY_BUDGET, cents).apply()
        _dailyBudgetAmount.value = cents
        AppLog.i("settings", "dailyBudget=${Money.formatYuan(cents)}")
    }

    /** 日预算 = 月预算 ÷ 本期天数（四舍五入到分）；只在按月设置时自动同步 */
    private fun syncDailyFromMonthly() {
        val monthly = _monthlyBudgetAmount.value
        if (!_dailyBudgetMode.value || monthly <= 0L) return
        val days = MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value).days
        val daily = Budget.dailyEquivalent(monthly, days)
        prefs.edit().putLong(KEY_DAILY_BUDGET, daily).apply()
        _dailyBudgetAmount.value = daily
    }

    /**
     * 切换日/月预算：切到按日时按当前月预算自动折算日均（月预算为空才保留手填值）；
     * 反向切回月预算时用"日预算 × 本期天数"补上全局月预算。
     */
    fun setDailyBudgetMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DAILY_BUDGET_MODE, enabled).apply()
        _dailyBudgetMode.value = enabled
        val days = MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value).days
        if (enabled && _monthlyBudgetAmount.value > 0L) {
            setDailyBudget(Budget.dailyEquivalent(_monthlyBudgetAmount.value, days))
        } else if (!enabled && _monthlyBudgetAmount.value <= 0L && _dailyBudgetAmount.value > 0L) {
            setMonthlyBudget(_dailyBudgetAmount.value * days)
        }
        AppLog.i("settings", "budgetDailyMode=$enabled")
    }

    // ---------- 设置：账单页结余卡背景图（null = 默认几何背景） ----------

    private val _cardImageUri = MutableStateFlow(prefs.getString(KEY_CARD_IMAGE, null))
    val cardImageUri: StateFlow<String?> = _cardImageUri

    fun setCardImage(uri: String?) {
        prefs.edit().putString(KEY_CARD_IMAGE, uri).apply()
        _cardImageUri.value = uri
        if (uri == null) {
            prefs.edit().remove(KEY_CARD_COLOR).remove(KEY_CARD_LUMINANCE).apply()
            _cardImageColor.value = null
            _cardImageLuminance.value = null
            pruneCardImages() // 恢复默认背景：图片文件也一并清掉
        }
        loadCardImage(uri)
        AppLog.i("settings", "cardImage=${uri ?: "default"}")
    }

    /**
     * 清掉不再使用的卡面图（只留当前这张）。
     * 选图时**不**清：编辑器里点「取消」要把上一张原样恢复，得等真正「应用」时才收尾。
     */
    fun pruneCardImages() {
        val keep = _cardImageUri.value
        viewModelScope.launch(Dispatchers.IO) { deleteOtherCardImages(getApplication(), keep) }
    }

    /**
     * 卡面图片：进程启动（或换图）时就异步解码，账单页第一次组合时通常已经就绪，
     * 不会出现"先默认背景再闪回图片"。先出缩略图（几毫秒）再出全尺寸。
     */
    private val _cardImage = MutableStateFlow<CardImage?>(null)
    val cardImage: StateFlow<CardImage?> = _cardImage

    private var cardImageJob: Job? = null

    private fun loadCardImage(path: String?) {
        cardImageJob?.cancel()
        if (path.isNullOrBlank()) {
            _cardImage.value = null
            return
        }
        cardImageJob = viewModelScope.launch(Dispatchers.IO) {
            // 1) 超小缩略图：拿到真实（模糊）图 + 平均色，立刻可用
            val preview = CardImageLoader.decode(getApplication(), path, CardImageLoader.PREVIEW_SIZE)
            val persistedColor = prefs.getInt(KEY_CARD_COLOR, 0).takeIf { it != 0 }
            val persistedLum = prefs.getFloat(KEY_CARD_LUMINANCE, -1f).takeIf { it >= 0f }
            val color = preview?.let { CardImageLoader.averageColor(it) } ?: persistedColor
            val luminance = preview?.let { CardImageLoader.meanLuminance(it) } ?: persistedLum
            if (color != null && luminance != null) {
                // 老数据（图片在"平均色"功能之前导入）在这里回填一次，下次冷启动直接用
                if (persistedColor == null || persistedLum == null) setCardImageStats(color, luminance)
                _cardImage.value = CardImage(full = null, preview = preview, colorArgb = color, luminance = luminance)
            }
            // 2) 正常尺寸
            val full = CardImageLoader.decode(getApplication(), path, 1600)
            if (full != null) {
                val finalColor = color ?: CardImageLoader.averageColor(full)
                val finalLum = luminance ?: CardImageLoader.meanLuminance(full)
                _cardImage.value = CardImage(full, preview, finalColor, finalLum)
            }
        }
    }

    /**
     * 背景图的"平均色 + 整体亮度"：随图一起持久化。
     * 冷启动时位图还要解码，先拿这个颜色打底 → 不会先闪一帧默认几何背景。
     */
    private val _cardImageColor = MutableStateFlow(
        prefs.getInt(KEY_CARD_COLOR, 0).takeIf { it != 0 },
    )
    val cardImageColor: StateFlow<Int?> = _cardImageColor

    private val _cardImageLuminance = MutableStateFlow(
        prefs.getFloat(KEY_CARD_LUMINANCE, -1f).takeIf { it >= 0f },
    )
    val cardImageLuminance: StateFlow<Float?> = _cardImageLuminance

    fun setCardImageStats(argbColor: Int, luminance: Float) {
        prefs.edit()
            .putInt(KEY_CARD_COLOR, argbColor)
            .putFloat(KEY_CARD_LUMINANCE, luminance)
            .apply()
        _cardImageColor.value = argbColor
        _cardImageLuminance.value = luminance
    }

    /** 背景图的位移/缩放/旋转（选图后可在卡片上直接调） */
    private val _cardTransform = MutableStateFlow(
        CardTransform.parse(prefs.getString(KEY_CARD_TRANSFORM, null)),
    )
    val cardTransform: StateFlow<CardTransform> = _cardTransform

    init {
        // 冷启动预热：立刻在 IO 线程碰一次数据库，让 SQLite 打开/建连的开销
        // 与 UI 首帧并行，而不是等页面来查时才付（配合"未就绪先不画"减少空白时间）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { txDao.countAll() }
            // 冷启动补记：App 没运行时已到点的分期/周期，下一次打开就按原定时间入账
            runCatching {
                PlanAutoPoster.postDue(db, zone = zone)
                PlanScheduler.schedule(getApplication(), db)
            }
        }
        // 卡面图片同样在启动时就开始解码（先缩略图再全尺寸）
        loadCardImage(_cardImageUri.value)
        // 顺手清一次历史遗留图片（编辑器里没走完流程就退出的那几张）
        pruneCardImages()
    }

    fun setCardTransform(transform: CardTransform) {
        val t = transform.normalized()
        prefs.edit().putString(KEY_CARD_TRANSFORM, t.serialize()).apply()
        _cardTransform.value = t
    }

    // ---------- 设置：货币符号（人民币 / 美元 / 欧元 …；只改符号，不做换算） ----------

    private val _currencySymbol = MutableStateFlow(
        (prefs.getString(KEY_CURRENCY_SYMBOL, Money.DEFAULT_SYMBOL) ?: Money.DEFAULT_SYMBOL)
            .also { Money.symbol = it },
    )
    val currencySymbol: StateFlow<String> = _currencySymbol

    fun setCurrencySymbol(symbol: String) {
        val value = symbol.ifBlank { Money.DEFAULT_SYMBOL }
        prefs.edit().putString(KEY_CURRENCY_SYMBOL, value).apply()
        _currencySymbol.value = value
        Money.symbol = value
        AppLog.i("settings", "currencySymbol=$value")
    }

    /**
     * 首页「本月支出（本月已花）」：固定取**当前会计月**（不随流水页翻月变化；区间随月首日滚动）。
     *
     * 口径 = **未被标记「已退款」的支出账单之和**。
     * 退款是成对记录：原支出标了「已退款」就不再计入支出，退款那笔照常计入收入；
     * **不再做「支出 − 退款到账」的相减**——那是账单还没有退款标记时的旧做法。
     * 剩余/建议日花/状态由 UI 用 Budget.compute 纯函数计算。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeSpent: StateFlow<Long?> = currentPeriod.flatMapLatest { period ->
        val (start, end) = period.toMillisRange(zone)
        txDao.observeSpendingTotal(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 首页"分类剩余预算"预览：本期各支出分类的**未退款支出**之和。
     * 与「本月支出」同一口径（各分类相加 = 本月支出）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeCategorySums: StateFlow<List<CategorySumRow>?> = currentPeriod.flatMapLatest { period ->
        val (start, end) = period.toMillisRange(zone)
        txDao.observeSpendingCategorySums(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 首页"资产"视图的走势数据：当前会计月全部交易行。
     * 只用于画两条很淡的趋势线（累计支出 / 存款），不复用账单页翻月状态。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homePeriodRows: StateFlow<List<TransactionRow>?> = currentPeriod.flatMapLatest { period ->
        val (start, end) = period.toMillisRange(zone)
        txDao.observeRows(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 首页"本日剩余"用：**今日未退款支出**（同 [homeSpent] 口径，只是范围是今天）。
     *
     * 工资/红包这类收入不参与（收入进来不会让今天的额度变多）；
     * 被标「已退款」的支出不算花掉。范围跟着"今天"走（[refreshToday]），
     * 跨零点或从后台回来时刷新一次，否则零点后还会一直算昨天的账。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeTodaySpent: StateFlow<Long?> = todayRef.flatMapLatest { day ->
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        txDao.observeSpendingTotal(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 月份 ----------

    private val _month = MutableStateFlow(YearMonth.now(zone))
    val month: StateFlow<YearMonth> = _month

    /** 统计周期：按月 / 按年（账单页与统计页共用） */
    private val _periodMode = MutableStateFlow(PeriodMode.MONTH)
    val periodMode: StateFlow<PeriodMode> = _periodMode

    fun setPeriodMode(mode: PeriodMode) {
        _periodMode.value = mode
        AppLog.i("period", "mode=$mode")
    }

    fun prevMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }

    /** 步进：月模式走一个月，年模式走一年 */
    fun prevPeriod() {
        _month.value = if (_periodMode.value == PeriodMode.YEAR) {
            _month.value.minusYears(1)
        } else {
            _month.value.minusMonths(1)
        }
    }

    fun nextPeriod() {
        _month.value = if (_periodMode.value == PeriodMode.YEAR) {
            _month.value.plusYears(1)
        } else {
            _month.value.plusMonths(1)
        }
    }

    /** 直接跳到指定月份（账单页月份胶囊的选择面板） */
    fun setMonth(m: YearMonth) { _month.value = m }

    /**
     * 翻月选中月份（语义 = 会计月开始所在的月）对应的毫秒区间。
     * 首日 = 1 时即自然月；首日 = 2 时"2026年10月"= 10-02 ~ 11-02（左闭右开）。
     */
    private fun range(m: YearMonth): Pair<Long, Long> =
        MonthPeriod.of(m, _monthStartDay.value).toMillisRange(zone)

    /** 年区间：12 个连续会计月（y 年 1 月首日（含）→ 次年 1 月首日（不含）） */
    private fun yearRange(y: Int): Pair<Long, Long> =
        MonthPeriod.year(y, _monthStartDay.value).toMillisRange(zone)

    /** 当前周期的毫秒区间（随月份/年份/周期模式/月首日变化） */
    private val periodRange: StateFlow<Pair<Long, Long>> =
        combine(_month, _periodMode, _monthStartDay) { m, mode, startDay ->
            when (mode) {
                PeriodMode.MONTH -> MonthPeriod.of(m, startDay).toMillisRange(zone)
                PeriodMode.YEAR -> MonthPeriod.year(m.year, startDay).toMillisRange(zone)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), range(YearMonth.now(zone)))

    // 账单/统计共用的当月数据：stateIn 缓存上一次结果 —— 切标签页回来先显示缓存、
    // 后台再刷新，不再"每次进页面从空闪到满"；没有订阅者 5 秒后自动停掉 Room 观察。
    // 退款记录始终参与（不再有"显示退款记录"开关）。
    @OptIn(ExperimentalCoroutinesApi::class)
    val periodRows: StateFlow<List<TransactionRow>?> =
        periodRange.flatMapLatest { (start, end) -> txDao.observeRows(start, end) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val periodTotals: StateFlow<List<TypeTotalRow>?> =
        periodRange.flatMapLatest { (start, end) -> txDao.observeTypeTotals(start, end) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 本期各支出分类的**未退款支出**：统计页圆环与预算对比共用（与 [periodSpendingTotal] 同口径） */
    @OptIn(ExperimentalCoroutinesApi::class)
    val periodCategorySums: StateFlow<List<CategorySumRow>?> =
        periodRange.flatMapLatest { (start, end) -> txDao.observeSpendingCategorySums(start, end) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 日历（功能1） ----------

    private val _calendarMonth = MutableStateFlow(YearMonth.now(zone))
    val calendarMonth: StateFlow<YearMonth> = _calendarMonth

    fun setCalendarMonth(month: YearMonth) { _calendarMonth.value = month }

    fun prevCalendarMonth() { _calendarMonth.value = _calendarMonth.value.minusMonths(1) }

    fun nextCalendarMonth() { _calendarMonth.value = _calendarMonth.value.plusMonths(1) }

    /** 日历月（自然月）的全部账单行 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val calendarRows: StateFlow<List<TransactionRow>?> = _calendarMonth.flatMapLatest { month ->
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        txDao.observeRows(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 本期「实际支出」= 未被标记「已退款」的支出之和。
     * 统计页 `02` 的总支出用它，与首页「本月支出」是同一个数。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val periodSpendingTotal: StateFlow<Long?> = periodRange.flatMapLatest { (start, end) ->
        txDao.observeSpendingTotal(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accounts: StateFlow<List<AccountEntity>> =
        accountDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accountBalances: Flow<List<AccountBalanceRow>> = txDao.observeBalances()

    /** 首页"总存款"预览：全部账户（初始金额 + 交易净额）合计 */
    val totalDepositCents: StateFlow<Long?> = accountBalances
        .map { rows -> rows.sumOf { it.initialBalanceCents + it.signedSumCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 交易 ----------

    fun saveTransaction(
        id: Long,
        type: TxType,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        timestamp: Long,
        note: String,
        /** 商户/收款方：通知捕获自带，手动记录可自定义（账单列表标题优先显示它） */
        counterparty: String = "",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (id > 0) {
                txDao.getById(id)?.let { old ->
                    txDao.update(
                        old.copy(
                            type = type, amountCents = amountCents, categoryId = categoryId,
                            accountId = accountId, timestamp = timestamp, note = note,
                            counterparty = counterparty,
                        )
                    )
                    // 编辑的是捕获来的交易 → 学习这次归类（"越用越准"；手动记账不学习）
                    if (old.source != TxSource.MANUAL) {
                        MerchantRules.learn(merchantRuleDao, type, categoryId, accountId, counterparty, note)
                    }
                }
            } else {
                txDao.insert(
                    TransactionEntity(
                        type = type, amountCents = amountCents, categoryId = categoryId,
                        accountId = accountId, timestamp = timestamp, note = note,
                        counterparty = counterparty,
                    )
                )
            }
        }
    }

    fun deleteTransaction(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteById(id)
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    // ---------- 批量删除（账单页：多选 / 按月 / 按年 / 全部） ----------

    suspend fun countTransactionsInRange(startMillis: Long, endMillis: Long): Int =
        withContext(Dispatchers.IO) { txDao.countByRange(startMillis, endMillis) }

    suspend fun countAllTransactions(): Int =
        withContext(Dispatchers.IO) { txDao.countAll() }

    fun deleteTransactionsByIds(ids: Collection<Long>, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteByIds(ids.toList())
                AppLog.i("tx", "batch deleted ${ids.size} by ids")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "batch delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    fun deleteTransactionsInRange(startMillis: Long, endMillis: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteByRange(startMillis, endMillis)
                AppLog.i("tx", "batch deleted in range [$startMillis, $endMillis)")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "range delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    fun deleteAllTransactions(onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteAll()
                AppLog.i("tx", "all transactions deleted")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "delete all failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    suspend fun transactionById(id: Long): TransactionEntity? =
        withContext(Dispatchers.IO) { txDao.getById(id) }

    // ---------- 账户 ----------

    /**
     * 保存账户。[kind] 区分存款 / 信用卡；只有信用卡才有额度与共用额度组，
     * 改成存款时会自动清掉额度与组关系。
     * `isDefault = true` 会把它设为唯一默认账户。
     */
    fun saveAccount(
        id: Long,
        name: String,
        initialBalanceCents: Long,
        note: String,
        creditLimitCents: Long = 0L,
        sharedGroupId: Long = 0L,
        isDefault: Boolean = false,
        kind: AccountKind = AccountKind.CASH,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isDefault) accountDao.clearDefaults()
                val limit = if (kind == AccountKind.CREDIT) creditLimitCents.coerceAtLeast(0L) else 0L
                val group = if (kind == AccountKind.CREDIT) sharedGroupId.coerceAtLeast(0L) else 0L
                if (id > 0) {
                    accountDao.getById(id)?.let { old ->
                        accountDao.update(
                            old.copy(
                                name = name, initialBalanceCents = initialBalanceCents, note = note,
                                kind = kind,
                                creditLimitCents = limit,
                                sharedGroupId = group,
                                isDefault = isDefault || old.isDefault,
                            ),
                        )
                        // 退出共用额度组：同组其余卡一并回到独立状态
                        if (old.sharedGroupId != 0L && group == 0L) {
                            accountDao.getAll()
                                .filter { it.sharedGroupId == old.sharedGroupId && it.id != old.id }
                                .forEach { accountDao.update(it.copy(sharedGroupId = 0L)) }
                        }
                    }
                } else {
                    accountDao.insert(
                        AccountEntity(
                            name = name, initialBalanceCents = initialBalanceCents, note = note,
                            kind = kind,
                            creditLimitCents = limit,
                            sharedGroupId = group,
                            isDefault = isDefault,
                        ),
                    )
                }
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("account", "save failed", e)
                withContext(Dispatchers.Main) { onResult("保存失败") }
            }
        }
    }

    /** 把某个账户设为唯一默认账户（记账页优先选中它） */
    fun setDefaultAccount(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                accountDao.clearDefaults()
                accountDao.markDefault(id)
                AppLog.i("account", "default=$id")
            } catch (e: Exception) {
                AppLog.e("account", "set default failed", e)
            }
        }
    }

    /**
     * 共用额度组：两张（或多张）信用卡共用同一个欠款额度。
     * [withAccountId] = null 表示退出共用组；否则把两张卡合并到同一组（组 id = 较小的账户 id）。
     * 组内额度取成员里最大的额度，避免共用后上限突然变小。
     */
    fun setSharedCreditGroup(accountId: Long, withAccountId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = accountDao.getAll()
                val a = all.firstOrNull { it.id == accountId } ?: return@launch
                if (withAccountId == null) {
                    // 退出：同组其余成员一并退出，避免留下半组数据
                    val group = a.sharedGroupId
                    all.filter { group != 0L && it.sharedGroupId == group }
                        .forEach { accountDao.update(it.copy(sharedGroupId = 0L)) }
                    return@launch
                }
                val b = all.firstOrNull { it.id == withAccountId } ?: return@launch
                val groupId = minOf(a.id, b.id)
                val limit = maxOf(a.creditLimitCents, b.creditLimitCents)
                listOf(a, b).forEach { accountDao.update(it.copy(sharedGroupId = groupId, creditLimitCents = limit)) }
                AppLog.i("account", "shared credit group #$groupId: ${a.id} + ${b.id}")
            } catch (e: Exception) {
                AppLog.e("account", "set shared group failed", e)
            }
        }
    }

    fun deleteAccount(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val used = txDao.countByAccount(id)
            if (used > 0) {
                withContext(Dispatchers.Main) { onResult("该账户下有 $used 笔交易，无法删除") }
            } else {
                accountDao.deleteById(id)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    // ---------- 分类 ----------

    /** 保存分类（新增或改名 / 换图标 / 改类型 / 挂到一级分类下）；icon 规格见 [CategoryIcon] */
    fun saveCategory(
        id: Long,
        name: String,
        type: TxType,
        icon: String,
        parentId: Long? = null,
        onResult: (savedId: Long, error: String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                withContext(Dispatchers.Main) { onResult(0L, "请输入名称") }
                return@launch
            }
            try {
                val all = categoryDao.getAll()
                val current = all.firstOrNull { it.id == id }
                val parent = parentId?.let { pid -> all.firstOrNull { it.id == pid } }
                // 只支持两级：二级分类的父级必须是一级分类
                if (parentId != null) {
                    when {
                        parent == null || parent.parentId != null -> {
                            withContext(Dispatchers.Main) { onResult(0L, "只能挂在一级分类下") }
                            return@launch
                        }
                        parent.type != type -> {
                            withContext(Dispatchers.Main) { onResult(0L, "二级分类必须与一级分类同类型") }
                            return@launch
                        }
                        id == parentId -> {
                            withContext(Dispatchers.Main) { onResult(0L, "不能选择自己作为上级") }
                            return@launch
                        }
                        id > 0 && all.any { it.parentId == id } -> {
                            withContext(Dispatchers.Main) { onResult(0L, "该分类下已有二级分类，不能再改为二级") }
                            return@launch
                        }
                    }
                }
                // 「其他」是删除分类时的兜底，改名/改类型会让兜底失效（只限制一级）
                if (current != null && current.name == "其他" && current.parentId == null &&
                    (trimmed != "其他" || type != current.type)
                ) {
                    withContext(Dispatchers.Main) { onResult(0L, "「其他」不能改名或修改类型") }
                    return@launch
                }
                // 同层级下不允许同名（不同一级分类下可以有同名二级分类）
                if (all.any {
                        it.id != id && it.type == type && it.parentId == parentId && it.name.trim() == trimmed
                    }
                ) {
                    withContext(Dispatchers.Main) { onResult(0L, "已存在同名分类") }
                    return@launch
                }
                val savedId = if (id > 0) {
                    current?.let { old ->
                        // 换图标时清掉旧的图片文件，避免内部存储越攒越多
                        if (old.icon != icon) {
                            CategoryIcon.deleteImage(getApplication(), CategoryIcon.imageOf(old.icon))
                        }
                        categoryDao.update(old.copy(name = trimmed, type = type, icon = icon, parentId = parentId))
                        // 一级分类改类型时，其二级分类跟着改，避免出现类型不一致的子树
                        if (old.parentId == null && old.type != type) {
                            all.filter { it.parentId == old.id }.forEach {
                                categoryDao.update(it.copy(type = type))
                            }
                        }
                    }
                    id
                } else {
                    val nextOrder = (all.filter { it.type == type && it.parentId == parentId }
                        .maxOfOrNull { it.sortOrder } ?: -1) + 1
                    categoryDao.insert(
                        CategoryEntity(
                            name = trimmed, type = type, icon = icon,
                            sortOrder = nextOrder, parentId = parentId,
                        ),
                    )
                }
                withContext(Dispatchers.Main) { onResult(savedId, null) }
            } catch (e: Exception) {
                AppLog.e("category", "save failed", e)
                withContext(Dispatchers.Main) { onResult(0L, "保存失败") }
            }
        }
    }

    /** 分类排序：在同级（同一父级、同一类型）内上移 / 下移一位 */
    fun moveCategory(id: Long, up: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = categoryDao.getAll()
                val target = all.firstOrNull { it.id == id } ?: return@launch
                val siblings = all
                    .filter { it.parentId == target.parentId && it.type == target.type }
                    .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                    .toMutableList()
                // 先把 sortOrder 归一化成下标，避免历史数据全是 0 时移动无效
                siblings.forEachIndexed { index, c ->
                    if (c.sortOrder != index) categoryDao.updateSortOrder(c.id, index)
                }
                val from = siblings.indexOfFirst { it.id == id }
                val to = if (up) from - 1 else from + 1
                if (from < 0 || to !in siblings.indices) return@launch
                categoryDao.updateSortOrder(siblings[from].id, to)
                categoryDao.updateSortOrder(siblings[to].id, from)
                AppLog.i("category", "move #$id ${if (up) "up" else "down"}")
            } catch (e: Exception) {
                AppLog.e("category", "move failed", e)
            }
        }
    }

    /** 删除分类：其下流水改挂到同类型的「其他」；「其他」本身不可删；一级分类连带删除其二级分类 */
    fun deleteCategory(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = categoryDao.getAll()
                val target = all.firstOrNull { it.id == id }
                if (target == null) {
                    withContext(Dispatchers.Main) { onResult("分类不存在") }
                    return@launch
                }
                if (target.name == "其他" && target.parentId == null) {
                    withContext(Dispatchers.Main) { onResult("「其他」不能删除") }
                    return@launch
                }
                val targets = listOf(target) + all.filter { it.parentId == target.id }
                targets.forEach { item ->
                    var fallback = all.firstOrNull {
                        it.type == item.type && it.name == "其他" && it.parentId == null
                    }
                    if (fallback == null) {
                        val newId = categoryDao.insert(CategoryEntity(name = "其他", type = item.type))
                        fallback = categoryDao.getAll().firstOrNull { it.id == newId }
                    }
                    if (fallback != null) {
                        txDao.reassignCategory(item.id, fallback.id)
                        categoryDao.deleteById(item.id)
                        CategoryIcon.deleteImage(getApplication(), CategoryIcon.imageOf(item.icon))
                        setCategoryBudget(item.id, 0L) // 分类删除时同步清理其分类预算
                        setCategoryBudgetPercent(item.id, 0)
                    }
                }
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("category", "delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    // ---------- 设置：分类预算（金额 / 比例两种方案，全局开关；首页"分类剩余预算"的数据源） ----------

    /** 全局方案：金额（月金额）或比例（占月预算百分比，见 [CategoryBudgetMath]） */
    private val _categoryBudgetMode = MutableStateFlow(
        if (prefs.getString(KEY_CAT_BUDGET_MODE, null) == MODE_PERCENT) {
            CategoryBudgetMode.PERCENT
        } else {
            CategoryBudgetMode.AMOUNT
        },
    )
    val categoryBudgetMode: StateFlow<CategoryBudgetMode> = _categoryBudgetMode

    private fun loadCategoryBudgetAmounts(): Map<Long, Long> = prefs.all.entries
        .mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_CAT_BUDGET_PREFIX)) return@mapNotNull null
            val id = key.removePrefix(KEY_CAT_BUDGET_PREFIX).toLongOrNull() ?: return@mapNotNull null
            val cents = (value as? Long) ?: return@mapNotNull null
            if (cents > 0) id to cents else null
        }
        .toMap()

    private fun loadCategoryBudgetPercents(): Map<Long, Int> = prefs.all.entries
        .mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_CAT_BUDGET_PCT_PREFIX)) return@mapNotNull null
            val id = key.removePrefix(KEY_CAT_BUDGET_PCT_PREFIX).toLongOrNull() ?: return@mapNotNull null
            val bp = (value as? Int) ?: return@mapNotNull null
            if (bp > 0) id to bp else null
        }
        .toMap()

    private val _categoryBudgetAmounts = MutableStateFlow(loadCategoryBudgetAmounts())
    private val _categoryBudgetPercents = MutableStateFlow(loadCategoryBudgetPercents())

    /** 各分类预算的比例（万分比，1% = 100；只含已设置项）——比例方案下列表/编辑框显示它 */
    val categoryBudgetPercents: StateFlow<Map<Long, Int>> = _categoryBudgetPercents

    /** 当前会计月单独设置的分类预算（null = 该月没有独立设置） */
    private val currentMonthPlan: StateFlow<MonthBudgetPlan?> = combine(currentPeriod, _budgetPlans) { period, plans ->
        plans[YearMonth.from(period.start)]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 各分类预算的**生效月金额**（分）：金额方案 = 全局金额叠加当前月的独立设置；
     * 比例方案按当前月预算折算。首页分类环、统计页饼图与"还可分配"都读它。
     */
    val categoryBudgets: StateFlow<Map<Long, Long>> = combine(
        _categoryBudgetAmounts, _categoryBudgetPercents, _categoryBudgetMode, monthlyBudgetCents, currentMonthPlan,
    ) { amounts, percents, mode, monthly, plan ->
        if (mode == CategoryBudgetMode.AMOUNT) {
            if (plan == null || plan.categoryCents.isEmpty()) amounts else amounts + plan.categoryCents
        } else {
            percents.mapValues { (_, bp) -> CategoryBudgetMath.centsOf(bp, monthly) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), effectiveCategoryBudgets())

    private fun effectiveCategoryBudgets(): Map<Long, Long> =
        if (_categoryBudgetMode.value == CategoryBudgetMode.AMOUNT) {
            val month = YearMonth.from(MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value).start)
            val own = _budgetPlans.value[month]?.categoryCents.orEmpty()
            _categoryBudgetAmounts.value + own
        } else {
            val monthly = effectiveMonthlyBudget()
            _categoryBudgetPercents.value.mapValues { (_, bp) -> CategoryBudgetMath.centsOf(bp, monthly) }
        }

    /**
     * 金额方案：cents ≤ 0 表示清除该分类预算。
     * 同时写入**当前会计月**的独立预算（功能5：每月可单独设置），并保留为全局默认值。
     */
    fun setCategoryBudget(categoryId: Long, cents: Long) {
        val editor = prefs.edit()
        if (cents > 0) {
            editor.putLong(KEY_CAT_BUDGET_PREFIX + categoryId, cents)
        } else {
            editor.remove(KEY_CAT_BUDGET_PREFIX + categoryId)
        }
        editor.apply()
        _categoryBudgetAmounts.value = loadCategoryBudgetAmounts()
        setCategoryBudgetFor(currentMonth(), categoryId, cents)
        AppLog.i("settings", "categoryBudget[$categoryId]=${Money.formatYuan(cents)}")
    }

    /** 当前会计月标签（按今天的会计月首日计算） */
    fun currentMonth(): YearMonth =
        YearMonth.from(MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value).start)

    /** 比例方案：bp（万分比）≤ 0 表示清除该分类预算 */
    fun setCategoryBudgetPercent(categoryId: Long, bp: Int) {
        val v = bp.coerceIn(0, CategoryBudgetMath.MAX_PERCENT)
        val editor = prefs.edit()
        if (v > 0) {
            editor.putInt(KEY_CAT_BUDGET_PCT_PREFIX + categoryId, v)
        } else {
            editor.remove(KEY_CAT_BUDGET_PCT_PREFIX + categoryId)
        }
        editor.apply()
        _categoryBudgetPercents.value = loadCategoryBudgetPercents()
        AppLog.i("settings", "categoryBudgetPct[$categoryId]=${CategoryBudgetMath.formatPercent(v)}")
    }

    /**
     * 切换分类预算方案（全局）：切换时按当前月预算换算另一侧的值，
     * 保证两种方案的读数始终一致（月预算未设置时无法折算，保持原值不动）。
     * 规则：切到比例 = 有金额的分类重算比例（金额侧是当前事实）；切到金额 = 有比例的分类重算金额。
     */
    fun setCategoryBudgetMode(mode: CategoryBudgetMode) {
        prefs.edit().putString(KEY_CAT_BUDGET_MODE, if (mode == CategoryBudgetMode.PERCENT) MODE_PERCENT else MODE_AMOUNT).apply()
        _categoryBudgetMode.value = mode
        val monthly = effectiveMonthlyBudget()
        if (monthly > 0L) {
            if (mode == CategoryBudgetMode.PERCENT) {
                val percents = _categoryBudgetPercents.value.toMutableMap()
                var changed = false
                _categoryBudgetAmounts.value.forEach { (id, cents) ->
                    val bp = CategoryBudgetMath.percentOf(cents, monthly)
                    if (bp != null && percents[id] != bp) {
                        percents[id] = bp
                        changed = true
                    }
                }
                if (changed) {
                    persistCategoryBudgetPercents(percents)
                    _categoryBudgetPercents.value = loadCategoryBudgetPercents()
                }
            } else {
                val amounts = _categoryBudgetAmounts.value.toMutableMap()
                var changed = false
                _categoryBudgetPercents.value.forEach { (id, bp) ->
                    val cents = CategoryBudgetMath.centsOf(bp, monthly)
                    if (cents > 0L && amounts[id] != cents) {
                        amounts[id] = cents
                        changed = true
                    }
                }
                if (changed) {
                    persistCategoryBudgetAmounts(amounts)
                    _categoryBudgetAmounts.value = loadCategoryBudgetAmounts()
                }
            }
        }
        AppLog.i("settings", "categoryBudgetMode=$mode")
    }

    private fun persistCategoryBudgetPercents(percents: Map<Long, Int>) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_CAT_BUDGET_PCT_PREFIX) }.forEach(editor::remove)
        percents.forEach { (id, bp) -> editor.putInt(KEY_CAT_BUDGET_PCT_PREFIX + id, bp) }
        editor.apply()
    }

    private fun persistCategoryBudgetAmounts(amounts: Map<Long, Long>) {
        val editor = prefs.edit()
        // 注意排除比例键："category_budget_pct_" 也以 "category_budget_" 开头，前缀匹配不能顺手删掉它
        prefs.all.keys
            .filter { it.startsWith(KEY_CAT_BUDGET_PREFIX) && !it.startsWith(KEY_CAT_BUDGET_PCT_PREFIX) }
            .forEach(editor::remove)
        amounts.forEach { (id, cents) -> editor.putLong(KEY_CAT_BUDGET_PREFIX + id, cents) }
        editor.apply()
    }

    // ---------- 账单导入 ----------

    private val _parsedBill = MutableStateFlow<ParsedBill?>(null)
    val parsedBill: StateFlow<ParsedBill?> = _parsedBill

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    private val _importDone = MutableStateFlow<ImportCommitResult?>(null)
    val importDone: StateFlow<ImportCommitResult?> = _importDone

    fun parseBillFile(uriString: String) {
        _parsedBill.value = null
        _importError.value = null
        _importDone.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<AccBookApp>()
                val bytes = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取所选文件")
                val bill = BillParsers.parse(bytes)
                    ?: throw IllegalArgumentException("无法识别账单格式，请选择微信/支付宝导出的账单 CSV")
                _parsedBill.value = bill
            } catch (e: Exception) {
                AppLog.e("import", "parse failed", e)
                _importError.value = e.message ?: "解析失败"
            }
        }
    }

    fun commitImport(bill: ParsedBill, accountId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cats = categoryDao.getAll()
                val entities = bill.rows.map { row ->
                    // 退款收入一律归「退款」分类：导入时按商户名猜出的购物/餐饮对退款行没有意义
                    val categoryId = (if (row.refundMark == RefundMark.REFUND_INCOME) {
                        RefundCategory.incomeCategoryId(cats)
                    } else {
                        cats.firstOrNull { it.type == row.type && it.name == row.guessedCategoryName }
                            ?.id
                            ?: cats.firstOrNull { it.type == row.type && it.name == "其他" }?.id
                            ?: cats.firstOrNull { it.type == row.type }?.id
                    })
                        ?: error("缺少${if (row.type == TxType.EXPENSE) "支出" else "收入"}分类，请先在分类管理中添加")
                    TransactionEntity(
                        type = row.type,
                        amountCents = row.amountCents,
                        categoryId = categoryId,
                        accountId = accountId,
                        timestamp = row.timestamp,
                        note = row.note,
                        counterparty = row.counterparty,
                        source = when (bill.source) {
                            BillSource.WECHAT -> TxSource.WECHAT
                            BillSource.ALIPAY -> TxSource.ALIPAY
                        },
                        externalId = row.externalId,
                        refundMark = row.refundMark,
                        // 导入的退款账单视为「已处理」：账单本身已经把原支出标成「已退款」，
                        // 不需要用户再指认，也不进「待确认账单」。
                        refundPending = false,
                    )
                }
                val ids = txDao.insertAllIgnore(entities)
                val inserted = ids.count { it != -1L }
                val duplicated = ids.count { it == -1L }
                AppLog.i("import", "committed: inserted=$inserted duplicated=$duplicated")
                withContext(Dispatchers.Main) { _importDone.value = ImportCommitResult(inserted, duplicated) }
            } catch (e: Exception) {
                AppLog.e("import", "commit failed", e)
                withContext(Dispatchers.Main) { _importError.value = e.message ?: "导入失败" }
            }
        }
    }

    fun clearImportState() {
        _parsedBill.value = null
        _importError.value = null
        _importDone.value = null
    }

    // ---------- 自动记账（阶段二：通知监听通道） ----------

    val pendingList: StateFlow<List<PendingTransactionEntity>> =
        pendingDao.observePending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 「待确认账单」里的退款待处理条目：已入账的退款收入，等用户选被退款的支出或「记为收入」。
     * 与捕获待确认条目**并进同一页**，不单独分区（用户要求）。
     */
    val pendingRefunds: StateFlow<List<TransactionRow>> =
        txDao.observePendingRefunds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 待处理总数 = 捕获待确认 + 退款待处理。
     * 个人页「待确认」胶囊与设置页「等待处理」都用它，别再各算一套。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingCount: StateFlow<Int> =
        combine(pendingDao.observeCount(), txDao.observePendingRefundCount()) { captures, refunds ->
            captures + refunds
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 捕获日志通道筛选：null = 全部（通知 + 页面合并） */
    val captureFilter = MutableStateFlow<CaptureChannel?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val captureRows: StateFlow<List<CapturedNotificationEntity>> = captureFilter
        .flatMapLatest { channel ->
            if (channel == null) captureDao.observeRecent(300)
            else captureDao.observeRecentBySource(channel, 300)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setCaptureFilter(channel: CaptureChannel?) {
        captureFilter.value = channel
    }

    val listenerConnected: StateFlow<Boolean> = CaptureCenter.connected
    val a11yConnected: StateFlow<Boolean> = CaptureCenter.a11yConnected
    val lastCaptureAt: StateFlow<Long> = CaptureCenter.lastEventAt

    private val _captureEnabled = MutableStateFlow(CaptureCenter.isCaptureEnabled())
    val captureEnabled: StateFlow<Boolean> = _captureEnabled

    fun setCaptureEnabled(enabled: Boolean) {
        CaptureCenter.setCaptureEnabled(enabled)
        _captureEnabled.value = enabled
    }

    private val _a11yCaptureEnabled = MutableStateFlow(CaptureCenter.isA11yCaptureEnabled())
    val a11yCaptureEnabled: StateFlow<Boolean> = _a11yCaptureEnabled

    fun setA11yCaptureEnabled(enabled: Boolean) {
        CaptureCenter.setA11yCaptureEnabled(enabled)
        _a11yCaptureEnabled.value = enabled
    }

    private val _debugCaptureAll = MutableStateFlow(CaptureCenter.isDebugCaptureAll(BuildConfig.DEBUG))
    val debugCaptureAll: StateFlow<Boolean> = _debugCaptureAll

    fun setDebugCaptureAll(enabled: Boolean) {
        CaptureCenter.setDebugCaptureAll(enabled)
        _debugCaptureAll.value = enabled
    }

    private val _debugCaptureAllPages = MutableStateFlow(CaptureCenter.isDebugCaptureAllPages(BuildConfig.DEBUG))
    val debugCaptureAllPages: StateFlow<Boolean> = _debugCaptureAllPages

    fun setDebugCaptureAllPages(enabled: Boolean) {
        CaptureCenter.setDebugCaptureAllPages(enabled)
        _debugCaptureAllPages.value = enabled
    }

    // ---------- 自动记账溯源（开发者功能，默认关） ----------

    private val _traceEnabled = MutableStateFlow(CaptureCenter.isTraceEnabled())
    val traceEnabled: StateFlow<Boolean> = _traceEnabled

    fun setTraceEnabled(enabled: Boolean) {
        CaptureCenter.setTraceEnabled(enabled)
        _traceEnabled.value = enabled
    }

    // ---------- 商户归类学习（越用越准） ----------

    /** 学到的规则（最近在前） */
    val merchantRules: StateFlow<List<com.controleco.app.data.db.MerchantRuleEntity>> =
        merchantRuleDao.observeRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteMerchantRule(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { merchantRuleDao.deleteById(id) }
    }

    fun clearMerchantRules() {
        viewModelScope.launch(Dispatchers.IO) { merchantRuleDao.clearAll() }
    }

    /** 溯源记录（最新在前，联查已带对应账单 id 与待确认状态） */
    val traceRows: StateFlow<List<CaptureTraceRow>> =
        traceDao.observeRecent(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val traceCount: StateFlow<Int> =
        traceDao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun clearTraces() {
        viewModelScope.launch(Dispatchers.IO) {
            traceDao.clearAll()
            AppLog.i("Trace", "capture traces cleared")
        }
    }

    fun isListenerGranted(): Boolean {
        val app = getApplication<AccBookApp>()
        return NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
    }

    /** 覆盖安装后系统可能静默关闭通知监听：权限页据此显示复查提示（监听重连后自动清除） */
    fun isReauthNeeded(): Boolean = CaptureCenter.isReauthNeeded()

    private val _refundLinkEnabled = MutableStateFlow(CaptureCenter.isRefundLinkEnabled())
    val refundLinkEnabled: StateFlow<Boolean> = _refundLinkEnabled

    fun setRefundLinkEnabled(enabled: Boolean) {
        CaptureCenter.setRefundLinkEnabled(enabled)
        _refundLinkEnabled.value = enabled
    }

    private val _captureNotifyEnabled = MutableStateFlow(CaptureCenter.isCaptureNotifyEnabled())
    val captureNotifyEnabled: StateFlow<Boolean> = _captureNotifyEnabled

    fun setCaptureNotifyEnabled(enabled: Boolean) {
        CaptureCenter.setCaptureNotifyEnabled(enabled)
        _captureNotifyEnabled.value = enabled
    }

    private val _autoEntryEnabled = MutableStateFlow(CaptureCenter.isAutoEntryEnabled())
    val autoEntryEnabled: StateFlow<Boolean> = _autoEntryEnabled

    fun setAutoEntryEnabled(enabled: Boolean) {
        CaptureCenter.setAutoEntryEnabled(enabled)
        _autoEntryEnabled.value = enabled
        // 互斥：开启自动入账即关闭悬浮窗入账（CaptureCenter 侧已写入，这里同步 UI 状态）
        if (enabled) _overlayConfirmEnabled.value = false
    }

    // ---------- 银行动账（短信 / 银行 App 推送） ----------

    private val _bankPushEnabled = MutableStateFlow(CaptureCenter.isBankPushEnabled())
    val bankPushEnabled: StateFlow<Boolean> = _bankPushEnabled

    fun setBankPushEnabled(enabled: Boolean) {
        CaptureCenter.setBankPushEnabled(enabled)
        _bankPushEnabled.value = enabled
    }

    private val _smsCaptureEnabled = MutableStateFlow(CaptureCenter.isSmsCaptureEnabled())
    val smsCaptureEnabled: StateFlow<Boolean> = _smsCaptureEnabled

    fun setSmsCaptureEnabled(enabled: Boolean) {
        CaptureCenter.setSmsCaptureEnabled(enabled)
        _smsCaptureEnabled.value = enabled
    }

    /** 收短信权限（银行短信通道的前提） */
    fun isSmsPermissionGranted(): Boolean = CaptureCenter.hasSmsPermission()

    // ---------- 悬浮确认卡 ----------

    private val _overlayConfirmEnabled = MutableStateFlow(CaptureCenter.isOverlayConfirmEnabled())
    val overlayConfirmEnabled: StateFlow<Boolean> = _overlayConfirmEnabled

    fun setOverlayConfirmEnabled(enabled: Boolean) {
        CaptureCenter.setOverlayConfirmEnabled(enabled)
        _overlayConfirmEnabled.value = enabled
        // 互斥：开启悬浮窗入账即关闭自动入账（CaptureCenter 侧已写入，这里同步 UI 状态）
        if (enabled) _autoEntryEnabled.value = false
    }

    /** 悬浮窗权限（悬浮确认卡的前提；未授权自动回落通知） */
    fun isOverlayGranted(): Boolean = CaptureCenter.canDrawOverlays()

    /** 最近一次弹卡判定（"已弹出"或未弹原因）：权限页展示，排查"为什么没弹卡" */
    val overlayDecision: StateFlow<String?> = CaptureCenter.overlayDecision

    /** debug：弹一张样例确认卡（立即/延迟；不落库） */
    fun showOverlayTest(delayMs: Long = 0, onResult: (String) -> Unit) =
        CaptureCenter.showOverlayTest(delayMs, onResult)

    private val _autoUpdateEnabled = MutableStateFlow(AppUpdate.isAutoUpdateEnabled(getApplication()))
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled

    fun setAutoUpdateEnabled(enabled: Boolean) {
        AppUpdate.setAutoUpdateEnabled(getApplication(), enabled)
        _autoUpdateEnabled.value = enabled
    }

    /** 手动检查更新（设置页「检查更新」）：无视开关与间隔；发现新版本走全局弹窗，无更新/失败经回调提示 */
    fun checkUpdateNow(onResult: (String?) -> Unit) = AppUpdate.checkNow(getApplication(), onResult)

    /** 通知权限（安卓 13+ 运行时） */
    fun canPostNotifications(): Boolean = CaptureCenter.canPostNotifications()

    /** debug：20 秒后发测试捕获通知 */
    fun scheduleNotifyTest(onResult: (String) -> Unit) = CaptureCenter.scheduleNotifyTest(onResult)

    // ---------- 登记退款（无金额退款通知 → 选一笔被退款的账单） ----------

    /** 近 60 天还没标「已退款」的支出候选 */
    suspend fun refundCandidates(): List<TransactionRow> = withContext(Dispatchers.IO) {
        txDao.recentUnrefundedExpenses(System.currentTimeMillis() - 60L * 24 * 3_600_000)
    }

    /**
     * 登记退款：生成一条退款收入（REFUND_INCOME，金额可改以支持部分退款，入原账户），
     * 并把用户选中的那笔支出标成 REFUNDED_EXPENSE（不再计入支出）。
     *
     * **两笔记录之间不建立任何关联**：支出只是"被标了已退款"，退款收入只是"一笔收入"。
     * 生成的退款收入 `refundPending = false`，不会进「待确认账单」。
     */
    fun registerRefund(expenseId: Long, amountCents: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val expense = txDao.getById(expenseId)
                when {
                    expense == null -> { onDone("原支出记录不存在"); return@launch }
                    expense.refundMark != RefundMark.NONE -> { onDone("该笔支出已标记退款"); return@launch }
                    amountCents <= 0 -> { onDone("退款金额需大于 0"); return@launch }
                }
                val cats = categoryDao.getAll()
                // 退款收入归「退款」分类（不是收入「其他」）：账单里一眼能看出这是退回来的钱
                val incomeCatId = RefundCategory.incomeCategoryId(cats)
                    ?: error("缺少收入分类，请先在分类管理中添加")
                txDao.insert(
                    TransactionEntity(
                        type = TxType.INCOME,
                        amountCents = amountCents,
                        categoryId = incomeCatId,
                        accountId = expense.accountId,
                        timestamp = System.currentTimeMillis(),
                        note = "退款收入（手动登记）",
                        counterparty = expense.counterparty,
                        source = TxSource.NOTIFICATION,
                        refundMark = RefundMark.REFUND_INCOME,
                        refundPending = false,
                    ),
                )
                txDao.markExpenseRefunded(expenseId)
                AppLog.i("refund", "registered refund for expense #$expenseId amount=${Money.formatYuan(amountCents)}")
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                AppLog.e("refund", "register failed", e)
                withContext(Dispatchers.Main) { onDone("登记失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    // ---------- 待处理退款（已入账的退款收入，只等用户处理） ----------

    /** 同金额的支出候选：待处理退款点开后列出来给用户选 */
    suspend fun refundExpenseCandidates(amountCents: Long): List<TransactionRow> =
        withContext(Dispatchers.IO) { txDao.expenseCandidatesByAmount(amountCents) }

    /**
     * 处理一笔待处理的退款：**把用户选中的那笔支出标成「已退款」**（不再计入支出），
     * 退款自己则标成「已处理」（`refundPending = 0`，不再出现在待确认里）。
     *
     * 两笔记录之间**不写任何关联**：这就是两个独立的标记。支出统计的口径是
     * 「未退款的支出之和」，所以标记一打上，这笔钱就不再计入本月支出与分类预算。
     *
     * 校验：退款必须存在、是退款收入且还没处理；原支出必须存在、是支出且还没标过已退款。
     */
    fun handlePendingRefund(refundId: Long, expenseId: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val refund = txDao.getById(refundId)
                val expense = txDao.getById(expenseId)
                if (refund == null || refund.refundMark != RefundMark.REFUND_INCOME ||
                    !refund.refundPending || expense == null || expense.type != TxType.EXPENSE
                ) {
                    val message = when {
                        refund == null -> "退款记录不存在"
                        refund.refundMark != RefundMark.REFUND_INCOME -> "该记录不是退款收入"
                        !refund.refundPending -> "该退款已处理过"
                        expense == null -> "原支出记录不存在"
                        else -> "只能选择支出账单"
                    }
                    withContext(Dispatchers.Main) { onDone(message) }
                    return@launch
                }
                if (expense.refundMark != RefundMark.NONE) {
                    withContext(Dispatchers.Main) { onDone("该支出已标记过已退款") }
                    return@launch
                }
                txDao.markExpenseRefunded(expenseId)
                txDao.clearRefundPending(refundId)
                AppLog.i("refund", "refund #$refundId handled, expense #$expenseId marked refunded")
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                AppLog.e("refund", "handle pending refund failed", e)
                withContext(Dispatchers.Main) { onDone("处理失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * 「记为收入」：这笔退款没有对应的支出账单，只按普通收入处理。
     * 清掉退款标记后它不再出现在待处理里；原支出保持原样（照常计入支出）。
     */
    fun markRefundAsIncome(refundId: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val refund = txDao.getById(refundId)
                if (refund == null || refund.refundMark != RefundMark.REFUND_INCOME) {
                    withContext(Dispatchers.Main) { onDone("该记录不是退款收入") }
                    return@launch
                }
                txDao.markRefundAsIncome(refundId)
                AppLog.i("refund", "refund #$refundId kept as plain income")
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                AppLog.e("refund", "mark refund as income failed", e)
                withContext(Dispatchers.Main) { onDone("操作失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /** 系统无障碍设置中是否已开启采集服务 */
    fun isA11yEnabled(): Boolean = AccBookAccessibilityService.isEnabled(getApplication())

    /** 系统随选朗读/TalkBack 是否已开启（微信 8.0.52+ 页面节点白名单的前提） */
    fun isScreenReaderEnabled(): Boolean = ScreenReaderAccess.isEnabled(getApplication())

    fun discardPending(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            PendingActions.discard(pendingDao, id)
        }
    }

    // ---------- 金额待补录 ----------

    private val _amountReplyEnabled = MutableStateFlow(CaptureCenter.isAmountReplyEnabled())
    val amountReplyEnabled: StateFlow<Boolean> = _amountReplyEnabled

    fun setAmountReplyEnabled(enabled: Boolean) {
        CaptureCenter.setAmountReplyEnabled(enabled)
        _amountReplyEnabled.value = enabled
    }

    /**
     * 待确认列表里补录金额（0 元占位条）：重算去重键；同笔完整捕获已到则占位条退场。
     * 回调带说明文本（失败/已处理），供 UI 提示。
     */
    fun setPendingAmount(id: Long, amountCents: Long, onDone: (String?) -> Unit = {}) {
        CaptureCenter.applyPendingAmount(id, amountCents) { result ->
            val message = when (result) {
                is CaptureCenter.AmountUpdateResult.Ok -> null
                CaptureCenter.AmountUpdateResult.NotFound -> "这条已处理过（同笔的完整记录已到）"
                CaptureCenter.AmountUpdateResult.Invalid -> "金额无效，请重新输入"
            }
            onDone(message)
        }
    }

    fun confirmPending(
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String = item.counterparty,
    ) {
        // 金额待补条（0 元）不能直接入账：先在列表里补金额
        if (item.amountCents <= 0) {
            AppLog.w("pending", "confirm rejected (amount missing) #${item.id}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            PendingActions.confirm(db, txDao, pendingDao, merchantRuleDao, item, categoryId, accountId, counterparty)
        }
    }

    fun confirmAllPending() {
        viewModelScope.launch(Dispatchers.IO) {
            // 只确认金额完整且非 ASK 的条目：ASK（微信红包待确认）必须由用户逐条明确决定，
            // 不能被「全部确认」批量入账；金额待补的也必须逐条补录或丢弃。
            val items = pendingDao.getAllPending().filter {
                it.status == com.controleco.app.data.db.PendingStatus.PENDING && it.amountCents > 0
            }
            if (items.isEmpty()) return@launch
            val cats = categoryDao.getAll()
            val accs = accountDao.getAll()
            val entities = items.map { item ->
                val cat = cats.firstOrNull {
                    it.id == CaptureDefaults.categoryId(
                        cats, item.type, item.counterparty.ifBlank { item.note },
                        isRefund = item.refundMark == RefundMark.REFUND_INCOME,
                    )
                }
                    ?: return@launch
                TransactionEntity(
                    type = item.type,
                    amountCents = item.amountCents,
                    categoryId = cat.id,
                    accountId = CaptureDefaults.accountId(
                        accs,
                        item.sourcePackage,
                        CaptureDefaults.bankNameOf(item.sourcePackage, item.ruleName),
                        instrumentLabel = item.paymentInstrument,
                    ) ?: return@launch,
                    timestamp = item.timestamp,
                    note = item.note.ifBlank { item.ruleName },
                    counterparty = item.counterparty,
                    source = CaptureDefaults.txSource(item.sourcePackage),
                    externalId = "NTF:${item.dedupKey}",
                    refundMark = item.refundMark,
                    // 批量确认的退款收入同样要指认原支出
                    refundPending = item.refundMark == RefundMark.REFUND_INCOME,
                    flowId = item.flowId,
                )
            }
            val inserted = db.withTransaction {
                val ids = txDao.insertAllIgnore(entities)
                items.forEach { pendingDao.deleteById(it.id) }
                ids.count { it != -1L }
            }
            AppLog.i("pending", "confirmAll: ${items.size} items, inserted=$inserted")
        }
    }

    /** 待确认确认弹窗的默认分类（退款收入固定「退款」；其余为该类型的"其他"，缺省取该类型第一个） */
    fun guessCategoryId(type: TxType, merchantHint: String = "", isRefund: Boolean = false): Long? =
        CaptureDefaults.categoryId(categories.value, type, merchantHint, isRefund = isRefund)

    /**
     * 按来源推测账户：微信→微信零钱、支付宝→支付宝、银行侧（短信/银行推送）→ 先按银行名匹配
     * 账户名，再退化到"银行"关键词。[ruleName] 供号码兜底的短信取回银行名用。
     */
    fun guessAccountId(sourcePackage: String, ruleName: String = "", instrument: String? = null): Long? =
        CaptureDefaults.accountId(
            accounts.value, sourcePackage, CaptureDefaults.bankNameOf(sourcePackage, ruleName),
            instrumentLabel = instrument,
        )

    fun clearCaptureLog() {
        viewModelScope.launch(Dispatchers.IO) {
            captureDao.clearAll()
            AppLog.i("Ntf", "capture log cleared")
        }
    }

    // ---------- 资产：净资产 / 存款 / 负债（功能1） ----------

    /**
     * 资产总览：净资产 = 存款 − 负债。
     * 余额 ≥ 0 计入存款，余额 < 0 计入负债（信用卡欠款、透支等）。
     */
    val assetSummary: StateFlow<AssetSummary?> = combine(accounts, accountBalances) { accs, rows ->
        val byId = rows.associateBy { it.accountId }
        AssetMath.summarize(
            accs.map { account ->
                val row = byId[account.id]
                AssetAccountSnapshot(
                    id = account.id,
                    sharedGroupId = account.sharedGroupId,
                    initialBalanceCents = row?.initialBalanceCents ?: account.initialBalanceCents,
                    flowCents = row?.signedSumCents ?: 0L,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 存款目标与存款分析（功能7） ----------

    private val _depositGoalCents = MutableStateFlow(prefs.getLong(KEY_DEPOSIT_GOAL, 0L))

    /** 每月存款目标（分）：0 = 未设置 */
    val depositGoalCents: StateFlow<Long> = _depositGoalCents

    fun setDepositGoal(cents: Long) {
        val v = cents.coerceAtLeast(0L)
        prefs.edit().putLong(KEY_DEPOSIT_GOAL, v).apply()
        _depositGoalCents.value = v
        AppLog.i("settings", "depositGoal=${Money.formatYuan(v)}")
    }

    private val _depositMonthGoals = MutableStateFlow(loadDepositMonthGoals())

    /** 按月存款目标（分）：没单独设置的月份回落到 [depositGoalCents] */
    val depositMonthGoals: StateFlow<Map<YearMonth, Long>> = _depositMonthGoals

    /** 某个会计月实际生效的存款目标（分），0 = 未设置 */
    fun depositGoalFor(month: YearMonth): Long =
        _depositMonthGoals.value[month] ?: _depositGoalCents.value

    /** 设置单月存款目标：0 = 清除该月设置，回落到默认目标 */
    fun setDepositGoalFor(month: YearMonth, cents: Long) {
        val v = cents.coerceAtLeast(0L)
        val key = KEY_DEPOSIT_MONTH_GOAL_PREFIX + month
        val editor = prefs.edit()
        if (v > 0L) editor.putLong(key, v) else editor.remove(key)
        editor.apply()
        _depositMonthGoals.value = loadDepositMonthGoals()
        AppLog.i("settings", "depositGoal[$month]=${Money.formatYuan(v)}")
    }

    private fun loadDepositMonthGoals(): Map<YearMonth, Long> =
        prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_DEPOSIT_MONTH_GOAL_PREFIX)) return@mapNotNull null
            val month = runCatching {
                YearMonth.parse(key.removePrefix(KEY_DEPOSIT_MONTH_GOAL_PREFIX))
            }.getOrNull()
            val cents = (value as? Long)?.takeIf { it > 0L }
            if (month != null && cents != null) month to cents else null
        }.toMap()

    /** 近 12 个会计月的账单行：存款趋势与每月新增存款的数据源 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val depositRows: StateFlow<List<TransactionRow>?> = currentPeriod.flatMapLatest { period ->
        val endMonth = YearMonth.from(period.start)
        val start = endMonth.minusMonths(11).atDay(1)
        val end = endMonth.plusMonths(1).atDay(1)
        txDao.observeRows(
            start.atStartOfDay(zone).toInstant().toEpochMilli(),
            end.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当月实际新增存款 = 当月收入 − 当月（未退款）支出 */
    val currentMonthSaving: StateFlow<DepositMath.MonthSaving?> = combine(periodRows, currentPeriod) { rows, period ->
        rows?.let { DepositMath.rangeSaving(it, period.start, period.endExclusive, zone) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 含当月的连续 12 个月存款趋势（缺数据的月补 0） */
    val depositTrend: StateFlow<List<DepositMath.MonthSaving>?> = combine(depositRows, currentPeriod) { rows, period ->
        rows?.let {
            DepositMath.trendEndingAt(
                DepositMath.monthlySavings(it, zone),
                YearMonth.from(period.start),
                12,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 二级分类统计口径（功能3） ----------

    private val _subcategoryRollupMode = MutableStateFlow(
        SubcategoryRollupMode.of(prefs.getString(KEY_SUBCAT_ROLLUP, null)),
    )

    /** 月支出按一级分类统计，还是把同名二级分类与一级分类合并 */
    val subcategoryRollupMode: StateFlow<SubcategoryRollupMode> = _subcategoryRollupMode

    fun setSubcategoryRollupMode(mode: SubcategoryRollupMode) {
        prefs.edit().putString(KEY_SUBCAT_ROLLUP, mode.name).apply()
        _subcategoryRollupMode.value = mode
        AppLog.i("settings", "subcategoryRollup=${mode.name}")
    }

    /** 按当前口径把分类支出折算到一级分类（统计页圆环、首页分类预算共用） */
    fun rollupCategorySums(
        sums: List<CategorySumRow>,
        categories: List<CategoryEntity>,
    ): List<CategorySumRow> = CategoryTree.rollup(sums, categories, _subcategoryRollupMode.value)

    // ---------- 分期 · 周期记账（功能2） ----------

    val installmentPlans: StateFlow<List<InstallmentPlanEntity>> =
        planDao.observeInstallments()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recurringPlans: StateFlow<List<RecurringPlanEntity>> =
        planDao.observeRecurring()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveInstallment(
        id: Long,
        title: String,
        accountId: Long,
        categoryId: Long,
        totalPrincipalCents: Long,
        totalInterestCents: Long,
        periods: Int,
        firstDateMillis: Long,
        deductAsset: Boolean,
        excludeAssetPeriods: String = "",
        postTimeMinutes: Int = 540,
        note: String = "",
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (title.isBlank()) { mainResult(onResult, "请输入名称"); return@launch }
                if (totalPrincipalCents <= 0L) { mainResult(onResult, "请输入本金"); return@launch }
                if (periods !in InstallmentMath.MIN_PERIODS..InstallmentMath.MAX_PERIODS) {
                    mainResult(onResult, "期数需在 1~${InstallmentMath.MAX_PERIODS} 之间"); return@launch
                }
                if (accountId <= 0L || categoryId <= 0L) { mainResult(onResult, "请选择账户和分类"); return@launch }
                val oldPlan = if (id > 0) planDao.getInstallment(id) else null
                val entity = InstallmentPlanEntity(
                    id = id,
                    title = title.trim(),
                    accountId = accountId,
                    categoryId = categoryId,
                    totalPrincipalCents = totalPrincipalCents.coerceAtLeast(0L),
                    totalInterestCents = totalInterestCents.coerceAtLeast(0L),
                    periods = periods,
                    firstDateMillis = firstDateMillis,
                    deductAsset = deductAsset,
                    excludeAssetPeriods = InstallmentMath.encodeExcludedPeriods(
                        InstallmentMath.parseExcludedPeriods(excludeAssetPeriods, periods),
                        periods,
                    ),
                    postTimeMinutes = postTimeMinutes.coerceIn(0, 24 * 60 - 1),
                    note = note,
                    postedPeriods = oldPlan?.postedPeriods ?: 0,
                    createdAt = oldPlan?.createdAt ?: System.currentTimeMillis(),
                )
                if (id > 0) planDao.updateInstallment(entity) else planDao.insertInstallment(entity)
                PlanAutoPoster.postDue(db, zone = zone)
                PlanScheduler.schedule(getApplication(), db)
                mainResult(onResult, null)
            } catch (e: Exception) {
                AppLog.e("plan", "save installment failed", e)
                mainResult(onResult, "保存失败")
            }
        }
    }

    fun saveRecurring(
        id: Long,
        title: String,
        type: TxType,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        frequency: RecurringFrequency,
        firstDateMillis: Long,
        deductAsset: Boolean,
        intervalCount: Int = 1,
        postTimeMinutes: Int = 540,
        endMode: RecurringEndMode = RecurringEndMode.NEVER,
        endCount: Int = 0,
        endDateMillis: Long = 0L,
        excludeAssetPeriods: String = "",
        autoPost: Boolean = false,
        note: String = "",
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (title.isBlank()) { mainResult(onResult, "请输入名称"); return@launch }
                if (amountCents <= 0L) { mainResult(onResult, "请输入金额"); return@launch }
                if (categoryId <= 0L || accountId <= 0L) { mainResult(onResult, "请选择账户和分类"); return@launch }
                if (frequency == RecurringFrequency.EVERY_N_DAYS &&
                    intervalCount !in 1..3650
                ) {
                    mainResult(onResult, "间隔天数需在 1~3650 之间"); return@launch
                }
                if (endMode == RecurringEndMode.COUNT && endCount !in 1..InstallmentMath.MAX_PERIOD_INDEX) {
                    mainResult(onResult, "结束次数需在 1~${InstallmentMath.MAX_PERIOD_INDEX} 之间"); return@launch
                }
                if (endMode == RecurringEndMode.DATE && endDateMillis <= 0L) {
                    mainResult(onResult, "请选择结束日期"); return@launch
                }
                val old = if (id > 0) planDao.getRecurring(id) else null
                val first = RecurringMath.dateOf(firstDateMillis, zone)
                val firstAt = RecurringMath.millisAtMinutes(first, postTimeMinutes, zone)
                val next = when {
                    old == null -> firstAt
                    old.nextDateMillis >= RecurringMath.NO_NEXT -> RecurringMath.NO_NEXT
                    // 还没记过 → 用新的首期；已记过 → 保留待出账的那一天，只按新出账时间重挂
                    old.postedCount == 0 -> firstAt
                    else -> RecurringMath.millisAtMinutes(
                        RecurringMath.dateOf(old.nextDateMillis, zone),
                        postTimeMinutes.coerceIn(0, 24 * 60 - 1),
                        zone,
                    )
                }
                val entity = RecurringPlanEntity(
                    id = id,
                    title = title.trim(),
                    type = type,
                    amountCents = amountCents.coerceAtLeast(0L),
                    categoryId = categoryId,
                    accountId = accountId,
                    frequency = frequency.name,
                    intervalCount = if (frequency == RecurringFrequency.EVERY_N_DAYS) {
                        intervalCount.coerceIn(1, 3650)
                    } else {
                        1
                    },
                    firstDateMillis = firstDateMillis,
                    nextDateMillis = next,
                    deductAsset = deductAsset,
                    autoPost = autoPost,
                    postTimeMinutes = postTimeMinutes.coerceIn(0, 24 * 60 - 1),
                    endMode = endMode.name,
                    endCount = if (endMode == RecurringEndMode.COUNT) {
                        endCount.coerceIn(1, InstallmentMath.MAX_PERIOD_INDEX)
                    } else {
                        0
                    },
                    endDateMillis = if (endMode == RecurringEndMode.DATE) endDateMillis else 0L,
                    postedCount = old?.postedCount ?: 0,
                    excludeAssetPeriods = InstallmentMath.encodeExcludedPeriods(
                        InstallmentMath.parseExcludedPeriods(
                            excludeAssetPeriods, InstallmentMath.MAX_PERIOD_INDEX,
                        ),
                        InstallmentMath.MAX_PERIOD_INDEX,
                    ),
                    note = note,
                    createdAt = old?.createdAt ?: System.currentTimeMillis(),
                )
                if (id > 0) planDao.updateRecurring(entity) else planDao.insertRecurring(entity)
                PlanAutoPoster.postDue(db, zone = zone)
                PlanScheduler.schedule(getApplication(), db)
                mainResult(onResult, null)
            } catch (e: Exception) {
                AppLog.e("plan", "save recurring failed", e)
                mainResult(onResult, "保存失败")
            }
        }
    }

    fun deleteInstallment(id: Long, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { planDao.deleteInstallment(id) }
                .onSuccess { mainResult(onDone, null) }
                .onFailure {
                    AppLog.e("plan", "delete installment failed", it)
                    mainResult(onDone, "删除失败")
                }
        }
    }

    fun deleteRecurring(id: Long, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { planDao.deleteRecurring(id) }
                .onSuccess { mainResult(onDone, null) }
                .onFailure {
                    AppLog.e("plan", "delete recurring failed", it)
                    mainResult(onDone, "删除失败")
                }
        }
    }

    /**
     * 记下一期分期：金额 = 该期本金 + 该期利息；日期按首期日期逐月顺延。
     * `deductAsset = false` 时写入 `excludeFromAssets`（只记账、不扣资产）。
     */
    fun postInstallment(id: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val plan = planDao.getInstallment(id) ?: run { mainResult(onDone, "计划不存在"); return@launch }
                if (plan.postedPeriods >= plan.periods) { mainResult(onDone, "该计划已全部记完"); return@launch }
                val index = plan.postedPeriods + 1
                val firstDate = RecurringMath.dateOf(plan.firstDateMillis, zone)
                val dueAt = InstallmentMath.periodMillis(firstDate, index, plan.postTimeMinutes, zone)
                val amount = InstallmentMath.periodTotal(
                    plan.totalPrincipalCents, plan.totalInterestCents, plan.periods, index,
                )
                if (amount <= 0L) { mainResult(onDone, "该期金额为 0"); return@launch }
                if (System.currentTimeMillis() < dueAt) {
                    val dueDate = InstallmentMath.periodDate(firstDate, index)
                    mainResult(
                        onDone,
                        "未到出账时间（${dueDate.monthValue}/${dueDate.dayOfMonth} " +
                            "${InstallmentMath.timeText(plan.postTimeMinutes)} 自动入账）",
                    )
                    return@launch
                }
                // 与自动入账共用同一把唯一键：重复点击 / 闹钟并发都不会写出两笔
                val key = "PLAN_INST:${plan.id}:$index"
                if (txDao.findIdByExternalId(key) == null) {
                    txDao.insert(
                        TransactionEntity(
                            type = PLAN_TX_TYPE,
                            amountCents = amount,
                            categoryId = plan.categoryId,
                            accountId = plan.accountId,
                            timestamp = dueAt,
                            note = plan.note.ifBlank { "${plan.title} 第${index}/${plan.periods}期" },
                            counterparty = plan.title,
                            source = TxSource.MANUAL,
                            externalId = key,
                            excludeFromAssets = InstallmentMath.excludesAsset(
                                plan.deductAsset, plan.excludeAssetPeriods, plan.periods, index,
                            ),
                        ),
                    )
                }
                planDao.updateInstallment(plan.copy(postedPeriods = index))
                AppLog.i("plan", "installment #$id posted period $index")
                mainResult(onDone, null)
            } catch (e: Exception) {
                AppLog.e("plan", "post installment failed", e)
                mainResult(onDone, "记账失败")
            }
        }
    }

    /** 记下一笔周期账单（按计划的下次日期入账，然后顺延下次日期） */
    fun postRecurring(id: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val plan = planDao.getRecurring(id) ?: run { mainResult(onDone, "计划不存在"); return@launch }
                if (plan.nextDateMillis >= RecurringMath.NO_NEXT) {
                    mainResult(onDone, "该计划已结束"); return@launch
                }
                val index = plan.postedCount + 1
                val dueAt = plan.nextDateMillis
                if (System.currentTimeMillis() < dueAt) {
                    val dueDate = RecurringMath.dateOf(dueAt, zone)
                    mainResult(
                        onDone,
                        "未到出账时间（${dueDate.monthValue}/${dueDate.dayOfMonth} " +
                            "${InstallmentMath.timeText(plan.postTimeMinutes)} 自动入账）",
                    )
                    return@launch
                }
                val date = RecurringMath.dateOf(dueAt, zone)
                // 与自动入账共用同一把唯一键，重复点击不会重复入账
                val key = "PLAN_REC:${plan.id}:$index"
                if (txDao.findIdByExternalId(key) == null) {
                    txDao.insert(
                        TransactionEntity(
                            type = plan.type,
                            amountCents = plan.amountCents,
                            categoryId = plan.categoryId,
                            accountId = plan.accountId,
                            timestamp = dueAt,
                            note = plan.note.ifBlank { plan.title },
                            counterparty = plan.title,
                            source = TxSource.MANUAL,
                            externalId = key,
                            excludeFromAssets = InstallmentMath.excludesAssetAt(
                                plan.deductAsset, plan.excludeAssetPeriods, index,
                            ),
                        ),
                    )
                }
                val frequency = RecurringFrequency.of(plan.frequency)
                val endMode = RecurringEndMode.of(plan.endMode)
                val next = RecurringMath.next(date, frequency, plan.intervalCount)
                val ended = RecurringMath.endedAfter(
                    endMode, plan.endCount, plan.endDateMillis, index, next, zone,
                )
                planDao.updateRecurring(
                    plan.copy(
                        postedCount = index,
                        nextDateMillis = if (ended) {
                            RecurringMath.NO_NEXT
                        } else {
                            RecurringMath.millisAtMinutes(next, plan.postTimeMinutes, zone)
                        },
                    ),
                )
                AppLog.i("plan", "recurring #$id posted, next=$next")
                mainResult(onDone, null)
            } catch (e: Exception) {
                AppLog.e("plan", "post recurring failed", e)
                mainResult(onDone, "记账失败")
            }
        }
    }

    // ---------- 备份还原（功能2） ----------

    /** 导出全部账单 + 分类（含二级）+ 分期/周期 + 设置到 SAF 文件 */
    fun exportBackup(uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = BackupStore.exportJson(getApplication(), db, prefs)
                getApplication<AccBookApp>().contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法写入所选文件")
                AppLog.i("backup", "exported ${text.length} chars")
                mainResult(onDone, null)
            } catch (e: Exception) {
                AppLog.e("backup", "export failed", e)
                mainResult(onDone, e.message ?: "导出失败")
            }
        }
    }

    /** 导出 CSV 账单（Excel 可直接打开，含账户、一二级分类、账单、预算、存款与设置） */
    fun exportBackupCsv(uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = BackupCsv.exportCsv(db, prefs)
                getApplication<AccBookApp>().contentResolver.openOutputStream(uri)?.use {
                    // 带 BOM，Excel 双击打开中文不乱码
                    it.write(("\uFEFF" + text).toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法写入所选文件")
                AppLog.i("backup", "exported csv ${text.length} chars")
                mainResult(onDone, null)
            } catch (e: Exception) {
                AppLog.e("backup", "csv export failed", e)
                mainResult(onDone, e.message ?: "导出失败")
            }
        }
    }

    /** 从备份文件恢复：清空当前账单/分类/账户/计划后重建，并恢复设置（JSON / CSV / XLSX 自动识别） */
    fun importBackup(uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getApplication<AccBookApp>().contentResolver.openInputStream(uri)?.use {
                    it.readBytes()
                } ?: throw IllegalStateException("无法读取所选文件")
                val count = BackupStore.importFile(getApplication(), db, prefs, bytes)
                // 设置可能变了（月首日 / 货币 / 预算），重新载入内存状态
                reloadSettingsFromPrefs()
                AppLog.i("backup", "restored $count transactions")
                mainResult(onDone, null)
            } catch (e: Exception) {
                AppLog.e("backup", "import failed", e)
                mainResult(onDone, e.message ?: "恢复失败")
            }
        }
    }

    private fun reloadSettingsFromPrefs() {
        _monthStartDay.value = MonthPeriod.clampStartDay(prefs.getInt(KEY_MONTH_START_DAY, 1))
        _currencySymbol.value = prefs.getString(KEY_CURRENCY_SYMBOL, Money.DEFAULT_SYMBOL)
            ?: Money.DEFAULT_SYMBOL
        Money.symbol = _currencySymbol.value
        _monthlyBudgetAmount.value = prefs.getLong(KEY_MONTHLY_BUDGET, 0L)
        _dailyBudgetAmount.value = prefs.getLong(KEY_DAILY_BUDGET, 0L)
        _dailyBudgetMode.value = prefs.getBoolean(KEY_DAILY_BUDGET_MODE, false)
        _budgetPlans.value = loadBudgetPlans()
        _categoryBudgetAmounts.value = loadCategoryBudgetAmounts()
        _categoryBudgetPercents.value = loadCategoryBudgetPercents()
        _depositGoalCents.value = prefs.getLong(KEY_DEPOSIT_GOAL, 0L)
        _depositMonthGoals.value = loadDepositMonthGoals()
        _homeQuickAddEnabled.value = prefs.getBoolean(KEY_HOME_QUICK_ADD, true)
        _subcategoryRollupMode.value = SubcategoryRollupMode.of(prefs.getString(KEY_SUBCAT_ROLLUP, null))
    }

    private suspend fun mainResult(callback: (String?) -> Unit, message: String?) {
        withContext(Dispatchers.Main) { callback(message) }
    }

    // ---------- 首页显示开关（功能4） ----------

    private val _homeQuickAddEnabled = MutableStateFlow(prefs.getBoolean(KEY_HOME_QUICK_ADD, true))

    /** 首页「记一笔」按钮是否显示（只影响首页，账单页的 + 不受影响） */
    val homeQuickAddEnabled: StateFlow<Boolean> = _homeQuickAddEnabled

    fun setHomeQuickAddEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HOME_QUICK_ADD, enabled).apply()
        _homeQuickAddEnabled.value = enabled
        AppLog.i("settings", "homeQuickAdd=$enabled")
    }

    // ---------- 首页资产：共用额度组显示哪张卡 ----------

    private val _assetCardPick = MutableStateFlow(loadAssetCardPick())

    /** 共用额度组 → 首页展示的那张卡（组内流水同步，资产只算一次） */
    val assetCardPick: StateFlow<Map<Long, Long>> = _assetCardPick

    fun setAssetCardPick(groupId: Long, accountId: Long) {
        if (groupId <= 0L || accountId <= 0L) return
        val next = _assetCardPick.value.toMutableMap()
        next[groupId] = accountId
        prefs.edit()
            .putString(KEY_ASSET_CARD_PICK, next.entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()
        _assetCardPick.value = next
        AppLog.i("settings", "assetCard group=$groupId account=$accountId")
    }

    private fun loadAssetCardPick(): Map<Long, Long> {
        val raw = prefs.getString(KEY_ASSET_CARD_PICK, null) ?: return emptyMap()
        return raw.split(',')
            .mapNotNull { token ->
                val parts = token.split(':')
                if (parts.size != 2) return@mapNotNull null
                val group = parts[0].toLongOrNull()
                val account = parts[1].toLongOrNull()
                if (group != null && account != null && group > 0L && account > 0L) {
                    group to account
                } else {
                    null
                }
            }
            .toMap()
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_CARD_IMAGE = "card_image_uri"
        const val KEY_CARD_TRANSFORM = "card_image_transform"
        const val KEY_CARD_COLOR = "card_image_color"
        const val KEY_CARD_LUMINANCE = "card_image_luminance"
        const val KEY_MONTHLY_BUDGET = "monthly_budget_cents"
        const val KEY_MONTH_START_DAY = "month_start_day"
        const val KEY_DAILY_BUDGET_MODE = "budget_daily_mode"
        const val KEY_DAILY_BUDGET = "daily_budget_cents"
        const val KEY_CURRENCY_SYMBOL = "currency_symbol"
        const val KEY_CAT_BUDGET_PREFIX = "category_budget_"
        const val KEY_CAT_BUDGET_PCT_PREFIX = "category_budget_pct_"
        const val KEY_CAT_BUDGET_MODE = "category_budget_mode"
        const val KEY_BUDGET_PLAN_PREFIX = "budget_plan_"
        const val KEY_DEPOSIT_GOAL = "deposit_goal_cents"
        const val KEY_DEPOSIT_MONTH_GOAL_PREFIX = "deposit_goal_month_"
        const val KEY_SUBCAT_ROLLUP = "subcategory_rollup_mode"
        const val KEY_HOME_QUICK_ADD = "home_quick_add_enabled"
        const val KEY_ASSET_CARD_PICK = "asset_card_pick"
        const val MODE_PERCENT = "percent"
        const val MODE_AMOUNT = "amount"
    }
}
