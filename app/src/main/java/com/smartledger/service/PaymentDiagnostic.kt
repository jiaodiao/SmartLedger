package com.smartledger.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PaymentDiagnostic(
    val id: String,
    val receivedAt: Long,
    val source: String,
    val fields: Map<String, String>,
    val usedText: String,
    val status: String = "处理中",
    val reason: String = "",
    val amount: Double? = null,
    val type: String? = null,
    val merchant: String? = null,
    val method: String? = null,
    val category: String? = null
) {
    val timeLabel: String get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(receivedAt))
    val summary: String get() = fields["普通正文"].orEmpty().ifBlank { usedText }.replace('\n', ' ').take(80)

    fun detailText(): String = buildString {
        appendLine("收到时间：$timeLabel")
        appendLine("来源：$source")
        appendLine("\n通知提供的信息")
        fields.forEach { (name, value) -> appendLine("$name：${value.ifBlank { "未提供" }}") }
        appendLine("\n实际用于识别的文本：\n${usedText.ifBlank { "未提供" }}")
        appendLine("\n应用提取的结果")
        appendLine("金额：${amount?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "未识别"}")
        appendLine("收支类型：${when (type) { "income" -> "收入"; "expense" -> "支出"; else -> "未识别" }}")
        appendLine("商家：${merchant?.takeIf { it.isNotBlank() } ?: "未提取到商家"}")
        appendLine("支付渠道：${method ?: "未识别"}")
        appendLine("分类：${category ?: "未产生分类结果"}")
        appendLine("\n最终处理结果：$status")
        if (reason.isNotBlank()) appendLine("原因：$reason")
    }
}

/** 只决定诊断记录范围，不参与支付识别或入账判断。 */
object PaymentDiagnosticPolicy {
    fun shouldRecord(packageName: String, title: String, content: String, monitored: Boolean): Boolean {
        if (!monitored) return false
        if (packageName == "com.tencent.mm") {
            // 只接受明确来自支付服务的通知；朋友聊天中提及支付也不收集。
            val sender = title.replace(Regex("^\\s*[\\[【]\\d+条[\\]】]\\s*"), "").trim()
            return sender == "微信支付" || sender.startsWith("微信支付：") || sender.startsWith("微信支付:")
        }
        val text = "$title $content"
        return listOf("支付", "付款", "收款", "交易", "动账", "消费", "支出", "收入", "退款", "到账", "扣款", "转入", "转出")
            .any { text.contains(it) }
    }
}

/** 有限历史；异步入账结束时只更新仍存在的记录，清空后不会被旧回调重新添加。 */
class PaymentDiagnosticHistory(
    var enabled: Boolean = false,
    initial: List<PaymentDiagnostic> = emptyList()
) {
    var entries: List<PaymentDiagnostic> = initial.take(30)
        private set

    fun add(entry: PaymentDiagnostic): Boolean {
        if (!enabled) return false
        entries = (listOf(entry) + entries).take(30)
        return true
    }

    fun update(id: String, change: (PaymentDiagnostic) -> PaymentDiagnostic) {
        if (enabled) entries = entries.map { if (it.id == id) change(it) else it }
    }

    fun clear() { entries = emptyList() }

    fun remove(id: String) { entries = entries.filterNot { it.id == id } }
}
