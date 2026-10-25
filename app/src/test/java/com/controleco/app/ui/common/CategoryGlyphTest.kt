package com.controleco.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类图形库的完整性校验：图标选择器直接遍历 [Glyph.entries]，
 * 任何重名 / 反查失败都会让选择器出现空白或错图标。
 */
class CategoryGlyphTest {

    @Test
    fun `图形名唯一`() {
        val names = Glyph.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `图形名可精确反查`() {
        Glyph.entries.forEach { glyph ->
            assertEquals(glyph, glyphByName(glyph.name))
        }
    }

    @Test
    fun `未知图形名返回空`() {
        assertNull(glyphByName("NOT_A_GLYPH"))
        assertNull(glyphByName(""))
    }

    @Test
    fun `按名字取形稳定且总是落在图形库内`() {
        listOf("餐饮", "交通", "宠物", "绿植", "", "新分类🎉", " 空格 ").forEach { name ->
            val glyph = glyphForName(name)
            assertTrue(name, Glyph.entries.contains(glyph))
            assertEquals(name, glyph, glyphForName(name))
        }
    }

    @Test
    fun `已有分类的默认取形不变`() {
        assertEquals(Glyph.SVG_MEAL, glyphForName("餐饮"))
        assertEquals(Glyph.SVG_CAR, glyphForName("交通"))
        assertEquals(Glyph.HOUSE, glyphForName("房租"))
        assertEquals(Glyph.SVG_SALARY, glyphForName("工资"))
        assertEquals(Glyph.DOTS, glyphForName("其他"))
    }

    @Test
    fun `新增图形只进选择器_不影响哈希取形`() {
        // 扩充图形不参与按名字哈希取形：随机名字只会落到原有图形上
        val expanded = setOf(
            Glyph.COFFEE, Glyph.PET, Glyph.SPORT, Glyph.FLOWER, Glyph.GIFT, Glyph.MUSIC,
            Glyph.CAMERA, Glyph.PLANE, Glyph.BUS, Glyph.BIKE, Glyph.DROP, Glyph.BOLT,
            Glyph.GRAD_CAP, Glyph.LAPTOP, Glyph.LIPSTICK, Glyph.APPLE, Glyph.ICE_CREAM,
            Glyph.WINE, Glyph.SHIELD, Glyph.RECEIPT, Glyph.WRENCH, Glyph.BABY, Glyph.HEART,
            Glyph.CROWN, Glyph.BED, Glyph.PIZZA, Glyph.CAKE, Glyph.CANDY, Glyph.BREAD,
            Glyph.FISH, Glyph.CARROT, Glyph.LEAF, Glyph.UMBRELLA, Glyph.SHIRT, Glyph.SHOE,
            Glyph.GLASSES, Glyph.WATCH, Glyph.PHONE, Glyph.HEADPHONE, Glyph.TV, Glyph.TRAIN,
            Glyph.SHIP, Glyph.FUEL, Glyph.PARKING, Glyph.TICKET, Glyph.TROPHY, Glyph.TARGET,
            Glyph.CLOCK, Glyph.CALENDAR, Glyph.WALLET, Glyph.BANK, Glyph.COIN, Glyph.CARD,
            Glyph.PIGGY,
        )
        (0 until 400).forEach { i ->
            val name = "分类$i"
            assertTrue(name, glyphForName(name) !in expanded)
        }
    }
}
