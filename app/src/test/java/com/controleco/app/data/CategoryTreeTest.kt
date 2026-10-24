package com.controleco.app.data

import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 二级分类树与统计口径的单测（功能3）。 */
class CategoryTreeTest {

    private fun category(
        id: Long,
        name: String,
        type: TxType = TxType.EXPENSE,
        sortOrder: Int = 0,
        parentId: Long? = null,
    ) = CategoryEntity(id = id, name = name, type = type, sortOrder = sortOrder, parentId = parentId)

    private val categories = listOf(
        category(1, "购物", sortOrder = 0),
        category(2, "交通", sortOrder = 1),
        category(3, "交通", sortOrder = 0, parentId = 1),
        category(4, "公交", sortOrder = 0, parentId = 2),
        category(5, "工资", TxType.INCOME, sortOrder = 0),
        category(6, "其他", sortOrder = 2),
    )

    private fun sum(id: Long, name: String, cents: Long) =
        CategorySumRow(categoryId = id, categoryName = name, totalCents = cents)

    @Test
    fun `topLevel 只返回一级分类并按 sortOrder 排序`() {
        assertEquals(
            listOf("购物", "交通", "其他"),
            CategoryTree.topLevel(categories, TxType.EXPENSE).map { it.name },
        )
        assertEquals(listOf("工资"), CategoryTree.topLevel(categories, TxType.INCOME).map { it.name })
    }

    @Test
    fun `childrenOf 只返回直属子分类`() {
        assertEquals(listOf(3L), CategoryTree.childrenOf(categories, 1).map { it.id })
        assertEquals(listOf(4L), CategoryTree.childrenOf(categories, 2).map { it.id })
    }

    @Test
    fun `flatten 把二级分类紧跟在所属一级分类之后`() {
        assertEquals(
            listOf(1L, 3L, 2L, 4L, 6L),
            CategoryTree.flatten(categories, TxType.EXPENSE).map { it.id },
        )
    }

    @Test
    fun `displayName 二级分类带上级名称`() {
        assertEquals("购物 / 交通", CategoryTree.displayName(categories, 3))
        assertEquals("交通", CategoryTree.displayName(categories, 2))
        assertEquals("", CategoryTree.displayName(categories, 999))
    }

    @Test
    fun `按一级分类口径_二级分类金额全部并回所属一级`() {
        val rolled = CategoryTree.rollup(
            sums = listOf(sum(3, "交通", 50_000), sum(2, "交通", 150_000)),
            categories = categories,
            mode = SubcategoryRollupMode.BY_PARENT,
        )
        assertEquals(2, rolled.size)
        assertEquals(150_000L, rolled.first { it.categoryId == 2L }.totalCents)
        assertEquals(50_000L, rolled.first { it.categoryId == 1L }.totalCents)
        // 并回后只保留一级分类名称
        assertEquals(setOf("交通", "购物"), rolled.map { it.categoryName }.toSet())
    }

    @Test
    fun `同名合并_购物下的交通并入一级分类交通_总额2000`() {
        val rolled = CategoryTree.rollup(
            sums = listOf(sum(3, "交通", 50_000), sum(2, "交通", 150_000)),
            categories = categories,
            mode = SubcategoryRollupMode.MERGE_SAME_NAME,
        )
        assertEquals(1, rolled.size)
        assertEquals(2L, rolled.first().categoryId)
        assertEquals("交通", rolled.first().categoryName)
        assertEquals(200_000L, rolled.first().totalCents)
    }

    @Test
    fun `同名合并_不同名的二级分类仍并回所属一级_不单独占比`() {
        val rolled = CategoryTree.rollup(
            sums = listOf(sum(4, "公交", 30_000), sum(2, "交通", 150_000)),
            categories = categories,
            mode = SubcategoryRollupMode.MERGE_SAME_NAME,
        )
        assertEquals(1, rolled.size)
        assertEquals(2L, rolled.first().categoryId)
        assertEquals(180_000L, rolled.first().totalCents)
    }

    @Test
    fun `未知分类原样保留_金额不会消失`() {
        val rolled = CategoryTree.rollup(
            sums = listOf(sum(99, "已删除", 12_345)),
            categories = categories,
            mode = SubcategoryRollupMode.BY_PARENT,
        )
        assertEquals(1, rolled.size)
        assertEquals(99L, rolled.first().categoryId)
        assertEquals(12_345L, rolled.first().totalCents)
    }

    @Test
    fun `零额分类不进入结果`() {
        val rolled = CategoryTree.rollup(
            sums = listOf(sum(2, "交通", 0)),
            categories = categories,
            mode = SubcategoryRollupMode.BY_PARENT,
        )
        assertEquals(0, rolled.size)
    }

    @Test
    fun `budgetOwnerId 二级分类的预算挂在所属一级分类`() {
        assertEquals(1L, CategoryTree.budgetOwnerId(categories, 3))
        assertEquals(2L, CategoryTree.budgetOwnerId(categories, 2))
        assertEquals(999L, CategoryTree.budgetOwnerId(categories, 999))
    }
}
