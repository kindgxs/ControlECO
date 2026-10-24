package com.controleco.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.ecoBottomContentPadding

/**
 * 备份还原（功能2）：导出 / 导入完整的本机数据。
 *
 * 导出内容：账户、一级 + 二级分类、全部账单、分期与周期计划、设置（预算、货币、主题等）。
 * 导入会覆盖本机现有账单与设置，所以先二次确认。
 */
@Composable
fun BackupScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    var pendingImport by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportBackup(uri) { err ->
            EcoToast.show(err ?: "备份已导出", if (err == null) EcoPillTone.Good else EcoPillTone.Bad)
        }
    }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.importBackup(uri) { err ->
            EcoToast.show(err ?: "已还原备份", if (err == null) EcoPillTone.Good else EcoPillTone.Bad)
        }
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(micro = "BACKUP / RESTORE", title = "备份还原", onBack = onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = ecoBottomContentPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "actions") {
                EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        EcoRow(
                            title = "导出备份",
                            subtitle = "账单 + 一二级分类 + 分期周期 + 设置",
                            icon = EcoIcons.Download,
                            onClick = { exporter.launch("controleco-backup.json") },
                            showChevron = true,
                        )
                        EcoRowDivider()
                        EcoRow(
                            title = "导入还原",
                            subtitle = "选择之前导出的 JSON 备份文件",
                            icon = EcoIcons.File,
                            onClick = { pendingImport = true },
                            showChevron = true,
                        )
                    }
                }
            }
            item(key = "note") {
                EcoPageNote(
                    "备份文件保存在你选择的目录里，只包含账单、分类、分期周期计划与设置。" +
                        "导入会覆盖本机现有数据，建议先导出一份当前备份。",
                )
            }
            item(key = "space") { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (pendingImport) {
        EcoConfirm(
            title = "导入还原",
            body = "导入会清空本机现有账单、分类、分期周期计划与设置，然后写入备份内容。确定继续？",
            confirmText = "选择文件",
            danger = true,
            onConfirm = {
                pendingImport = false
                importer.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
            onDismiss = { pendingImport = false },
        )
    }
}
