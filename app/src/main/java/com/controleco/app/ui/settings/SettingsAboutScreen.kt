package com.controleco.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.BuildConfig
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.SettingsDivider
import com.controleco.app.ui.common.SettingsNote
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SettingsPane
import com.controleco.app.ui.common.SettingsRow
import com.controleco.app.ui.common.SettingsSection
import com.controleco.app.ui.common.SettingsValueRow
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoHeroCard
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.theme.onHero
import com.controleco.app.ui.theme.onHeroChip
import com.controleco.app.ui.theme.onHeroMuted
import com.controleco.app.update.AppUpdate

/** 开源仓库（与个人页「更多」里的仓库行保持一致） */
private const val GITHUB_REPO_PATH = "ArrogHie/ControlECO"
private const val GITHUB_REPO_URL = "https://github.com/$GITHUB_REPO_PATH"

// 深色 hero 上的前景色：由主题推导（hero 渐变永远偏深，色相跟主题走）
private val HERO_INK: Color
    @Composable get() = MaterialTheme.eco.onHero
private val HERO_SUB: Color
    @Composable get() = MaterialTheme.eco.onHeroMuted
private val HERO_PILL_INK: Color
    @Composable get() = MaterialTheme.eco.onHeroChip

private data class LicenseEntry(val name: String, val license: String, val note: String)

private val LICENSES = listOf(
    LicenseEntry("Jetpack Compose", "Apache-2.0", "声明式 UI 工具包（androidx.compose.*）"),
    LicenseEntry("Material 3", "Apache-2.0", "Jetson 组件与主题（androidx.compose.material3）"),
    LicenseEntry("AndroidX Core / Activity", "Apache-2.0", "兼容库与 Activity Compose 集成"),
    LicenseEntry("AndroidX Lifecycle / Navigation", "Apache-2.0", "状态收集与单 Activity 导航"),
    LicenseEntry("Room", "Apache-2.0", "本地 SQLite 持久化（androidx.room）"),
    LicenseEntry("Kotlin / kotlinx.coroutines", "Apache-2.0", "语言运行时与协程调度"),
    LicenseEntry("JUnit 4", "EPL-1.0", "单元测试（仅测试构建使用）"),
)

/**
 * 设置 → 关于易控钱包：品牌 hero + 隐私承诺 + 版本/构建/仓库 + 独立仓库入口。
 *
 * 「检查更新」沿用全局更新流程：AppUpdate 发现新版本后写入 updateAvailable，
 * 由 Activity 级的提示框只做提示并跳转原仓库 Release 页面（不再应用内下载安装），
 * 本页不重复实现更新逻辑，只负责发起检查与结果提示。
 */
