package com.controleco.app.data

import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.TxType

/** 二级分类的统计口径（设置页可切换） */
enum class SubcategoryRollupMode(val label: String, val hint: String) {
    /** 全部二级分类都并回所属一级分类 */
    BY_PARENT("按一级分类", "二级分类的支出全部并回其所属一级分类"),
    /** 与某个一级分类同名的二级分类并到那个一级分类，其余仍并回所属一级分类 */
    MERGE_SAME_NAME("同名合并", "同名的二级分类与一级分类合并统计"),
    ;

    companion object {
        fun of(value: String?): SubcategoryRollupMode =
            entries.firstOrNull { it.name == value } ?: BY_PARENT
    }
}

/**
 * 分类树工具（纯函数，可单测）。
 *
 * 只支持两级：`parentId == null` 是一级分类，其余都必须指向一个一级分类。
 */
object CategoryTree {

    fun topLevel(categories: List<CategoryEntity>, type: TxType? = null): List<CategoryEntity> =
        categories
            .filter { it.parentId == null && (type == null || it.type == type) }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    fun childrenOf(categories: List<CategoryEntity>, parentId: Long): List<CategoryEntity> =
        categories
            .filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    /** 展示顺序：一级分类按 sortOrder，紧随其后的二级分类也按 sortOrder */
    fun flatten(categories: List<CategoryEntity>, type: TxType): List<CategoryEntity> =
        topLevel(categories, type).flatMap { top -> listOf(top) + childrenOf(categories, top.id) }

    /** 记账页可选的分类：一级 + 其二级（展开用） */
    fun selectable(categories: List<CategoryEntity>, type: TxType): List<CategoryEntity> =
        categories.filter { it.type == type }

    fun nameOf(categories: List<CategoryEntity>, id: Long?): String? =
        categories.firstOrNull { it.id == id }?.name

    /** 完整名称：「购物 / 交通」；一级分类只显示自己 */
    fun displayName(categories: List<CategoryEntity>, categoryId: Long): String {
        val byId = categories.associateBy { it.id }
        val cat = byId[categoryId] ?: return ""
        val parent = cat.parentId?.let { byId[it] } ?: return cat.name
        return "${parent.name} / ${cat.name}"
    }

    /**
     * 把按分类聚合的支出折算到统计口径。
     *
     * - BY_PARENT：二级分类的金额并入所属一级分类；
     * - MERGE_SAME_NAME：二级分类与某一级分类同名时并入那个一级分类，否则并入所属一级分类。
     *
     * 预算只挂在一级分类上，所以返回值里的 id / name 一定是一级分类的。
     * 未知分类（已删除等异常数据）原样保留，避免金额凭空消失。
     */
    fun rollup(
        sums: List<CategorySumRow>,
        categories: List<CategoryEntity>,
        mode: SubcategoryRollupMode,
    ): List<CategorySumRow> {
        val byId = categories.associateBy { it.id }
        val topByName = categories
            .filter { it.parentId == null && it.type == TxType.EXPENSE }
            .associateBy { it.name }
        val merged = LinkedHashMap<Long, CategorySumRow>()
        sums.forEach { sum ->
            if (sum.totalCents == 0L) return@forEach
            val cat = byId[sum.categoryId]
            val target: CategoryEntity? = when {
                cat == null -> null
                cat.parentId == null -> cat
                mode == SubcategoryRollupMode.MERGE_SAME_NAME -> topByName[cat.name] ?: byId[cat.parentId]
                else -> byId[cat.parentId]
            }
            if (target == null) {
                val existing = merged[sum.categoryId]
                merged[sum.categoryId] = CategorySumRow(
                    categoryId = sum.categoryId,
                    categoryName = sum.categoryName,
                    totalCents = (existing?.totalCents ?: 0L) + sum.totalCents,
                )
            } else {
                val existing = merged[target.id]
                merged[target.id] = CategorySumRow(
                    categoryId = target.id,
                    categoryName = target.name,
                    totalCents = (existing?.totalCents ?: 0L) + sum.totalCents,
                )
            }
        }
        return merged.values.sortedByDescending { it.totalCents }
    }

    /**
     * 某个分类的预算挂靠点：二级分类 → 所属一级分类；一级 → 自己。
     */
    fun budgetOwnerId(categories: List<CategoryEntity>, categoryId: Long): Long {
        val byId = categories.associateBy { it.id }
        val cat = byId[categoryId] ?: return categoryId
        return cat.parentId ?: cat.id
    }
}
