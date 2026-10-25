package com.controleco.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 强调色偏好：7 套主题色会改变强调色族、hero 渐变、主操作渐变，
 * 「纯白」还会额外把中性面（背景/卡片/分隔线）换成白与石墨；
 * 收支/警告/危险语义色永远不变。
 *
 * 另外支持**自定义主题色**：用户自选一个颜色，只取它的色相与饱和度，
 * 明度骨架沿用当前预设（见 `ecoColors`），保证对比度可预期。
 *
 * 用独立 SharedPreferences 保存，启动时在 [com.controleco.app.AccBookApp] 初始化；
 * 不放进 LedgerViewModel，避免为一个纯展示偏好污染账本数据层。
 */
object AccentThemeStore {
    private const val PREFS = "eco_ui"
    private const val KEY = "accent_theme"
    private const val KEY_CUSTOM = "accent_custom_color"

    var current: EcoAccent by mutableStateOf(EcoAccent.Tide)
        private set

    /** 自定义主题色；为 null 表示只用 [current] 预设 */
    var customBase: Color? by mutableStateOf(null)
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        current = EcoAccent.fromId(p.getString(KEY, EcoAccent.Tide.id))
        customBase = p.getInt(KEY_CUSTOM, 0).takeIf { it != 0 }?.let { Color(it) }
    }

    private fun prefsOf(context: Context): android.content.SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }

    fun set(context: Context, accent: EcoAccent) {
        current = accent
        customBase = null
        prefsOf(context).edit().putString(KEY, accent.id).remove(KEY_CUSTOM).apply()
    }

    /** 选中自定义颜色：以当前预设的明度骨架派生强调色族 */
    fun setCustom(context: Context, color: Color) {
        customBase = color
        prefsOf(context).edit().putInt(KEY_CUSTOM, color.toArgb()).apply()
    }

    fun clearCustom(context: Context) {
        customBase = null
        prefsOf(context).edit().remove(KEY_CUSTOM).apply()
    }
}
