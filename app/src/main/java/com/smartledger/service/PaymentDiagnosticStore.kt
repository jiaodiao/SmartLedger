package com.smartledger.service

import android.app.Notification
import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object PaymentDiagnosticStore {
    data class State(val enabled: Boolean = false, val entries: List<PaymentDiagnostic> = emptyList())
    private val state = MutableStateFlow(State())
    private var history = PaymentDiagnosticHistory()
    private var loaded = false

    // noBackupFilesDir 同时排除系统备份；诊断原文不会进入账单导出或备份。
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "payment_diagnostics.json"))

    @Synchronized
    fun observe(context: Context): StateFlow<State> {
        load(context)
        return state
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        load(context)
        history.enabled = enabled
        save(context)
    }

    @Synchronized
    fun clear(context: Context) {
        load(context)
        history.clear()
        save(context)
    }

    @Synchronized
    fun discard(context: Context, id: String) {
        runCatching {
            history.remove(id)
            save(context)
        }
    }

    @Synchronized
    fun begin(context: Context, packageName: String, title: String, content: String, monitored: Boolean,
              notification: Notification, receivedAt: Long): String? = runCatching {
        load(context)
        if (!history.enabled || !PaymentDiagnosticPolicy.shouldRecord(packageName, title, content, monitored)) return null
        val id = UUID.randomUUID().toString()
        val source = when (packageName) {
            "com.tencent.mm" -> "微信支付"
            "com.eg.android.AlipayGphone" -> "支付宝"
            "com.unionpay" -> "云闪付"
            else -> context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        }
        history.add(PaymentDiagnostic(id, receivedAt, source, fieldsOf(notification), clipped("$title $content")))
        save(context)
        id
    }.getOrNull()

    @Synchronized
    fun complete(context: Context, id: String?, usedText: String, status: String, reason: String = "",
                 parsed: ParsedPayment? = null, category: String? = null) {
        if (id == null) return
        runCatching {
            load(context)
            if (!history.enabled || history.entries.none { it.id == id }) return
            history.update(id) { old -> old.copy(
                usedText = clipped(usedText), status = status, reason = reason,
                amount = parsed?.amount ?: old.amount, type = parsed?.type ?: old.type,
                merchant = parsed?.merchant ?: old.merchant, method = parsed?.paymentMethod ?: old.method,
                category = category ?: old.category
            ) }
            save(context)
        }
    }

    @Synchronized
    fun failIfProcessing(context: Context, id: String?, usedText: String) {
        if (id == null) return
        runCatching {
            if (!history.enabled || history.entries.none { it.id == id }) return
            history.update(id) { old -> if (old.status == "处理中") old.copy(
                usedText = clipped(usedText), status = "处理失败", reason = "识别后处理发生异常，请检查实际账单后重试"
            ) else old }
            save(context)
        }
    }

    @Synchronized
    fun addChildFields(context: Context, id: String?, notification: Notification) {
        if (id == null) return
        runCatching {
            if (!history.enabled || history.entries.none { it.id == id }) return
            history.update(id) { it.copy(fields = it.fields + fieldsOf(notification).mapKeys { field -> "补全子通知·${field.key}" }) }
            save(context)
        }
    }

    private fun clipped(text: String) = if (text.length <= 4096) text else text.take(4096) + "\n（过长，后续内容已截断）"

    private fun fieldsOf(notification: Notification): Map<String, String> {
        val extras = notification.extras
        val fields = linkedMapOf<String, String>()
        listOf("原始标题" to Notification.EXTRA_TITLE, "展开标题" to Notification.EXTRA_TITLE_BIG,
            "普通正文" to Notification.EXTRA_TEXT, "展开正文" to Notification.EXTRA_BIG_TEXT,
            "副标题" to Notification.EXTRA_SUB_TEXT, "摘要" to Notification.EXTRA_SUMMARY_TEXT,
            "附加信息" to Notification.EXTRA_INFO_TEXT).forEach { (label, key) ->
            fields[label] = clipped(extras?.getCharSequence(key)?.toString().orEmpty())
        }
        fields["分行内容"] = clipped(extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty())
        fields["滚动提示"] = clipped(notification.tickerText?.toString().orEmpty())
        // 不保存 MessagingStyle 消息数组，避免混合分组携带普通聊天原文。
        return fields
    }

    private fun load(context: Context) {
        if (loaded) return
        history = runCatching {
            val json = file(context).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val array = json.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until minOf(array.length(), 30)).map { index ->
                val entry = array.getJSONObject(index)
                val fields = entry.getJSONObject("fields")
                fun value(key: String) = entry.optString(key).takeIf { it.isNotBlank() }
                PaymentDiagnostic(entry.getString("id"), entry.getLong("receivedAt"), entry.getString("source"),
                    fields.keys().asSequence().associateWith { fields.getString(it) }, entry.optString("usedText"),
                    if (entry.optString("status") == "处理中") "处理结果未记录" else entry.optString("status"),
                    if (entry.optString("status") == "处理中") "应用上次在处理期间结束，请以实际账单为准" else entry.optString("reason"),
                    if (entry.has("amount")) entry.getDouble("amount") else null,
                    value("type"), value("merchant"), value("method"), value("category"))
            }
            PaymentDiagnosticHistory(json.optBoolean("enabled", false), entries)
        }.getOrElse { PaymentDiagnosticHistory() }
        loaded = true
        publish()
    }

    private fun publish() { state.value = State(history.enabled, history.entries) }

    private fun save(context: Context) {
        publish()
        val entries = JSONArray()
        history.entries.forEach { entry ->
            entries.put(JSONObject().put("id", entry.id).put("receivedAt", entry.receivedAt)
                .put("source", entry.source).put("fields", JSONObject(entry.fields))
                .put("usedText", entry.usedText).put("status", entry.status).put("reason", entry.reason)
                .put("amount", entry.amount).put("type", entry.type).put("merchant", entry.merchant)
                .put("method", entry.method).put("category", entry.category))
        }
        val target = file(context)
        val stream = target.startWrite()
        try {
            stream.write(JSONObject().put("enabled", history.enabled).put("entries", entries).toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }
}