@Composable
fun SettingsAboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    val openRepo: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)))
        }.onFailure {
            EcoToast.show("没有可用的浏览器，仓库地址：$GITHUB_REPO_PATH", EcoPillTone.Warn)
        }
        Unit
    }

    SettingsPage(micro = "ABOUT", title = "关于易控钱包", onBack = onBack) {
        AboutHero()

        SettingsSection(title = "隐私与安全", aside = "LOCAL ONLY")
        SettingsPane {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                PrivacyBullet("只写本机数据库", "，不联网、不上传，无云端账号与第三方统计。")
                Spacer(Modifier.height(8.dp))
                PrivacyBullet("数据存于应用私有目录", "（Room / SQLite），卸载即清除，可被系统备份规则排除。")
                Spacer(Modifier.height(8.dp))
                PrivacyBullet(
                    "通知监听 / 无障碍 / 悬浮窗权限",
                    "只用于识别支付信息与弹出确认卡片，捕获内容仅在本机解析与留存。",
                )
                Spacer(Modifier.height(8.dp))
                PrivacyBullet("账单导入文件只在本机解析", "，识别与去重后写入本地账本。")
            }
        }

        SettingsSection(title = "版本", aside = "BUILD")
        SettingsPane {
            SettingsValueRow("版本号", "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            SettingsDivider()
            SettingsValueRow("构建类型", if (BuildConfig.DEBUG) "debug / 含调试开关" else "release")
            SettingsDivider()
            SettingsValueRow("开源仓库", "ArrogHie / ControlECO")
            SettingsDivider()
            SettingsRow(
                title = "检查更新",
                icon = EcoIcons.Refresh,
                iconTint = MaterialTheme.eco.blue,
                trail = if (checking) "检查中…" else "v${BuildConfig.VERSION_NAME}",
                trailColor = if (checking) MaterialTheme.eco.quiet else MaterialTheme.eco.muted,
                showChevron = !checking,
                onClick = {
                    if (!checking) {
                        checking = true
                        AppUpdate.checkNow(context) { message ->
                            checking = false
                            when {
                                message == null -> EcoToast.show("发现新版本，正在打开发布页…", EcoPillTone.Blue)
                                message.startsWith("检查失败") -> EcoToast.show(message, EcoPillTone.Warn)
                                else -> EcoToast.show(message, EcoPillTone.Good)
                            }
                        }
                    }
                },
            )
            SettingsDivider()
            SettingsRow(
                title = "开源许可",
                icon = EcoIcons.File,
                iconTint = MaterialTheme.eco.violet,
                showChevron = true,
                onClick = { showLicenses = true },
            )
        }

        SettingsPane(
            modifier = Modifier.padding(top = 12.dp),
            shape = RoundedCornerShape(14.dp),
            elevation = 6.dp,
        ) {
            SettingsRow(
                title = "在浏览器中打开仓库",
                subtitle = "github.com/$GITHUB_REPO_PATH",
                icon = EcoIcons.External,
                iconTint = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = openRepo,
            )
        }

        Spacer(Modifier.height(19.dp))
        Text(
            "•  MADE FOR ONE PERSON'S DAILY LEDGER  •",
            style = EcoType.MicroQuiet.copy(fontSize = 8.sp, letterSpacing = 1.04.sp),
            color = MaterialTheme.eco.quiet,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showLicenses) {
        EcoSheet(
            onDismiss = { showLicenses = false },
            title = "开源许可",
        ) {
            Column(Modifier.fillMaxWidth()) {
                LICENSES.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider(inset = 0.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = EcoType.RowTitle.copy(fontSize = 11.5.sp),
                                color = MaterialTheme.eco.ink,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                entry.note,
                                style = EcoType.RowSub.copy(fontSize = 8.5.sp),
                                color = MaterialTheme.eco.muted,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.license,
                            style = EcoType.Pill,
                            color = MaterialTheme.eco.quiet,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Spacer(Modifier.height(6.dp))
                EcoGhostButton(text = "关闭", onClick = { showLicenses = false }, height = 40.dp)
            }
        }
    }
}

// ---------- 品牌 hero ----------

@Composable
private fun AboutHero() {
    val c = MaterialTheme.eco
    EcoHeroCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(8.dp, 26.dp, 8.dp, 26.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(c.accentGradA, c.accentGradC)))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    EcoIcons.Leaf,
                    contentDescription = "易控钱包",
                    tint = c.onAccent,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "易控钱包",
                    style = EcoType.RowTitle.copy(
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.W800,
                    ),
                    color = HERO_INK,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "本地记账 / 账单导入 / 自动捕获",
                    style = EcoType.RowSub.copy(fontSize = 8.5.sp, lineHeight = 13.sp),
                    color = HERO_SUB,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(23.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = EcoType.Pill.copy(fontSize = 8.sp),
                    color = HERO_PILL_INK,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------- 隐私条目 ----------

@Composable
private fun PrivacyBullet(bold: String, rest: String) {
    val c = MaterialTheme.eco
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            EcoIcons.Check,
            contentDescription = null,
            tint = c.jade,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(13.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.W700, color = c.ink2)) { append(bold) }
                append(rest)
            },
            style = EcoType.RowSub.copy(fontSize = 9.sp, lineHeight = 15.sp),
            color = c.muted,
        )
    }
}
