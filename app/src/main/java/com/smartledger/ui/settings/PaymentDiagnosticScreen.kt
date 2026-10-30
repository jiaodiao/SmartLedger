package com.smartledger.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartledger.service.PaymentDiagnosticStore
import com.smartledger.ui.components.SmartLedgerDialog
import com.smartledger.ui.theme.SmartLedgerColors

@Composable
fun PaymentDiagnosticScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember(context) { PaymentDiagnosticStore.observe(context.applicationContext) }
    val state by store.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SmartLedgerColors.bg),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = SmartLedgerColors.fg)
                }
                Text("最近识别详情", style = MaterialTheme.typography.titleLarge,
                    color = SmartLedgerColors.fg, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = state.entries.isNotEmpty()) { Text("清空") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(containerColor = SmartLedgerColors.surface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记录诊断信息", color = SmartLedgerColors.fg, fontWeight = FontWeight.Medium)
                        Text(if (state.enabled) "已开启，保留最近 30 条" else "已关闭，已有记录仍可查看",
                            style = MaterialTheme.typography.bodySmall, color = SmartLedgerColors.fgSecondary)
                    }
                    Switch(checked = state.enabled, onCheckedChange = {
                        runCatching { PaymentDiagnosticStore.setEnabled(context, it) }.onFailure {
                            Toast.makeText(context, "无法保存诊断设置，请检查存储空间", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        }
        item {
            Text("仅记录支付相关通知，原文保存在本机，不自动上传，也不进入账单备份。普通微信聊天不记录；无法明确支付来源的微信综合摘要不收录。复制前请检查并隐去隐私信息。\n\n“未提取到商家”不代表通知原文一定没有商家，请点开查看各字段。",
                modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall,
                color = SmartLedgerColors.fgSecondary)
        }
        if (state.entries.isEmpty()) {
            item {
                Text("暂无诊断记录\n开启后等待下一条支付通知，无法补回过去的通知。",
                    modifier = Modifier.padding(24.dp), color = SmartLedgerColors.fgSecondary)
            }
        }
        items(state.entries, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                .clickable { selectedId = entry.id },
                colors = CardDefaults.cardColors(containerColor = SmartLedgerColors.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${entry.timeLabel} · ${entry.source}", color = SmartLedgerColors.fg,
                        style = MaterialTheme.typography.titleSmall)
                    Text(entry.summary.ifBlank { "通知未提供正文" }, color = SmartLedgerColors.fgSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(buildString {
                        append(entry.status)
                        if (entry.amount != null) {
                            append(" · ").append(entry.category ?: "未产生分类结果")
                            if (entry.merchant.isNullOrBlank()) append(" · 未提取到商家")
                        }
                    }, color = SmartLedgerColors.fgSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    state.entries.find { it.id == selectedId }?.let { entry ->
        SmartLedgerDialog(
            onDismissRequest = { selectedId = null }, title = "识别详情",
            confirmText = "复制本条详情", onConfirm = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("支付通知识别详情", entry.detailText()))
                Toast.makeText(context, "已复制，请隐去隐私信息后再分享", Toast.LENGTH_SHORT).show()
            }, dismissText = "关闭", content = {
                SelectionContainer {
                    Text(entry.detailText(), modifier = Modifier.heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }
    if (confirmClear) {
        SmartLedgerDialog(onDismissRequest = { confirmClear = false }, title = "清空诊断记录",
            text = "清空本机诊断记录，不影响账单和诊断开关。", confirmText = "清空",
            onConfirm = {
                runCatching { PaymentDiagnosticStore.clear(context) }.onFailure {
                    Toast.makeText(context, "无法清空诊断记录，请重试", Toast.LENGTH_SHORT).show()
                }
                confirmClear = false
            })
    }
}
