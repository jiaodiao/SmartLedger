package com.smartledger.util

import com.smartledger.data.db.entity.Transaction
import java.io.Reader
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class CsvImportRow(
    val time: Long,
    val type: String,
    val amount: Double,
    val category: String,
    val merchant: String?,
    val method: String?,
    val note: String?,
    val source: String
)

data class CsvImportReport(
    val added: Int = 0,
    val duplicates: Int = 0,
    val invalid: Int = 0,
    val error: String? = null
) {
    val success: Boolean get() = error == null && (added > 0 || duplicates > 0)
    val message: String get() = when {
        error != null -> if (invalid > 0) "$error\n格式异常 $invalid 行。" else error
        added == 0 && duplicates > 0 && invalid == 0 -> "没有新增账单，$duplicates 笔记录均已存在。"
        else -> "新增 $added 笔，跳过重复 $duplicates 笔，格式异常 $invalid 行。"
    }
}

data class CsvImportData(
    val rows: List<CsvImportRow> = emptyList(),
    val invalid: Int = 0,
    val error: String? = null
)

data class CsvImportPlan(val rows: List<CsvImportRow>, val report: CsvImportReport)

/** 本应用账单 CSV 的解析与去重计划；预览和实际写入使用同一规则。 */
object CsvImportFormat {
    private val header = listOf("日期", "时间", "类型", "金额", "分类", "商户", "支付方式", "备注", "来源")
    private val dateTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(reader: Reader): CsvImportData {
        val records = try {
            readRecords(reader.readText().removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n'))
        } catch (_: IllegalArgumentException) {
            return CsvImportData(error = "CSV 引号格式错误，请选择本应用导出的账单 CSV。")
        }
        if (records.isEmpty()) return CsvImportData(error = "文件为空，没有账单记录。")
        val columns = records.first()
        if (columns == null || columns.size !in 7..9 || columns != header.take(columns.size)) {
            return CsvImportData(error = "文件格式不支持，请选择本应用导出的账单 CSV；暂不支持微信、支付宝原始账单。")
        }
        var invalid = 0
        val rows = records.drop(1).mapNotNull { fields ->
            try {
                require(fields != null && fields.size == columns.size)
                val type = when (fields[2]) {
                    "收入" -> "income"
                    "支出" -> "expense"
                    else -> throw IllegalArgumentException()
                }
                val amount = fields[3].toDouble()
                require(amount.isFinite() && amount > 0 && amount < Long.MAX_VALUE / 100.0)
                val time = LocalDateTime.parse("${fields[0]} ${fields[1]}", dateTimeFormat)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val source = fields.getOrElse(8) { "手动" }
                require(source == "自动" || source == "手动")
                CsvImportRow(
                    time, type, amount, fields[4], fields[5].ifBlank { null },
                    fields[6].ifBlank { null }, fields.getOrNull(7)?.ifBlank { null },
                    if (source == "自动") "auto" else "manual"
                )
            } catch (_: Exception) {
                invalid++
                null
            }
        }
        val error = when {
            records.size == 1 -> "文件中没有账单记录。"
            rows.isEmpty() -> "未找到有效账单，请检查日期、类型和金额等字段。"
            else -> null
        }
        return CsvImportData(rows, invalid, error)
    }

    fun plan(data: CsvImportData, existing: List<Transaction>): CsvImportPlan {
        if (data.error != null) return CsvImportPlan(emptyList(), CsvImportReport(invalid = data.invalid, error = data.error))
        // 延续原有规则：金额、收支类型、商户相同，且时间相差不超过一秒则跳过。
        data class Key(val cents: Long, val type: String, val merchant: String?)
        val known = mutableMapOf<Key, MutableList<Long>>()
        existing.forEach {
            known.getOrPut(Key(CurrencyUtil.toCents(it.amount), it.type, it.merchant?.ifBlank { null })) { mutableListOf() }
                .add(it.transactionTime)
        }
        var duplicates = 0
        val added = data.rows.filter { row ->
            val times = known.getOrPut(Key(CurrencyUtil.toCents(row.amount), row.type, row.merchant)) { mutableListOf() }
            if (times.any { it in (row.time - 1000)..(row.time + 1000) }) {
                duplicates++
                false
            } else {
                times.add(row.time)
                true
            }
        }
        return CsvImportPlan(added, CsvImportReport(added.size, duplicates, data.invalid))
    }

    /** 支持转义双引号、逗号及引号内换行，不能按物理行拆账单。 */
    private fun readRecords(text: String): List<List<String>?> {
        val records = mutableListOf<List<String>?>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var invalid = false
        var index = 0
        fun finishField() {
            fields.add(field.toString().trim())
            field.clear()
            closed = false
        }
        fun finishRecord() {
            finishField()
            if (invalid || fields.any { it.isNotBlank() } || fields.size > 1) {
                records.add(if (invalid) null else fields.toList())
            }
            fields.clear()
            invalid = false
        }
        while (index < text.length) {
            val ch = text[index++]
            if (quoted) {
                if (ch == '"') {
                    if (index < text.length && text[index] == '"') {
                        field.append('"')
                        index++
                    } else {
                        quoted = false
                        closed = true
                    }
                } else field.append(ch)
            } else when (ch) {
                ',' -> finishField()
                '\n' -> finishRecord()
                '"' -> if (field.isEmpty() && !closed) quoted = true else invalid = true
                else -> if (closed && !ch.isWhitespace()) invalid = true else if (!closed) field.append(ch)
            }
        }
        require(!quoted)
        if (fields.isNotEmpty() || field.isNotEmpty() || closed || invalid) finishRecord()
        return records
    }
}
