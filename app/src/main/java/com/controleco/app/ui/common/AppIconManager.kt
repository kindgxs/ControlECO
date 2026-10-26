package com.controleco.app.ui.common

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.controleco.app.BuildConfig

/**
 * 应用图标管理：通过启用/禁用 Launcher activity-alias 实现换图标。
 *
 * 在 [com.controleco.app.AccBookApp] 启动时调用 [init]，保证当前选中的图标组件处于启用状态；
 * 切换图标后立即通过 [PackageManager.setComponentEnabledSetting] 生效，不需要重启应用。
 */
object AppIconManager {

    private const val PREFS = "eco_ui"
    private const val KEY = "app_icon_style"

    /** 所有支持的图标样式 */
    enum class Style(
        val id: String,
        val label: String,
        /** 对应 AndroidManifest 中的 activity-alias 短名 */
        val alias: String,
    ) {
        DEFAULT("default", "默认绿", ".LauncherDefault"),
        BLUE("blue", "蓝色", ".LauncherBlue"),
        AMBER("amber", "琥珀", ".LauncherAmber"),
        RED("red", "红色", ".LauncherRed"),
        DARK("dark", "深色", ".LauncherDark"),
        PURPLE("purple", "紫色", ".LauncherPurple"),
    }

    var current by mutableStateOf(Style.DEFAULT)
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val savedId = p.getString(KEY, Style.DEFAULT.id)
        val style = Style.entries.find { it.id == savedId } ?: Style.DEFAULT
        current = style
        apply(context, style)
    }

    fun set(context: Context, style: Style) {
        if (style == current) return
        current = style
        prefsOf(context).edit().putString(KEY, style.id).apply()
        apply(context, style)
    }

    private fun apply(context: Context, selected: Style) {
        val pm = context.applicationContext.packageManager
        val pkg = BuildConfig.APPLICATION_ID
        Style.entries.forEach { style ->
            val component = ComponentName(pkg, "$pkg${style.alias}")
            val enabled = style == selected
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            // 不要杀当前进程；系统会在下次 launcher 刷新时更新图标
            pm.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun prefsOf(context: Context): android.content.SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
}
